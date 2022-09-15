package dependencybuild

import (
	_ "embed"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/configmap"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1alpha1"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	"k8s.io/apimachinery/pkg/util/intstr"
	"strings"
)

const (
	WorkspaceBuildSettings = "build-settings"
	WorkspaceSource        = "source"
)

//go:embed scripts/maven-settings.sh
var mavenSettings string

//go:embed scripts/gradle-settings.sh
var gradleSettings string

//go:embed scripts/maven-build.sh
var mavenBuild string

//go:embed scripts/gradle-build.sh
var gradleBuild string

//go:embed scripts/deploy.sh
var deploy string

func createPipelineSpec(maven bool, sidecarImage string, namespace string) *pipelinev1beta1.PipelineSpec {
	var settings string
	var build string
	if maven {
		settings = mavenSettings
		build = mavenBuild
	} else {
		settings = gradleSettings
		build = gradleBuild
	}
	zero := int64(0)
	buildSetup := pipelinev1beta1.TaskSpec{
		Workspaces: []pipelinev1beta1.WorkspaceDeclaration{{Name: WorkspaceBuildSettings}, {Name: WorkspaceSource}},
		Params: []v1alpha1.ParamSpec{
			{Name: PipelineBuildId, Type: pipelinev1beta1.ParamTypeString},
			{Name: PipelineScmUrl, Type: pipelinev1beta1.ParamTypeString},
			{Name: PipelineScmTag, Type: pipelinev1beta1.ParamTypeString},
			{Name: PipelineImage, Type: pipelinev1beta1.ParamTypeString},
			{Name: PipelineGoals, Type: pipelinev1beta1.ParamTypeArray},
			{Name: PipelineJavaVersion, Type: pipelinev1beta1.ParamTypeString},
			{Name: PipelineToolVersion, Type: pipelinev1beta1.ParamTypeString},
			{Name: PipelinePath, Type: pipelinev1beta1.ParamTypeString},
			{Name: PipelineEnforceVersion, Type: pipelinev1beta1.ParamTypeString},
			{Name: PipelineIgnoredArtifacts, Type: pipelinev1beta1.ParamTypeString},
			{Name: "NAMESPACE", Type: pipelinev1beta1.ParamTypeString, Default: &pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: namespace}},
		},
		Sidecars: []pipelinev1beta1.Sidecar{createSidecar(sidecarImage, namespace)},
		Steps: []pipelinev1beta1.Step{
			{
				Container: v1.Container{
					Name:  "git-clone",
					Image: "gcr.io/tekton-releases/github.com/tektoncd/pipeline/cmd/git-init:v0.21.0", //TODO: should not be hard coded
					Resources: v1.ResourceRequirements{
						Requests: v1.ResourceList{"memory": resource.MustParse("128Mi"), "cpu": resource.MustParse("10m")},
						Limits:   v1.ResourceList{"memory": resource.MustParse("512Mi"), "cpu": resource.MustParse("300m")},
					},
					Args: []string{"-path=$(workspaces." + WorkspaceSource + ".path)", "-url=$(params." + PipelineScmUrl + ")", "-revision=$(params." + PipelineScmTag + ")"},
				},
			},
			{
				Container: v1.Container{
					Name:            "settings",
					Image:           "registry.access.redhat.com/ubi8/ubi:8.5", //TODO: should not be hard coded
					SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
					Resources: v1.ResourceRequirements{
						Requests: v1.ResourceList{"memory": resource.MustParse("128Mi"), "cpu": resource.MustParse("10m")},
						Limits:   v1.ResourceList{"memory": resource.MustParse("128Mi"), "cpu": resource.MustParse("300m")},
					},
				},
				Script: settings,
			},
			{
				Container: v1.Container{
					Name:            "build",
					Image:           "$(params." + PipelineImage + ")",
					WorkingDir:      "$(workspaces." + WorkspaceSource + ".path)/$(params." + PipelinePath + ")",
					SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
					Resources: v1.ResourceRequirements{
						//TODO: limits management and configuration
						Requests: v1.ResourceList{"memory": resource.MustParse("512Mi"), "cpu": resource.MustParse("300m")},
					},
					Args: []string{"$(params.GOALS[*])"},
				},
				Script: build,
			},
			{
				Container: v1.Container{
					Name:            "deploy-and-check-for-contaminates",
					Image:           "registry.access.redhat.com/ubi8/ubi:8.5", //TODO: hard coded
					SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
					Resources: v1.ResourceRequirements{
						//TODO: make configurable
						Requests: v1.ResourceList{"memory": resource.MustParse("256Mi"), "cpu": resource.MustParse("10m")},
						Limits:   v1.ResourceList{"memory": resource.MustParse("256Mi"), "cpu": resource.MustParse("300m")},
					},
				},
				Script: deploy,
			},
		},
	}
	if !maven {
		buildSetup.Params = append(buildSetup.Params, v1alpha1.ParamSpec{Name: PipelineGradleManipulatorArgs, Type: pipelinev1beta1.ParamTypeString, Default: &pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: "-DdependencySource=NONE -DignoreUnresolvableDependencies=true -DpluginRemoval=ALL -DversionModification=false"}})
	}

	ps := &pipelinev1beta1.PipelineSpec{
		Results: []pipelinev1beta1.PipelineResult{},
		Tasks: []pipelinev1beta1.PipelineTask{
			{
				Name: artifactbuild.TaskName,
				TaskSpec: &pipelinev1beta1.EmbeddedTask{
					TaskSpec: buildSetup,
				},
				Params: []pipelinev1beta1.Param{}, Workspaces: []pipelinev1beta1.WorkspacePipelineTaskBinding{
					{Name: WorkspaceBuildSettings, Workspace: WorkspaceBuildSettings},
					{Name: WorkspaceSource, Workspace: "source"},
				},
			},
		},
		Workspaces: []v1alpha1.PipelineWorkspaceDeclaration{{Name: WorkspaceBuildSettings}, {Name: WorkspaceSource}},
	}

	for _, i := range buildSetup.Results {
		ps.Results = append(ps.Results, pipelinev1beta1.PipelineResult{Name: i.Name, Description: i.Description, Value: "$(tasks." + artifactbuild.TaskName + ".results." + i.Name + ")"})
	}
	for _, i := range buildSetup.Params {
		ps.Params = append(ps.Params, pipelinev1beta1.ParamSpec{Name: i.Name, Description: i.Description, Default: i.Default, Type: i.Type})
		var value pipelinev1beta1.ArrayOrString
		if i.Type == pipelinev1beta1.ParamTypeString {
			value = pipelinev1beta1.ArrayOrString{Type: i.Type, StringVal: "$(params." + i.Name + ")"}
		} else {
			value = pipelinev1beta1.ArrayOrString{Type: i.Type, ArrayVal: []string{"$(params." + i.Name + "[*])"}}
		}
		ps.Tasks[0].Params = append(ps.Tasks[0].Params, pipelinev1beta1.Param{
			Name:  i.Name,
			Value: value})
	}
	return ps
}

