//go:build normal
// +build normal

package e2e

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/configmap"
	"io"
	"io/ioutil"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	projectv1 "github.com/openshift/api/project/v1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/serializer"
	utilrand "k8s.io/apimachinery/pkg/util/rand"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	"k8s.io/apimachinery/pkg/util/wait"
	"knative.dev/pkg/apis"
)

const (
	testNamespace          = "jvm-build-service-test-namespace-"
	maxNameLength          = 63
	randomLength           = 5
	maxGeneratedNameLength = maxNameLength - randomLength
)

type testArgs struct {
	t  *testing.T
	ns string

	timeout  time.Duration
	interval time.Duration

	gitClone *v1beta1.Task
	maven    *v1beta1.Task
	pipeline *v1beta1.Pipeline
	run      *v1beta1.PipelineRun
}

func (ta *testArgs) Logf(msg string) {
	ta.t.Logf(fmt.Sprintf("time: %s: %s", time.Now().String(), msg))
}

func generateName(base string) string {
	if len(base) > maxGeneratedNameLength {
		base = base[:maxGeneratedNameLength]
	}
	return fmt.Sprintf("%s%s", base, utilrand.String(randomLength))
}

func dumpPods(ta *testArgs, namespace string) {
	podClient := kubeClient.CoreV1().Pods(namespace)
	podList, err := podClient.List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		ta.Logf(fmt.Sprintf("error list pods %s", err.Error()))
		return
	}
	ta.Logf(fmt.Sprintf("dumpPods have %d items in list", len(podList.Items)))
	for _, pod := range podList.Items {
		ta.Logf(fmt.Sprintf("dumpPods looking at pod %s in phase %s", pod.Name, pod.Status.Phase))

		for _, container := range pod.Spec.Containers {
			req := podClient.GetLogs(pod.Name, &corev1.PodLogOptions{Container: container.Name})
			readCloser, err := req.Stream(context.TODO())
			if err != nil {
				ta.Logf(fmt.Sprintf("error getting pod logs for container %s: %s", container.Name, err.Error()))
				continue
			}
			b, err := ioutil.ReadAll(readCloser)
			if err != nil {
				ta.Logf(fmt.Sprintf("error reading pod stream %s", err.Error()))
				continue
			}
			podLog := string(b)
			ta.Logf(fmt.Sprintf("pod logs for container %s in pod %s:  %s", container.Name, pod.Name, podLog))

		}

	}
}

func dumpBadEvents(ta *testArgs) {
	eventClient := kubeClient.EventsV1().Events(ta.ns)
	eventList, err := eventClient.List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		ta.Logf(fmt.Sprintf("error listing events: %s", err.Error()))
		return
	}
	ta.Logf(fmt.Sprintf("dumpBadEvents have %d items in total list", len(eventList.Items)))
	for _, event := range eventList.Items {
		if event.Type == corev1.EventTypeNormal {
			continue
		}
		ta.Logf(fmt.Sprintf("non-normal event reason %s about obj %s:%s message %s", event.Reason, event.Regarding.Kind, event.Regarding.Name, event.Note))
	}
}

func dumpNodes(ta *testArgs) {
	nodeClient := kubeClient.CoreV1().Nodes()
	nodeList, err := nodeClient.List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		ta.Logf(fmt.Sprintf("error listin nodes: %s", err.Error()))
		return
	}
	ta.Logf(fmt.Sprintf("dumpNodes found %d nodes in list, but only logging worker nodes", len(nodeList.Items)))
	for _, node := range nodeList.Items {
		_, ok := node.Labels["node-role.kubernetes.io/master"]
		if ok {
			continue
		}
		if node.Status.Allocatable.Cpu() == nil {
			ta.Logf(fmt.Sprintf("Node %s does not have allocatable cpu", node.Name))
			continue
		}
		if node.Status.Allocatable.Memory() == nil {
			ta.Logf(fmt.Sprintf("Node %s does not have allocatable mem", node.Name))
			continue
		}
		if node.Status.Capacity.Cpu() == nil {
			ta.Logf(fmt.Sprintf("Node %s does not have capacity cpu", node.Name))
			continue
		}
		if node.Status.Capacity.Memory() == nil {
			ta.Logf(fmt.Sprintf("Node %s does not have capacity mem", node.Name))
			continue
		}
		alloccpu := node.Status.Allocatable.Cpu()
		allocmem := node.Status.Allocatable.Memory()
		capaccpu := node.Status.Capacity.Cpu()
		capacmem := node.Status.Capacity.Memory()
		ta.Logf(fmt.Sprintf("Node %s allocatable CPU %s allocatable mem %s capacity CPU %s capacitymem %s",
			node.Name,
			alloccpu.String(),
			allocmem.String(),
			capaccpu.String(),
			capacmem.String()))
	}
}

