package dependencybuild

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/jbsconfig"
	"github.com/tektoncd/cli/pkg/cli"
	"k8s.io/client-go/kubernetes"
	"k8s.io/utils/strings/slices"
	"sort"
	"strconv"
	"strings"
	"time"

	"k8s.io/apimachinery/pkg/api/resource"
	"k8s.io/apimachinery/pkg/labels"

	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	v12 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	"knative.dev/pkg/apis"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	"github.com/go-logr/logr"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/systemconfig"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout               = 300 * time.Second
	PipelineBuildId              = "DEPENDENCY_BUILD"
	PipelineParamScmUrl          = "URL"
	PipelineParamScmTag          = "TAG"
	PipelineParamScmHash         = "HASH"
	PipelineParamPath            = "CONTEXT_DIR"
	PipelineParamChainsGitUrl    = "CHAINS-GIT_URL"
	PipelineParamChainsGitCommit = "CHAINS-GIT_COMMIT"
	PipelineParamImage           = "IMAGE"
	PipelineParamGoals           = "GOALS"
	PipelineParamJavaVersion     = "JAVA_VERSION"
	PipelineParamToolVersion     = "TOOL_VERSION"
	PipelineParamEnforceVersion  = "ENFORCE_VERSION"
	PipelineParamCacheUrl        = "CACHE_URL"
	PipelineResultImage          = "IMAGE_URL"
	PipelineResultImageDigest    = "IMAGE_DIGEST"

	BuildInfoPipelineResultBuildInfo = "BUILD_INFO"

	PipelineTypeLabel     = "jvmbuildservice.io/pipeline-type"
	PipelineTypeBuildInfo = "build-info"
	PipelineTypeBuild     = "build"

	RetryDueToMemoryAnnotation = "jvmbuildservice.io/retry-build-lookup-due-to-memory"
	MaxRetries                 = 3
	MemoryIncrement            = 512

	PipelineRunFinalizer = "jvmbuildservice.io/finalizer"
	JavaHome             = "JAVA_HOME"
)

type ReconcileDependencyBuild struct {
	client          client.Client
	scheme          *runtime.Scheme
	eventRecorder   record.EventRecorder
	clientSet       *kubernetes.Clientset
	logReaderParams *cli.TektonParams
}

func newReconciler(mgr ctrl.Manager, clientset *kubernetes.Clientset, logReaderParams *cli.TektonParams) reconcile.Reconciler {
	return &ReconcileDependencyBuild{
		clientSet:       clientset,
		client:          mgr.GetClient(),
		scheme:          mgr.GetScheme(),
		eventRecorder:   mgr.GetEventRecorderFor("DependencyBuild"),
		logReaderParams: logReaderParams,
	}
}

func (r *ReconcileDependencyBuild) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	var cancel context.CancelFunc
	ctx, cancel = context.WithTimeout(ctx, contextTimeout)
	defer cancel()
	log := ctrl.Log.WithName("dependencybuild").WithValues("namespace", request.NamespacedName.Namespace, "resource", request.Name)

	db := v1alpha1.DependencyBuild{}
	dberr := r.client.Get(ctx, request.NamespacedName, &db)
	if dberr != nil {
		if !errors.IsNotFound(dberr) {
			log.Error(dberr, "Reconcile key %s as dependencybuild unexpected error", request.NamespacedName.String())
			return ctrl.Result{}, dberr
		}
	}

	pr := pipelinev1beta1.PipelineRun{}
	trerr := r.client.Get(ctx, request.NamespacedName, &pr)
	if trerr != nil {
		if !errors.IsNotFound(trerr) {
			log.Error(trerr, "Reconcile key %s as pipelinerun unexpected error", request.NamespacedName.String())
			return ctrl.Result{}, trerr
		}
	}

	if trerr != nil && dberr != nil {
		log.Info(fmt.Sprintf("Reconcile key %s received not found errors for both pipelineruns and dependencybuilds (probably deleted)", request.NamespacedName.String()))
		return ctrl.Result{}, nil
	}

	switch {
	case dberr == nil:
		log = log.WithValues("kind", "DependencyBuild", "db-scm-url", db.Spec.ScmInfo.SCMURL, "db-scm-tag", db.Spec.ScmInfo.Tag)
		err, done := r.handleDeprecatedFields(ctx, db)
		if done || err != nil {
			return reconcile.Result{}, err
		}
		done, err = r.handleS3SyncDependencyBuild(ctx, &db, log)
		if done || err != nil {
			return reconcile.Result{}, err
		}
		switch db.Status.State {
		case "", v1alpha1.DependencyBuildStateNew:
			return r.handleStateNew(ctx, log, &db)
		case v1alpha1.DependencyBuildStateSubmitBuild:
			return r.handleStateSubmitBuild(ctx, &db)
		case v1alpha1.DependencyBuildStateFailed:
			return reconcile.Result{}, nil
		case v1alpha1.DependencyBuildStateBuilding:
			return r.handleStateBuilding(ctx, log, &db)
		case v1alpha1.DependencyBuildStateContaminated:
			return r.handleStateContaminated(ctx, &db)
		case v1alpha1.DependencyBuildStateComplete:
			return reconcile.Result{}, nil
		}

	case trerr == nil:

		done, err := r.handleS3SyncPipelineRun(ctx, log, &pr)
		if done || err != nil {
			return reconcile.Result{}, err
		}
		if pr.DeletionTimestamp != nil {
			//always remove the finalizer if it is deleted
			//but continue with the method
			//if the PR is deleted while it is running then we want to allow that
			result, err2 := RemovePipelineFinalizer(ctx, &pr, r.client)
			if err2 != nil {
				return result, err2
			}
		}
		log = log.WithValues("kind", "PipelineRun")
		pipelineType := pr.Labels[PipelineTypeLabel]
		switch pipelineType {
		case PipelineTypeBuildInfo:
			// Note in the case where shared repositories are configured the build discovery pipeline can shortcut
			// setting the DependencyBuild state to Complete utilising the pre-existing builds.
			return r.handleAnalyzeBuildPipelineRunReceived(ctx, log, &pr)
		case PipelineTypeBuild:
			return r.handleBuildPipelineRunReceived(ctx, log, &pr)
		}
	}

	return reconcile.Result{}, nil
}

