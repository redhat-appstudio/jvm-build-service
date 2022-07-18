package e2e

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"io/ioutil"
	"net/http"
	"os"
	"path/filepath"
	"testing"
	"time"

	projectv1 "github.com/openshift/api/project/v1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
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

func generateName(base string) string {
	if len(base) > maxGeneratedNameLength {
		base = base[:maxGeneratedNameLength]
	}
	return fmt.Sprintf("%s%s", base, utilrand.String(randomLength))
}

func dumpPods(ta *testArgs) {
	podClient := kubeClient.CoreV1().Pods(ta.ns)
	podList, err := podClient.List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		debugAndFailTest(ta, fmt.Sprintf("error list pods %v", err))
	}
	ta.t.Logf("dumpPods have %d items in list", len(podList.Items))
	for _, pod := range podList.Items {
		ta.t.Logf("dumpPods looking at pod %s in phase %s", pod.Name, pod.Status.Phase)

		for _, container := range pod.Spec.Containers {
			req := podClient.GetLogs(pod.Name, &corev1.PodLogOptions{Container: container.Name})
			readCloser, err := req.Stream(context.TODO())
			if err != nil {
				debugAndFailTest(ta, fmt.Sprintf("error getting pod logs for container %s: %s", container.Name, err.Error()))
			}
			b, err := ioutil.ReadAll(readCloser)
			if err != nil {
				debugAndFailTest(ta, fmt.Sprintf("error reading pod stream %s", err.Error()))
			}
			podLog := string(b)
			ta.t.Logf("pod logs for container %s in pod %s:  %s", container.Name, pod.Name, podLog)

		}

	}
}

func debugAndFailTest(ta *testArgs, failMsg string) {
	dumpPods(ta)
	ta.t.Fatalf(failMsg)
}

func setup(t *testing.T, ta *testArgs) *testArgs {
	if ta == nil {
		ta = &testArgs{
			t:        t,
			timeout:  time.Minute * 10,
			interval: time.Minute,
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
	ta.gitClone = &v1beta1.Task{}
	obj := streamRemoteYamlToTektonObj("https://raw.githubusercontent.com/redhat-appstudio/build-definitions/main/tasks/git-clone.yaml", ta.gitClone, ta)
	var ok bool
	ta.gitClone, ok = obj.(*v1beta1.Task)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("https://raw.githubusercontent.com/redhat-appstudio/build-definitions/main/tasks/git-clone.yaml did not produce a task: %#v", obj))
	}
	var err error
	ta.gitClone, err = tektonClient.TektonV1beta1().Tasks(ta.ns).Create(context.TODO(), ta.gitClone, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
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
	defer projectCleanup(ta)

	path, err := os.Getwd()
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	ta.t.Logf("current working dir: %s", path)

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
		ta.t.Logf("PR analyzer image: %s", analyserImage)
		for _, step := range ta.maven.Spec.Steps {
			if step.Name != "analyse-dependencies" {
				continue
			}
			ta.t.Logf("Updating analyse-dependencies step with image %s", analyserImage)
			step.Image = analyserImage
		}
	}
	sidecarImage := os.Getenv("JVM_BUILD_SERVICE_SIDECAR_IMAGE")
	if len(sidecarImage) > 0 {
		ta.t.Logf("PR sidecar image: %s", sidecarImage)
		for _, sidecar := range ta.maven.Spec.Sidecars {
			if sidecar.Name != "proxy" {
				continue
			}
			ta.t.Logf("Updating proxy sidecar with image %s", sidecarImage)
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

	runYamlPath := filepath.Join(path, "..", "..", "hack", "examples", "run.yaml")
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
				ta.t.Logf("get pr %s produced err: %s", ta.run.Name, err.Error())
				return false, nil
			}
			if !pr.IsDone() {
				prBytes, err := json.MarshalIndent(pr, "", "  ")
				if err != nil {
					ta.t.Logf("problem marshalling in progress pipelinerun to bytes: %s", err.Error())
					return false, nil
				}
				ta.t.Logf("in flight pipeline run: %s", string(prBytes))
			}
			if !pr.GetStatusCondition().GetCondition(apis.ConditionSucceeded).IsTrue() {
				prBytes, err := json.MarshalIndent(pr, "", "  ")
				if err != nil {
					ta.t.Logf("problem marshalling failed pipelinerun to bytes: %s", err.Error())
					return false, nil
				}
				ta.t.Logf("not yet successful pipeline run: %s", string(prBytes))

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
				ta.t.Logf("error listing artifactbuilds: %s", err.Error())
				return false, nil
			}
			gotABs := false
			if len(abList.Items) > 0 {
				gotABs = true
			}
			dbList, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.t.Logf("error listing dependencybuilds: %s", err.Error())
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

	ta.t.Run("taskruns related to artifactbuilds and dependencybuilds generated", func(t *testing.T) {
		err = wait.PollImmediate(ta.interval, ta.timeout, func() (done bool, err error) {
			taskRuns, err := tektonClient.TektonV1beta1().TaskRuns(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.t.Logf("taskrun list error: %s", err.Error())
				return false, nil
			}
			if len(taskRuns.Items) == 0 {
				ta.t.Logf("no taskruns yet")
				return false, nil
			}
			foundGenericLabel := false
			foundABRLabel := false
			foundDBLabel := false
			for _, taskRun := range taskRuns.Items {
				ta.t.Logf("taskrun %s has label map of len %d", taskRun.Name, len(taskRun.Labels))
				for k := range taskRun.Labels {
					if k == artifactbuild.TaskRunLabel {
						foundGenericLabel = true
					}
					if k == artifactbuild.ArtifactBuildIdLabel {
						foundABRLabel = true
					}
					if k == artifactbuild.DependencyBuildIdLabel {
						foundDBLabel = true
					}
					if foundABRLabel && foundDBLabel && foundGenericLabel {
						return true, nil
					}
				}
			}
			return false, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for artifactbuild and dependencybuild related taskruns")
		}
	})

	ta.t.Run("some artfacdtbuilds and dependencybuilds complete", func(t *testing.T) {
		err = wait.PollImmediate(ta.interval, 2*ta.timeout, func() (done bool, err error) {
			abList, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.t.Logf("error list artifactbuilds: %s", err.Error())
				return false, nil
			}
			abComplete := false
			for _, ab := range abList.Items {
				if ab.Status.State == v1alpha1.ArtifactBuildStateComplete {
					abComplete = true
					break
				}
			}
			dbComplete := false
			dbList, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			for _, db := range dbList.Items {
				if db.Status.State == v1alpha1.DependencyBuildStateComplete {
					dbComplete = true
					break
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
}
