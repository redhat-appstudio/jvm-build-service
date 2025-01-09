package dependencybuild

import (
	_ "embed"
	"encoding/base64"
	"fmt"
	"github.com/go-logr/logr"
	v12 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
)

const (
	PostBuildVolume      = "post-build-volume"
	PostBuildVolumeMount = "/var/workdir"
	WorkspaceSource      = "source"
	WorkspaceTls         = "tls"

	GitTaskName         = "git-clone"
	PreBuildTaskName    = "pre-build"
	PreBuildGitTaskName = "pre-build-git"
	BuildTaskName       = "build"
	PostBuildTaskName   = "post-build"
	DeployTaskName      = "deploy"

	DomainProxyImage       = "quay.io/redhat-user-workloads/konflux-jbs-pnc-tenant/domain-proxy:latest"
	PNCKonfluxToolingImage = "quay.io/redhat-user-workloads/konflux-jbs-pnc-tenant/konflux-tooling:latest"
)

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

//go:embed scripts/dockerfile-entry-script.sh
var dockerfileEntryScript string

//go:embed scripts/build-entry.sh
var buildEntryScript string

//go:embed scripts/Dockerfile.build-trusted-artifacts
var buildTrustedArtifacts string

// TODO: ### Either remove or replace with verification step *but* the contaminants/verification is all tied to the build pipeline in dependencybuild.go
/*
func createDeployPipelineSpec(jbsConfig *v1alpha1.JBSConfig, buildRequestProcessorImage string) (*tektonpipeline.PipelineSpec, error) {
	// Original deploy pipeline used to run maven deployment and also tag the images using 'oras tag'
	// with the SHA256 encoded sum of the GAVs.
	resolver := tektonpipeline.ResolverRef{
		// We can use either a http or git resolver. Using http as avoids cloning an entire repository.
		Resolver: "http",
		Params: []tektonpipeline.Param{
			{
				Name: "url",
				Value: tektonpipeline.ParamValue{
					Type:      tektonpipeline.ParamTypeString,
					StringVal: v1alpha1.KonfluxMavenDeployDefinitions,
				},
			},
		},
	}
	ps := &tektonpipeline.PipelineSpec{
		Params: []tektonpipeline.ParamSpec{{Name: PipelineResultImageDigest, Type: tektonpipeline.ParamTypeString}},
		Tasks: []tektonpipeline.PipelineTask{
			{
				Name: DeployTaskName,
				TaskRef: &tektonpipeline.TaskRef{
					// Can't specify name and resolver as they clash.
					ResolverRef: resolver,
				},
				Params: []tektonpipeline.Param{
					{
						Name: PipelineResultImage,
						Value: tektonpipeline.ParamValue{
							Type:      tektonpipeline.ParamTypeString,
							StringVal: "$(params." + PipelineResultImage + ")",
						},
					},
					{
						Name: PipelineResultImageDigest,
						Value: tektonpipeline.ParamValue{
							Type:      tektonpipeline.ParamTypeString,
							StringVal: "$(params." + PipelineResultImageDigest + ")",
						},
					},
					{
						Name: "MVN_REPO",
						Value: tektonpipeline.ParamValue{
							Type:      tektonpipeline.ParamTypeString,
							StringVal: jbsConfig.Spec.MavenDeployment.Repository,
						},
					},
					{
						Name: "MVN_USERNAME",
						Value: tektonpipeline.ParamValue{
							Type:      tektonpipeline.ParamTypeString,
							StringVal: jbsConfig.Spec.MavenDeployment.Username,
						},
					},
					{
						Name: "MVN_PASSWORD",
						Value: tektonpipeline.ParamValue{
							Type:      tektonpipeline.ParamTypeString,
							StringVal: v1alpha1.MavenSecretName,
						},
					},
					{
						Name: "JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE",
						Value: tektonpipeline.ParamValue{
							Type:      tektonpipeline.ParamTypeString,
							StringVal: buildRequestProcessorImage,
						},
					},
				},
			},
		},
	}
	return ps, nil
}
*/

