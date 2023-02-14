package dependencybuild

import (
	_ "embed"
	"encoding/base64"
	"fmt"
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

//go:embed scripts/sbt-build.sh
var sbtBuild string

//go:embed scripts/ant-build.sh
var antBuild string

//go:embed scripts/install-package.sh
var packageTemplate string

func createPipelineSpec(tool string, commitTime int64, jbsConfig *v1alpha12.JBSConfig, recipe *v1alpha12.BuildRecipe, db *v1alpha12.DependencyBuild, paramValues []pipelinev1beta1.Param) (*pipelinev1beta1.PipelineSpec, string, error) {

	zero := int64(0)
	verifyBuiltArtifactsArgs := []string{
		"verify-built-artifacts",
		"--repository-url=$(params.CACHE_URL)",
		"--global-settings=/usr/share/maven/conf/settings.xml",
		"--settings=$(workspaces.build-settings.path)/settings.xml",
		"--deploy-path=$(workspaces.source.path)/artifacts",
		"--results-file=$(results." + artifactbuild.PassedVerification + ".path)",
	}

	if !jbsConfig.Spec.RequireArtifactVerification {
		verifyBuiltArtifactsArgs = append(verifyBuiltArtifactsArgs, "--report-only")
	}
	deployArgs := []string{
		"deploy-container",
		"--path=$(workspaces.source.path)/artifacts",
		"--logs-path=$(workspaces.source.path)/logs",
		"--source-path=$(workspaces.source.path)/source",
		"--task-run=$(context.taskRun.name)",
	}
	if jbsConfig.Spec.ImageRegistry.Host != "" {
		deployArgs = append(deployArgs, "--registry-host="+jbsConfig.Spec.ImageRegistry.Host)
	}
	if jbsConfig.Spec.ImageRegistry.Port != "" {
		deployArgs = append(deployArgs, "--registry-port="+jbsConfig.Spec.ImageRegistry.Port)
	}
	if jbsConfig.Spec.ImageRegistry.Owner != "" {
		deployArgs = append(deployArgs, "--registry-owner="+jbsConfig.Spec.ImageRegistry.Owner)
	}
	if jbsConfig.Spec.ImageRegistry.Repository != "" {
		deployArgs = append(deployArgs, "--registry-repository="+jbsConfig.Spec.ImageRegistry.Repository)
	}
	if jbsConfig.Spec.ImageRegistry.Insecure {
		deployArgs = append(deployArgs, "--registry-insecure=")
	}
	if jbsConfig.Spec.ImageRegistry.PrependTag != "" {
		deployArgs = append(deployArgs, "--registry-prepend-tag="+jbsConfig.Spec.ImageRegistry.PrependTag)
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
		"$(workspaces." + WorkspaceSource + ".path)/workspace",
	}
	var settings string
	var build string
	trueBool := true
	if tool == "maven" {
		settings = mavenSettings
		build = mavenBuild
	} else if tool == "gradle" {
		settings = gradleSettings
		build = gradleBuild
		preprocessorArgs[0] = "gradle-prepare"
	} else if tool == "sbt" {
		settings = "" //TODO: look at removing the setttings step altogether
		build = sbtBuild
		preprocessorArgs[0] = "sbt-prepare"
	} else if tool == "ant" {
		settings = ""
		build = antBuild
		preprocessorArgs[0] = "ant-prepare"
	} else {
		settings = "echo unknown build tool " + tool + " && exit 1"
		build = ""
	}
	gitArgs := ""
	if db.Spec.ScmInfo.Private {
		gitArgs = "echo \"$GIT_TOKEN\"  > $HOME/.git-credentials\nchmod 400 $HOME/.git-credentials\n"
		gitArgs = gitArgs + "echo '[credential]\n        helper=store\n' > $HOME/.gitconfig\n"
	}
	gitArgs = gitArgs + "git clone $(params." + PipelineScmUrl + ") $(workspaces." + WorkspaceSource + ".path)/workspace && cd $(workspaces." + WorkspaceSource + ".path)/workspace && git reset --hard $(params." + PipelineScmTag + ")"

	if !recipe.DisableSubmodules {
		gitArgs = gitArgs + " --recurse-submodules"
	}
	defaultContainerRequestMemory, err := resource.ParseQuantity(settingOrDefault(jbsConfig.Spec.BuildSettings.TaskRequestMemory, "512Mi"))
	if err != nil {
		return nil, "", err
	}
	buildContainerRequestMemory, err := resource.ParseQuantity(settingOrDefault(jbsConfig.Spec.BuildSettings.BuildRequestMemory, "512Mi"))
	if err != nil {
		return nil, "", err
	}
	defaultContainerRequestCPU, err := resource.ParseQuantity(settingOrDefault(jbsConfig.Spec.BuildSettings.TaskRequestCPU, "10m"))
	if err != nil {
		return nil, "", err
	}
	defaultContainerLimitCPU, err := resource.ParseQuantity(settingOrDefault(jbsConfig.Spec.BuildSettings.TaskLimitCPU, "300m"))
	if err != nil {
		return nil, "", err
	}
	buildContainerRequestCPU, err := resource.ParseQuantity(settingOrDefault(jbsConfig.Spec.BuildSettings.BuildRequestCPU, "300m"))
	if err != nil {
		return nil, "", err
	}

	if recipe.AdditionalMemory > 0 {
		additional := resource.MustParse(fmt.Sprintf("%dMi", recipe.AdditionalMemory))
		buildContainerRequestMemory.Add(additional)
		defaultContainerRequestMemory.Add(additional)
	}
	buildRepos := ""
	if len(recipe.Repositories) > 0 {
		for c, i := range recipe.Repositories {
			if c == 0 {
				buildRepos = "-" + i
			} else {
				buildRepos = buildRepos + "," + i
			}
		}
	}
	build = strings.ReplaceAll(strings.ReplaceAll(build, "{{INSTALL_PACKAGE_SCRIPT}}", install), "{{PRE_BUILD_SCRIPT}}", recipe.PreBuildScript)

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
			{Name: PipelineCacheUrl, Type: pipelinev1beta1.ParamTypeString, Default: &pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: "http://jvm-build-workspace-artifact-cache.$(context.pipelineRun.namespace).svc.cluster.local/v2/cache/rebuild" + buildRepos + "/" + strconv.FormatInt(commitTime, 10)}},
		},
		Results: []pipelinev1beta1.TaskResult{{Name: artifactbuild.Contaminants}, {Name: artifactbuild.DeployedResources}, {Name: artifactbuild.Image}, {Name: artifactbuild.PassedVerification}},
		Steps: []pipelinev1beta1.Step{
			{
				Name:            "git-clone",
				Image:           "$(params." + PipelineImage + ")",
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Resources: v1.ResourceRequirements{
					Requests: v1.ResourceList{"memory": defaultContainerRequestMemory, "cpu": defaultContainerRequestCPU},
					Limits:   v1.ResourceList{"memory": defaultContainerRequestMemory, "cpu": defaultContainerLimitCPU},
				},
				Script: gitArgs,
				Env: []v1.EnvVar{
					{Name: "GIT_TOKEN", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha12.GitSecretName}, Key: v1alpha12.GitSecretTokenKey, Optional: &trueBool}}},
				},
			},
			{
				Name:            "settings",
				Image:           "registry.access.redhat.com/ubi8/ubi:8.5", //TODO: should not be hard coded
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Env: []v1.EnvVar{
					{Name: PipelineCacheUrl, Value: "$(params." + PipelineCacheUrl + ")"},
				},
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
				Env: []v1.EnvVar{
					{Name: PipelineCacheUrl, Value: "$(params." + PipelineCacheUrl + ")"},
				},
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
				WorkingDir:      "$(workspaces." + WorkspaceSource + ".path)/workspace",
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

				Script: build,
			},
			{
				Name:            "verify-built-artifacts",
				Image:           "$(params." + PipelineRequestProcessorImage + ")",
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Resources: v1.ResourceRequirements{
					//TODO: make configurable
					Requests: v1.ResourceList{"memory": defaultContainerRequestMemory, "cpu": defaultContainerRequestCPU},
					Limits:   v1.ResourceList{"memory": defaultContainerRequestMemory, "cpu": defaultContainerLimitCPU},
				},
				Args: verifyBuiltArtifactsArgs,
			},
			{
				Name:            "deploy-and-check-for-contaminates",
				Image:           "$(params." + PipelineRequestProcessorImage + ")",
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Env: []v1.EnvVar{
					{Name: "REGISTRY_TOKEN", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha12.ImageSecretName}, Key: v1alpha12.ImageSecretTokenKey, Optional: &trueBool}}},
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

	//we generate a docker file that can be used to reproduce this build
	//this is for diagnostic purposes, if you have a failing build it can be really hard to figure out how to fix it without this
	df := "FROM " + extractParam(PipelineRequestProcessorImage, paramValues) + " AS build-request-processor" +
		"\nFROM " + strings.ReplaceAll(extractParam(PipelineRequestProcessorImage, paramValues), "hacbs-jvm-build-request-processor", "hacbs-jvm-cache") + " AS cache" +
		"\nFROM " + extractParam(PipelineImage, paramValues) +
		"\nUSER 0 " +
		"\nCOPY --from=build-request-processor /deployments/ /root/build-request-processor" +
		"\nCOPY --from=build-request-processor /lib/jvm/java-17 /root/system-java" +
		"\nCOPY --from=cache /deployments/ /root/cache" +
		"\nRUN mkdir -p /root/workspace && mkdir -p /root/settings && microdnf install vim" +
		"\nRUN " + doSubstitution(gitArgs, paramValues, commitTime) +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte("#!/bin/sh\n/root/system-java/bin/java -jar /root/cache/quarkus-run.jar >/root/cache.log &")) + " | base64 -d >/root/start-cache.sh" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte(doSubstitution(settings, paramValues, commitTime))) + " | base64 -d >/root/settings.sh" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte("#!/bin/sh\n/root/system-java/bin/java -jar /root/build-request-processor/quarkus-run.jar "+doSubstitution(strings.Join(preprocessorArgs, " "), paramValues, commitTime))) + " | base64 -d >/root/preprocessor.sh" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte(doSubstitution(build, paramValues, commitTime))) + " | base64 -d >/root/build.sh" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte("#!/bin/sh\n/root/settings.sh\n/root/preprocessor.sh\ncd /root/workspace/workspace\n/root/build.sh "+strings.Join(extractArrayParam(PipelineGoals, paramValues), " "))) + " | base64 -d >/root/run-full-build.sh" +
		"\nRUN chmod +x /root/*.sh"

	return ps, df, nil
}

func extractParam(key string, paramValues []pipelinev1beta1.Param) string {
	for _, i := range paramValues {
		if i.Name == key {
			return i.Value.StringVal
		}
	}
	return ""
}
func extractArrayParam(key string, paramValues []pipelinev1beta1.Param) []string {
	for _, i := range paramValues {
		if i.Name == key {
			return i.Value.ArrayVal
		}
	}
	return []string{}
}

func doSubstitution(script string, paramValues []pipelinev1beta1.Param, commitTime int64) string {
	for _, i := range paramValues {
		if i.Value.Type == pipelinev1beta1.ParamTypeString {
			script = strings.ReplaceAll(script, "$(params."+i.Name+")", i.Value.StringVal)
		}
	}
	script = strings.ReplaceAll(script, "$(params.CACHE_URL)", "http://localhost:8080/v2/cache/rebuild/"+strconv.FormatInt(commitTime, 10)+"/")
	script = strings.ReplaceAll(script, "$(workspaces.build-settings.path)", "/root/settings/")
	script = strings.ReplaceAll(script, "$(workspaces.source.path)", "/root/workspace/")
	return script
}

func settingOrDefault(setting, def string) string {
	if len(strings.TrimSpace(setting)) == 0 {
		return def
	}
	return setting
}
