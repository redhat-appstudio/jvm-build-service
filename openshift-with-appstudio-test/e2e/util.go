package e2e

import (
	"context"
	"fmt"
	"io"
	"io/ioutil"
	"net/http"
	"testing"
	"time"

	projectv1 "github.com/openshift/api/project/v1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/configmap"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/serializer"
	utilrand "k8s.io/apimachinery/pkg/util/rand"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	"k8s.io/apimachinery/pkg/util/wait"
)

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
			"enable-rebuilds":                  "true",
			"maven-repository-300-jboss":       "https://repository.jboss.org/nexus/content/groups/public/",
			"maven-repository-301-jitpack":     "https://jitpack.io",
			"maven-repository-302-confluent":   "https://packages.confluent.io/maven",
			"maven-repository-303-gradle":      "https://repo.gradle.org/artifactory/libs-releases",
			"maven-repository-304-eclipselink": "https://download.eclipse.org/rt/eclipselink/maven.repo"}}
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

func bothABsAndDBsGenerated(ta *testArgs) (bool, error) {
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
