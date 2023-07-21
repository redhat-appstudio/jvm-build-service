package dependencybuild

import (
	_ "embed"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"
	"strconv"
	"strings"

	v1alpha12 "github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
)

const (
	WorkspaceBuildSettings = "build-settings"
	WorkspaceSource        = "source"
	WorkspaceTls           = "tls"
	OriginalContentPath    = "/original-content"
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

//go:embed scripts/entry-script.sh
var entryScript string

func createPipelineSpec(tool string, commitTime int64, jbsConfig *v1alpha12.JBSConfig, systemConfig *v1alpha12.SystemConfig, recipe *v1alpha12.BuildRecipe, db *v1alpha12.DependencyBuild, paramValues []pipelinev1beta1.Param, buildRequestProcessorImage string) (*pipelinev1beta1.PipelineSpec, string, error) {

	marshaled, err := json.Marshal(recipe)
	if err != nil {
		return nil, "", err
	}
	imageId := util.HashString(string(marshaled) + buildRequestProcessorImage + tool + db.Name)
	zero := int64(0)
	verifyBuiltArtifactsArgs := verifyParameters(jbsConfig, recipe)

	preBuildImageName, preBuildImageArgs, deployArgs, tagArgs := imageRegistryCommands(imageId, recipe, db, jbsConfig)
	gitArgs := gitArgs(db, recipe)
	install := additionalPackages(recipe)

	preprocessorArgs := []string{
		"maven-prepare",
		"-r",
		"$(params.CACHE_URL)",
		"$(workspaces." + WorkspaceSource + ".path)/workspace",
	}
	additionalMemory := recipe.AdditionalMemory
	if systemConfig.Spec.MaxAdditionalMemory > 0 && additionalMemory > systemConfig.Spec.MaxAdditionalMemory {
		additionalMemory = systemConfig.Spec.MaxAdditionalMemory
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
		settings = mavenSettings
		build = antBuild
		preprocessorArgs[0] = "ant-prepare"
	} else {
		settings = "echo unknown build tool " + tool + " && exit 1"
		build = ""
	}
	//horrible hack
	//we need to get our TLS CA's into our trust store
	//we just add it at the start of the build
	build = artifactbuild.InstallKeystoreScript() + "\n" + build

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
	build = strings.ReplaceAll(build, "{{INSTALL_PACKAGE_SCRIPT}}", install)
	build = strings.ReplaceAll(build, "{{PRE_BUILD_SCRIPT}}", recipe.PreBuildScript)
	build = strings.ReplaceAll(build, "{{POST_BUILD_SCRIPT}}", recipe.PostBuildScript)
	cacheUrl := "https://jvm-build-workspace-artifact-cache-tls." + jbsConfig.Namespace + ".svc.cluster.local/v2/cache/rebuild"
	if jbsConfig.Spec.CacheSettings.DisableTLS {
		cacheUrl = "http://jvm-build-workspace-artifact-cache." + jbsConfig.Namespace + ".svc.cluster.local/v2/cache/rebuild"
	}
	var javaHome string
	if recipe.JavaVersion == "7" || recipe.JavaVersion == "8" {
		javaHome = "/lib/jvm/java-1." + recipe.JavaVersion + ".0"
	} else {
		javaHome = "/lib/jvm/java-" + recipe.JavaVersion
	}

	pullPolicy := pullPolicy(buildRequestProcessorImage)
	limits, err := memoryLimits(jbsConfig, additionalMemory)
	if err != nil {
		return nil, "", err
	}

	pipelineParams := []pipelinev1beta1.ParamSpec{
		{Name: PipelineBuildId, Type: pipelinev1beta1.ParamTypeString},
		{Name: PipelineParamScmUrl, Type: pipelinev1beta1.ParamTypeString},
		{Name: PipelineParamScmTag, Type: pipelinev1beta1.ParamTypeString},
		{Name: PipelineParamScmHash, Type: pipelinev1beta1.ParamTypeString},
		{Name: PipelineParamChainsGitUrl, Type: pipelinev1beta1.ParamTypeString},
		{Name: PipelineParamChainsGitCommit, Type: pipelinev1beta1.ParamTypeString},
		{Name: PipelineParamImage, Type: pipelinev1beta1.ParamTypeString},
		{Name: PipelineParamGoals, Type: pipelinev1beta1.ParamTypeArray},
		{Name: PipelineParamJavaVersion, Type: pipelinev1beta1.ParamTypeString},
		{Name: PipelineParamToolVersion, Type: pipelinev1beta1.ParamTypeString},
		{Name: PipelineParamPath, Type: pipelinev1beta1.ParamTypeString},
		{Name: PipelineParamEnforceVersion, Type: pipelinev1beta1.ParamTypeString},
		{Name: PipelineParamCacheUrl, Type: pipelinev1beta1.ParamTypeString, Default: &pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: cacheUrl + buildRepos + "/" + strconv.FormatInt(commitTime, 10)}},
	}
	buildSetup := pipelinev1beta1.TaskSpec{
		Workspaces: []pipelinev1beta1.WorkspaceDeclaration{{Name: WorkspaceBuildSettings}, {Name: WorkspaceSource}, {Name: WorkspaceTls}},
		Params:     pipelineParams,
		Steps: []pipelinev1beta1.Step{
			{
				Name:            "git-clone-and-settings",
				Image:           "$(params." + PipelineParamImage + ")",
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Resources: v1.ResourceRequirements{
					Requests: v1.ResourceList{"memory": limits.defaultRequestMemory, "cpu": limits.defaultRequestCPU},
					Limits:   v1.ResourceList{"memory": limits.defaultRequestMemory, "cpu": limits.defaultLimitCPU},
				},
				Script: gitArgs + "\n" + settings,
				Env: []v1.EnvVar{
					{Name: PipelineParamCacheUrl, Value: "$(params." + PipelineParamCacheUrl + ")"},
					{Name: "GIT_TOKEN", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha12.GitSecretName}, Key: v1alpha12.GitSecretTokenKey, Optional: &trueBool}}},
				},
			},
			{
				Name:            "preprocessor",
				Image:           buildRequestProcessorImage,
				ImagePullPolicy: pullPolicy,
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Env: []v1.EnvVar{
					{Name: PipelineParamCacheUrl, Value: "$(params." + PipelineParamCacheUrl + ")"},
				},
				Resources: v1.ResourceRequirements{
					//TODO: make configurable
					Requests: v1.ResourceList{"memory": limits.defaultRequestMemory, "cpu": limits.defaultRequestCPU},
					Limits:   v1.ResourceList{"memory": limits.defaultRequestMemory, "cpu": limits.defaultLimitCPU},
				},
				Script: artifactbuild.InstallKeystoreIntoBuildRequestProcessor(preprocessorArgs),
			},
			{
				Name:            "create-pre-build-image",
				Image:           buildRequestProcessorImage,
				ImagePullPolicy: pullPolicy,
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Env: []v1.EnvVar{
					{Name: "REGISTRY_TOKEN", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha12.ImageSecretName}, Key: v1alpha12.ImageSecretTokenKey, Optional: &trueBool}}},
				},
				Resources: v1.ResourceRequirements{
					//TODO: make configurable
					Requests: v1.ResourceList{"memory": limits.defaultBuildRequestMemory, "cpu": limits.defaultRequestCPU},
					Limits:   v1.ResourceList{"memory": limits.defaultBuildRequestMemory, "cpu": limits.defaultLimitCPU},
				},
				Script: artifactbuild.InstallKeystoreIntoBuildRequestProcessor(preBuildImageArgs),
			},
		},
	}

	buildTask := pipelinev1beta1.TaskSpec{
		Workspaces: []pipelinev1beta1.WorkspaceDeclaration{{Name: WorkspaceBuildSettings}, {Name: WorkspaceSource}, {Name: WorkspaceTls}},
		Params:     pipelineParams,
		Results: []pipelinev1beta1.TaskResult{
			{Name: artifactbuild.PipelineResultContaminants},
			{Name: artifactbuild.PipelineResultDeployedResources},
			{Name: PipelineResultImage},
			{Name: PipelineResultImageDigest},
			{Name: artifactbuild.PipelineResultPassedVerification},
			{Name: artifactbuild.PipelineResultVerificationResult},
		},
		Steps: []pipelinev1beta1.Step{
			{
				Name:            "build",
				Image:           preBuildImageName,
				ImagePullPolicy: v1.PullAlways,
				WorkingDir:      "$(workspaces." + WorkspaceSource + ".path)/workspace",
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Env: []v1.EnvVar{
					{Name: JavaHome, Value: javaHome},
					{Name: PipelineParamCacheUrl, Value: "$(params." + PipelineParamCacheUrl + ")"},
					{Name: PipelineParamEnforceVersion, Value: "$(params." + PipelineParamEnforceVersion + ")"},
				},
				Resources: v1.ResourceRequirements{
					//TODO: limits management and configuration
					Requests: v1.ResourceList{"memory": limits.buildRequestMemory, "cpu": limits.buildRequestCPU},
				},
				Args: []string{"$(params.GOALS[*])"},

				Script: settings + "\ncp -r -a " + OriginalContentPath + "/* $(workspaces.source.path)\n" + build,
			},
			{
				Name:            "verify-deploy-and-check-for-contaminates",
				Image:           buildRequestProcessorImage,
				ImagePullPolicy: pullPolicy,
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Env: []v1.EnvVar{
					{Name: "REGISTRY_TOKEN", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha12.ImageSecretName}, Key: v1alpha12.ImageSecretTokenKey, Optional: &trueBool}}},
				},
				Resources: v1.ResourceRequirements{
					//TODO: make configurable
					Requests: v1.ResourceList{"memory": limits.defaultBuildRequestMemory, "cpu": limits.defaultRequestCPU},
					Limits:   v1.ResourceList{"memory": limits.defaultBuildRequestMemory, "cpu": limits.defaultLimitCPU},
				},
				Script: artifactbuild.InstallKeystoreIntoBuildRequestProcessor(verifyBuiltArtifactsArgs, deployArgs),
			},
		},
	}

	tagTask := pipelinev1beta1.TaskSpec{
		Workspaces: []pipelinev1beta1.WorkspaceDeclaration{{Name: WorkspaceBuildSettings}, {Name: WorkspaceSource}, {Name: WorkspaceTls}},
		Params:     []pipelinev1beta1.ParamSpec{{Name: "GAVS", Type: pipelinev1beta1.ParamTypeString}},
		Steps: []pipelinev1beta1.Step{
			{
				Name:            "tag",
				Image:           buildRequestProcessorImage,
				ImagePullPolicy: pullPolicy,
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Env: []v1.EnvVar{
					{Name: "REGISTRY_TOKEN", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha12.ImageSecretName}, Key: v1alpha12.ImageSecretTokenKey, Optional: &trueBool}}},
				},
				Resources: v1.ResourceRequirements{
					//TODO: make configurable
					Requests: v1.ResourceList{"memory": limits.defaultBuildRequestMemory, "cpu": limits.defaultRequestCPU},
					Limits:   v1.ResourceList{"memory": limits.defaultBuildRequestMemory, "cpu": limits.defaultLimitCPU},
				},
				Script: artifactbuild.InstallKeystoreIntoBuildRequestProcessor(tagArgs),
			},
		},
	}
	ps := &pipelinev1beta1.PipelineSpec{
		Tasks: []pipelinev1beta1.PipelineTask{
			{
				Name: artifactbuild.PreBuildTaskName,
				TaskSpec: &pipelinev1beta1.EmbeddedTask{
					TaskSpec: buildSetup,
				},
				Params: []pipelinev1beta1.Param{}, Workspaces: []pipelinev1beta1.WorkspacePipelineTaskBinding{
					{Name: WorkspaceBuildSettings, Workspace: WorkspaceBuildSettings},
					{Name: WorkspaceSource, Workspace: WorkspaceSource},
					{Name: WorkspaceTls, Workspace: WorkspaceTls},
				},
			},
			{
				Name:     artifactbuild.BuildTaskName,
				RunAfter: []string{artifactbuild.PreBuildTaskName},
				TaskSpec: &pipelinev1beta1.EmbeddedTask{
					TaskSpec: buildTask,
				},
				Params: []pipelinev1beta1.Param{}, Workspaces: []pipelinev1beta1.WorkspacePipelineTaskBinding{
					{Name: WorkspaceBuildSettings, Workspace: WorkspaceBuildSettings},
					{Name: WorkspaceSource, Workspace: WorkspaceSource},
					{Name: WorkspaceTls, Workspace: WorkspaceTls},
				},
			},
			{
				Name:     artifactbuild.TagTaskName,
				RunAfter: []string{artifactbuild.BuildTaskName},
				TaskSpec: &pipelinev1beta1.EmbeddedTask{
					TaskSpec: tagTask,
				},
				Params: []pipelinev1beta1.Param{}, Workspaces: []pipelinev1beta1.WorkspacePipelineTaskBinding{
					{Name: WorkspaceBuildSettings, Workspace: WorkspaceBuildSettings},
					{Name: WorkspaceSource, Workspace: WorkspaceSource},
					{Name: WorkspaceTls, Workspace: WorkspaceTls},
				},
			},
		},
		Workspaces: []pipelinev1beta1.PipelineWorkspaceDeclaration{{Name: WorkspaceBuildSettings}, {Name: WorkspaceSource}, {Name: WorkspaceTls}},
	}

	for _, i := range buildTask.Results {
		ps.Results = append(ps.Results, pipelinev1beta1.PipelineResult{Name: i.Name, Description: i.Description, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: "$(tasks." + artifactbuild.BuildTaskName + ".results." + i.Name + ")"}})
	}
	for _, i := range buildSetup.Params {
		ps.Params = append(ps.Params, pipelinev1beta1.ParamSpec{Name: i.Name, Description: i.Description, Default: i.Default, Type: i.Type})
		var value pipelinev1beta1.ResultValue
		if i.Type == pipelinev1beta1.ParamTypeString {
			value = pipelinev1beta1.ResultValue{Type: i.Type, StringVal: "$(params." + i.Name + ")"}
		} else {
			value = pipelinev1beta1.ResultValue{Type: i.Type, ArrayVal: []string{"$(params." + i.Name + "[*])"}}
		}
		ps.Tasks[0].Params = append(ps.Tasks[0].Params, pipelinev1beta1.Param{
			Name:  i.Name,
			Value: value})
		ps.Tasks[1].Params = append(ps.Tasks[1].Params, pipelinev1beta1.Param{
			Name:  i.Name,
			Value: value})
	}
	ps.Tasks[2].Params = append(ps.Tasks[2].Params, pipelinev1beta1.Param{
		Name:  "GAVS",
		Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: "$(tasks." + artifactbuild.BuildTaskName + ".results." + artifactbuild.PipelineResultDeployedResources + ")"}})

	//we generate a docker file that can be used to reproduce this build
	//this is for diagnostic purposes, if you have a failing build it can be really hard to figure out how to fix it without this
	df := "FROM " + buildRequestProcessorImage + " AS build-request-processor" +
		"\nFROM " + strings.ReplaceAll(buildRequestProcessorImage, "hacbs-jvm-build-request-processor", "hacbs-jvm-cache") + " AS cache" +
		"\nFROM " + extractParam(PipelineParamImage, paramValues) +
		"\nUSER 0" +
		"\nWORKDIR /root" +
		"\nENV CACHE_URL=" + doSubstitution("$(params."+PipelineParamCacheUrl+")", paramValues, commitTime, buildRepos) +
		"\nRUN mkdir -p /root/project /root/software/settings && microdnf install vim curl procps-ng bash-completion" +
		"\nCOPY --from=build-request-processor /deployments/ /root/software/build-request-processor" +
		// Copying JDK17 for the cache.
		"\nCOPY --from=build-request-processor /lib/jvm/jre-17 /root/software/system-java" +
		"\nCOPY --from=build-request-processor /etc/java/java-17-openjdk /etc/java/java-17-openjdk" +
		"\nCOPY --from=cache /deployments/ /root/software/cache" +
		"\nRUN " + doSubstitution(gitArgs, paramValues, commitTime, buildRepos) +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte("#!/bin/sh\n/root/software/system-java/bin/java -Dkube.disabled=true -Dquarkus.kubernetes-client.trust-certs=true -jar /root/software/cache/quarkus-run.jar >/root/cache.log &"+
		"\necho \"Please wait a few seconds for cache to start. Run 'tail -f cache.log'\"\n")) + " | base64 -d >/root/start-cache.sh" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte(doSubstitution(settings, paramValues, commitTime, buildRepos))) + " | base64 -d >/root/settings.sh" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte("#!/bin/sh\n/root/software/system-java/bin/java -jar /root/software/build-request-processor/quarkus-run.jar "+doSubstitution(strings.Join(preprocessorArgs, " "), paramValues, commitTime, buildRepos)+"\n")) + " | base64 -d >/root/preprocessor.sh" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte(doSubstitution(build, paramValues, commitTime, buildRepos))) + " | base64 -d >/root/build.sh" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte("#!/bin/sh\n/root/settings.sh\n/root/preprocessor.sh\ncd /root/project/workspace\n/root/build.sh "+strings.Join(extractArrayParam(PipelineParamGoals, paramValues), " ")+"\n")) + " | base64 -d >/root/run-full-build.sh" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte(entryScript)) + " | base64 -d >/root/entry-script.sh" +
		"\nRUN chmod +x /root/*.sh" +
		"\nCMD [ \"/bin/bash\", \"/root/entry-script.sh\" ]"

	return ps, df, nil
}