func (r *ReconcileDependencyBuild) handleDeprecatedFields(ctx context.Context, db v1alpha1.DependencyBuild) (error, bool) {
	deprecatedInfoRemoved := false
	if db.Status.DeprecatedCurrentBuildRecipe != nil {
		db.Status.DeprecatedCurrentBuildRecipe = nil
		deprecatedInfoRemoved = true
	}
	if db.Status.DeprecatedDiagnosticDockerFiles != nil {
		db.Status.DeprecatedDiagnosticDockerFiles = nil
		deprecatedInfoRemoved = true
	}
	if db.Status.DeprecatedFailedBuildRecipes != nil {
		db.Status.DeprecatedFailedBuildRecipes = nil
		deprecatedInfoRemoved = true
	}
	if db.Status.DeprecatedLastCompletedBuildPipelineRun != "" {
		db.Status.DeprecatedLastCompletedBuildPipelineRun = ""
		deprecatedInfoRemoved = true
	}
	if deprecatedInfoRemoved {
		return r.client.Status().Update(ctx, &db), true
	}
	return nil, false
}

func (r *ReconcileDependencyBuild) handleStateNew(ctx context.Context, log logr.Logger, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	jbsConfig := &v1alpha1.JBSConfig{}
	err := r.client.Get(ctx, types.NamespacedName{Namespace: db.Namespace, Name: v1alpha1.JBSConfigName}, jbsConfig)
	if err != nil && !errors.IsNotFound(err) {
		return reconcile.Result{}, err
	}
	// create pipeline run
	pr := pipelinev1beta1.PipelineRun{}
	pr.Finalizers = []string{PipelineRunFinalizer}
	systemConfig := v1alpha1.SystemConfig{}
	err = r.client.Get(ctx, types.NamespacedName{Name: systemconfig.SystemConfigKey}, &systemConfig)
	if err != nil {
		if !errors.IsNotFound(err) {
			return reconcile.Result{}, err
		}
		//on not found we don't return the error
		//no need to retry it would just result in an infinite loop
		return reconcile.Result{}, nil
	}
	additionalMemory := 0
	if db.Annotations != nil && db.Annotations[RetryDueToMemoryAnnotation] == "true" {
		//TODO: hard coded for now
		//should be enough for the build lookup task
		additionalMemory = 1024
	}
	pr.Spec.PipelineSpec, err = r.createLookupBuildInfoPipeline(ctx, log, db, jbsConfig, additionalMemory, &systemConfig)
	if err != nil {
		return reconcile.Result{}, err
	}
	if !jbsConfig.Spec.CacheSettings.DisableTLS {
		pr.Spec.Workspaces = []pipelinev1beta1.WorkspaceBinding{{Name: "tls", ConfigMap: &v1.ConfigMapVolumeSource{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha1.TlsConfigMapName}}}}
	} else {
		pr.Spec.Workspaces = []pipelinev1beta1.WorkspaceBinding{{Name: "tls", EmptyDir: &v1.EmptyDirVolumeSource{}}}
	}
	pr.Namespace = db.Namespace
	pr.GenerateName = db.Name + "-build-discovery-"
	pr.Labels = map[string]string{artifactbuild.PipelineRunLabel: "", artifactbuild.DependencyBuildIdLabel: db.Name, PipelineTypeLabel: PipelineTypeBuildInfo}
	if err := controllerutil.SetOwnerReference(db, &pr, r.scheme); err != nil {
		return reconcile.Result{}, err
	}
	db.Status.State = v1alpha1.DependencyBuildStateAnalyzeBuild
	if err := r.client.Status().Update(ctx, db); err != nil {
		return reconcile.Result{}, err
	}
	if err := r.client.Create(ctx, &pr); err != nil {
		return reconcile.Result{}, nil
	}
	return reconcile.Result{}, nil
}