func createPipelineSpec(log logr.Logger, tool string, commitTime int64, jbsConfig *v1alpha1.JBSConfig, systemConfig *v1alpha1.SystemConfig, recipe *v1alpha1.BuildRecipe, db *v1alpha1.DependencyBuild, paramValues []tektonpipeline.Param, buildRequestProcessorImage string, buildId string, existingImages map[string]string, orasOptions string) (*tektonpipeline.PipelineSpec, string, error) {

	// Rather than tagging with hash of json build recipe, buildrequestprocessor image and db.Name as the former two
	// could change with new image versions just use db.Name (which is a hash of scm url/tag/path so should be stable)
	imageId := db.Name
	zero := int64(0)
	verifyBuiltArtifactsArgs := verifyParameters(jbsConfig, recipe)
	deployArgs := []string{
		"verify",
		fmt.Sprintf("--path=%s/deployment", PostBuildVolumeMount),
		"--task-run-name=$(context.taskRun.name)",
		"--build-id=" + buildId,
		"--scm-uri=" + db.Spec.ScmInfo.SCMURL,
		"--scm-commit=" + db.Spec.ScmInfo.CommitHash,
	}

	install := additionalPackages(recipe)
	tlsVerify := "true"
	if orasOptions != "" {
		tlsVerify = "false"
	}

	toolEnv := make([]v1.EnvVar, 0)
	// Used by JBS to override the version
	toolEnv = append(toolEnv, v1.EnvVar{Name: PipelineParamEnforceVersion, Value: recipe.EnforceVersion})
	toolEnv = append(toolEnv, v1.EnvVar{Name: PipelineParamProjectVersion, Value: db.Spec.Version})

	additionalMemory := recipe.AdditionalMemory
	if systemConfig.Spec.MaxAdditionalMemory > 0 && additionalMemory > systemConfig.Spec.MaxAdditionalMemory {
		log.Info(fmt.Sprintf("additionalMemory specified %#v but system MaxAdditionalMemory is %#v and is limiting that value", additionalMemory, systemConfig.Spec.MaxAdditionalMemory))
		additionalMemory = systemConfig.Spec.MaxAdditionalMemory
	}
	var buildToolSection string
	if tool == "maven" {
		buildToolSection = mavenBuild
	} else if tool == "gradle" {
		buildToolSection = gradleBuild
	} else if tool == "sbt" {
		buildToolSection = sbtBuild
	} else if tool == "ant" {
		buildToolSection = antBuild
	} else {
		buildToolSection = "echo unknown build tool " + tool + " && exit 1"
	}
	build := buildEntryScript
	// TODO: How to handle/remove the TLS support from STONEBLD-847
	////horrible hack
	////we need to get our TLS CA's into our trust store
	////we just add it at the start of the build
	//build = artifactbuild.InstallKeystoreScript() + "\n" + build

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
	build = strings.ReplaceAll(build, "{{BUILD}}", buildToolSection)
	build = strings.ReplaceAll(build, "{{INSTALL_PACKAGE_SCRIPT}}", install)
	build = strings.ReplaceAll(build, "{{PRE_BUILD_SCRIPT}}", recipe.PreBuildScript)
	build = strings.ReplaceAll(build, "{{POST_BUILD_SCRIPT}}", recipe.PostBuildScript)
	cacheUrl := "https://jvm-build-workspace-artifact-cache-tls." + jbsConfig.Namespace + ".svc.cluster.local/v2/cache/rebuild"
	if jbsConfig.Spec.CacheSettings.DisableTLS {
		cacheUrl = "http://jvm-build-workspace-artifact-cache." + jbsConfig.Namespace + ".svc.cluster.local/v2/cache/rebuild"
	}
	cacheUrl = cacheUrl + buildRepos + "/" + strconv.FormatInt(commitTime, 10)

	//we generate a docker file that can be used to reproduce this build
	//this is for diagnostic purposes, if you have a failing build it can be really hard to figure out how to fix it without this
	log.Info(fmt.Sprintf("Generating dockerfile with recipe build image %#v", recipe.Image))
	preprocessorScript := "#!/bin/sh\n/var/workdir/software/system-java/bin/java -jar /var/workdir/software/build-request-processor/quarkus-run.jar " + recipe.Tool + "-prepare /var/workdir/workspace --recipe-image=" + recipe.Image + " --request-processor-image=" + buildRequestProcessorImage + " --disabled-plugins=" + strings.Join(recipe.DisabledPlugins, ",")
	buildScript := doSubstitution(build, paramValues, commitTime, buildRepos)
	envVars := extractEnvVar(toolEnv)
	cmdArgs := extractArrayParam(PipelineParamGoals, paramValues)
	konfluxScript := "\n" + envVars + "\nset -- \"$@\" " + cmdArgs + "\n\n" + buildScript

	// Diagnostic Containerfile
	// TODO: Looks like diagnostic files won't work with UBI7 anymore. This needs to be followed up on; potentially
	//		 we could just disable the cache for this scenario?
	imageRegistry := jbsConfig.ImageRegistry()
	preBuildImageFrom := imageRegistry.Host + "/" + imageRegistry.Owner + "/" + imageRegistry.Repository + ":" + imageId + "-pre-build-image"
	df := "FROM " + buildRequestProcessorImage + " AS build-request-processor" +
		"\nFROM " + strings.ReplaceAll(buildRequestProcessorImage, "hacbs-jvm-build-request-processor", "hacbs-jvm-cache") + " AS cache" +
		"\nFROM " + strings.TrimSpace(strings.Split(buildTrustedArtifacts, "FROM")[1]) +
		"\nFROM " + recipe.Image +
		"\nUSER 0" +
		"\nWORKDIR /var/workdir" +
		"\nENV PROXY_URL=" + doSubstitution("$(params."+PipelineParamProxyUrl+")", paramValues, commitTime, buildRepos) +
		"\nRUN microdnf --setopt=install_weak_deps=0 --setopt=tsflags=nodocs install -y jq" +
		"\nRUN mkdir -p /var/workdir/software/settings /original-content/marker /var/workdir/workspace/source" +
		"\nCOPY --from=build-request-processor /deployments/ /var/workdir/software/build-request-processor" +
		// Copying JDK17 for the cache.
		// TODO: Could we determine if we are using UBI8 and avoid this?
		"\nCOPY --from=build-request-processor /lib/jvm/jre-17 /var/workdir/software/system-java" +
		"\nCOPY --from=oras /usr/local/bin/ /usr/bin" +
		"\nCOPY --from=build-request-processor /etc/java/java-17-openjdk /etc/java/java-17-openjdk" +
		"\nCOPY --from=cache /deployments/ /var/workdir/software/cache" +
		"\nCOPY --from=oras /usr/local/bin /usr/bin" +
		"\nRUN TAR_OPTIONS=--no-same-owner use-archive \"oci:" + imageRegistry.Host + "/" + imageRegistry.Owner + "/" + imageRegistry.Repository +
		"@$(oras manifest fetch " + preBuildImageFrom + " | jq --raw-output '.layers[0].digest')=/var/workdir/workspace/source\"" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte("#!/bin/sh\n/var/workdir/software/system-java/bin/java -Dbuild-policy.default.store-list=rebuilt,central,jboss,redhat -Dkube.disabled=true -Dquarkus.kubernetes-client.trust-certs=true -jar /var/workdir/software/cache/quarkus-run.jar >/var/workdir/cache.log &"+
		"\nwhile ! cat /var/workdir/cache.log | grep 'Listening on:'; do\n        echo \"Waiting for Cache to start\"\n        sleep 1\ndone \n")) + " | base64 -d >/var/workdir/start-cache.sh" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte(preprocessorScript)) + " | base64 -d >/var/workdir/preprocessor.sh" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte(buildScript)) + " | base64 -d >/var/workdir/build.sh" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte("#!/bin/sh\n/var/workdir/preprocessor.sh\n"+envVars+"\n/var/workdir/build.sh "+cmdArgs+"\n")) + " | base64 -d >/var/workdir/run-full-build.sh" +
		"\nRUN echo " + base64.StdEncoding.EncodeToString([]byte(dockerfileEntryScript)) + " | base64 -d >/var/workdir/entry-script.sh" +
		"\nRUN chmod +x /var/workdir/*.sh" +
		"\nCMD [ \"/bin/bash\", \"/var/workdir/entry-script.sh\" ]"

	fmt.Printf("### Using recipe %#v with tool %#v and buildRequestImage %#v \n", recipe.Image, tool, buildRequestProcessorImage)

	pullPolicy := pullPolicy(buildRequestProcessorImage)
	limits, err := memoryLimits(jbsConfig, additionalMemory)
	if err != nil {
		return nil, "", err
	}

	pipelineParams := []tektonpipeline.ParamSpec{
		{Name: PipelineBuildId, Type: tektonpipeline.ParamTypeString},
		{Name: PipelineParamChainsGitUrl, Type: tektonpipeline.ParamTypeString},
		{Name: PipelineParamChainsGitCommit, Type: tektonpipeline.ParamTypeString},
		{Name: PipelineParamGoals, Type: tektonpipeline.ParamTypeArray},
		{Name: PipelineParamPath, Type: tektonpipeline.ParamTypeString},
		{Name: PipelineParamProxyUrl, Type: tektonpipeline.ParamTypeString, Default: &tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: cacheUrl}},
	}
	secretVariables := secretVariables(jbsConfig)

	runAfter := make([]string, 0)
	var runAfterBuild []string

	preBuildImage := existingImages[recipe.Image+"-"+recipe.Tool]
	preBuildImageRequired := preBuildImage == ""
	if preBuildImageRequired {
		preBuildImage = "$(tasks." + PreBuildTaskName + ".results." + PipelineResultPreBuildImageDigest + ")"
		runAfter = []string{PreBuildGitTaskName}
	}
	runAfterBuild = append(runAfter, BuildTaskName)

	ps := &tektonpipeline.PipelineSpec{
		Workspaces: []tektonpipeline.PipelineWorkspaceDeclaration{{Name: WorkspaceSource}},
	}

	if preBuildImageRequired {
		gitResolver := tektonpipeline.ResolverRef{
			// We can use either a http or git resolver. Using http as avoids cloning an entire repository.
			Resolver: "http",
			Params: []tektonpipeline.Param{
				{
					Name: "url",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: v1alpha1.KonfluxGitDefinition,
					},
				},
			},
		}
		preBuildResolver := tektonpipeline.ResolverRef{
			// We can use either a http or git resolver. Using http as avoids cloning an entire repository.
			Resolver: "http",
			Params: []tektonpipeline.Param{
				{
					Name: "url",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: v1alpha1.KonfluxPreBuildDefinitions,
					},
				},
			},
		}
		preBuildGitResolver := tektonpipeline.ResolverRef{
			// We can use either a http or git resolver. Using http as avoids cloning an entire repository.
			Resolver: "http",
			Params: []tektonpipeline.Param{
				{
					Name: "url",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: v1alpha1.KonfluxPreBuildGitDefinitions,
					},
				},
			},
		}
		pipelineGitTask := []tektonpipeline.PipelineTask{{
			Name: GitTaskName,
			TaskRef: &tektonpipeline.TaskRef{
				// Can't specify name and resolver as they clash.
				ResolverRef: gitResolver,
			},
			Workspaces: []tektonpipeline.WorkspacePipelineTaskBinding{
				{Name: "output", Workspace: WorkspaceSource},
			},
			Params: []tektonpipeline.Param{
				{
					Name: "url",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: modifyURLFragment(log, db.Spec.ScmInfo.SCMURL),
					},
				},
				{
					Name: "revision",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: db.Spec.ScmInfo.CommitHash,
					},
				},
				{
					Name: "submodules",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: strconv.FormatBool(!recipe.DisableSubmodules),
					},
				},
			},
		}}
		pipelinePreBuildTask := []tektonpipeline.PipelineTask{{
			Name:     PreBuildTaskName,
			RunAfter: []string{GitTaskName},
			TaskRef: &tektonpipeline.TaskRef{
				// Can't specify name and resolver as they clash.
				ResolverRef: preBuildResolver,
			},
			Workspaces: []tektonpipeline.WorkspacePipelineTaskBinding{
				{Name: WorkspaceSource, Workspace: WorkspaceSource},
			},
			Params: []tektonpipeline.Param{
				{
					Name: "IMAGE_URL",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: registryArgsWithDefaults(jbsConfig, imageId+"-pre-build-image"),
					},
				},
				{
					Name: "NAME",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: imageId,
					},
				},
				{
					Name: "RECIPE_IMAGE",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: recipe.Image,
					},
				},
				{
					Name: "BUILD_TOOL",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: tool,
					},
				},
				{
					Name: "BUILD_TOOL_VERSION",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: recipe.ToolVersion,
					},
				},
				{
					Name: "JAVA_VERSION",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: recipe.JavaVersion,
					},
				},
				{
					Name: "BUILD_SCRIPT",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: konfluxScript,
					},
				},
				// This will break various parts of JBS as the PNC tooling does NOT support disabling plugins on
				// a per build basis.
				//
				//{
				//	Name: "BUILD_PLUGINS",
				//	Value: tektonpipeline.ParamValue{
				//		Type:      tektonpipeline.ParamTypeString,
				//		StringVal: strings.Join(recipe.DisabledPlugins, ","),
				//	},
				//},
				{
					Name: "PNC_KONFLUX_TOOLING_IMAGE",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: PNCKonfluxToolingImage,
					},
				},
			},
		}}
		pipelinePreBuildGitTask := []tektonpipeline.PipelineTask{{
			Name:     PreBuildGitTaskName,
			RunAfter: []string{PreBuildTaskName},
			TaskRef: &tektonpipeline.TaskRef{
				// Can't specify name and resolver as they clash.
				ResolverRef: preBuildGitResolver,
			},
			Params: []tektonpipeline.Param{
				{
					Name: "NAME",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: imageId,
					},
				},
				{
					Name: "PRE_BUILD_IMAGE_DIGEST",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: "$(tasks." + PreBuildTaskName + ".results." + PipelineResultPreBuildImageDigest + ")",
					},
				},
				{
					Name: "GIT_IDENTITY",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: jbsConfig.Spec.GitSourceArchive.Identity,
					},
				},
				{
					Name: "GIT_URL",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: jbsConfig.Spec.GitSourceArchive.URL,
					},
				},
				{
					Name: "GIT_SSL_VERIFICATION",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: strconv.FormatBool(jbsConfig.Spec.GitSourceArchive.DisableSSLVerification),
					},
				},
				{
					Name: "GIT_REUSE_REPOSITORY",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: strconv.FormatBool(db.Annotations[artifactbuild.DependencyScmAnnotation] == "true"),
					},
				},
				{
					Name: "SCM_URL",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: db.Spec.ScmInfo.SCMURL,
					},
				},
				{
					Name: "SCM_HASH",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: db.Spec.ScmInfo.CommitHash,
					},
				},
				{
					Name: "JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: buildRequestProcessorImage,
					},
				},
			},
		}}
		ps.Tasks = append(pipelineGitTask, ps.Tasks...)
		ps.Tasks = append(pipelinePreBuildTask, ps.Tasks...)
		ps.Tasks = append(pipelinePreBuildGitTask, ps.Tasks...)
		ps.Results = []tektonpipeline.PipelineResult{
			{Name: PipelineResultPreBuildImageDigest, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: "$(tasks." + PreBuildTaskName + ".results." + PipelineResultPreBuildImageDigest + ")"}},
			{Name: PipelineResultGitArchive, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: "$(tasks." + PreBuildGitTaskName + ".results." + PipelineResultGitArchive + ")"}},
		}
	}

	// Note - its also possible to refer to a remote pipeline ref as well as a task.
	buildResolver := tektonpipeline.ResolverRef{
		// We can use either a http or git resolver. Using http as avoids cloning an entire repository.
		Resolver: "http",
		Params: []tektonpipeline.Param{
			{
				Name: "url",
				Value: tektonpipeline.ParamValue{
					Type:      tektonpipeline.ParamTypeString,
					StringVal: v1alpha1.KonfluxBuildDefinitions,
				},
			},
		},
	}

	allowlistUrl, err := url.Parse(cacheUrl)
	if err != nil {
		return nil, "", err
	}
	ps.Tasks = append([]tektonpipeline.PipelineTask{
		{
			Name:     BuildTaskName,
			RunAfter: runAfter,
			TaskRef: &tektonpipeline.TaskRef{
				// Can't specify name and resolver as they clash.
				ResolverRef: buildResolver,
			},
			Timeout: &v12.Duration{Duration: time.Hour * v1alpha1.DefaultTimeout},
			Params: []tektonpipeline.Param{
				{
					Name: "DOCKERFILE",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: ".jbs/Containerfile"},
				},
				{
					Name: "IMAGE",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: registryArgsWithDefaults(jbsConfig, buildId)},
				},
				{
					Name: "SOURCE_ARTIFACT",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: preBuildImage,
					},
				},
				{
					Name: "TLSVERIFY",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: tlsVerify,
					},
				},
				{
					Name: "BUILD_ARGS",
					Value: tektonpipeline.ParamValue{
						Type: tektonpipeline.ParamTypeArray,
						ArrayVal: []string{
							// This allows us to set environment variables that can be picked up by our Containerfile/build script.
							PipelineParamProxyUrl + "=" + cacheUrl,
						},
					},
				},
				{
					Name: "HERMETIC",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: "true",
					},
				},
				{
					Name: "BUILD_IMAGE",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: domainProxyImage(),
					},
				},
				{
					Name: "ENABLE_DOMAIN_PROXY",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: "true",
					},
				},
				{
					Name: "DOMAIN_PROXY_TARGET_ALLOWLIST",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: allowlistUrl.Host + ",localhost,cdn-ubi.redhat.com,repo1.maven.org,repo.scala-sbt.org,scala.jfrog.io,repo.typesafe.com,jfrog-prod-usw2-shared-oregon-main.s3.amazonaws.com",
					},
				},
				{
					Name: "DOMAIN_PROXY_INTERNAL_PROXY_HOST",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: "indy-generic-proxy",
					},
				},
				{
					Name: "DOMAIN_PROXY_INTERNAL_PROXY_PORT",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: "80",
					},
				},
				{
					Name: "DOMAIN_PROXY_INTERNAL_PROXY_USER",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: buildId + "+tracking",
					},
				},
				{
					Name: "DOMAIN_PROXY_INTERNAL_PROXY_PASSWORD",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: "${ACCESS_TOKEN}", // TODO how to get the access token value?
					},
				},
				{
					Name: "DOMAIN_PROXY_INTERNAL_NON_PROXY_HOSTS",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: allowlistUrl.Host + ",localhost",
					},
				},
			},
		}}, ps.Tasks...)

	// Results for https://github.com/konflux-ci/build-definitions/tree/main/task/buildah-oci-ta/0.2
	// IMAGE_DIGEST
	// IMAGE_URL
	ps.Results = append(ps.Results, tektonpipeline.PipelineResult{Name: PipelineResultImage, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: "$(tasks." + BuildTaskName + ".results." + PipelineResultImage + ")"}})
	ps.Results = append(ps.Results, tektonpipeline.PipelineResult{Name: PipelineResultImageDigest, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: "$(tasks." + BuildTaskName + ".results." + PipelineResultImageDigest + ")"}})

	postBuildTask := tektonpipeline.TaskSpec{
		// Using a default emptyDir volume as this task is unique to JBS and don't want it interfering with
		// the shared workspace.
		Volumes: []v1.Volume{{Name: PostBuildVolume, VolumeSource: v1.VolumeSource{EmptyDir: &v1.EmptyDirVolumeSource{}}}},
		Params:  append(pipelineParams, tektonpipeline.ParamSpec{Name: PipelineResultPreBuildImageDigest, Type: tektonpipeline.ParamTypeString}),
		Results: []tektonpipeline.TaskResult{
			{Name: PipelineResultContaminants},
			{Name: PipelineResultDeployedResources},
			{Name: PipelineResultPassedVerification},
			{Name: PipelineResultVerificationResult},
		},
		StepTemplate: &tektonpipeline.StepTemplate{
			VolumeMounts: []v1.VolumeMount{{Name: PostBuildVolume, MountPath: PostBuildVolumeMount}},
		},
		Steps: []tektonpipeline.Step{
			{
				Name:            "restore-post-build-artifacts",
				Image:           strings.TrimSpace(strings.Split(buildTrustedArtifacts, "FROM")[1]),
				ImagePullPolicy: v1.PullIfNotPresent,
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Env:             secretVariables,
				// While the manifest digest is available we need the manifest of the layer within the archive hence
				// using 'oras manifest fetch' to extract the correct layer.
				Script: fmt.Sprintf(`echo "Restoring artifacts"
export ORAS_OPTIONS="%s"
URL=%s
DIGEST=$(tasks.%s.results.IMAGE_DIGEST)
AARCHIVE=$(oras manifest fetch $ORAS_OPTIONS $URL@$DIGEST | jq --raw-output '.layers[0].digest')
echo "URL $URL DIGEST $DIGEST AARCHIVE $AARCHIVE"
use-archive oci:$URL@$AARCHIVE=%s`, orasOptions, registryArgsWithDefaults(jbsConfig, ""), BuildTaskName, PostBuildVolumeMount),
			},
			{
				Name:            "verify-and-check-for-contaminates",
				Image:           buildRequestProcessorImage,
				ImagePullPolicy: pullPolicy,
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				Env:             secretVariables,
				ComputeResources: v1.ResourceRequirements{
					Requests: v1.ResourceList{"memory": limits.defaultBuildRequestMemory, "cpu": limits.defaultRequestCPU},
					Limits:   v1.ResourceList{"memory": limits.defaultBuildRequestMemory, "cpu": limits.defaultLimitCPU},
				},
				Script: artifactbuild.InstallKeystoreIntoBuildRequestProcessor(verifyBuiltArtifactsArgs, deployArgs),
			},
		},
	}
	pipelineTask := []tektonpipeline.PipelineTask{{
		Name:     PostBuildTaskName,
		RunAfter: runAfterBuild,
		TaskSpec: &tektonpipeline.EmbeddedTask{
			TaskSpec: postBuildTask,
		},
		Timeout: &v12.Duration{Duration: time.Hour * v1alpha1.DefaultTimeout},
		Params:  []tektonpipeline.Param{{Name: PipelineResultPreBuildImageDigest, Value: tektonpipeline.ParamValue{Type: tektonpipeline.ParamTypeString, StringVal: preBuildImage}}},
	}}
	ps.Tasks = append(pipelineTask, ps.Tasks...)
	for _, i := range postBuildTask.Results {
		ps.Results = append(ps.Results, tektonpipeline.PipelineResult{Name: i.Name, Description: i.Description, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: "$(tasks." + PostBuildTaskName + ".results." + i.Name + ")"}})
	}

	deployResolver := tektonpipeline.ResolverRef{
		// We can use either a http or git resolver. Using http as avoids cloning an entire repository.
		Resolver: "http",
		Params: []tektonpipeline.Param{
			{
				Name: "url",
				Value: tektonpipeline.ParamValue{
					Type:      tektonpipeline.ParamTypeString,
					StringVal: v1alpha1.KonfluxMavenDeployDefinitions,
				},
			},
		},
	}
	ps.Tasks = append([]tektonpipeline.PipelineTask{
		{
			Name:     DeployTaskName,
			RunAfter: append(runAfterBuild, PostBuildTaskName),
			// Don't need to specify WorkspacePipelineTaskBinding as the deploy task is using
			// an independent volume.
			TaskRef: &tektonpipeline.TaskRef{
				// Can't specify name and resolver as they clash.
				ResolverRef: deployResolver,
			},
			Params: []tektonpipeline.Param{
				{
					Name: PipelineResultImage,
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: "$(tasks." + BuildTaskName + ".results." + PipelineResultImage + ")",
					},
				},
				{
					Name: PipelineResultImageDigest,
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: "$(tasks." + BuildTaskName + ".results." + PipelineResultImageDigest + ")",
					},
				},
				{
					Name: "MVN_REPO",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: jbsConfig.Spec.MavenDeployment.Repository,
					},
				},
				{
					Name: "MVN_USERNAME",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: jbsConfig.Spec.MavenDeployment.Username,
					},
				},
				{
					Name: "MVN_PASSWORD",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: v1alpha1.MavenSecretName,
					},
				},
				{
					Name: "MVN_SERVER_ID",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: "jbs-server",
					},
				},
				{
					Name: "PNC_KONFLUX_TOOLING_IMAGE",
					Value: tektonpipeline.ParamValue{
						Type:      tektonpipeline.ParamTypeString,
						StringVal: PNCKonfluxToolingImage,
					},
				},
			},
		}}, ps.Tasks...)

	for _, i := range pipelineParams {
		ps.Params = append(ps.Params, tektonpipeline.ParamSpec{Name: i.Name, Description: i.Description, Default: i.Default, Type: i.Type})
		var value tektonpipeline.ResultValue
		if i.Type == tektonpipeline.ParamTypeString {
			value = tektonpipeline.ResultValue{Type: i.Type, StringVal: "$(params." + i.Name + ")"}
		} else {
			value = tektonpipeline.ResultValue{Type: i.Type, ArrayVal: []string{"$(params." + i.Name + "[*])"}}
		}
		ps.Tasks[0].Params = append(ps.Tasks[0].Params, tektonpipeline.Param{
			Name:  i.Name,
			Value: value})
		index := 0
		if preBuildImageRequired {
			index += 1
			ps.Tasks[index].Params = append(ps.Tasks[index].Params, tektonpipeline.Param{
				Name:  i.Name,
				Value: value})
			index += 1
			ps.Tasks[index].Params = append(ps.Tasks[index].Params, tektonpipeline.Param{
				Name:  i.Name,
				Value: value})
		}
		index += 1
		ps.Tasks[index].Params = append(ps.Tasks[index].Params, tektonpipeline.Param{
			Name:  i.Name,
			Value: value})
	}

	return ps, df, nil
}