func pullPolicy(buildRequestProcessorImage string) v1.PullPolicy {
	pullPolicy := v1.PullIfNotPresent
	if strings.HasPrefix(buildRequestProcessorImage, "quay.io/minikube") {
		pullPolicy = v1.PullNever
	} else if strings.HasSuffix(buildRequestProcessorImage, ":dev") {
		pullPolicy = v1.PullAlways
	}
	return pullPolicy
}

type memLimits struct {
	defaultRequestMemory, defaultBuildRequestMemory, defaultRequestCPU, defaultLimitCPU, buildRequestCPU, buildRequestMemory resource.Quantity
}

func memoryLimits(jbsConfig *v1alpha12.JBSConfig, additionalMemory int) (*memLimits, error) {
	limits := memLimits{}
	var err error
	limits.defaultRequestMemory, err = resource.ParseQuantity(settingOrDefault(jbsConfig.Spec.BuildSettings.TaskRequestMemory, "512Mi"))
	if err != nil {
		return nil, err
	}
	limits.defaultBuildRequestMemory, err = resource.ParseQuantity(settingOrDefault(jbsConfig.Spec.BuildSettings.BuildRequestMemory, "1024Mi"))
	if err != nil {
		return nil, err
	}
	limits.defaultRequestCPU, err = resource.ParseQuantity(settingOrDefault(jbsConfig.Spec.BuildSettings.TaskRequestCPU, "10m"))
	if err != nil {
		return nil, err
	}
	limits.defaultLimitCPU, err = resource.ParseQuantity(settingOrDefault(jbsConfig.Spec.BuildSettings.TaskLimitCPU, "300m"))
	if err != nil {
		return nil, err
	}
	limits.buildRequestCPU, err = resource.ParseQuantity(settingOrDefault(jbsConfig.Spec.BuildSettings.BuildRequestCPU, "300m"))
	if err != nil {
		return nil, err
	}

	limits.buildRequestMemory = limits.defaultBuildRequestMemory
	if additionalMemory > 0 {
		additional := resource.MustParse(fmt.Sprintf("%dMi", additionalMemory))
		limits.buildRequestMemory.Add(additional)
		limits.defaultRequestMemory.Add(additional)
	}
	return &limits, nil
}