func (r *ReconcileDependencyBuild) handleAnalyzeBuildPipelineRunReceived(ctx context.Context, log logr.Logger, pr *pipelinev1beta1.PipelineRun) (reconcile.Result, error) {
	if pr.Status.CompletionTime == nil {
		return reconcile.Result{}, nil
	}
	ownerRefs := pr.GetOwnerReferences()
	if len(ownerRefs) == 0 {
		msg := "pipelinerun missing ownerrefs %s:%s"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name)
		log.Info(msg, pr.Namespace, pr.Name)
		return reconcile.Result{}, nil
	}
	ownerName := ""
	for _, ownerRef := range ownerRefs {
		if strings.EqualFold(ownerRef.Kind, "dependencybuild") || strings.EqualFold(ownerRef.Kind, "dependencybuilds") {
			ownerName = ownerRef.Name
			break
		}
	}
	if len(ownerName) == 0 {
		msg := "pipelinerun missing dependencybuild ownerrefs %s:%s"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, "MissingOwner", msg, pr.Namespace, pr.Name)
		log.Info(msg, pr.Namespace, pr.Name)

		return reconcile.Result{}, nil
	}

	key := types.NamespacedName{Namespace: pr.Namespace, Name: ownerName}
	db := v1alpha1.DependencyBuild{}
	err := r.client.Get(ctx, key, &db)
	if err != nil {
		msg := "get for pipelinerun %s:%s owning db %s:%s yielded error %s"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name, pr.Namespace, ownerName, err.Error())
		log.Error(err, fmt.Sprintf(msg, pr.Namespace, pr.Name, pr.Namespace, ownerName, err.Error()))
		if !errors.IsNotFound(err) {
			return reconcile.Result{}, err
		}
		//on not found we don't return the error
		//no need to retry it would just result in an infinite loop
		return reconcile.Result{}, nil
	}
	if db.Status.DiscoveryPipelineResults == nil {
		db.Status.DiscoveryPipelineResults = &v1alpha1.PipelineResults{}
	}
	modified := r.handleTektonResultsForPipeline(db.Status.DiscoveryPipelineResults, pr)
	if db.Status.State != v1alpha1.DependencyBuildStateAnalyzeBuild {
		if modified {
			return reconcile.Result{}, r.client.Status().Update(ctx, &db)
		}
		return reconcile.Result{}, nil
	}

	var buildInfo string
	//we grab the results here and put them on the ABR
	for _, res := range pr.Status.Results {
		switch res.Name {
		case BuildInfoPipelineResultBuildInfo:
			buildInfo = res.Value.StringVal
		}
	}
	success := pr.Status.GetCondition(apis.ConditionSucceeded).IsTrue()
	if !success {
		if (db.Annotations == nil || db.Annotations[RetryDueToMemoryAnnotation] != "true") && r.failedDueToMemory(ctx, log, pr) {
			err := r.client.Delete(ctx, pr)
			if err != nil {
				return reconcile.Result{}, err
			}
			if db.Annotations == nil {
				db.Annotations = map[string]string{}
			}
			db.Annotations[RetryDueToMemoryAnnotation] = "true"
			return r.handleStateNew(ctx, log, &db)
		} else {
			db.Status.State = v1alpha1.DependencyBuildStateFailed
			db.Status.Message = buildInfo
		}

	} else {
		unmarshalled := marshalledBuildInfo{}

		if err := json.Unmarshal([]byte(buildInfo), &unmarshalled); err != nil {
			r.eventRecorder.Eventf(&db, v1.EventTypeWarning, "InvalidJson", "Failed to unmarshal build info for AB %s/%s JSON: %s", db.Namespace, db.Name, buildInfo)

			db.Status.State = v1alpha1.DependencyBuildStateFailed
			db.Status.Message = "failed to unmarshal json build info: " + err.Error() + ": " + buildInfo
			return reconcile.Result{}, r.client.Status().Update(ctx, &db)
		}

		//read our builder images from the config
		var allBuilderImages []BuilderImage
		allBuilderImages, err = r.processBuilderImages(ctx, log)
		if err != nil {
			return reconcile.Result{}, err
		}
		// for now we are ignoring the tool versions
		// and just using the supplied invocations
		buildRecipes := []*v1alpha1.BuildRecipe{}
		db.Status.CommitTime = unmarshalled.CommitTime

		if len(unmarshalled.Invocations) == 0 {
			log.Error(nil, "Unable to determine build tool", "info", unmarshalled)
			db.Status.State = v1alpha1.DependencyBuildStateFailed
			return reconcile.Result{}, r.client.Status().Update(ctx, &db)
		}
		for _, command := range unmarshalled.Invocations {
			//loop through the builder images to find one that meets all the requirements
			//if there is no match then we ignore the combo
			for _, image := range allBuilderImages {
				imageOk := true
				for tool, version := range command.ToolVersion {
					versions, exists := image.Tools[tool]
					if !exists {
						imageOk = false
						break
					}
					if !slices.Contains(versions, version) {
						imageOk = false
						break
					}
				}
				if imageOk {
					buildRecipes = append(buildRecipes, &v1alpha1.BuildRecipe{Image: image.Image, CommandLine: command.Commands, EnforceVersion: unmarshalled.EnforceVersion, ToolVersion: command.ToolVersion[command.Tool], ToolVersions: command.ToolVersion, JavaVersion: command.ToolVersion["jdk"], Tool: command.Tool, PreBuildScript: unmarshalled.PreBuildScript, PostBuildScript: unmarshalled.PostBuildScript, AdditionalDownloads: unmarshalled.AdditionalDownloads, DisableSubmodules: unmarshalled.DisableSubmodules, AdditionalMemory: unmarshalled.AdditionalMemory, Repositories: unmarshalled.Repositories, AllowedDifferences: unmarshalled.AllowedDifferences, DisabledPlugins: unmarshalled.DisabledPlugins})
					break
				}
			}

		}

		db.Status.PotentialBuildRecipes = buildRecipes

		if len(unmarshalled.Image) > 0 {
			log.Info(fmt.Sprintf("Found preexisting shared build with deployed GAVs %#v from image %#v", unmarshalled.Gavs, unmarshalled.Image))
			db.Status.State = v1alpha1.DependencyBuildStateComplete
			con, err := r.createRebuiltArtifacts(ctx, log, pr, &db, unmarshalled.Image, unmarshalled.Digest, unmarshalled.Gavs)
			if err != nil {
				return reconcile.Result{}, err
			} else if !con {
				return reconcile.Result{}, nil
			}
		} else {
			db.Status.State = v1alpha1.DependencyBuildStateSubmitBuild
		}
	}

	err = r.client.Status().Update(ctx, &db)
	if err != nil {
		return reconcile.Result{}, err
	}

	return reconcile.Result{}, nil
}

type marshalledBuildInfo struct {
	Invocations         []invocation
	EnforceVersion      string
	AdditionalDownloads []v1alpha1.AdditionalDownload
	CommitTime          int64
	PreBuildScript      string
	PostBuildScript     string
	DisableSubmodules   bool
	AdditionalMemory    int
	Repositories        []string
	AllowedDifferences  []string
	Image               string
	Digest              string
	Gavs                []string
	DisabledPlugins     string
}

type invocation struct {
	Tool        string
	Commands    []string
	ToolVersion map[string]string
}

func (r *ReconcileDependencyBuild) processBuilderImages(ctx context.Context, log logr.Logger) ([]BuilderImage, error) {
	systemConfig := v1alpha1.SystemConfig{}
	getCtx := ctx
	err := r.client.Get(getCtx, types.NamespacedName{Name: systemconfig.SystemConfigKey}, &systemConfig)
	if err != nil {
		return nil, err
	}
	//TODO how important is the order here?  do we want 11,8,17 per the old form at https://github.com/redhat-appstudio/jvm-build-service/blob/b91ec6e1888e43962cba16fcaee94e0c9f64557d/deploy/operator/config/system-config.yaml#L8
	// the unit tests's imaage verification certainly assumes a order
	result := []BuilderImage{}
	for _, val := range systemConfig.Spec.Builders {
		result = append(result, BuilderImage{
			Image:    val.Image,
			Tools:    r.processBuilderImageTags(val.Tag),
			Priority: val.Priority,
		})
	}
	sort.Slice(result, func(i, j int) bool {
		return result[i].Priority > result[j].Priority
	})
	return result, nil
}

func (r *ReconcileDependencyBuild) processBuilderImageTags(tags string) map[string][]string {
	tagList := strings.Split(tags, ",")
	tools := map[string][]string{}
	for _, tag := range tagList {
		split := strings.Split(tag, ":")
		key := split[0]
		val := split[1]
		tools[key] = append(tools[key], strings.Split(val, ";")...)
	}
	return tools
}

