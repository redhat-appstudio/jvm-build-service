package e2e

import (
	"bytes"
	"context"
	_ "embed"
	"encoding/base64"
	"encoding/json"
	errors2 "errors"
	"fmt"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/dependencybuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/jbsconfig"
	"html/template"
	"io"
	"k8s.io/apimachinery/pkg/api/resource"
	"k8s.io/apimachinery/pkg/util/intstr"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	jvmclientset "github.com/redhat-appstudio/jvm-build-service/pkg/client/clientset/versioned"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
	pipelineclientset "github.com/tektoncd/pipeline/pkg/client/clientset/versioned"
	v13 "k8s.io/api/apps/v1"
	v1 "k8s.io/api/rbac/v1"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/serializer"
	utilrand "k8s.io/apimachinery/pkg/util/rand"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	"k8s.io/apimachinery/pkg/util/wait"
	"k8s.io/cli-runtime/pkg/printers"
	kubeset "k8s.io/client-go/kubernetes"
	v12 "k8s.io/client-go/kubernetes/typed/core/v1"
	"k8s.io/client-go/rest"
)

func generateName(base string) string {
	if len(base) > maxGeneratedNameLength {
		base = base[:maxGeneratedNameLength]
	}
	return fmt.Sprintf("%s%s", base, utilrand.String(randomLength))
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
	ta.Logf(fmt.Sprintf("dumpNodes found %d nodes in list", len(nodeList.Items)))
	for _, node := range nodeList.Items {
		_, master := node.Labels["node-role.kubernetes.io/master"]
		if master {
			ta.Logf(fmt.Sprintf("Node %s is master node", node.Name))
		}
		if node.Status.Allocatable.Cpu() == nil {
			ta.Logf(fmt.Sprintf("Node %s does not have allocatable cpu", node.Name))
			continue
		}
		if node.Status.Allocatable.Memory() == nil {
			ta.Logf(fmt.Sprintf("Node %s does not have allocatable mem", node.Name))
			continue
		}
		if node.Status.Allocatable.Storage() == nil {
			ta.Logf(fmt.Sprintf("Node %s does not have allocatable storage", node.Name))
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
		if node.Status.Capacity.Storage() == nil {
			ta.Logf(fmt.Sprintf("Node %s does not have capacity storage", node.Name))
			continue
		}
		alloccpu := node.Status.Allocatable.Cpu()
		allocmem := node.Status.Allocatable.Memory()
		allocstorage := node.Status.Allocatable.Storage()
		capaccpu := node.Status.Capacity.Cpu()
		capacmem := node.Status.Capacity.Memory()
		capstorage := node.Status.Capacity.Storage()
		ta.Logf(fmt.Sprintf("Node %s allocatable CPU %s allocatable mem %s allocatable storage %s capacity CPU %s capacitymem %s capacity storage %s",
			node.Name,
			alloccpu.String(),
			allocmem.String(),
			allocstorage.String(),
			capaccpu.String(),
			capacmem.String(),
			capstorage.String()))
	}
}

func debugAndFailTest(ta *testArgs, failMsg string) {
	GenerateStatusReport(ta.ns, jvmClient, kubeClient, tektonClient)
	dumpPodDetails(ta)
	dumpBadEvents(ta)
	ta.t.Fatalf("%s", failMsg)

}

func commonSetup(t *testing.T, gitCloneUrl string, namespace string) *testArgs {

	ta := &testArgs{
		t:        t,
		timeout:  time.Minute * 15,
		interval: time.Second * 10,
	}
	setupClients(ta.t)

	if len(ta.ns) == 0 {
		ns, _ := os.LookupEnv("JBS_WORKER_NAMESPACE")
		if len(ns) > 0 {
			ta.ns = ns
		} else {
			ta.ns = generateName(namespace)
			namespace := &corev1.Namespace{}
			namespace.Name = ta.ns
			_, err := kubeClient.CoreV1().Namespaces().Create(context.Background(), namespace, metav1.CreateOptions{})

			if err != nil {
				debugAndFailTest(ta, fmt.Sprintf("%#v", err))
			}
		}
	}

	eventClient := kubeClient.EventsV1().Events(ta.ns)
	go watchEvents(eventClient, ta)
	dumpNodes(ta)

	var err error

	// have seen delays in CRD presence along with missing pipeline SA
	err = wait.PollUntilContextTimeout(context.TODO(), 1*time.Second, 1*time.Minute, true, func(ctx context.Context) (done bool, err error) {
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

	ta.gitClone = &tektonpipeline.Task{}
	obj := streamRemoteYamlToTektonObj(gitCloneUrl, ta.gitClone, ta)
	var ok bool
	ta.gitClone, ok = obj.(*tektonpipeline.Task)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("%s did not produce a task: %#v", gitCloneTaskUrl, obj))
	}
	ta.gitClone, err = tektonClient.TektonV1().Tasks(ta.ns).Create(context.TODO(), ta.gitClone, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	path, err := os.Getwd()
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	mavenYamlPath := filepath.Join(path, "..", "..", "hack", "examples", "maven-v0.2.yaml")
	ta.maven = &tektonpipeline.Task{}
	obj = streamFileYamlToTektonObj(mavenYamlPath, ta.maven, ta)
	ta.maven, ok = obj.(*tektonpipeline.Task)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("file %s did not produce a task: %#v", mavenYamlPath, obj))
	}
	// override images if need be
	quayUsername, _ := os.LookupEnv("QUAY_USERNAME")
	analyserImage := os.Getenv("JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE")
	if len(analyserImage) > 0 {
		ta.Logf(fmt.Sprintf("PR analyzer image: %s", analyserImage))
		for i, step := range ta.maven.Spec.Steps {
			if step.Name != "analyse-dependencies" {
				continue
			}
			ta.Logf(fmt.Sprintf("Updating analyse-dependencies step with image %s", analyserImage))
			ta.maven.Spec.Steps[i].Image = analyserImage
		}
	} else if len(quayUsername) > 0 {
		// TODO: delete this block ....
		image := "quay.io/" + quayUsername + "/hacbs-jvm-build-request-processor:dev"
		for i, step := range ta.maven.Spec.Steps {
			if step.Name != "analyse-dependencies" {
				continue
			}
			ta.Logf(fmt.Sprintf("Updating analyse-dependencies step with image %s", image))
			ta.maven.Spec.Steps[i].Image = image
			if strings.Contains(image, "minikube") {
				ta.maven.Spec.Steps[i].ImagePullPolicy = corev1.PullNever
				ta.Logf("Setting pull policy to never for minikube tests")
			}
		}
	}
	ta.maven, err = tektonClient.TektonV1().Tasks(ta.ns).Create(context.TODO(), ta.maven, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	pipelineYamlPath := filepath.Join(path, "..", "..", "hack", "examples", "pipeline.yaml")
	ta.pipeline = &tektonpipeline.Pipeline{}
	obj = streamFileYamlToTektonObj(pipelineYamlPath, ta.pipeline, ta)
	ta.pipeline, ok = obj.(*tektonpipeline.Pipeline)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("file %s did not produce a pipeline: %#v", pipelineYamlPath, obj))
	}
	ta.pipeline, err = tektonClient.TektonV1().Pipelines(ta.ns).Create(context.TODO(), ta.pipeline, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	return ta
}
func setup(t *testing.T, namespace string) *testArgs {
	return setupConfig(t, namespace)
}
func setupConfig(t *testing.T, namespace string) *testArgs {

	ta := commonSetup(t, gitCloneTaskUrl, namespace)
	err := wait.PollUntilContextTimeout(context.TODO(), 1*time.Second, 1*time.Minute, true, func(ctx context.Context) (done bool, err error) {
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

	owner := os.Getenv("QUAY_E2E_ORGANIZATION")
	if owner == "" {
		owner = "redhat-appstudio-qe"
	}

	decoded, err := base64.StdEncoding.DecodeString(os.Getenv("QUAY_TOKEN"))
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	secret := corev1.Secret{ObjectMeta: metav1.ObjectMeta{Name: "jvm-build-image-secrets", Namespace: ta.ns},
		Data: map[string][]byte{corev1.DockerConfigJsonKey: decoded},
		Type: corev1.SecretTypeDockerConfigJson}
	_, err = kubeClient.CoreV1().Secrets(ta.ns).Create(context.TODO(), &secret, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	serviceAccount, err := kubeClient.CoreV1().ServiceAccounts(ta.ns).Get(context.TODO(), "pipeline", metav1.GetOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	serviceAccount.Secrets = append(serviceAccount.Secrets, corev1.ObjectReference{
		Name:      secret.Name,
		Namespace: secret.Namespace,
	})
	_, err = kubeClient.CoreV1().ServiceAccounts(serviceAccount.Namespace).Update(context.TODO(), serviceAccount, metav1.UpdateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	jbsConfig := v1alpha1.JBSConfig{
		ObjectMeta: metav1.ObjectMeta{
			Namespace: ta.ns,
			Name:      v1alpha1.JBSConfigName,
		},
		Spec: v1alpha1.JBSConfigSpec{
			EnableRebuilds: true,
			MavenBaseLocations: map[string]string{
				"maven-repository-300-jboss":     "https://repository.jboss.org/nexus/content/groups/public/",
				"maven-repository-301-confluent": "https://packages.confluent.io/maven",
				"maven-repository-302-redhat":    "https://maven.repository.redhat.com/ga",
				"maven-repository-303-jitpack":   "https://jitpack.io",
				"maven-repository-304-gradle":    "https://repo.gradle.org/artifactory/libs-releases"},

			CacheSettings: v1alpha1.CacheSettings{ //up the cache size, this is a lot of builds all at once, we could limit the number of pods instead but this gets the test done faster
				RequestMemory: "1024Mi",
				LimitMemory:   "1024Mi",
				WorkerThreads: "100",
				RequestCPU:    "10m",
				DisableTLS:    true,
			},
			Registry: v1alpha1.ImageRegistrySpec{
				ImageRegistry: v1alpha1.ImageRegistry{
					Host:       "quay.io",
					Owner:      owner,
					Repository: "test-images",
					PrependTag: strconv.FormatInt(time.Now().UnixMilli(), 10),
				},
			},
		},
		Status: v1alpha1.JBSConfigStatus{},
	}
	createRepo(ta, &jbsConfig)
	err = deployMavenSecret(ta) // openshift-ci.sh does not create secret during e2e testing
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	_, err = jvmClient.JvmbuildserviceV1alpha1().JBSConfigs(ta.ns).Create(context.TODO(), &jbsConfig, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	err = waitForCache(ta)
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	return ta
}

func waitForCache(ta *testArgs) error {
	err := wait.PollUntilContextTimeout(context.TODO(), 10*time.Second, 5*time.Minute, true, func(ctx context.Context) (done bool, err error) {
		cache, err := kubeClient.AppsV1().Deployments(ta.ns).Get(context.TODO(), v1alpha1.CacheDeploymentName, metav1.GetOptions{})
		if err != nil {
			ta.Logf(fmt.Sprintf("get of cache: %s", err.Error()))
			return false, nil
		}
		if cache.Status.AvailableReplicas > 0 {
			ta.Logf("Cache is available")
			return true, nil
		}
		for _, cond := range cache.Status.Conditions {
			if cond.Type == v13.DeploymentProgressing && cond.Status == "False" {
				return false, errors2.New("cache deployment failed")
			}

		}
		ta.Logf("Cache is progressing")
		return false, nil
	})
	if err != nil {
		debugAndFailTest(ta, "cache not present in timely fashion")
	}
	return err
}

func createRepo(ta *testArgs, jbsConfig *v1alpha1.JBSConfig) {
	mavenUsername := os.Getenv("MAVEN_USERNAME")
	mavenRepository := os.Getenv("MAVEN_REPOSITORY")
	mavenPassword := os.Getenv("MAVEN_PASSWORD")
	if len(mavenUsername) > 0 && len(mavenRepository) > 0 && len(mavenPassword) > 0 {
		jbsConfig.Spec.MavenDeployment = v1alpha1.MavenDeployment{
			Username:   mavenUsername,
			Repository: mavenRepository,
		}
		err := deployRepoService(ta)
		if err != nil {
			debugAndFailTest(ta, err.Error())
		}
		err = deployRepoConfigMap(ta)
		if err != nil {
			debugAndFailTest(ta, err.Error())
		}
		err = deployRepo(ta, mavenUsername, mavenPassword)
		if err != nil {
			debugAndFailTest(ta, err.Error())
		}
		err = waitForRepo(ta)
		if err != nil {
			debugAndFailTest(ta, err.Error())
		}
	}
}

func deployMavenSecret(ta *testArgs) error {
	mavenPassword := os.Getenv("MAVEN_PASSWORD")
	var err error
	if len(mavenPassword) > 0 {
		_, err = kubeClient.CoreV1().Secrets(ta.ns).Get(context.TODO(), v1alpha1.MavenSecretName, metav1.GetOptions{})
		if err != nil && errors.IsNotFound(err) {
			ta.Logf(fmt.Sprintf("Creating maven secret in namespace %s", ta.ns))
			mavenSecret := &corev1.Secret{}
			mavenSecret.Name = v1alpha1.MavenSecretName
			mavenSecret.Namespace = ta.ns
			mavenSecret.StringData = map[string]string{"mavenpassword": os.Getenv("MAVEN_PASSWORD")}
			mavenSecret.Type = "Opaque"
			_, err = kubeClient.CoreV1().Secrets(ta.ns).Create(context.TODO(), mavenSecret, metav1.CreateOptions{})
		}
	}
	return err
}

func deployRepoService(ta *testArgs) error {
	_, err := kubeClient.CoreV1().Services(ta.ns).Get(context.TODO(), v1alpha1.RepoDeploymentName, metav1.GetOptions{})
	if err != nil && errors.IsNotFound(err) {
		ta.Logf(fmt.Sprintf("Creating repository service in namespace %s", ta.ns))
		repoService := &corev1.Service{}
		repoService.Name = v1alpha1.RepoDeploymentName
		repoService.Namespace = ta.ns
		repoService.Spec = corev1.ServiceSpec{
			Ports: []corev1.ServicePort{
				{
					Name:       "http",
					Port:       80,
					TargetPort: intstr.IntOrString{IntVal: 8080},
				},
			},
			Type:     corev1.ServiceTypeClusterIP,
			Selector: map[string]string{"app": v1alpha1.RepoDeploymentName},
		}
		_, err = kubeClient.CoreV1().Services(ta.ns).Create(context.TODO(), repoService, metav1.CreateOptions{})
	}
	return err
}

//go:embed Dockerfile.reposilite
var trustedReposiliteImage string

func deployRepoConfigMap(ta *testArgs) error {
	_, err := kubeClient.CoreV1().ConfigMaps(ta.ns).Get(context.TODO(), v1alpha1.RepoConfigMapName, metav1.GetOptions{})
	if err != nil && errors.IsNotFound(err) {
		var path string
		path, err = os.Getwd()
		if err != nil {
			return err
		}
		var configBytes []byte
		configBytes, err = os.ReadFile(filepath.Clean(filepath.Join(path, "reposilite-config.json")))
		if err != nil {
			return err
		}
		cfgMap := corev1.ConfigMap{}
		cfgMap.Name = v1alpha1.RepoConfigMapName
		cfgMap.Namespace = ta.ns
		cfgMap.Data = map[string]string{v1alpha1.RepoConfigFileName: string(configBytes)}
		_, err = kubeClient.CoreV1().ConfigMaps(ta.ns).Create(context.TODO(), &cfgMap, metav1.CreateOptions{})
	}
	return err
}

func deployRepo(ta *testArgs, mavenUsername string, mavenPassword string) error {
	_, err := kubeClient.AppsV1().Deployments(ta.ns).Get(context.TODO(), v1alpha1.RepoDeploymentName, metav1.GetOptions{})
	if err != nil && errors.IsNotFound(err) {
		ta.Logf(fmt.Sprintf("Creating repository in namespace %s", ta.ns))
		repo := &v13.Deployment{}
		repo.Name = v1alpha1.RepoDeploymentName
		repo.Namespace = ta.ns
		repo.Spec.RevisionHistoryLimit = new(int32)
		repo.Spec.Strategy = v13.DeploymentStrategy{Type: v13.RecreateDeploymentStrategyType}
		repo.Spec.Selector = &metav1.LabelSelector{MatchLabels: map[string]string{"app": v1alpha1.RepoDeploymentName}}
		repo.Spec.Template.Labels = map[string]string{"app": v1alpha1.RepoDeploymentName}
		memory := resource.MustParse("256Mi")
		cpu := resource.MustParse("100m")
		port := int32(8080)
		repo.Spec.Template.Spec.ServiceAccountName = "pipeline"
		repo.Spec.Template.Spec.SecurityContext = &corev1.PodSecurityContext{RunAsUser: new(int64)}
		configMountPath := "/config"
		repo.Spec.Template.Spec.Containers = []corev1.Container{{
			Name:            v1alpha1.RepoDeploymentName,
			Image:           strings.TrimSpace(strings.Split(trustedReposiliteImage, "FROM")[1]),
			ImagePullPolicy: corev1.PullIfNotPresent,
			Ports: []corev1.ContainerPort{
				{
					Name:          "http",
					ContainerPort: port,
					Protocol:      "TCP",
				},
			},
			Resources: corev1.ResourceRequirements{
				Requests: map[corev1.ResourceName]resource.Quantity{
					"memory": memory,
					"cpu":    cpu},
				Limits: map[corev1.ResourceName]resource.Quantity{
					"memory": memory,
					"cpu":    cpu},
			},
			StartupProbe:  &corev1.Probe{FailureThreshold: 120, PeriodSeconds: 1, ProbeHandler: corev1.ProbeHandler{HTTPGet: &corev1.HTTPGetAction{Path: "/", Port: intstr.FromInt32(port)}}},
			LivenessProbe: &corev1.Probe{FailureThreshold: 3, PeriodSeconds: 5, ProbeHandler: corev1.ProbeHandler{HTTPGet: &corev1.HTTPGetAction{Path: "/", Port: intstr.FromInt32(port)}}},
			VolumeMounts: []corev1.VolumeMount{{
				Name:      "config",
				MountPath: configMountPath,
			}},
		}}
		repo.Spec.Template.Spec.Volumes = []corev1.Volume{
			{Name: "config", VolumeSource: corev1.VolumeSource{ConfigMap: &corev1.ConfigMapVolumeSource{LocalObjectReference: corev1.LocalObjectReference{Name: v1alpha1.RepoConfigMapName}}}},
		}
		repo.Spec.Template.Spec.Containers[0].Env = []corev1.EnvVar{
			{Name: "REPOSILITE_OPTS", Value: "--shared-configuration " + configMountPath + "/" + v1alpha1.RepoConfigFileName + " --token " + mavenUsername + ":" + mavenPassword},
		}
		_, err = kubeClient.AppsV1().Deployments(ta.ns).Create(context.TODO(), repo, metav1.CreateOptions{})
	}
	return err
}

func waitForRepo(ta *testArgs) error {
	err := wait.PollUntilContextTimeout(context.TODO(), 10*time.Second, 5*time.Minute, true, func(ctx context.Context) (done bool, err error) {
		repo, err := kubeClient.AppsV1().Deployments(ta.ns).Get(context.TODO(), v1alpha1.RepoDeploymentName, metav1.GetOptions{})
		if err != nil {
			ta.Logf(fmt.Sprintf("get of repository: %s", err.Error()))
			return false, nil
		}
		if repo.Status.AvailableReplicas > 0 {
			ta.Logf("Repository is available")
			return true, nil
		}
		for _, cond := range repo.Status.Conditions {
			if cond.Type == v13.DeploymentProgressing && cond.Status == "False" {
				return false, errors2.New("repository deployment failed")
			}

		}
		ta.Logf("Repository is progressing")
		return false, nil
	})
	if err != nil {
		debugAndFailTest(ta, "repository not present in timely fashion")
	}
	return err
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

func decodeBytesToTektonObjbytes(bytes []byte, obj runtime.Object, ta *testArgs) runtime.Object {
	decodingScheme := runtime.NewScheme()
	utilruntime.Must(tektonpipeline.AddToScheme(decodingScheme))
	decoderCodecFactory := serializer.NewCodecFactory(decodingScheme)
	decoder := decoderCodecFactory.UniversalDecoder(tektonpipeline.SchemeGroupVersion)
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
	readAll, err := io.ReadAll(resp.Body)
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	fmt.Printf("Reading from %s url\n", url)
	return decodeBytesToTektonObjbytes(readAll, obj, ta)
}

func streamFileYamlToTektonObj(path string, obj runtime.Object, ta *testArgs) runtime.Object {
	readFile, err := os.ReadFile(filepath.Clean(path))
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	fmt.Printf("Reading from %s file\n", path)
	return decodeBytesToTektonObjbytes(readFile, obj, ta)
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

//go:embed report.html
var reportTemplate string

// dumping the logs slows down generation
// when working on the report you might want to turn it off
// this should always be true in the committed code though
const DUMP_LOGS = true

func GenerateStatusReport(namespace string, jvmClient *jvmclientset.Clientset, kubeClient *kubeset.Clientset, pipelineClient *pipelineclientset.Clientset) {

	directory := os.Getenv("ARTIFACT_DIR")
	if directory == "" {
		directory = "/tmp/jvm-build-service-report"
	} else {
		directory = directory + "/jvm-build-service-report/" + namespace
	}
	err := os.MkdirAll(directory, 0755) //#nosec G306 G301
	if err != nil {
		panic(err)
	}
	podClient := kubeClient.CoreV1().Pods(namespace)
	podList, err := podClient.List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		panic(err)
	}
	pipelineList, err := pipelineClient.TektonV1().PipelineRuns(namespace).List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		panic(err)
	}
	taskRunList, err := pipelineClient.TektonV1().TaskRuns(namespace).List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		panic(err)
	}
	artifact := ArtifactReportData{}
	dependency := DependencyReportData{}
	dependencyBuildClient := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(namespace)
	artifactBuilds, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(namespace).List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		panic(err)
	}
	for _, ab := range artifactBuilds.Items {
		localDir := ab.Status.State + "/" + ab.Name
		tmp := ab
		createdBy := ""
		if ab.Annotations != nil {
			for k, v := range ab.Annotations {
				if strings.HasPrefix(k, artifactbuild.DependencyBuildContaminatedByAnnotation) {
					createdBy = " (created by build " + v + ")"
				}
			}
		}
		message := ""
		if ab.Status.State != v1alpha1.ArtifactBuildStateComplete {
			message = " " + ab.Status.Message
		}
		instance := &ReportInstanceData{Name: ab.Name + createdBy + message, State: ab.Status.State, Yaml: encodeToYaml(&tmp)}
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

		_ = os.MkdirAll(directory+"/"+localDir, 0755) //#nosec G306 G301
		for _, pod := range podList.Items {
			if strings.HasPrefix(pod.Name, ab.Name) {
				logFile := dumpPod(pod, directory, localDir, podClient, true)
				instance.Logs = append(instance.Logs, logFile...)
			}
		}
		for _, pipelineRun := range pipelineList.Items {
			if strings.HasPrefix(pipelineRun.Name, ab.Name) {
				t := pipelineRun
				yaml := encodeToYaml(&t)
				target := directory + "/" + localDir + "-" + "pipeline-" + t.Name
				err := os.WriteFile(target, []byte(yaml), 0644) //#nosec G306)
				if err != nil {
					print(fmt.Sprintf("Failed to write pipleine file %s: %s", target, err))
				}
				instance.Logs = append(instance.Logs, localDir+"-"+"pipeline-"+t.Name)
			}
		}
	}
	sort.Sort(SortableArtifact(artifact.Instances))

	dependencyBuilds, err := dependencyBuildClient.List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		panic(err)
	}
	for _, db := range dependencyBuilds.Items {
		dependency.Total++
		localDir := db.Status.State + "/" + db.Name
		tmp := db
		tool := "maven"
		if db.Status.CurrentBuildAttempt() != nil {
			tool = db.Status.CurrentBuildAttempt().Recipe.Tool
		}
		if db.Status.FailedVerification {
			tool += " (FAILED VERIFICATION)"
		}
		url := strings.TrimSuffix(db.Spec.ScmInfo.SCMURL, ".git")
		if strings.Contains(url, "github.com") {
			if len(db.Spec.ScmInfo.Tag) == 40 && !strings.Contains(db.Spec.ScmInfo.Tag, ".") && !strings.Contains(db.Spec.ScmInfo.Tag, "-") {
				url = fmt.Sprintf("%s/commit/%s", url, db.Spec.ScmInfo.Tag)
			} else {
				url = fmt.Sprintf("%s/releases/tag/%s", url, db.Spec.ScmInfo.Tag)
			}
		}
		instance := &ReportInstanceData{
			State:  db.Status.State,
			Yaml:   encodeToYaml(&tmp),
			Name:   fmt.Sprintf("%s @{%s} (%s) %s", db.Spec.ScmInfo.SCMURL, db.Spec.ScmInfo.Tag, db.Name, tool),
			GitUrl: url,
		}

		dependency.Instances = append(dependency.Instances, instance)
		print(db.Status.State + "\n")
		switch db.Status.State {
		case v1alpha1.DependencyBuildStateComplete:
			dependency.Complete++
		case v1alpha1.DependencyBuildStateFailed:
			dependency.Failed++
		case v1alpha1.DependencyBuildStateContaminated:
			dependency.Contaminated++
		case v1alpha1.DependencyBuildStateBuilding:
			dependency.Building++
		default:
			dependency.Other++
		}
		_ = os.MkdirAll(directory+"/"+localDir, 0755) //#nosec G306 G301
		for index, docker := range db.Status.BuildAttempts {

			localPart := localDir + "-docker-" + strconv.Itoa(index) + ".txt"
			fileName := directory + "/" + localPart
			err = os.WriteFile(fileName, []byte(docker.Build.DiagnosticDockerFile), 0644) //#nosec G306
			if err != nil {
				print(fmt.Sprintf("Failed to write docker filer %s: %s", fileName, err))
			} else {
				instance.Logs = append(instance.Logs, localPart)
			}
		}
		for _, pod := range podList.Items {
			if strings.HasPrefix(pod.Name, db.Name) {
				logFile := dumpPod(pod, directory, localDir, podClient, true)
				instance.Logs = append(instance.Logs, logFile...)
			}
		}
		for _, pipelineRun := range pipelineList.Items {
			println(fmt.Sprintf("pipelinerun %s for DB %s", pipelineRun.Name, db.Name))
			if strings.HasPrefix(pipelineRun.Name, db.Name) {
				t := pipelineRun
				yaml := encodeToYaml(&t)
				localPart := localDir + "-" + t.Name + "-pipelinerun.yaml"
				target := directory + "/" + localPart
				err := os.WriteFile(target, []byte(yaml), 0644) //#nosec G306)
				if err != nil {
					print(fmt.Sprintf("Failed to write pipleine file %s: %s", target, err))
				} else {
					instance.Logs = append(instance.Logs, localPart)
				}
				if db.Status.FailedVerification {
					verification := ""
					for _, res := range pipelineRun.Status.Results {
						if res.Name == dependencybuild.PipelineResultVerificationResult {
							verification = res.Value.StringVal
						}
					}
					if verification != "" {
						localPart := localDir + "-" + "pipeline-" + t.Name + "-FAILED-VERIFICATION"
						target := directory + "/" + localPart

						parsed := map[string][]string{}
						err := json.Unmarshal([]byte(verification), &parsed)
						if err != nil {
							print(fmt.Sprintf("Failed to parse json for pipleine file %s: %s", target, err))
						}
						output := ""
						for k, v := range parsed {
							if len(v) > 0 {
								output += "\n\nFAILED: " + k + "\n"
								for _, i := range v {
									output += "\t" + i + "\n"
								}
							}
						}

						err = os.WriteFile(target, []byte(output), 0644) //#nosec G306)
						if err != nil {
							print(fmt.Sprintf("Failed to write pipleine file %s: %s", target, err))
						} else {
							instance.Logs = append(instance.Logs, localPart)
						}
					}
				}
			}
		}
		for _, taskRun := range taskRunList.Items {
			println(fmt.Sprintf("taskrun %s for DB %s", taskRun.Name, db.Name))
			if strings.HasPrefix(taskRun.Name, db.Name) {
				t := taskRun
				yaml := encodeToYaml(&t)
				localPart := localDir + "-" + t.Name + "-taskrun.yaml"
				target := directory + "/" + localPart
				println(fmt.Sprintf("writing taskrun %s to %s", taskRun.Name, target))
				err := os.WriteFile(target, []byte(yaml), 0644) //#nosec G306)
				if err != nil {
					print(fmt.Sprintf("Failed to write taskrun file %s: %s", target, err))
				} else {
					instance.Logs = append(instance.Logs, localPart)
				}
			}
		}
	}
	sort.Sort(SortableArtifact(dependency.Instances))

	report := directory + "/index.html"

	data := ReportData{
		Name:       namespace,
		Artifact:   artifact,
		Dependency: dependency,
	}

	_ = os.MkdirAll(directory+"/logs", 0755) //#nosec G306 G301
	for _, pod := range podList.Items {
		if strings.HasPrefix(pod.Name, "jvm-build-workspace-artifact-cache") {
			logFile := dumpPod(pod, directory, "logs", podClient, true)
			data.CacheLogs = append(data.CacheLogs, logFile...)
		} else if strings.HasPrefix(pod.Name, v1alpha1.RepoDeploymentName) {
			logFile := dumpPod(pod, directory, "logs", podClient, true)
			data.RepoLogs = append(data.RepoLogs, logFile...)
		}
	}
	operatorPodClient := kubeClient.CoreV1().Pods("jvm-build-service")
	operatorList, err := operatorPodClient.List(context.TODO(), metav1.ListOptions{})
	if err == nil {
		for _, pod := range operatorList.Items {
			logFile := dumpPod(pod, directory, "logs", operatorPodClient, true)
			data.OperatorLogs = append(data.OperatorLogs, logFile...)
		}
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
		panic(err)
	}
	print("Created report file://" + report + "\n")
}

func innerDumpPod(req *rest.Request, baseDirectory, localDirectory, podName, containerName string, skipSkipped bool) error {
	var readCloser io.ReadCloser
	var err error
	readCloser, err = req.Stream(context.TODO())
	if err != nil {
		print(fmt.Sprintf("error getting pod logs for container %s: %s", containerName, err.Error()))
		return err
	}
	defer func(readCloser io.ReadCloser) {
		err := readCloser.Close()
		if err != nil {
			print(fmt.Sprintf("Failed to close ReadCloser reading pod logs for container %s: %s", containerName, err.Error()))
		}
	}(readCloser)
	var b []byte
	b, err = io.ReadAll(readCloser)
	if skipSkipped && len(b) < 1000 {
		if strings.Contains(string(b), "Skipping step because a previous step failed") {
			return errors2.New("the step failed")
		}
	}
	if err != nil {
		print(fmt.Sprintf("error reading pod stream %s", err.Error()))
		return err
	}
	directory := baseDirectory + "/" + localDirectory
	err = os.MkdirAll(directory, 0755) //#nosec G306 G301
	if err != nil {
		print(fmt.Sprintf("Failed to create artifact dir %s: %s", directory, err))
		return err
	}
	localPart := localDirectory + podName + "-" + containerName
	fileName := baseDirectory + "/" + localPart
	err = os.WriteFile(fileName, b, 0644) //#nosec G306
	if err != nil {
		print(fmt.Sprintf("Failed artifact dir %s: %s", directory, err))
		return err
	}
	return nil
}

func dumpPod(pod corev1.Pod, baseDirectory string, localDirectory string, kubeClient v12.PodInterface, skipSkipped bool) []string {
	if !DUMP_LOGS {
		return []string{}
	}
	containers := []corev1.Container{}
	containers = append(containers, pod.Spec.InitContainers...)
	containers = append(containers, pod.Spec.Containers...)
	ret := []string{}
	for _, container := range containers {
		req := kubeClient.GetLogs(pod.Name, &corev1.PodLogOptions{Container: container.Name})
		err := innerDumpPod(req, baseDirectory, localDirectory, pod.Name, container.Name, skipSkipped)
		if err != nil {
			continue
		}
		ret = append(ret, localDirectory+pod.Name+"-"+container.Name)
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
	Building     int
	Other        int
	Total        int
	Instances    []*ReportInstanceData
}
type ReportData struct {
	Name         string
	Artifact     ArtifactReportData
	Dependency   DependencyReportData
	CacheLogs    []string
	OperatorLogs []string
	RepoLogs     []string
}

type ReportInstanceData struct {
	Name   string
	Logs   []string
	State  string
	Yaml   string
	GitUrl string
}

type SortableArtifact []*ReportInstanceData

func (a SortableArtifact) Len() int           { return len(a) }
func (a SortableArtifact) Less(i, j int) bool { return strings.Compare(a[i].Name, a[j].Name) < 0 }
func (a SortableArtifact) Swap(i, j int)      { a[i], a[j] = a[j], a[i] }

type MavenRepoDetails struct {
	Username string
	Url      string
	Password string
}

func setupMinikube(t *testing.T, namespace string) *testArgs {

	ta := commonSetup(t, gitCloneTaskUrl, namespace)
	//go through and limit all deployments
	//we have very little memory, we need some limits to make sure minikube can actually run
	//limit every deployment to 100mb
	list, err := kubeClient.CoreV1().Namespaces().List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	for _, ns := range list.Items {
		deploymentList, err := kubeClient.AppsV1().Deployments(ns.Name).List(context.TODO(), metav1.ListOptions{})
		if err != nil {
			debugAndFailTest(ta, err.Error())
		}
		for depIdx := range deploymentList.Items {
			dep := deploymentList.Items[depIdx]
			if dep.Namespace != "jvm-build-service" {
				fmt.Printf("Adjusting memory limit for pod %s.%s\n", dep.Namespace, dep.Name)
				for i := range dep.Spec.Template.Spec.Containers {
					if dep.Spec.Template.Spec.Containers[i].Resources.Limits == nil {
						dep.Spec.Template.Spec.Containers[i].Resources.Limits = corev1.ResourceList{}
					}
					if dep.Spec.Template.Spec.Containers[i].Resources.Requests == nil {
						dep.Spec.Template.Spec.Containers[i].Resources.Requests = corev1.ResourceList{}
					}
					dep.Spec.Template.Spec.Containers[i].Resources.Limits[corev1.ResourceMemory] = resource.MustParse("110Mi")
					dep.Spec.Template.Spec.Containers[i].Resources.Requests[corev1.ResourceMemory] = resource.MustParse("100Mi")
				}
				_, err := kubeClient.AppsV1().Deployments(ns.Name).Update(context.TODO(), &dep, metav1.UpdateOptions{})
				if err != nil {
					panic(err)
				}
			}
		}
	}

	// Don't need to create a ServiceAccount as its created in deploy/base/sa.yaml

	//now create the binding
	crb := v1.ClusterRoleBinding{}
	crb.Name = "pipeline-" + ta.ns
	crb.Namespace = ta.ns
	crb.RoleRef.Name = "pipeline"
	crb.RoleRef.Kind = "ClusterRole"
	crb.Subjects = []v1.Subject{{Name: "pipeline", Kind: "ServiceAccount", Namespace: ta.ns}}
	_, err = kubeClient.RbacV1().ClusterRoleBindings().Create(context.Background(), &crb, metav1.CreateOptions{})
	if err != nil {
		fmt.Printf("Problem creating cluster role %#v \n", err)
		debugAndFailTest(ta, "pipeline ClusterRole not created in timely fashion")
	}

	var port string
	var insecure bool
	// DEV_IP is set in GH Action minikube.yaml
	devIp := os.Getenv("DEV_IP")
	if devIp == "" {
		devIp = "quay.io"
		port = ""
		insecure = false
	} else {
		port = "5000"
		insecure = true
	}
	owner := os.Getenv("QUAY_USERNAME")
	if owner == "" {
		owner = "testuser"
	}
	jbsConfig := v1alpha1.JBSConfig{
		ObjectMeta: metav1.ObjectMeta{
			Namespace:   ta.ns,
			Name:        v1alpha1.JBSConfigName,
			Annotations: map[string]string{jbsconfig.TestRegistry: strconv.FormatBool(insecure)},
		},
		Spec: v1alpha1.JBSConfigSpec{
			EnableRebuilds:    true,
			AdditionalRecipes: []string{"https://github.com/jvm-build-service-test-data/recipe-repo"},
			BuildSettings: v1alpha1.BuildSettings{
				BuildRequestMemory: "512Mi",
				TaskRequestMemory:  "256Mi",
				TaskLimitMemory:    "256mi",
			},
			MavenBaseLocations: map[string]string{
				"maven-repository-300-jboss":     "https://repository.jboss.org/nexus/content/groups/public/",
				"maven-repository-301-confluent": "https://packages.confluent.io/maven",
				"maven-repository-302-redhat":    "https://maven.repository.redhat.com/ga",
				"maven-repository-303-jitpack":   "https://jitpack.io"},

			CacheSettings: v1alpha1.CacheSettings{ //up the cache size, this is a lot of builds all at once, we could limit the number of pods instead but this gets the test done faster
				RequestMemory: "1024Mi",
				LimitMemory:   "1024Mi",
				RequestCPU:    "200m",
				LimitCPU:      "500m",
				WorkerThreads: "100",
				DisableTLS:    true,
				Storage:       "756Mi",
			},
			Registry: v1alpha1.ImageRegistrySpec{
				ImageRegistry: v1alpha1.ImageRegistry{
					Host:       devIp,
					Owner:      owner,
					Repository: "test-images",
					Port:       port,
					Insecure:   insecure,
					PrependTag: strconv.FormatInt(time.Now().UnixMilli(), 10),
				},
			},
		},
		Status: v1alpha1.JBSConfigStatus{},
	}
	createRepo(ta, &jbsConfig)
	_, err = jvmClient.JvmbuildserviceV1alpha1().JBSConfigs(ta.ns).Create(context.TODO(), &jbsConfig, metav1.CreateOptions{})
	if err != nil {
		fmt.Printf("Problem creating JBSConfig %#v \n", err)
		debugAndFailTest(ta, err.Error())
	}

	time.Sleep(time.Second * 10)

	dumpPodDetails(ta)

	err = waitForCache(ta)
	if err != nil {
		fmt.Printf("Problem waiting for cache %#v \n", err)
		debugAndFailTest(ta, err.Error())
	}
	return ta
}

func dumpPodDetails(ta *testArgs) {
	list, err := kubeClient.CoreV1().Namespaces().List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	for _, ns := range list.Items {
		podList, err := kubeClient.CoreV1().Pods(ns.Name).List(context.TODO(), metav1.ListOptions{})
		if err != nil {
			debugAndFailTest(ta, err.Error())
		}
		for _, pod := range podList.Items {
			fmt.Printf("Pod %s\n", pod.Name)
			for _, cs := range pod.Spec.Containers {
				fmt.Printf("Container %s has CPU limit %s and request %s and Memory limit %s and request %s\n", cs.Name, cs.Resources.Limits.Cpu().String(), cs.Resources.Requests.Cpu().String(), cs.Resources.Limits.Memory().String(), cs.Resources.Requests.Memory().String())
			}
		}
	}
}
