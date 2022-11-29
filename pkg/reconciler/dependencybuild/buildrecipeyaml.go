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

//go:embed scripts/install-package.sh
var packageTemplate string

func createPipelineSpec(maven bool, commitTime int64, userConfig *v1alpha12.UserConfig, recipe *v1alpha12.BuildRecipe, systemConfig *v1alpha12.SystemConfig) (*pipelinev1beta1.PipelineSpec, error) {

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
		"--logs-path=$(workspaces.source.path)/logs",
		"--source-path=$(workspaces.source.path)/source",
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

	install := ""
	for count, i := range recipe.AdditionalDownloads {
		if i.FileType == "tar" {
			if i.BinaryPath == "" {
				install = "echo 'Binary path not specified for package " + i.Uri + "'; exit 1"
			}

		} else if i.FileType == "executable" {
			if i.FileName == "" {
				install = "echo 'File name not specified for package " + i.Uri + "'; exit 1"
			}
		} else {
			//unknown
			//we still run the pipeline so there is logs
			install = "echo 'Unknown file type " + i.FileType + " for package " + i.Uri + "'; exit 1"
			break
		}
		template := packageTemplate
		fileName := i.FileName
		if fileName == "" {
			fileName = "package-" + strconv.Itoa(count)
		}
		template = strings.ReplaceAll(template, "{URI}", i.Uri)
		template = strings.ReplaceAll(template, "{FILENAME}", fileName)
		template = strings.ReplaceAll(template, "{SHA256}", i.Sha256)
		template = strings.ReplaceAll(template, "{TYPE}", i.FileType)
		template = strings.ReplaceAll(template, "{BINARY_PATH}", i.BinaryPath)
		install = install + template
	}

	preprocessorArgs := []string{
		"maven-prepare",
		"-r",
		"$(params.CACHE_URL)",
		"$(workspaces." + WorkspaceSource + ".path)/source",
	}
	if !maven {
		preprocessorArgs[0] = "gradle-prepare"
	}
	gitArgs := []string{"-path=$(workspaces." + WorkspaceSource + ".path)/source", "-url=$(params." + PipelineScmUrl + ")", "-revision=$(params." + PipelineScmTag + ")"}

	if recipe.DisableSubmodules {
		gitArgs = append(gitArgs, "-submodules=false")
	}

	gitInitImage := settingOrDefault(systemConfig.Spec.Images.GitInit, "registry.redhat.io/openshift-pipelines/pipelines-git-init-rhel8@sha256:af7dd5b3b1598a980f17d5f5d3d8a4b11ab4f5184677f7f17ad302baa36bd3c1")

	defaultContainerRequestMemory, err := resource.ParseQuantity(settingOrDefault(userConfig.Spec.BuildSettings.TaskRequestMemory, "256Mi"))
	if err != nil {
		return nil, err
	}
	buildContainerRequestMemory, err := resource.ParseQuantity(settingOrDefault(userConfig.Spec.BuildSettings.BuildRequestMemory, "512Mi"))
	if err != nil {
		return nil, err
	}
	defaultContainerRequestCPU, err := resource.ParseQuantity(settingOrDefault(userConfig.Spec.BuildSettings.TaskRequestCPU, "10m"))
	if err != nil {
		return nil, err
	}
	defaultContainerLimitCPU, err := resource.ParseQuantity(settingOrDefault(userConfig.Spec.BuildSettings.TaskLimitCPU, "300m"))
	if err != nil {
		return nil, err
	}
	buildContainerRequestCPU, err := resource.ParseQuantity(settingOrDefault(userConfig.Spec.BuildSettings.BuildRequestCPU, "300m"))
	if err != nil {
		return nil, err
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
			{Name: PipelineCacheUrl, Type: pipelinev1beta1.ParamTypeString, Default: &pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: "http://jvm-build-workspace-artifact-cache.$(context.pipelineRun.namespace).svc.cluster.local/v1/cache/default/" + strconv.FormatInt(commitTime, 10)}},
		},
		Results: []pipelinev1beta1.TaskResult{{Name: artifactbuild.Contaminants}, {Name: artifactbuild.DeployedResources}, {Name: artifactbuild.Image}},
		Steps: []pipelinev1beta1.Step{
			{
				Name:            "git-clone",
				Image:           gitInitImage,
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Resources: v1.ResourceRequirements{
					Requests: v1.ResourceList{"memory": defaultContainerRequestMemory, "cpu": defaultContainerRequestCPU},
					Limits:   v1.ResourceList{"memory": defaultContainerRequestMemory, "cpu": defaultContainerLimitCPU},
				},
				Args: gitArgs,
			},
			{
				Name:            "settings",
				Image:           "$(params." + PipelineImage + ")", //just use the builder image here, it has everything needed to generate the settings
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Resources: v1.ResourceRequirements{
					Requests: v1.ResourceList{"memory": defaultContainerRequestMemory, "cpu": defaultContainerRequestCPU},
					Limits:   v1.ResourceList{"memory": defaultContainerRequestMemory, "cpu": defaultContainerLimitCPU},
				},
				Script: settings,
			},
			{
				Name:            "preprocessor",
				Image:           "$(params." + PipelineRequestProcessorImage + ")",
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Resources: v1.ResourceRequirements{
					//TODO: make configurable
					Requests: v1.ResourceList{"memory": defaultContainerRequestMemory, "cpu": defaultContainerRequestCPU},
					Limits:   v1.ResourceList{"memory": defaultContainerRequestMemory, "cpu": defaultContainerLimitCPU},
				},
				Args: preprocessorArgs,
			},
			{
				Name:            "build",
				Image:           "$(params." + PipelineImage + ")",
				WorkingDir:      "$(workspaces." + WorkspaceSource + ".path)/source/$(params." + PipelinePath + ")",
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Env: []v1.EnvVar{
					{Name: PipelineCacheUrl, Value: "$(params." + PipelineCacheUrl + ")"},
					{Name: PipelineEnforceVersion, Value: "$(params." + PipelineEnforceVersion + ")"},
				},
				Resources: v1.ResourceRequirements{
					//TODO: limits management and configuration
					Requests: v1.ResourceList{"memory": buildContainerRequestMemory, "cpu": buildContainerRequestCPU},
				},
				Args: []string{"$(params.GOALS[*])"},

				Script: strings.ReplaceAll(strings.ReplaceAll(build, "{{INSTALL_PACKAGE_SCRIPT}}", install), "{{PRE_BUILD_SCRIPT}}", recipe.PreBuildScript),
			},
			{
				Name:            "deploy-and-check-for-contaminates",
				Image:           "$(params." + PipelineRequestProcessorImage + ")",
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Env: []v1.EnvVar{
					{Name: "REGISTRY_TOKEN", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha12.UserSecretName}, Key: v1alpha12.UserSecretTokenKey, Optional: &trueBool}}},
				},
				Resources: v1.ResourceRequirements{
					//TODO: make configurable
					Requests: v1.ResourceList{"memory": buildContainerRequestMemory, "cpu": defaultContainerRequestCPU},
					Limits:   v1.ResourceList{"memory": buildContainerRequestMemory, "cpu": defaultContainerLimitCPU},
				},
				Args: deployArgs,
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
	return ps, nil
}

func settingOrDefault(setting, def string) string {
	if len(strings.TrimSpace(setting)) == 0 {
		return def
	}
	return setting
}