func (r *ReconcileDependencyBuild) handleStateSubmitBuild(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	//the current recipe has been built, we need to pick a new one
	//pick the first recipe in the potential list
	//new build, kick off a pipeline run to run the build
	//first we update the recipes, but add a flag that this is not submitted yet

	//no more attempts
	if len(db.Status.PotentialBuildRecipes) == 0 {
		db.Status.State = v1alpha1.DependencyBuildStateFailed
		r.eventRecorder.Eventf(db, v1.EventTypeWarning, "BuildFailed", "The DependencyBuild %s/%s moved to failed, all recipes exhausted", db.Namespace, db.Name)
		return reconcile.Result{}, r.client.Status().Update(ctx, db)
	}
	ba := v1alpha1.BuildAttempt{}
	ba.Recipe = db.Status.PotentialBuildRecipes[0]
	pipelineName := currentDependencyBuildPipelineName(db)
	ba.Build = &v1alpha1.BuildPipelineRun{
		PipelineName: pipelineName,
	}
	//and remove if from the potential list
	db.Status.PotentialBuildRecipes = db.Status.PotentialBuildRecipes[1:]
	db.Status.BuildAttempts = append(db.Status.BuildAttempts, &ba)
	db.Status.State = v1alpha1.DependencyBuildStateBuilding
	//create the pipeline run
	return reconcile.Result{}, r.client.Status().Update(ctx, db)

}

func (r *ReconcileDependencyBuild) handleStateBuilding(ctx context.Context, log logr.Logger, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {

	//first we check to see if the pipeline exists
	attempt := db.Status.BuildAttempts[len(db.Status.BuildAttempts)-1]

	pr := pipelinev1beta1.PipelineRun{}
	err := r.client.Get(ctx, types.NamespacedName{Name: attempt.Build.PipelineName, Namespace: db.Namespace}, &pr)
	if err == nil {
		//the build already exists
		//do nothing
		return reconcile.Result{}, nil
	}
	if !errors.IsNotFound(err) {
		//other error
		return reconcile.Result{}, err
	}

	//now submit the pipeline
	pr.Finalizers = []string{PipelineRunFinalizer}
	buildRequestProcessorImage, err := r.buildRequestProcessorImage(ctx, log)
	if err != nil {
		return reconcile.Result{}, err
	}
	pr.Namespace = db.Namespace
	// we do not use generate name since a) it was used in creating the db and the db name has random ids b) there is a 1 to 1 relationship (but also consider potential recipe retry)
	// c) it allows us to use the already exist error on create to short circuit the creation of dbs if owner refs updates to the db before
	// we move the db out of building
	pr.Name = attempt.Build.PipelineName
	pr.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: db.Name, artifactbuild.PipelineRunLabel: "", PipelineTypeLabel: PipelineTypeBuild}

	jbsConfig := &v1alpha1.JBSConfig{}
	err = r.client.Get(ctx, types.NamespacedName{Namespace: db.Namespace, Name: v1alpha1.JBSConfigName}, jbsConfig)
	if err != nil && !errors.IsNotFound(err) {
		return reconcile.Result{}, err
	}
	pr.Spec.PipelineRef = nil

	if err != nil {
		return reconcile.Result{}, err
	}
	scmUrl := modifyURLFragment(log, db.Spec.ScmInfo.SCMURL)
	paramValues := []pipelinev1beta1.Param{
		{Name: PipelineBuildId, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Name}},
		{Name: PipelineParamScmUrl, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: scmUrl}},
		{Name: PipelineParamScmTag, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.Tag}},
		{Name: PipelineParamScmHash, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.CommitHash}},
		{Name: PipelineParamChainsGitUrl, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: scmUrl}},
		{Name: PipelineParamChainsGitCommit, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.CommitHash}},
		{Name: PipelineParamPath, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.Path}},
		{Name: PipelineParamImage, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: attempt.Recipe.Image}},
		{Name: PipelineParamGoals, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeArray, ArrayVal: attempt.Recipe.CommandLine}},
		{Name: PipelineParamEnforceVersion, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: attempt.Recipe.EnforceVersion}},
		{Name: PipelineParamToolVersion, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: attempt.Recipe.ToolVersion}},
		{Name: PipelineParamJavaVersion, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: attempt.Recipe.JavaVersion}},
	}

	systemConfig := v1alpha1.SystemConfig{}
	err = r.client.Get(ctx, types.NamespacedName{Name: systemconfig.SystemConfigKey}, &systemConfig)
	if err != nil {
		return reconcile.Result{}, err
	}
	diagnostic := ""
	// TODO: set owner, pass parameter to do verify if true, via an annoaton on the dependency build, may eed to wait for dep build to exist verify is an optional, use append on each step in build recipes
	pr.Spec.PipelineSpec, diagnostic, err = createPipelineSpec(attempt.Recipe.Tool, db.Status.CommitTime, jbsConfig, &systemConfig, attempt.Recipe, db, paramValues, buildRequestProcessorImage)
	if err != nil {
		return reconcile.Result{}, err
	}

	attempt.Build.DiagnosticDockerFile = diagnostic
	pr.Spec.Params = paramValues
	pr.Spec.Workspaces = []pipelinev1beta1.WorkspaceBinding{
		{Name: WorkspaceBuildSettings, EmptyDir: &v1.EmptyDirVolumeSource{}},
		{Name: WorkspaceSource, EmptyDir: &v1.EmptyDirVolumeSource{}},
	}

	if !jbsConfig.Spec.CacheSettings.DisableTLS {
		pr.Spec.Workspaces = append(pr.Spec.Workspaces, pipelinev1beta1.WorkspaceBinding{Name: "tls", ConfigMap: &v1.ConfigMapVolumeSource{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha1.TlsConfigMapName}}})
	} else {
		pr.Spec.Workspaces = append(pr.Spec.Workspaces, pipelinev1beta1.WorkspaceBinding{Name: "tls", EmptyDir: &v1.EmptyDirVolumeSource{}})
	}
	pr.Spec.Timeouts = &pipelinev1beta1.TimeoutFields{Pipeline: &v12.Duration{Duration: time.Hour * 3}}
	if err := controllerutil.SetOwnerReference(db, &pr, r.scheme); err != nil {
		return reconcile.Result{}, err
	}
	//now we submit the build
	if err := r.client.Create(ctx, &pr); err != nil {
		if errors.IsAlreadyExists(err) {
			log.V(4).Info("handleStateBuilding: pipelinerun %s:%s already exists, not retrying", pr.Namespace, pr.Name)
			return reconcile.Result{}, nil
		}
		r.eventRecorder.Eventf(db, v1.EventTypeWarning, "PipelineRunCreationFailed", "The DependencyBuild %s/%s failed to create its build pipeline run", db.Namespace, db.Name)
		return reconcile.Result{}, err
	}
	return reconcile.Result{}, r.client.Status().Update(ctx, db)
}