func debugAndFailTest(ta *testArgs, failMsg string) {
	dumpPods(ta, ta.ns)
	dumpPods(ta, "jvm-build-service")
	dumpBadEvents(ta)
	ta.t.Fatalf(failMsg)
}

func setup(t *testing.T, ta *testArgs) *testArgs {
	if ta == nil {
		ta = &testArgs{
			t:        t,
			timeout:  time.Minute * 10,
			interval: time.Second * 15,
		}
	}
	setupClients(ta.t)

	if len(ta.ns) == 0 {
		ta.ns = generateName(testNamespace)
		_, err := projectClient.ProjectV1().ProjectRequests().Create(context.Background(), &projectv1.ProjectRequest{
			ObjectMeta: metav1.ObjectMeta{Name: ta.ns},
		}, metav1.CreateOptions{})

		if err != nil {
			debugAndFailTest(ta, fmt.Sprintf("%#v", err))
		}
	}

	dumpNodes(ta)

	var err error
	err = wait.PollImmediate(1*time.Second, 1*time.Minute, func() (done bool, err error) {
		_, err = kubeClient.CoreV1().ServiceAccounts(ta.ns).Get(context.TODO(), "pipeline", metav1.GetOptions{})
		if err != nil {
			ta.Logf(fmt.Sprintf("get of pipeline SA err: %s", err.Error()))
			return false, nil
		}
		return true, nil
	})
	if err != nil {
		debugAndFailTest(ta, "pipeline SA not created in timely fashion")
	}

	// have seen delays in CRD presence along with missing pipeline SA
	err = wait.PollImmediate(1*time.Second, 1*time.Minute, func() (done bool, err error) {
		_, err = apiextensionClient.ApiextensionsV1().CustomResourceDefinitions().Get(context.TODO(), "tasks.tekton.dev", metav1.GetOptions{})
		if err != nil {
			ta.Logf(fmt.Sprintf("get of task CRD: %s", err.Error()))
			return false, nil
		}
		return true, nil
	})
	if err != nil {
		debugAndFailTest(ta, "task CRD not present in timely fashion")
	}

	ta.gitClone = &v1beta1.Task{}
	obj := streamRemoteYamlToTektonObj("https://raw.githubusercontent.com/redhat-appstudio/build-definitions/main/tasks/git-clone.yaml", ta.gitClone, ta)
	var ok bool
	ta.gitClone, ok = obj.(*v1beta1.Task)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("https://raw.githubusercontent.com/redhat-appstudio/build-definitions/main/tasks/git-clone.yaml did not produce a task: %#v", obj))
	}
	ta.gitClone, err = tektonClient.TektonV1beta1().Tasks(ta.ns).Create(context.TODO(), ta.gitClone, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	cm := corev1.ConfigMap{ObjectMeta: metav1.ObjectMeta{Name: "jvm-build-config", Namespace: ta.ns},
		Data: map[string]string{
			"enable-rebuilds":                "true",
			"maven-repository-300-jboss":     "https://repository.jboss.org/nexus/content/groups/public/",
			"maven-repository-301-jitpack":   "https://jitpack.io",
			"maven-repository-302-confluent": "https://packages.confluent.io/maven",
			"maven-repository-303-gradle":    "https://repo.gradle.org/artifactory/libs-releases"}}
	_, err = kubeClient.CoreV1().ConfigMaps(ta.ns).Create(context.TODO(), &cm, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	err = wait.PollImmediate(1*time.Second, 1*time.Minute, func() (done bool, err error) {
		_, err = kubeClient.AppsV1().Deployments(ta.ns).Get(context.TODO(), configmap.CacheDeploymentName, metav1.GetOptions{})
		if err != nil {
			ta.Logf(fmt.Sprintf("get of cache: %s", err.Error()))
			return false, nil
		}
		_, err = kubeClient.AppsV1().Deployments(ta.ns).Get(context.TODO(), configmap.LocalstackDeploymentName, metav1.GetOptions{})
		if err != nil {
			ta.Logf(fmt.Sprintf("get of localstack: %s", err.Error()))
			return false, nil
		}
		return true, nil
	})
	if err != nil {
		debugAndFailTest(ta, "cache and/or localstack not present in timely fashion")
	}
	return ta
}

func projectCleanup(ta *testArgs) {
	projectClient.ProjectV1().Projects().Delete(context.Background(), ta.ns, metav1.DeleteOptions{})
}

func decodeBytesToTektonObjbytes(bytes []byte, obj runtime.Object, ta *testArgs) runtime.Object {
	decodingScheme := runtime.NewScheme()
	utilruntime.Must(v1beta1.AddToScheme(decodingScheme))
	decoderCodecFactory := serializer.NewCodecFactory(decodingScheme)
	decoder := decoderCodecFactory.UniversalDecoder(v1beta1.SchemeGroupVersion)
	err := runtime.DecodeInto(decoder, bytes, obj)
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	return obj
}

func streamRemoteYamlToTektonObj(url string, obj runtime.Object, ta *testArgs) runtime.Object {
	resp, err := http.Get(url)
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	defer resp.Body.Close()
	bytes, err := io.ReadAll(resp.Body)
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	return decodeBytesToTektonObjbytes(bytes, obj, ta)
}

func streamFileYamlToTektonObj(path string, obj runtime.Object, ta *testArgs) runtime.Object {
	bytes, err := ioutil.ReadFile(path)
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	return decodeBytesToTektonObjbytes(bytes, obj, ta)
}

func TestExampleRun(t *testing.T) {
	ta := setup(t, nil)
	//TODO, for now at least, keeping our test project to allow for analyzing the various CRD instances both for failure
	// and successful runs (in case a run succeeds, but we find something amiss if we look at passing runs; our in repo
	// tests do now run in conjunction with say the full suite of e2e's in the e2e-tests runs, so no contention there.
	//defer projectCleanup(ta)

	path, err := os.Getwd()
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	ta.Logf(fmt.Sprintf("current working dir: %s", path))

	mavenYamlPath := filepath.Join(path, "..", "..", "deploy", "base", "maven-v0.2.yaml")
	ta.maven = &v1beta1.Task{}
	obj := streamFileYamlToTektonObj(mavenYamlPath, ta.maven, ta)
	var ok bool
	ta.maven, ok = obj.(*v1beta1.Task)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("file %s did not produce a task: %#v", mavenYamlPath, obj))
	}
	// override images if need be
	analyserImage := os.Getenv("JVM_BUILD_SERVICE_ANALYZER_IMAGE")
	if len(analyserImage) > 0 {
		ta.Logf(fmt.Sprintf("PR analyzer image: %s", analyserImage))
		for _, step := range ta.maven.Spec.Steps {
			if step.Name != "analyse-dependencies" {
				continue
			}
			ta.Logf(fmt.Sprintf("Updating analyse-dependencies step with image %s", analyserImage))
			step.Image = analyserImage
		}
	}
	sidecarImage := os.Getenv("JVM_BUILD_SERVICE_SIDECAR_IMAGE")
	if len(sidecarImage) > 0 {
		ta.Logf(fmt.Sprintf("PR sidecar image: %s", sidecarImage))
		for _, sidecar := range ta.maven.Spec.Sidecars {
			if sidecar.Name != "proxy" {
				continue
			}
			ta.Logf(fmt.Sprintf("Updating proxy sidecar with image %s", sidecarImage))
			sidecar.Image = sidecarImage
		}
	}
	ta.maven, err = tektonClient.TektonV1beta1().Tasks(ta.ns).Create(context.TODO(), ta.maven, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	pipelineYamlPath := filepath.Join(path, "..", "..", "hack", "examples", "pipeline.yaml")
	ta.pipeline = &v1beta1.Pipeline{}
	obj = streamFileYamlToTektonObj(pipelineYamlPath, ta.pipeline, ta)
	ta.pipeline, ok = obj.(*v1beta1.Pipeline)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("file %s did not produce a pipeline: %#v", pipelineYamlPath, obj))
	}
	ta.pipeline, err = tektonClient.TektonV1beta1().Pipelines(ta.ns).Create(context.TODO(), ta.pipeline, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	runYamlPath := filepath.Join(path, "..", "..", "hack", "examples", "run-e2e-shaded-app.yaml")
	ta.run = &v1beta1.PipelineRun{}
	obj = streamFileYamlToTektonObj(runYamlPath, ta.run, ta)
	ta.run, ok = obj.(*v1beta1.PipelineRun)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("file %s did not produce a pipelinerun: %#v", runYamlPath, obj))
	}
	ta.run, err = tektonClient.TektonV1beta1().PipelineRuns(ta.ns).Create(context.TODO(), ta.run, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	ta.t.Run("pipelinerun completes successfully", func(t *testing.T) {
		err = wait.PollImmediate(ta.interval, ta.timeout, func() (done bool, err error) {
			pr, err := tektonClient.TektonV1beta1().PipelineRuns(ta.ns).Get(context.TODO(), ta.run.Name, metav1.GetOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("get pr %s produced err: %s", ta.run.Name, err.Error()))
				return false, nil
			}
			if !pr.IsDone() {
				prBytes, err := json.MarshalIndent(pr, "", "  ")
				if err != nil {
					ta.Logf(fmt.Sprintf("problem marshalling in progress pipelinerun to bytes: %s", err.Error()))
					return false, nil
				}
				ta.Logf(fmt.Sprintf("in flight pipeline run: %s", string(prBytes)))
			}
			if !pr.GetStatusCondition().GetCondition(apis.ConditionSucceeded).IsTrue() {
				prBytes, err := json.MarshalIndent(pr, "", "  ")
				if err != nil {
					ta.Logf(fmt.Sprintf("problem marshalling failed pipelinerun to bytes: %s", err.Error()))
					return false, nil
				}
				ta.Logf(fmt.Sprintf("not yet successful pipeline run: %s", string(prBytes)))

			}
			return true, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out when waiting for the pipeline run to complete")
		}
	})

	ta.t.Run("artifactbuilds and dependencybuilds generated", func(t *testing.T) {
		err = wait.PollImmediate(ta.interval, ta.timeout, func() (done bool, err error) {
			abList, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error listing artifactbuilds: %s", err.Error()))
				return false, nil
			}
			gotABs := false
			if len(abList.Items) > 0 {
				gotABs = true
			}
			dbList, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error listing dependencybuilds: %s", err.Error()))
				return false, nil
			}
			gotDBs := false
			if len(dbList.Items) > 0 {
				gotDBs = true
			}
			if gotABs && gotDBs {
				return true, nil
			}
			return false, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for generation of artifactbuilds and dependencybuilds")
		}
	})

	ta.t.Run("some artfactbuilds and dependencybuilds complete", func(t *testing.T) {
		err = wait.PollImmediate(ta.interval, 2*ta.timeout, func() (done bool, err error) {
			abList, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error list artifactbuilds: %s", err.Error()))
				return false, nil
			}
			//we want to make sure there is more than one ab, and that they are all complete
			abComplete := len(abList.Items) > 0
			ta.Logf(fmt.Sprintf("number of artifactbuilds: %d", len(abList.Items)))
			for _, ab := range abList.Items {
				if ab.Status.State != v1alpha1.ArtifactBuildStateComplete {
					ta.Logf(fmt.Sprintf("artifactbuild %s not complete", ab.Spec.GAV))
					abComplete = false
					break
				}
			}
			dbList, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			dbComplete := len(dbList.Items) > 0
			ta.Logf(fmt.Sprintf("number of dependencybuilds: %d", len(dbList.Items)))
			for _, db := range dbList.Items {
				if db.Status.State != v1alpha1.DependencyBuildStateComplete {
					ta.Logf(fmt.Sprintf("depedencybuild %s not complete", db.Spec.ScmInfo.SCMURL))
					dbComplete = false
					break
				} else if db.Status.State == v1alpha1.DependencyBuildStateFailed {
					ta.Logf(fmt.Sprintf("depedencybuild %s FAILED", db.Spec.ScmInfo.SCMURL))
					return false, errors.New(fmt.Sprintf("depedencybuild %s for repo %s FAILED", db.Name, db.Spec.ScmInfo.SCMURL))
				}
			}
			if abComplete && dbComplete {
				return true, nil
			}
			return false, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for some artifactbuilds and dependencybuilds to complete")
		}
	})

	ta.t.Run("contaminated build is resolved", func(t *testing.T) {
		//our sample repo has shaded-jdk11 which is contaminated by simple-jdk8
		var contaminated string
		var simpleJDK8 string
		err = wait.PollImmediate(ta.interval, 2*ta.timeout, func() (done bool, err error) {

			dbContaminated := false
			shadedComplete := false
			contaminantBuild := false
			dbList, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error list dependencybuilds: %s", err.Error()))
				return false, err
			}
			ta.Logf(fmt.Sprintf("number of dependencybuilds: %d", len(dbList.Items)))
			for _, db := range dbList.Items {
				if db.Status.State == v1alpha1.DependencyBuildStateContaminated {
					dbContaminated = true
					contaminated = db.Name
					break
				} else if strings.Contains(db.Spec.ScmInfo.SCMURL, "shaded-jdk11") && db.Status.State == v1alpha1.DependencyBuildStateComplete {
					//its also possible that the build has already resolved itself
					contaminated = db.Name
					shadedComplete = true
				} else if strings.Contains(db.Spec.ScmInfo.SCMURL, "simple-jdk8") {
					contaminantBuild = true
				}
			}
			if dbContaminated || (shadedComplete && contaminantBuild) {
				return true, nil
			}
			return false, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for contaminated build to appear")
		}
		ta.Logf(fmt.Sprintf("contaminated dependencybuild: %s", contaminated))
		//make sure simple-jdk8 was requested as a result
		err = wait.PollImmediate(ta.interval, 2*ta.timeout, func() (done bool, err error) {
			abList, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error list artifactbuilds: %s", err.Error()))
				return false, err
			}
			found := false
			ta.Logf(fmt.Sprintf("number of artifactbuilds: %d", len(abList.Items)))
			for _, ab := range abList.Items {
				if strings.Contains(ab.Spec.GAV, "simple-jdk8") {
					simpleJDK8 = ab.Name
					found = true
					break
				}
			}
			return found, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for simple-jdk8 to appear as an artifactbuild")
		}
		//now make sure simple-jdk8 eventually completes
		err = wait.PollImmediate(ta.interval, 2*ta.timeout, func() (done bool, err error) {
			ab, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).Get(context.TODO(), simpleJDK8, metav1.GetOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error getting simple-jdk8 ArtifactBuild: %s", err.Error()))
				return false, err
			}
			ta.Logf(fmt.Sprintf("simple-jdk8 State: %s", ab.Status.State))
			return ab.Status.State == v1alpha1.ArtifactBuildStateComplete, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for simple-jdk8 to complete")
		}
		//now make sure shaded-jdk11 eventually completes
		err = wait.PollImmediate(ta.interval, 2*ta.timeout, func() (done bool, err error) {
			db, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).Get(context.TODO(), contaminated, metav1.GetOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error getting shaded-jdk11 DependencyBuild: %s", err.Error()))
				return false, err
			}
			ta.Logf(fmt.Sprintf("shaded-jdk11 State: %s", db.Status.State))
			if db.Status.State == v1alpha1.DependencyBuildStateFailed {
				msg := fmt.Sprintf("contaminated db %s failed, exitting wait", contaminated)
				ta.Logf(msg)
				return false, fmt.Errorf(msg)
			}
			return db.Status.State == v1alpha1.DependencyBuildStateComplete, err
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for shaded-jdk11 to complete")
		}
	})
}