func additionalPackages(recipe *v1alpha12.BuildRecipe) string {
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
		} else if i.FileType == "rpm" {
			if i.PackageName == "" {
				install = "echo 'Package name not specified for rpm type'; exit 1"
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
		template = strings.ReplaceAll(template, "{PACKAGE_NAME}", i.PackageName)
		install = install + template
	}
	return install
}

func gitArgs(db *v1alpha12.DependencyBuild, recipe *v1alpha12.BuildRecipe) string {
	gitArgs := ""
	if db.Spec.ScmInfo.Private {
		gitArgs = "echo \"$GIT_TOKEN\"  > $HOME/.git-credentials\nchmod 400 $HOME/.git-credentials\n"
		gitArgs = gitArgs + "echo '[credential]\n        helper=store\n' > $HOME/.gitconfig\n"
	}
	gitArgs = gitArgs + "git clone $(params." + PipelineParamScmUrl + ") $(workspaces." + WorkspaceSource + ".path)/workspace && cd $(workspaces." + WorkspaceSource + ".path)/workspace && git reset --hard $(params." + PipelineParamScmHash + ")"

	if !recipe.DisableSubmodules {
		gitArgs = gitArgs + " && git submodule init && git submodule update --recursive"
	}
	return gitArgs
}

func imageRegistryCommands(imageId string, recipe *v1alpha12.BuildRecipe, db *v1alpha12.DependencyBuild, jbsConfig *v1alpha12.JBSConfig) (string, []string, []string, []string) {
	preBuildImageName := ""
	preBuildImageTag := imageId + "-pre-build-image"
	preBuildImageArgs := []string{
		"deploy-pre-build-image",
		"--builder-image=" + recipe.Image,
		"--source-path=$(workspaces.source.path)",
		"--image-source-path=" + OriginalContentPath,
		"--image-name=" + preBuildImageTag,
	}

	deployArgs := []string{
		"deploy",
		"--image-id=" + imageId,
		"--path=$(workspaces.source.path)/artifacts",
		"--logs-path=$(workspaces.source.path)/logs",
		"--build-info-path=$(workspaces.source.path)/build-info",
		"--source-path=$(workspaces.source.path)/source",
		"--task-run=$(context.taskRun.name)",
		"--scm-uri=" + db.Spec.ScmInfo.SCMURL,
		"--scm-commit=" + db.Spec.ScmInfo.CommitHash,
	}
	tagArgs := []string{
		"tag-container",
		"--image-id=" + imageId,
	}
	imageRegistry := jbsConfig.ImageRegistry()
	registryArgs := []string{}
	if imageRegistry.Host != "" {
		registryArgs = append(registryArgs, "--registry-host="+imageRegistry.Host)
		preBuildImageName += imageRegistry.Host
	} else {
		preBuildImageName += "quay.io"
	}
	if imageRegistry.Port != "" && imageRegistry.Port != "443" {
		registryArgs = append(registryArgs, "--registry-port="+imageRegistry.Port)
		preBuildImageName += ":" + imageRegistry.Port
	}
	if imageRegistry.Owner != "" {
		registryArgs = append(registryArgs, "--registry-owner="+imageRegistry.Owner)
		preBuildImageName += "/" + imageRegistry.Owner
	}
	if imageRegistry.Repository != "" {
		registryArgs = append(registryArgs, "--registry-repository="+imageRegistry.Repository)
		preBuildImageName += "/" + imageRegistry.Repository
	} else {
		preBuildImageName += "/artifact-deployments"
	}
	preBuildImageName += ":" + preBuildImageTag
	if imageRegistry.Insecure {
		registryArgs = append(registryArgs, "--registry-insecure")
	}
	if jbsConfig.ImageRegistry().PrependTag != "" {
		registryArgs = append(registryArgs, "--registry-prepend-tag="+imageRegistry.PrependTag)
	}
	deployArgs = append(deployArgs, registryArgs...)
	preBuildImageArgs = append(preBuildImageArgs, registryArgs...)
	tagArgs = append(tagArgs, registryArgs...)
	tagArgs = append(tagArgs, "$(params.GAVS)")
	return preBuildImageName, preBuildImageArgs, deployArgs, tagArgs
}