func currentDependencyBuildPipelineName(db *v1alpha1.DependencyBuild) string {
	return fmt.Sprintf("%s-build-%d", db.Name, len(db.Status.BuildAttempts))
}

func (r *ReconcileDependencyBuild) handleBuildPipelineRunReceived(ctx context.Context, log logr.Logger, pr *pipelinev1beta1.PipelineRun) (reconcile.Result, error) {
	if pr.Status.CompletionTime != nil {
		db, err := r.dependencyBuildForPipelineRun(ctx, log, pr)
		if err != nil || db == nil {
			return reconcile.Result{}, err
		}

		attempt := db.Status.GetBuildPipelineRun(pr.Name)
		if attempt == nil {
			msg := fmt.Sprintf("unknown build pipeline run for db %s %s:%s", db.Name, pr.Namespace, pr.Name)
			r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, msg)
			log.Info(msg, pr.Namespace, pr.Name)
			return reconcile.Result{}, nil
		}
		run := attempt.Build

		if run.Complete {
			//we have already seen this

			//we still need to check for tekton results updates though
			if r.handleTektonResults(db, pr) {
				return reconcile.Result{}, r.client.Status().Update(ctx, db)
			}

			return reconcile.Result{}, nil
		}

		run.Complete = true
		run.Succeeded = pr.Status.GetCondition(apis.ConditionSucceeded).IsTrue()

		if !run.Succeeded {
			log.Info(fmt.Sprintf("build %s failed", pr.Name))

			//if there was a cache issue we want to retry the build
			//we check and see if there is a cache pod newer than the build
			//if so we just delete the pipelinerun
			p := v1.PodList{}
			listOpts := &client.ListOptions{
				Namespace:     pr.Namespace,
				LabelSelector: labels.SelectorFromSet(map[string]string{"app": v1alpha1.CacheDeploymentName}),
			}
			err := r.client.List(ctx, &p, listOpts)
			if err != nil {
				return reconcile.Result{}, err
			}
			if db.Status.PipelineRetries < MaxRetries {

				doRetry := false
				for _, pod := range p.Items {
					if pod.ObjectMeta.CreationTimestamp.After(pr.ObjectMeta.CreationTimestamp.Time) {
						doRetry = true
						msg := fmt.Sprintf("Cache problems detected, retrying the build for DependencyBuild %s", db.Name)
						log.Info(msg)
					}

				}
				if !doRetry && attempt.Recipe.AdditionalMemory < 2048 {
					if r.failedDueToMemory(ctx, log, pr) {
						msg := fmt.Sprintf("OOMKilled Pod detected, retrying the build for DependencyBuild with more memory %s, PR UID: %s, Current additional memory: %d", db.Name, pr.UID, attempt.Recipe.AdditionalMemory)
						log.Info(msg)
						doRetry = true
						//increase the memory limit
						if attempt.Recipe.AdditionalMemory == 0 {
							attempt.Recipe.AdditionalMemory = MemoryIncrement
						} else {
							attempt.Recipe.AdditionalMemory = attempt.Recipe.AdditionalMemory * 2
						}
						for i := range db.Status.PotentialBuildRecipes {
							db.Status.PotentialBuildRecipes[i].AdditionalMemory = attempt.Recipe.AdditionalMemory
						}
					}
				}

				if doRetry {
					existing := db.Status.PotentialBuildRecipes
					db.Status.PotentialBuildRecipes = []*v1alpha1.BuildRecipe{attempt.Recipe}
					db.Status.PotentialBuildRecipes = append(db.Status.PotentialBuildRecipes, existing...)
					db.Status.PipelineRetries++
					db.Status.State = v1alpha1.DependencyBuildStateSubmitBuild
					err := r.client.Status().Update(ctx, db)
					return reconcile.Result{}, err
				}
			}

		}

		//the pr is done, lets update the run details

		if run.Succeeded {
			var image string
			var digest string
			var passedVerification bool
			var verificationResults string
			var gavs []string
			var hermeticBuildImage string
			for _, i := range pr.Status.Results {
				if i.Name == PipelineResultImage {
					image = i.Value.StringVal
				} else if i.Name == PipelineResultImageDigest {
					digest = i.Value.StringVal
				} else if i.Name == artifactbuild.PipelineResultContaminants {

					db.Status.Contaminants = []v1alpha1.Contaminant{}
					//unmarshal directly into the contaminants field
					err := json.Unmarshal([]byte(i.Value.StringVal), &db.Status.Contaminants)
					if err != nil {
						return reconcile.Result{}, err
					}
				} else if i.Name == artifactbuild.PipelineResultPassedVerification {
					parseBool, _ := strconv.ParseBool(i.Value.StringVal)
					passedVerification = !parseBool
				} else if i.Name == artifactbuild.PipelineResultGavs {
					deployed := strings.Split(i.Value.StringVal, ",")
					db.Status.DeployedArtifacts = deployed
				} else if i.Name == artifactbuild.PipelineResultVerificationResult {
					verificationResults = i.Value.StringVal
				}
			}
			run.Results = &v1alpha1.BuildPipelineRunResults{
				Image:               image,
				ImageDigest:         digest,
				Verified:            passedVerification,
				VerificationResults: verificationResults,
				Gavs:                gavs,
				HermeticBuildImage:  hermeticBuildImage,
			}

			for _, i := range pr.Status.Results {
				if i.Name == artifactbuild.PipelineResultContaminants {

					db.Status.Contaminants = []v1alpha1.Contaminant{}
					//unmarshal directly into the contaminants field
					err := json.Unmarshal([]byte(i.Value.StringVal), &db.Status.Contaminants)
					if err != nil {
						return reconcile.Result{}, err
					}
				} else if i.Name == artifactbuild.PipelineResultDeployedResources && len(i.Value.StringVal) > 0 {
					//we need to create 'DeployedArtifact' resources for the objects that were deployed
					deployed := strings.Split(i.Value.StringVal, ",")

					con, err := r.createRebuiltArtifacts(ctx, log, pr, db, image, digest, deployed)

					if err != nil {
						return reconcile.Result{}, err
					} else if !con {
						return reconcile.Result{}, nil
					}
				} else if i.Name == artifactbuild.PipelineResultPassedVerification {
					parseBool, _ := strconv.ParseBool(i.Value.StringVal)
					db.Status.FailedVerification = !parseBool
				}
			}

			if len(db.Status.Contaminants) == 0 {
				db.Status.State = v1alpha1.DependencyBuildStateComplete
			} else {
				r.eventRecorder.Eventf(db, v1.EventTypeWarning, "BuildContaminated", "The DependencyBuild %s/%s was contaminated with community dependencies", db.Namespace, db.Name)
				//the dependency was contaminated with community deps
				//most likely shaded in
				//we don't need to update the status here, it will be handled by the handleStateComplete method
				//even though there are contaminates they may not be in artifacts we care about
				err := r.handleBuildCompletedWithContaminants(ctx, db, log)
				if err != nil {
					return reconcile.Result{}, err
				}
				return reconcile.Result{}, nil
			}
		} else {
			//try again, if there are no more recipes this gets handled in the submit build logic
			db.Status.State = v1alpha1.DependencyBuildStateSubmitBuild
		}
		err = r.client.Status().Update(ctx, db)
		return reconcile.Result{}, err
	} else if pr.GetDeletionTimestamp() != nil {
		//pr is being deleted
		db, err := r.dependencyBuildForPipelineRun(ctx, log, pr)
		if err != nil || db == nil {
			return reconcile.Result{}, err
		}
		changed := r.handleTektonResults(db, pr)
		ba := db.Status.GetBuildPipelineRun(pr.Name)
		//the relevant run was not complete, mark it as failed
		if !ba.Build.Complete {
			ba.Build.Succeeded = false
			ba.Build.Complete = true
			db.Status.State = v1alpha1.DependencyBuildStateSubmitBuild
			changed = true
		}
		if changed {
			err = r.client.Status().Update(ctx, db)
			if err != nil {
				return reconcile.Result{}, err
			}
		}
		return reconcile.Result{}, nil
	}
	return reconcile.Result{}, nil

}

