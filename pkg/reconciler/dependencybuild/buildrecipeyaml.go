package dependencybuild

import (
	_ "embed"
	v1alpha12 "github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1alpha1"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	"strconv"
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

func createPipelineSpec(maven bool, namespace string, commitTime int64, userConfig *v1alpha12.UserConfig) *pipelinev1beta1.PipelineSpec {
	var settings string
	var build string
	trueBool := true
	if maven {
		settings = mavenSettings
		build = mavenBuild
	} else {
		settings = gradleSettings
		build = gradleBuild
	}
	zero := int64(0)
	deployArgs := []string{
		"deploy-container",
		"--tar-path=$(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz",
		"--task-run=$(context.taskRun.name)",
	}
	if userConfig.Spec.ImageRegistry.Host != "" {
		deployArgs = append(deployArgs, "--registry-host="+userConfig.Spec.ImageRegistry.Host)
	}
	if userConfig.Spec.ImageRegistry.Port != "" {
		deployArgs = append(deployArgs, "--registry-port="+userConfig.Spec.ImageRegistry.Port)
	}
	if userConfig.Spec.ImageRegistry.Owner != "" {
		deployArgs = append(deployArgs, "--registry-owner="+userConfig.Spec.ImageRegistry.Owner)
	}
	if userConfig.Spec.ImageRegistry.Repository != "" {
		deployArgs = append(deployArgs, "--registry-repository="+userConfig.Spec.ImageRegistry.Repository)
	}
	if userConfig.Spec.ImageRegistry.Insecure {
		deployArgs = append(deployArgs, "--registry-insecure=")
	}
	if userConfig.Spec.ImageRegistry.PrependTag != "" {
		deployArgs = append(deployArgs, "--registry-prepend-tag="+userConfig.Spec.ImageRegistry.PrependTag)
	}
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
			{Name: PipelineRequestProcessorImage, Type: pipelinev1beta1.ParamTypeString},
			{Name: PipelineIgnoredArtifacts, Type: pipelinev1beta1.ParamTypeString},
			{Name: PipelineCacheUrl, Type: pipelinev1beta1.ParamTypeString, Default: &pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: "http://jvm-build-workspace-artifact-cache." + namespace + ".svc.cluster.local/v1/cache/default/" + strconv.FormatInt(commitTime, 10)}},
			{Name: "NAMESPACE", Type: pipelinev1beta1.ParamTypeString, Default: &pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: namespace}},
		},
		Results: []pipelinev1beta1.TaskResult{{Name: artifactbuild.Contaminants}, {Name: artifactbuild.DeployedResources}},
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
					Image:           "$(params." + PipelineRequestProcessorImage + ")",
					SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
					Env: []v1.EnvVar{
						{Name: "REGISTRY_TOKEN", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha12.UserSecretName}, Key: v1alpha12.UserSecretTokenKey, Optional: &trueBool}}},
					},
					Resources: v1.ResourceRequirements{
						//TODO: make configurable
						Requests: v1.ResourceList{"memory": resource.MustParse("256Mi"), "cpu": resource.MustParse("10m")},
						Limits:   v1.ResourceList{"memory": resource.MustParse("256Mi"), "cpu": resource.MustParse("300m")},
					},
					Args: deployArgs,
				},
			},
		},
	}
	if !maven {
		buildSetup.Params = append(buildSetup.Params, v1alpha1.ParamSpec{Name: PipelineGradleManipulatorArgs, Type: pipelinev1beta1.ParamTypeString, Default: &pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: "-DdependencySource=NONE -DignoreUnresolvableDependencies=true -DpluginRemoval=ALL -DversionModification=false"}})
	}

	ps := &pipelinev1beta1.PipelineSpec{
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