func secretVariables(jbsConfig *v1alpha1.JBSConfig) []v1.EnvVar {
	trueBool := true
	secretVariables := make([]v1.EnvVar, 0)
	if jbsConfig.ImageRegistry().SecretName != "" {
		// Builds or tooling mostly use the .docker/config.json directly which is updated via Tekton/Kubernetes secrets. But the
		// Java code may require the token as well.
		secretVariables = []v1.EnvVar{
			{Name: "REGISTRY_TOKEN", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: jbsConfig.ImageRegistry().SecretName}, Key: v1alpha1.ImageSecretTokenKey, Optional: &trueBool}}},
		}
	}
	if jbsConfig.Spec.MavenDeployment.Repository != "" {
		secretVariables = append(secretVariables, v1.EnvVar{Name: "MAVEN_PASSWORD", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha1.MavenSecretName}, Key: v1alpha1.MavenSecretKey, Optional: &trueBool}}})
	}
	if jbsConfig.Spec.GitSourceArchive.Identity != "" {
		secretVariables = append(secretVariables, v1.EnvVar{Name: "GIT_DEPLOY_TOKEN", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha1.GitRepoSecretName}, Key: v1alpha1.GitRepoSecretKey, Optional: &trueBool}}})
	}
	return secretVariables
}