func (r *ReconcileDependencyBuild) dependencyBuildForPipelineRun(ctx context.Context, log logr.Logger, pr *pipelinev1beta1.PipelineRun) (*v1alpha1.DependencyBuild, error) {
	// get db
	ownerRefs := pr.GetOwnerReferences()
	if len(ownerRefs) == 0 {
		msg := "pipelinerun missing ownerrefs %s:%s"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name)
		log.Info(msg, pr.Namespace, pr.Name)

		return nil, nil
	}
	if len(ownerRefs) > 1 {
		// workaround for event/logging methods that can only take string args
		count := fmt.Sprintf("%d", len(ownerRefs))
		msg := "pipelinerun %s:%s has %s ownerrefs but only using the first dependencybuild ownerfef"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name, count)
		log.Info(msg, pr.Namespace, pr.Name, count)
	}
	// even though we filter out artifactbuild pipelineruns, let's check the kind and make sure
	// we use a dependencybuild ownerref
	var ownerRef *v12.OwnerReference
	for i, or := range ownerRefs {
		if strings.EqualFold(or.Kind, "dependencybuild") || strings.EqualFold(or.Kind, "dependencybuilds") {
			ownerRef = &ownerRefs[i]
			break
		}
	}
	if ownerRef == nil {
		msg := "pipelinerun missing dependencybuild onwerrefs %s:%s"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name)
		log.Info(msg, pr.Namespace, pr.Name)

		return nil, nil
	}

	key := types.NamespacedName{Namespace: pr.Namespace, Name: ownerRef.Name}
	db := v1alpha1.DependencyBuild{}
	err := r.client.Get(ctx, key, &db)
	if err != nil {
		msg := "get for pipelinerun %s:%s owning db %s:%s yielded error %s"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name, pr.Namespace, ownerRef.Name, err.Error())
		log.Error(err, fmt.Sprintf(msg, pr.Namespace, pr.Name, pr.Namespace, ownerRef.Name, err.Error()))
		//on not found we don't return the error
		//no need to retry
		if errors.IsNotFound(err) {
			return nil, nil
		}
		return nil, err
	}
	return &db, nil
}

// This checks that the build is still considered uncontaminated
// even if some artifacts in the build were contaminated it may still be considered a success if there was
// no actual request for these artifacts. This can change if new artifacts are requested, so even when complete
// we still need to verify that hte build is ok
// this method will always update the status if it does not return an error
func (r *ReconcileDependencyBuild) handleBuildCompletedWithContaminants(ctx context.Context, db *v1alpha1.DependencyBuild, l logr.Logger) error {

	ownerGavs := map[string]bool{}
	db.Status.State = v1alpha1.DependencyBuildStateComplete
	l.Info("Build was contaminated, attempting to rebuild contaminants if required", "build", db.Name)
	//get all the owning artifact builds
	//if any of these are contaminated
	for _, ownerRef := range db.OwnerReferences {
		if strings.EqualFold(ownerRef.Kind, "artifactbuild") || strings.EqualFold(ownerRef.Kind, "artifactbuilds") {
			ab := v1alpha1.ArtifactBuild{}
			err := r.client.Get(ctx, types.NamespacedName{Name: ownerRef.Name, Namespace: db.Namespace}, &ab)
			if err != nil {
				l.Info(fmt.Sprintf("Unable to find owner %s to to mark as affected by contamination from %s", ownerRef.Name, db.Name), "action", "UPDATE")
				if !errors.IsNotFound(err) {
					return err
				}
				//on not found we don't return the error
				//no need to retry it would just result in an infinite loop
				return nil
			}
			ownerGavs[ab.Spec.GAV] = true
		}
	}
	for _, contaminant := range db.Status.Contaminants {
		for _, artifact := range contaminant.ContaminatedArtifacts {
			if ownerGavs[artifact] {
				db.Status.State = v1alpha1.DependencyBuildStateContaminated
				abrName := artifactbuild.CreateABRName(contaminant.GAV)
				abr := v1alpha1.ArtifactBuild{}
				//look for existing ABR
				err := r.client.Get(ctx, types.NamespacedName{Name: abrName, Namespace: db.Namespace}, &abr)
				suffix := util.HashString(contaminant.GAV)[0:20]
				if err != nil {
					l.Info(fmt.Sprintf("Creating ArtifactBuild %s for GAV %s to resolve contamination of %s", abrName, contaminant.GAV, artifact), "contaminate", contaminant, "owner", artifact, "action", "ADD")
					//we just assume this is because it does not exist
					//TODO: how to check the type of the error?
					abr.Spec = v1alpha1.ArtifactBuildSpec{GAV: contaminant.GAV}
					abr.Name = abrName
					abr.Namespace = db.Namespace
					abr.Annotations = map[string]string{}
					//use this annotation to link back to the dependency build
					abr.Annotations[artifactbuild.DependencyBuildContaminatedByAnnotation+suffix] = db.Name
					err := r.client.Create(ctx, &abr)
					if err != nil {
						return err
					}
				} else {
					abr.Annotations = map[string]string{}
					abr.Annotations[artifactbuild.DependencyBuildContaminatedByAnnotation+suffix] = db.Name
					l.Info("Marking ArtifactBuild %s as a contaminant of %s", abr.Name, db.Name, "action", "ADD")
					err := r.client.Update(ctx, &abr)
					if err != nil {
						return err
					}
				}
				break
			}
		}
	}
	if db.Status.State == v1alpha1.DependencyBuildStateContaminated {
		l.Info("build was marked as contaminated as some required artifacts were contaminated", "build", db.Name)
	} else {
		l.Info("build was marked as complete as no contaminated artifacts were requested", "build", db.Name)
	}
	return r.client.Status().Update(ctx, db)
}
func (r *ReconcileDependencyBuild) handleStateContaminated(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	contaminants := db.Status.Contaminants
	if len(contaminants) == 0 {
		//all fixed, just set the state back to building and try again
		//this is triggered when contaminants are removed by the ABR controller
		//setting it back to building should re-try the recipe that actually worked
		db.Status.State = v1alpha1.DependencyBuildStateNew
		return reconcile.Result{}, r.client.Update(ctx, db)
	}
	return reconcile.Result{}, nil
}

