package e2e

import (
	"bytes"
	"context"
	_ "embed"
	"fmt"
	"html/template"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"

	projectv1 "github.com/openshift/api/project/v1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	jvmclientset "github.com/redhat-appstudio/jvm-build-service/pkg/client/clientset/versioned"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/serializer"
	utilrand "k8s.io/apimachinery/pkg/util/rand"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	"k8s.io/apimachinery/pkg/util/wait"
	"k8s.io/cli-runtime/pkg/printers"
	kubeset "k8s.io/client-go/kubernetes"
	v12 "k8s.io/client-go/kubernetes/typed/core/v1"
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
			b, err := io.ReadAll(readCloser)
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
	_ = GenerateStatusReport(ta.ns, jvmClient, kubeClient)
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
	obj := streamRemoteYamlToTektonObj(gitCloneTaskUrl, ta.gitClone, ta)
	var ok bool
	ta.gitClone, ok = obj.(*v1beta1.Task)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("%s did not produce a task: %#v", gitCloneTaskUrl, obj))
	}
	ta.gitClone, err = tektonClient.TektonV1beta1().Tasks(ta.ns).Create(context.TODO(), ta.gitClone, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	owner := os.Getenv("QUAY_E2E_ORGANIZATION")
	if owner == "" {
		owner = "redhat-appstudio-qe"
	}
	userConfig := v1alpha1.UserConfig{
		ObjectMeta: metav1.ObjectMeta{
			Namespace: ta.ns,
			Name:      v1alpha1.UserConfigName,
		},
		Spec: v1alpha1.UserConfigSpec{
			EnableRebuilds:    true,
			DisableLocalstack: true,
			MavenBaseLocations: map[string]string{
				"maven-repository-300-jboss":         "https://repository.jboss.org/nexus/content/groups/public/",
				"maven-repository-301-gradleplugins": "https://plugins.gradle.org/m2",
				"maven-repository-302-confluent":     "https://packages.confluent.io/maven",
				"maven-repository-303-gradle":        "https://repo.gradle.org/artifactory/libs-releases",
				"maven-repository-304-eclipselink":   "https://download.eclipse.org/rt/eclipselink/maven.repo",
				"maven-repository-305-redhat":        "https://maven.repository.redhat.com/ga",
				"maven-repository-306-jitpack":       "https://jitpack.io",
				"maven-repository-307-jsweet":        "https://repository.jsweet.org/artifactory/libs-release-local"},
			CacheSettings: v1alpha1.CacheSettings{},
			ImageRegistry: v1alpha1.ImageRegistry{
				Host:       "quay.io",
				Owner:      owner,
				Repository: "test-images",
				PrependTag: strconv.FormatInt(time.Now().UnixMilli(), 10),
			},
		},
		Status: v1alpha1.UserConfigStatus{},
	}
	_, err = jvmClient.JvmbuildserviceV1alpha1().UserConfigs(ta.ns).Create(context.TODO(), &userConfig, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	secret := corev1.Secret{ObjectMeta: metav1.ObjectMeta{Name: "jvm-build-secrets", Namespace: ta.ns},
		StringData: map[string]string{"registry.token": os.Getenv("QUAY_TOKEN")}}
	_, err = kubeClient.CoreV1().Secrets(ta.ns).Create(context.TODO(), &secret, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	err = wait.PollImmediate(1*time.Second, 1*time.Minute, func() (done bool, err error) {
		_, err = kubeClient.AppsV1().Deployments(ta.ns).Get(context.TODO(), v1alpha1.CacheDeploymentName, metav1.GetOptions{})
		if err != nil {
			ta.Logf(fmt.Sprintf("get of cache: %s", err.Error()))
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

//func projectCleanup(ta *testArgs) {
//	projectClient.ProjectV1().Projects().Delete(context.Background(), ta.ns, metav1.DeleteOptions{})
//}

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

func encodeToYaml(obj runtime.Object) string {

	y := printers.YAMLPrinter{}
	b := bytes.Buffer{}
	_ = y.PrintObj(obj, &b)
	return b.String()
}

func streamRemoteYamlToTektonObj(url string, obj runtime.Object, ta *testArgs) runtime.Object {
	resp, err := http.Get(url) //#nosec G107
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	defer func(Body io.ReadCloser) {
		_ = Body.Close()
	}(resp.Body)
	bytes, err := io.ReadAll(resp.Body)
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	return decodeBytesToTektonObjbytes(bytes, obj, ta)
}

func streamFileYamlToTektonObj(path string, obj runtime.Object, ta *testArgs) runtime.Object {
	bytes, err := os.ReadFile(filepath.Clean(path))
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	return decodeBytesToTektonObjbytes(bytes, obj, ta)
}

func dumpPodsGlob(ta *testArgs, namespace, glob string) {
	podClient := kubeClient.CoreV1().Pods(namespace)
	podList, err := podClient.List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		ta.Logf(fmt.Sprintf("error list pods %s", err.Error()))
		return
	}
	ta.Logf(fmt.Sprintf("dumpPods have %d items in list", len(podList.Items)))
	for _, pod := range podList.Items {
		if !strings.Contains(pod.Name, glob) {
			continue
		}
		ta.Logf(fmt.Sprintf("dumpPods looking at pod %s in phase %s", pod.Name, pod.Status.Phase))

		containers := []corev1.Container{}
		containers = append(containers, pod.Spec.InitContainers...)
		containers = append(containers, pod.Spec.Containers...)
		for _, container := range containers {
			req := podClient.GetLogs(pod.Name, &corev1.PodLogOptions{Container: container.Name})
			readCloser, err := req.Stream(context.TODO())
			if err != nil {
				ta.Logf(fmt.Sprintf("error getting pod logs for container %s: %s", container.Name, err.Error()))
				continue
			}
			b, err := io.ReadAll(readCloser)
			if err != nil {
				ta.Logf(fmt.Sprintf("error reading pod stream %s", err.Error()))
				continue
			}
			podLog := string(b)
			ta.Logf(fmt.Sprintf("pod logs for container %s in pod %s:  %s", container.Name, pod.Name, podLog))

		}

	}
}

/*
func dbDumpForState(ta *testArgs, state string) {
	dbList, dberr := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
	if dberr != nil {
		ta.Logf(fmt.Sprintf("DB list error %s", dberr.Error()))
	} else {
		for _, db := range dbList.Items {
			if db.Status.State != state {
				continue
			}
			dumpDBPods(ta, &db)
		}
	}
}
*/

func dumpDBPods(ta *testArgs, db *v1alpha1.DependencyBuild) {
	dbName := db.Name
	podClient := kubeClient.CoreV1().Pods(ta.ns)
	ta.Logf(fmt.Sprintf("*****Examining failed db %s", dbName))
	podList := []corev1.Pod{}
	prList := pipelineRuns(ta, dbName, artifactbuild.DependencyBuildIdLabel)
	if len(prList) == 0 {
		ta.Logf(fmt.Sprintf("the label query fo db %s produced no hits, let's try based on name", dbName))
		podList = podsByName(ta, dbName)
	}
	for _, pr := range prList {
		podList = append(podList, prPods(ta, pr.Name)...)
	}
	for _, pod := range podList {
		containers := []corev1.Container{}
		containers = append(containers, pod.Spec.InitContainers...)
		containers = append(containers, pod.Spec.Containers...)
		for _, container := range containers {
			req := podClient.GetLogs(pod.Name, &corev1.PodLogOptions{Container: container.Name})
			readCloser, err2 := req.Stream(context.TODO())
			if err2 != nil {
				ta.Logf(fmt.Sprintf("error getting pod logs for container %s: %s", container.Name, err2.Error()))
				continue
			}
			b, err2 := io.ReadAll(readCloser)
			if err2 != nil {
				ta.Logf(fmt.Sprintf("error reading pod stream %s", err2.Error()))
				continue
			}
			podLog := string(b)
			ta.Logf(fmt.Sprintf("pod logs for container %s in pod %s:  %s", container.Name, pod.Name, podLog))

			url := strings.Replace(db.Spec.ScmInfo.SCMURL, "https://", "", 1)
			url = strings.Replace(url, "/", "_", -1)
			url = strings.Replace(url, ":", "_", -1)

			directory := os.Getenv("ARTIFACT_DIR") + "/failed-dependency-builds/" + url
			ta.Logf(fmt.Sprintf("Creating artifact dir %s", directory))
			err := os.MkdirAll(directory, 0755) //#nosec G306 G301
			if err != nil {
				ta.Logf(fmt.Sprintf("Failed to create artifact dir %s: %s", directory, err))
			}
			err = os.WriteFile(directory+"/"+pod.Name+container.Name, b, 0644) //#nosec G306
			if err != nil {
				ta.Logf(fmt.Sprintf("Failed artifact dir %s: %s", directory, err))
			}
		}
	}
	ta.Logf(fmt.Sprintf("******Done with db %s", dbName))

}

func dumpABPods(ta *testArgs, abName, gav string) {
	podClient := kubeClient.CoreV1().Pods(ta.ns)
	ta.Logf(fmt.Sprintf("*****Examining failed ab %s", abName))
	podList := []corev1.Pod{}
	prList := pipelineRuns(ta, artifactbuild.ABRLabelForGAV(gav), artifactbuild.ArtifactBuildIdLabel)
	if len(prList) == 0 {
		ta.Logf(fmt.Sprintf("the label query fo ab %s produced no hits, let's try based on name", abName))
		podList = prPods(ta, abName)
	}
	for _, pr := range prList {
		podList = prPods(ta, pr.Name)
	}
	for _, pod := range podList {
		containers := []corev1.Container{}
		containers = append(containers, pod.Spec.InitContainers...)
		containers = append(containers, pod.Spec.Containers...)
		for _, container := range containers {
			req := podClient.GetLogs(pod.Name, &corev1.PodLogOptions{Container: container.Name})
			readCloser, err2 := req.Stream(context.TODO())
			if err2 != nil {
				ta.Logf(fmt.Sprintf("error getting pod logs for container %s: %s", container.Name, err2.Error()))
				continue
			}
			b, err2 := io.ReadAll(readCloser)
			if err2 != nil {
				ta.Logf(fmt.Sprintf("error reading pod stream %s", err2.Error()))
				continue
			}
			podLog := string(b)
			ta.Logf(fmt.Sprintf("pod logs for container %s in pod %s:  %s", container.Name, pod.Name, podLog))

		}
	}
	ta.Logf(fmt.Sprintf("******Done with ab %s", abName))
}

func abDumpForState(ta *testArgs, state string) {
	abList, aberr := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
	if aberr != nil {
		ta.Logf(fmt.Sprintf("AB list error %s", aberr.Error()))
	} else {
		for _, ab := range abList.Items {
			if ab.Status.State != state {
				continue
			}
			dumpABPods(ta, ab.Name, ab.Spec.GAV)
		}
	}
}

func activePipelineRuns(ta *testArgs, dbg bool) bool {
	prClient := tektonClient.TektonV1beta1().PipelineRuns(ta.ns)
	listOptions := metav1.ListOptions{
		LabelSelector: fmt.Sprintf("%s=", artifactbuild.PipelineRunLabel),
	}
	prList, err := prClient.List(context.TODO(), listOptions)
	if err != nil {
		ta.Logf(fmt.Sprintf("error listing pipelineruns: %s", err.Error()))
		return true
	}
	for _, pr := range prList.Items {
		if !pr.IsDone() {
			if dbg {
				ta.Logf(fmt.Sprintf("pr %s not done out of %d items", pr.Name, len(prList.Items)))
			}
			return true
		}
	}
	if dbg {
		ta.Logf(fmt.Sprintf("all prs are done out of %d items", len(prList.Items)))
	}
	return false
}

func pipelineRuns(ta *testArgs, name, label string) []v1beta1.PipelineRun {
	prClient := tektonClient.TektonV1beta1().PipelineRuns(ta.ns)
	listOptions := metav1.ListOptions{
		LabelSelector: fmt.Sprintf("%s=%s", label, name),
	}
	dbList, err := prClient.List(context.TODO(), listOptions)
	if err != nil {
		ta.Logf(fmt.Sprintf("error listing prs %s", err.Error()))
		return []v1beta1.PipelineRun{}
	}
	return dbList.Items
}

func prPods(ta *testArgs, name string) []corev1.Pod {
	podClient := kubeClient.CoreV1().Pods(ta.ns)
	listOptions := metav1.ListOptions{
		LabelSelector: fmt.Sprintf("tekton.dev/pipelineRun=%s", name),
	}
	podList, err := podClient.List(context.TODO(), listOptions)
	if err != nil {
		ta.Logf(fmt.Sprintf("error listing pr pods %s", err.Error()))
		return []corev1.Pod{}
	}
	return podList.Items
}

func podsByName(ta *testArgs, name string) []corev1.Pod {
	podClient := kubeClient.CoreV1().Pods(ta.ns)
	podList, err := podClient.List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		ta.Logf(fmt.Sprintf("error listing pods %s", err.Error()))
		return []corev1.Pod{}
	}
	retArr := []corev1.Pod{}
	for _, pod := range podList.Items {
		if !strings.HasPrefix(pod.Name, name) {
			continue
		}
		// for now we won't try to filter based on pod conditions or container statuses
		retArr = append(retArr, pod)
	}
	return retArr
}

//go:embed report.html
var reportTemplate string

// dumping the logs slows down generation
// when working on the report you might want to turn it off
// this should always be true in the committed code though
const DUMP_LOGS = true

func GenerateStatusReport(namespace string, jvmClient *jvmclientset.Clientset, kubeClient *kubeset.Clientset) error {

	directory := os.Getenv("ARTIFACT_DIR")
	if directory == "" {
		directory = "/tmp/jvm-build-service-report"
	} else {
		directory = directory + "/jvm-build-service-report"
	}
	err := os.MkdirAll(directory, 0755) //#nosec G306 G301
	if err != nil {
		return err
	}
	podClient := kubeClient.CoreV1().Pods(namespace)
	podList, err := podClient.List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		return err
	}
	artifact := ArtifactReportData{}
	dependency := DependencyReportData{}
	dependencyBuildClient := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(namespace)
	artifactBuilds, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(namespace).List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		return err
	}
	for _, ab := range artifactBuilds.Items {
		localDir := ab.Status.State + "/" + ab.Name
		tmp := ab
		createdBy := ""
		if ab.Annotations != nil {
			for k, v := range ab.Annotations {
				if strings.HasPrefix(k, artifactbuild.DependencyBuildContaminatedBy) {
					createdBy = " (created by build " + v + ")"
				}
			}
		}
		instance := &ReportInstanceData{Name: ab.Name + createdBy, State: ab.Status.State, Yaml: encodeToYaml(&tmp)}
		artifact.Instances = append(artifact.Instances, instance)
		artifact.Total++
		print(ab.Status.State + "\n")
		switch ab.Status.State {
		case v1alpha1.ArtifactBuildStateComplete:
			artifact.Complete++
		case v1alpha1.ArtifactBuildStateFailed:
			artifact.Failed++
		case v1alpha1.ArtifactBuildStateMissing:
			artifact.Missing++
		default:
			artifact.Other++
		}
		for _, pod := range podList.Items {
			if strings.HasPrefix(pod.Name, ab.Name) {
				logFile := dumpPod(pod, directory, localDir, podClient)
				instance.Logs = append(instance.Logs, logFile...)
			}
		}
	}
	sort.Sort(SortableArtifact(artifact.Instances))

	dependencyBuilds, err := dependencyBuildClient.List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		return err
	}
	for _, db := range dependencyBuilds.Items {
		dependency.Total++
		localDir := db.Status.State + "/" + db.Name
		tmp := db
		instance := &ReportInstanceData{State: db.Status.State, Yaml: encodeToYaml(&tmp), Name: fmt.Sprintf("%s @{%s} (%s)", db.Spec.ScmInfo.SCMURL, db.Spec.ScmInfo.Tag, db.Name)}
		dependency.Instances = append(dependency.Instances, instance)
		print(db.Status.State + "\n")
		switch db.Status.State {
		case v1alpha1.DependencyBuildStateComplete:
			dependency.Complete++
		case v1alpha1.DependencyBuildStateFailed:
			dependency.Failed++
		case v1alpha1.DependencyBuildStateContaminated:
			dependency.Contaminated++
		default:
			dependency.Other++
		}
		for _, pod := range podList.Items {
			if strings.HasPrefix(pod.Name, db.Name) {
				logFile := dumpPod(pod, directory, localDir, podClient)
				instance.Logs = append(instance.Logs, logFile...)
			}
		}
	}
	sort.Sort(SortableArtifact(dependency.Instances))

	report := directory + "/index.html"

	data := ReportData{
		Artifact:   artifact,
		Dependency: dependency,
	}

	t, err := template.New("report").Parse(reportTemplate)
	if err != nil {
		panic(err)
	}
	buf := new(bytes.Buffer)
	err = t.Execute(buf, data)
	if err != nil {
		panic(err)
	}

	err = os.WriteFile(report, buf.Bytes(), 0644) //#nosec G306
	if err != nil {
		return err
	}
	print("Created report file://" + report + "\n")
	return nil
}

func dumpPod(pod corev1.Pod, baseDirectory string, localDirectory string, kubeClient v12.PodInterface) []string {
	if !DUMP_LOGS {
		return []string{}
	}
	containers := []corev1.Container{}
	containers = append(containers, pod.Spec.InitContainers...)
	containers = append(containers, pod.Spec.Containers...)
	ret := []string{}
	for _, container := range containers {
		req := kubeClient.GetLogs(pod.Name, &corev1.PodLogOptions{Container: container.Name})
		readCloser, err2 := req.Stream(context.TODO())
		if err2 != nil {
			print(fmt.Sprintf("error getting pod logs for container %s: %s", container.Name, err2.Error()))
			continue
		}
		b, err2 := io.ReadAll(readCloser)
		if err2 != nil {
			print(fmt.Sprintf("error reading pod stream %s", err2.Error()))
			continue
		}
		directory := baseDirectory + "/" + localDirectory
		err := os.MkdirAll(directory, 0755) //#nosec G306 G301
		if err != nil {
			print(fmt.Sprintf("Failed to create artifact dir %s: %s", directory, err))
		}
		localPart := localDirectory + pod.Name + container.Name
		fileName := baseDirectory + "/" + localPart
		err = os.WriteFile(fileName, b, 0644) //#nosec G306
		if err != nil {
			print(fmt.Sprintf("Failed artifact dir %s: %s", directory, err))
		}
		ret = append(ret, localDirectory+pod.Name+container.Name)
	}
	return ret
}

type ArtifactReportData struct {
	Complete  int
	Failed    int
	Missing   int
	Other     int
	Total     int
	Instances []*ReportInstanceData
}

type DependencyReportData struct {
	Complete     int
	Failed       int
	Contaminated int
	Other        int
	Total        int
	Instances    []*ReportInstanceData
}
type ReportData struct {
	Artifact   ArtifactReportData
	Dependency DependencyReportData
}

type ReportInstanceData struct {
	Name  string
	Logs  []string
	State string
	Yaml  string
}

type SortableArtifact []*ReportInstanceData

func (a SortableArtifact) Len() int           { return len(a) }
func (a SortableArtifact) Less(i, j int) bool { return strings.Compare(a[i].Name, a[j].Name) < 0 }
func (a SortableArtifact) Swap(i, j int)      { a[i], a[j] = a[j], a[i] }