func createSidecar(image string, namespace string) pipelinev1beta1.Sidecar {
	trueBool := true
	zero := int64(0)
	sidecar := pipelinev1beta1.Sidecar{
		Container: v1.Container{
			Name:  "proxy",
			Image: image,
			Env: []v1.EnvVar{
				{Name: "QUARKUS_LOG_FILE_ENABLE", Value: "true"},
				{Name: "QUARKUS_LOG_FILE_PATH", Value: "$(workspaces." + WorkspaceBuildSettings + ".path)/sidecar.log"},
				{Name: "IGNORED_ARTIFACTS", Value: "$(params.IGNORED_ARTIFACTS)"},
				{Name: "QUARKUS_VERTX_EVENT_LOOPS_POOL_SIZE", Value: "2"},
				{Name: "QUARKUS_THREAD_POOL_MAX_THREADS", Value: "6"},
				{Name: "QUARKUS_REST_CLIENT_CACHE_SERVICE_URL", Value: "http://" + configmap.CacheDeploymentName + "." + namespace + ".svc.cluster.local"},
				{Name: "QUARKUS_S3_ENDPOINT_OVERRIDE", Value: "http://" + configmap.LocalstackDeploymentName + "." + namespace + ".svc.cluster.local:4572"},
				{Name: "QUARKUS_S3_AWS_REGION", Value: "us-east-1"},
				{Name: "QUARKUS_S3_AWS_CREDENTIALS_TYPE", Value: "static"},
				{Name: "QUARKUS_S3_AWS_CREDENTIALS_STATIC_PROVIDER_ACCESS_KEY_ID", Value: "accesskey"},
				{Name: "QUARKUS_S3_AWS_CREDENTIALS_STATIC_PROVIDER_SECRET_ACCESS_KEY", Value: "secretkey"},
				{Name: "REGISTRY_TOKEN",
					ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{Key: "registry.token", LocalObjectReference: v1.LocalObjectReference{Name: "jvm-build-secrets"}, Optional: &trueBool}},
				},
			},
			VolumeMounts: []v1.VolumeMount{
				{Name: "$(workspaces." + WorkspaceBuildSettings + ".volume)", MountPath: "$(workspaces." + WorkspaceBuildSettings + ".path)"}},
			LivenessProbe: &v1.Probe{
				ProbeHandler:        v1.ProbeHandler{HTTPGet: &v1.HTTPGetAction{Path: "/q/health/live", Port: intstr.IntOrString{IntVal: 2000}}},
				InitialDelaySeconds: 1,
				PeriodSeconds:       3,
			},
			ReadinessProbe: &v1.Probe{
				ProbeHandler:        v1.ProbeHandler{HTTPGet: &v1.HTTPGetAction{Path: "/q/health/ready", Port: intstr.IntOrString{IntVal: 2000}}},
				InitialDelaySeconds: 1,
				PeriodSeconds:       3,
			},
			Resources: v1.ResourceRequirements{
				//TODO: make configurable
				Requests: map[v1.ResourceName]resource.Quantity{"memory": resource.MustParse("256Mi"), "cpu": resource.MustParse("10m")},
				Limits:   map[v1.ResourceName]resource.Quantity{"memory": resource.MustParse("256Gi"), "cpu": resource.MustParse("2")}},
			SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
		}}

	if !strings.HasPrefix(image, "quay.io/redhat-appstudio") {
		// work around for developer mode while we are hard coding the task spec in the controller
		sidecar.ImagePullPolicy = v1.PullAlways
	}
	return sidecar
}