func (r *ReconcileDependencyBuild) createRebuiltArtifacts(ctx context.Context, log logr.Logger, pr *pipelinev1beta1.PipelineRun, db *v1alpha1.DependencyBuild,
	image string, digest string, deployed []string) (bool, error) {
	db.Status.DeployedArtifacts = deployed

	for _, i := range deployed {
		ra := v1alpha1.RebuiltArtifact{}

		ra.Namespace = pr.Namespace
		ra.Name = artifactbuild.CreateABRName(i)
		if err := controllerutil.SetOwnerReference(db, &ra, r.scheme); err != nil {
			return false, err
		}
		ra.Spec.GAV = i
		ra.Spec.Image = image
		ra.Spec.Digest = digest
		err := r.client.Create(ctx, &ra)
		if err != nil {
			if !errors.IsAlreadyExists(err) {
				return false, err
			} else {
				//if it already exists we update the image field
				err := r.client.Get(ctx, types.NamespacedName{Namespace: ra.Namespace, Name: ra.Name}, &ra)
				if err != nil {
					if !errors.IsNotFound(err) {
						return false, err
					}
					//on not found we don't return the error
					//no need to retry it would just result in an infinite loop
					return false, nil
				}
				ra.Spec.Image = image
				ra.Spec.Digest = digest
				log.Info(fmt.Sprintf("Updating existing RebuiltArtifact %s to reference image %s", ra.Name, ra.Spec.Image), "action", "UPDATE")
				err = r.client.Update(ctx, &ra)
				if err != nil {
					return false, err
				}
			}
		}
	}
	return true, nil
}

func (r *ReconcileDependencyBuild) createLookupBuildInfoPipeline(ctx context.Context, log logr.Logger, db *v1alpha1.DependencyBuild, jbsConfig *v1alpha1.JBSConfig, additionalMemory int, systemConfig *v1alpha1.SystemConfig) (*pipelinev1beta1.PipelineSpec, error) {
	image, err := r.buildRequestProcessorImage(ctx, log)
	if err != nil {
		return nil, err
	}
	build := db.Spec
	path := build.ScmInfo.Path
	zero := int64(0)
	cacheUrl := "https://jvm-build-workspace-artifact-cache-tls." + jbsConfig.Namespace + ".svc.cluster.local"
	if jbsConfig.Spec.CacheSettings.DisableTLS {
		cacheUrl = "http://jvm-build-workspace-artifact-cache." + jbsConfig.Namespace + ".svc.cluster.local"
	}
	registries := jbsconfig.ImageRegistriesToString(log, jbsConfig.Spec.SharedRegistries)

	trueBool := true
	args := []string{
		"lookup-build-info",
		"--cache-url",
		cacheUrl,
		"--scm-url",
		build.ScmInfo.SCMURL,
		"--scm-tag",
		build.ScmInfo.Tag,
		"--scm-commit",
		build.ScmInfo.CommitHash,
		"--version",
		build.Version,
		"--task-run-name=$(context.taskRun.name)",
		"--tool-versions",
		r.createToolVersionString(systemConfig),
	}
	if len(path) > 0 {
		args = append(args, "--context", path)
	}

	//don't look for existing artifacts on a rebuild
	if db.Annotations == nil || db.Annotations[artifactbuild.RebuiltAnnotation] != "true" {
		// Search not only the configured shared registries but the main registry as well.
		if registries == "" {
			registries = jbsconfig.ImageRegistryToString(jbsConfig.ImageRegistry())
		} else {
			registries += ";" + jbsconfig.ImageRegistryToString(jbsConfig.ImageRegistry())
		}
	}
	if registries != "" {
		args = append(args, "--registries", registries)
	}

	if build.ScmInfo.Private {
		args = append(args, "--private-repo")
	}
	pullPolicy := v1.PullIfNotPresent
	if strings.HasPrefix(image, "quay.io/minikube") {
		pullPolicy = v1.PullNever
	} else if strings.HasSuffix(image, "dev") {
		pullPolicy = v1.PullAlways
	}
	secretOptional := false
	if jbsConfig.Annotations != nil {
		val := jbsConfig.Annotations[jbsconfig.TestRegistry]
		if val == "true" {
			secretOptional = true
		}
	}
	memory := fmt.Sprintf("%dMi", 512+additionalMemory)
	envVars := []v1.EnvVar{
		{Name: "JAVA_OPTS", Value: "-XX:+CrashOnOutOfMemoryError"},
		{Name: "GIT_TOKEN", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha1.GitSecretName}, Key: v1alpha1.GitSecretTokenKey, Optional: &trueBool}}},
	}
	if jbsConfig.ImageRegistry().SecretName != "" {
		envVars = append(envVars, v1.EnvVar{Name: "REGISTRY_TOKEN", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: jbsConfig.ImageRegistry().SecretName}, Key: v1alpha1.ImageSecretTokenKey, Optional: &secretOptional}}})
	}
	return &pipelinev1beta1.PipelineSpec{
		Workspaces: []pipelinev1beta1.PipelineWorkspaceDeclaration{{Name: "tls"}},
		Results:    []pipelinev1beta1.PipelineResult{{Name: BuildInfoPipelineResultBuildInfo, Value: pipelinev1beta1.ResultValue{Type: pipelinev1beta1.ParamTypeString, StringVal: "$(tasks.task.results." + BuildInfoPipelineResultBuildInfo + ")"}}},
		Tasks: []pipelinev1beta1.PipelineTask{
			{
				Name:       "task",
				Workspaces: []pipelinev1beta1.WorkspacePipelineTaskBinding{{Name: "tls", Workspace: "tls"}},
				TaskSpec: &pipelinev1beta1.EmbeddedTask{
					TaskSpec: pipelinev1beta1.TaskSpec{
						Workspaces: []pipelinev1beta1.WorkspaceDeclaration{{Name: "tls"}},
						Results:    []pipelinev1beta1.TaskResult{{Name: BuildInfoPipelineResultBuildInfo}},
						Steps: []pipelinev1beta1.Step{
							{
								Name:            "process-build-requests",
								Image:           image,
								ImagePullPolicy: pullPolicy,
								SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
								Script:          artifactbuild.InstallKeystoreIntoBuildRequestProcessor(args),
								ComputeResources: v1.ResourceRequirements{
									//TODO: make configurable
									Requests: v1.ResourceList{"memory": resource.MustParse(memory), "cpu": resource.MustParse("10m")},
									Limits:   v1.ResourceList{"memory": resource.MustParse(memory)},
								},
								Env: envVars,
							},
						},
					},
				},
			},
		},
	}, nil
}