func verifyParameters(jbsConfig *v1alpha12.JBSConfig, recipe *v1alpha12.BuildRecipe) []string {
	verifyBuiltArtifactsArgs := []string{
		"verify-built-artifacts",
		"--repository-url=$(params.CACHE_URL)?upstream-only=true",
		"--global-settings=/usr/share/maven/conf/settings.xml",
		"--settings=$(workspaces.build-settings.path)/settings.xml",
		"--deploy-path=$(workspaces.source.path)/artifacts",
		"--task-run-name=$(context.taskRun.name)",
		"--results-file=$(results." + artifactbuild.PipelineResultPassedVerification + ".path)",
	}

	if !jbsConfig.Spec.RequireArtifactVerification {
		verifyBuiltArtifactsArgs = append(verifyBuiltArtifactsArgs, "--report-only")
	}

	if len(recipe.AllowedDifferences) > 0 {
		for _, i := range recipe.AllowedDifferences {
			verifyBuiltArtifactsArgs = append(verifyBuiltArtifactsArgs, "--excludes="+i)
		}
	}
	return verifyBuiltArtifactsArgs
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

func doSubstitution(script string, paramValues []pipelinev1beta1.Param, commitTime int64, buildRepos string) string {
	for _, i := range paramValues {
		if i.Value.Type == pipelinev1beta1.ParamTypeString {
			script = strings.ReplaceAll(script, "$(params."+i.Name+")", i.Value.StringVal)
		}
	}
	script = strings.ReplaceAll(script, "$(params.CACHE_URL)", "http://localhost:8080/v2/cache/rebuild"+buildRepos+"/"+strconv.FormatInt(commitTime, 10)+"/")
	script = strings.ReplaceAll(script, "$(workspaces.build-settings.path)", "/root/software/settings")
	script = strings.ReplaceAll(script, "$(workspaces.source.path)", "/root/project")
	script = strings.ReplaceAll(script, "$(workspaces.tls.path)", "/root/project/tls/service-ca.crt")
	return script
}

func settingOrDefault(setting, def string) string {
	if len(strings.TrimSpace(setting)) == 0 {
		return def
	}
	return setting
}