func pullPolicy(buildRequestProcessorImage string) v1.PullPolicy {
	pullPolicy := v1.PullIfNotPresent
	if strings.HasSuffix(buildRequestProcessorImage, ":dev") || strings.HasSuffix(buildRequestProcessorImage, ":latest") {
		pullPolicy = v1.PullAlways
	}
	return pullPolicy
}

type memLimits struct {
	defaultRequestMemory, defaultBuildRequestMemory, defaultRequestCPU, defaultLimitCPU, buildRequestCPU, buildLimitCPU, buildRequestMemory resource.Quantity
}

func memoryLimits(jbsConfig *v1alpha1.JBSConfig, additionalMemory int) (*memLimits, error) {
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
	limits.buildLimitCPU, err = resource.ParseQuantity(settingOrDefault(jbsConfig.Spec.BuildSettings.BuildLimitCPU, "2"))
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

func additionalPackages(recipe *v1alpha1.BuildRecipe) string {
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

// This effectively duplicates the defaults from DeployPreBuildImageCommand.java
func registryArgsWithDefaults(jbsConfig *v1alpha1.JBSConfig, preBuildImageTag string) string {

	imageRegistry := jbsConfig.ImageRegistry()
	var registryArgs strings.Builder
	if imageRegistry.Host != "" {
		registryArgs.WriteString(imageRegistry.Host)
	} else {
		registryArgs.WriteString("quay.io")
	}
	if imageRegistry.Port != "" && imageRegistry.Port != "443" {
		registryArgs.WriteString(":")
		registryArgs.WriteString(imageRegistry.Port)
	}
	// 'else' :
	// No need to pass a default port (of 443) and its not supported by select-oci-auth.sh according
	// to the tests in https://github.com/konflux-ci/build-trusted-artifacts/pull/103
	registryArgs.WriteString("/")
	if imageRegistry.Owner != "" {
		registryArgs.WriteString(imageRegistry.Owner)
		registryArgs.WriteString("/")
	}
	if imageRegistry.Repository != "" {
		registryArgs.WriteString(imageRegistry.Repository)
	} else {
		registryArgs.WriteString("artifact-deployments")
	}
	// If no tag (or digest) is passed in that allows just the host:owner:repo to be reconstructed.
	if preBuildImageTag != "" {
		registryArgs.WriteString(":")
		registryArgs.WriteString(prependTagToImage(preBuildImageTag, imageRegistry.PrependTag))
	}
	return registryArgs.String()
}

// This is similar to ContainerRegistryDeployer.java::createImageName with the same image tag length restriction.
func prependTagToImage(imageId string, prependTag string) string {

	i := strings.LastIndex(imageId, ":")
	var slice, tag string
	if i != -1 {
		slice = imageId[0:i] + ":"
		tag = prependTag + "_" + imageId[i+1:]
	} else {
		slice = ""
		if prependTag != "" {
			tag = prependTag + "_" + imageId
		} else {
			tag = imageId
		}
	}
	if len(tag) > 128 {
		tag = tag[0:128]
	}
	imageId = slice + tag
	return imageId
}

func verifyParameters(jbsConfig *v1alpha1.JBSConfig, recipe *v1alpha1.BuildRecipe) []string {
	verifyBuiltArtifactsArgs := []string{
		"verify-built-artifacts",
		"--repository-url=$(params." + PipelineParamProxyUrl + ")",
		fmt.Sprintf("--deploy-path=%s/deployment", PostBuildVolumeMount),
		"--task-run-name=$(context.taskRun.name)",
		"--results-file=$(results." + PipelineResultPassedVerification + ".path)",
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

func extractArrayParam(key string, paramValues []tektonpipeline.Param) string {
	// Within the recipe parameters its possible variables are used as '-Pversion=$(PROJECT_VERSION)'.
	// However, this only works in the container and not within the diagnostic container files.
	re := regexp.MustCompile("[(]|[)]")
	result := ""
	for _, i := range paramValues {
		if i.Name == key {
			for _, j := range i.Value.ArrayVal {
				result += re.ReplaceAllString(j, "") + " "
			}
		}
	}
	return result
}

func extractEnvVar(envVar []v1.EnvVar) string {
	result := ""
	for _, i := range envVar {
		result += "export " + i.Name + "=" + i.Value + "\n"
	}
	return result
}

func doSubstitution(script string, paramValues []tektonpipeline.Param, commitTime int64, buildRepos string) string {
	for _, i := range paramValues {
		if i.Value.Type == tektonpipeline.ParamTypeString {
			script = strings.ReplaceAll(script, "$(params."+i.Name+")", i.Value.StringVal)
		}
	}
	script = strings.ReplaceAll(script, "$(params."+PipelineParamProxyUrl+")", "http://localhost:8080/v2/cache/rebuild"+buildRepos+"/"+strconv.FormatInt(commitTime, 10)+"/")
	script = strings.ReplaceAll(script, "$(workspaces.source.path)", "/var/workdir/workspace")
	script = strings.ReplaceAll(script, "$(workspaces.tls.path)", "/var/workdir/software/tls/service-ca.crt")
	return script
}

func settingOrDefault(setting, def string) string {
	if len(strings.TrimSpace(setting)) == 0 {
		return def
	}
	return setting
}

func domainProxyImage() string {
	domainProxyImage := os.Getenv("JVM_BUILD_SERVICE_DOMAIN_PROXY_IMAGE")
	if len(domainProxyImage) == 0 {
		domainProxyImage = DomainProxyImage
	}
	return domainProxyImage
}