// returns a string containing all builder image tools
func (r *ReconcileDependencyBuild) createToolVersionString(config *v1alpha1.SystemConfig) string {
	tools := map[string][]string{}
	for _, i := range config.Spec.Builders {
		tags := r.processBuilderImageTags(i.Tag)
		for k, v := range tags {
			_, exists := tools[k]
			if exists {
				for _, newVersion := range v {
					if !slices.Contains(tools[k], newVersion) {
						tools[k] = append(tools[k], newVersion)
					}
				}
			} else {
				tools[k] = v
			}
		}
	}
	ret := ""
	for tool, versions := range tools {
		if ret != "" {
			ret = ret + ","
		}
		ret += tool
		ret += ":"
		first := true
		for _, version := range versions {
			if !first {
				ret += ";"
			} else {
				first = false
			}
			ret += version
		}
	}
	return ret
}

func (r *ReconcileDependencyBuild) failedDueToMemory(ctx context.Context, log logr.Logger, pr *pipelinev1beta1.PipelineRun) bool {
	for _, trs := range pr.Status.ChildReferences {
		tr := pipelinev1beta1.TaskRun{}
		err := r.client.Get(ctx, types.NamespacedName{Namespace: pr.Namespace, Name: trs.Name}, &tr)
		if err != nil {
			log.Error(err, "Unable to retrieve TaskRun to check for memory conditions")
		} else {

			for _, cont := range tr.Status.Steps {
				//check for oomkilled pods
				if cont.Terminated != nil && (cont.Terminated.ExitCode == 137 || cont.Terminated.ExitCode == 134 || cont.Terminated.Reason == "OOMKilled") {
					return true
				}
			}
		}
	}
	return false
}

func (r *ReconcileDependencyBuild) buildRequestProcessorImage(ctx context.Context, log logr.Logger) (string, error) {
	image, err := util.GetImageName(ctx, r.client, log, "build-request-processor", "JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE")
	return image, err
}

func (r *ReconcileDependencyBuild) handleTektonResults(db *v1alpha1.DependencyBuild, pr *pipelinev1beta1.PipelineRun) bool {
	if pr.GetAnnotations() == nil {
		return false
	}
	ba := db.Status.GetBuildPipelineRun(pr.Name)
	if ba.Build.Results == nil {
		ba.Build.Results = &v1alpha1.BuildPipelineRunResults{}
	}
	if ba.Build.Results.PipelineResults == nil {
		ba.Build.Results.PipelineResults = &v1alpha1.PipelineResults{}
	}
	return r.handleTektonResultsForPipeline(ba.Build.Results.PipelineResults, pr)
}

func (r *ReconcileDependencyBuild) handleTektonResultsForPipeline(ba *v1alpha1.PipelineResults, pr *pipelinev1beta1.PipelineRun) bool {
	log := pr.GetAnnotations()["results.tekton.dev/log"]
	rec := pr.GetAnnotations()["results.tekton.dev/record"]
	result := pr.GetAnnotations()["results.tekton.dev/result"]
	changed := false
	if log != "" && log != ba.Logs {
		ba.Logs = log
		changed = true
	}
	if rec != "" && rec != ba.Record {
		ba.Record = rec
		changed = true
	}
	if result != "" && result != ba.Result {
		ba.Result = result
		changed = true
	}
	return changed
}

type BuilderImage struct {
	Image    string
	Tools    map[string][]string
	Priority int
}

func RemovePipelineFinalizer(ctx context.Context, pr *pipelinev1beta1.PipelineRun, client client.Client) (reconcile.Result, error) {
	//remove the finalizer
	if pr.Finalizers != nil {
		mod := false
		for i, fz := range pr.Finalizers {
			if fz == PipelineRunFinalizer {
				newLength := len(pr.Finalizers) - 1
				pr.Finalizers[i] = pr.Finalizers[newLength] // Copy last element to index i.
				pr.Finalizers[newLength] = ""               // Erase last element (write zero value).
				pr.Finalizers = pr.Finalizers[:newLength]   // Truncate slice.
				mod = true
				break
			}
		}
		if mod {
			err := client.Update(ctx, pr)
			if err != nil {
				return reconcile.Result{}, err
			}
		}
	}
	return reconcile.Result{}, nil
}

// This is to remove any '#xxx' fragment from a URI so that git clone commands don't need separate adjustment
func modifyURLFragment(log logr.Logger, scmURL string) string {
	var result = scmURL
	hashIndex := strings.Index(scmURL, "#")
	if hashIndex > -1 {
		log.Info(fmt.Sprintf("Removing URL fragment of %s from %s", scmURL[hashIndex+1:], scmURL))
		result = scmURL[:hashIndex]
	}
	return result
}
