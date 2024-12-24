package dependencybuild

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/google/uuid"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/jbsconfig"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/pod"
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
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout                    = 300 * time.Second
	PipelineBuildId                   = "DEPENDENCY_BUILD"
	PipelineParamScmUrl               = "SCM_URL"
	PipelineParamScmTag               = "TAG"
	PipelineParamScmHash              = "SCM_HASH"
	PipelineParamPath                 = "CONTEXT_DIR"
	PipelineParamChainsGitUrl         = "CHAINS-GIT_URL"
	PipelineParamChainsGitCommit      = "CHAINS-GIT_COMMIT"
	PipelineParamGoals                = "GOALS"
	PipelineParamEnforceVersion       = "ENFORCE_VERSION"
	PipelineParamProjectVersion       = "PROJECT_VERSION"
	PipelineParamProxyUrl             = "PROXY_URL"
	PipelineResultImage               = "IMAGE_URL"
	PipelineResultImageDigest         = "IMAGE_DIGEST"
	PipelineResultPreBuildImageDigest = "PRE_BUILD_IMAGE_DIGEST"
	PipelineResultContaminants        = "CONTAMINANTS"
	PipelineResultDeployedResources   = "DEPLOYED_RESOURCES"
	PipelineResultVerificationResult  = "VERIFICATION_RESULTS"
	PipelineResultPassedVerification  = "PASSED_VERIFICATION" //#nosec
	PipelineResultGitArchive          = "GIT_ARCHIVE"

	BuildInfoPipelineResultBuildInfo = "BUILD_INFO"

	PipelineTypeLabel     = "jvmbuildservice.io/pipeline-type"
	RedeployAnnotation    = "jvmbuildservice.io/redeploy"
	PipelineTypeBuildInfo = "build-info"
	PipelineTypeBuild     = "build"
	PipelineTypeDeploy    = "deploy"

	MaxRetries      = 3
	MemoryIncrement = 2048

	PipelineRunFinalizer = "jvmbuildservice.io/finalizer"
	DeploySuffix         = "-deploy"
)

type ReconcileDependencyBuild struct {
	client        client.Client
	scheme        *runtime.Scheme
	eventRecorder record.EventRecorder
	clientSet     *kubernetes.Clientset
}

func newReconciler(mgr ctrl.Manager, clientset *kubernetes.Clientset) reconcile.Reconciler {
	return &ReconcileDependencyBuild{
		clientSet:     clientset,
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("DependencyBuild"),
	}
}

func (r *ReconcileDependencyBuild) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	var cancel context.CancelFunc
	ctx, cancel = context.WithTimeout(ctx, contextTimeout)
	defer cancel()
	log := ctrl.Log.WithName("dependencybuild").WithValues("namespace", request.NamespacedName.Namespace, "resource", request.Name)
	ctx = logr.NewContext(ctx, log)
	db := v1alpha1.DependencyBuild{}
	dberr := r.client.Get(ctx, request.NamespacedName, &db)
	if dberr != nil {
		if !errors.IsNotFound(dberr) {
			log.Error(dberr, "Reconcile key %s as dependencybuild unexpected error", request.NamespacedName.String())
			return ctrl.Result{}, dberr
		}
	}

	pr := tektonpipeline.PipelineRun{}
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
		ctx = logr.NewContext(ctx, log)
		if db.Annotations != nil && db.Annotations[RedeployAnnotation] != "" {
			return r.handleRedeployAnnotation(ctx, &db)
		}

		switch db.Status.State {
		case "", v1alpha1.DependencyBuildStateNew:
			return r.handleStateNew(ctx, &db)
		case v1alpha1.DependencyBuildStateSubmitBuild:
			return r.handleStateSubmitBuild(ctx, &db)
		case v1alpha1.DependencyBuildStateAnalyzeBuild:
			return r.handleStateAnalyzeBuild(ctx, &db)
		case v1alpha1.DependencyBuildStateFailed:
			return reconcile.Result{}, nil
		case v1alpha1.DependencyBuildStateBuilding:
			return r.handleStateBuilding(ctx, &db)
		//case v1alpha1.DependencyBuildStateDeploying:
		//	return r.handleStateDeploying(ctx, &db)
		case v1alpha1.DependencyBuildStateContaminated:
			return r.handleStateContaminated(ctx, &db)
		case v1alpha1.DependencyBuildStateComplete:
			return reconcile.Result{}, nil
		}

	case trerr == nil:
		if pr.DeletionTimestamp != nil {
			//always remove the finalizer if it is deleted
			//but continue with the method
			//if the PR is deleted while it is running then we want to allow that
			result, err2 := r.removePipelineFinalizer(ctx, &pr)
			if err2 != nil {
				return result, err2
			}
		}
		log = log.WithValues("kind", "PipelineRun")
		ctx = logr.NewContext(ctx, log)
		pipelineType := pr.Labels[PipelineTypeLabel]
		switch pipelineType {
		case PipelineTypeBuildInfo:
			// Note in the case where shared repositories are configured the build discovery pipeline can shortcut
			// setting the DependencyBuild state to Complete utilising the pre-existing builds.
			return r.handleAnalyzeBuildPipelineRunReceived(ctx, &pr)
		case PipelineTypeBuild:
			return r.handleBuildPipelineRunReceived(ctx, &pr)
			//case PipelineTypeDeploy:
			//	return r.handleDeployPipelineRunReceived(ctx, &pr)
		}
	}

	return reconcile.Result{}, nil
}

func (r *ReconcileDependencyBuild) handleRedeployAnnotation(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	//delete the existing pipeline if it exists
	pr := tektonpipeline.PipelineRun{}
	prName := db.Name + DeploySuffix
	err := r.client.Get(ctx, types.NamespacedName{Name: prName, Namespace: db.Namespace}, &pr)
	if err == nil {
		//the pipeline already exists
		//do nothing
		err = r.client.Delete(ctx, &pr)
		if err != nil {
			return reconcile.Result{}, err
		}
	}
	//handle redeployment
	return reconcile.Result{}, r.updateDependencyBuildState(ctx, db, v1alpha1.DependencyBuildStateDeploying, "redeployment was requested")
}

func (r *ReconcileDependencyBuild) handleStateNew(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {

	log, _ := logr.FromContext(ctx)
	var err error
	if len(db.Spec.BuildRecipeConfigMap) > 0 {
		configMap := v1.ConfigMap{}
		err = r.client.Get(ctx, types.NamespacedName{Namespace: db.Namespace, Name: db.Spec.BuildRecipeConfigMap}, &configMap)
		if err != nil {
			return reconcile.Result{}, err
		}
		if err := controllerutil.SetOwnerReference(db, &configMap, r.scheme); err != nil {
			return reconcile.Result{}, err
		}
		if err := r.client.Update(ctx, &configMap); err != nil {
			return reconcile.Result{}, err
		}
	}
	jbsConfig := &v1alpha1.JBSConfig{}
	err = r.client.Get(ctx, types.NamespacedName{Namespace: db.Namespace, Name: v1alpha1.JBSConfigName}, jbsConfig)
	if err != nil && !errors.IsNotFound(err) {
		return reconcile.Result{}, err
	}
	// create pipeline run
	pr := tektonpipeline.PipelineRun{}
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
	additionalMemory := MemoryIncrement * db.Status.PipelineRetries

	log.Info(fmt.Sprintf("running build discovery with %d additional memory", additionalMemory))
	pr.Spec.PipelineSpec, err = r.createLookupBuildInfoPipeline(ctx, db, jbsConfig, additionalMemory, &systemConfig)
	if err != nil {
		return reconcile.Result{}, err
	}
	if !jbsConfig.Spec.CacheSettings.DisableTLS {
		pr.Spec.Workspaces = []tektonpipeline.WorkspaceBinding{{Name: WorkspaceTls, ConfigMap: &v1.ConfigMapVolumeSource{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha1.TlsConfigMapName}}}}
	} else {
		pr.Spec.Workspaces = []tektonpipeline.WorkspaceBinding{{Name: WorkspaceTls, EmptyDir: &v1.EmptyDirVolumeSource{}}}
	}
	pr.Namespace = db.Namespace
	pr.Name = fmt.Sprintf("%s-build-discovery-%d", db.Name, db.Status.PipelineRetries)
	pr.Labels = map[string]string{artifactbuild.PipelineRunLabel: "", artifactbuild.DependencyBuildIdLabel: db.Name, PipelineTypeLabel: PipelineTypeBuildInfo}
	if err := controllerutil.SetOwnerReference(db, &pr, r.scheme); err != nil {
		return reconcile.Result{}, err
	}
	if err := r.client.Create(ctx, &pr); err != nil {
		if !errors.IsAlreadyExists(err) {
			return reconcile.Result{}, err
		}
	}
	return reconcile.Result{}, r.updateDependencyBuildState(ctx, db, v1alpha1.DependencyBuildStateAnalyzeBuild, "build discovery pipeline created")
}

func (r *ReconcileDependencyBuild) handleAnalyzeBuildPipelineRunReceived(ctx context.Context, pr *tektonpipeline.PipelineRun) (reconcile.Result, error) {
	log, _ := logr.FromContext(ctx)
	if pr.Status.CompletionTime == nil && pr.DeletionTimestamp == nil {
		return reconcile.Result{}, nil
	}
	ownerRefs := pr.GetOwnerReferences()
	if len(ownerRefs) == 0 {
		msg := "pipelinerun missing ownerrefs %s:%s"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, "MissingOwner", msg, pr.Namespace, pr.Name)
		log.Info(fmt.Sprintf(msg, pr.Namespace, pr.Name))
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
		log.Info(fmt.Sprintf(msg, pr.Namespace, pr.Name))

		return reconcile.Result{}, nil
	}

	key := types.NamespacedName{Namespace: pr.Namespace, Name: ownerName}
	db := v1alpha1.DependencyBuild{}
	err := r.client.Get(ctx, key, &db)

	if db.Status.State == v1alpha1.DependencyBuildStateAnalyzeBuild && !pr.IsDone() && pr.DeletionTimestamp != nil {
		//analysis pipeline deleted mid analysis
		db.Status.State = v1alpha1.DependencyBuildStateFailed
		db.Status.Message = "Analysis pipeline deleted"
		return reconcile.Result{}, r.client.Status().Update(ctx, &db)
	}

	if err != nil {
		msg := "get for pipelinerun %s:%s owning db %s:%s yielded error %s"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, "GetError", msg, pr.Namespace, pr.Name, pr.Namespace,
			ownerName, err.Error())
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
		if (db.Status.PipelineRetries < MaxRetries) && r.failedDueToMemory(ctx, log, pr) {
			// Abuse pipeline retries to apply the same logic
			// We reset it back to zero before the builds start
			db.Status.PipelineRetries++
			// Don't need to call update on the DB here as it is called at the end of the function.
			//
			// Need to delete this pipeline as it has failed from OOM. If we don't delete it
			// will loop again and cause another pipeline retries causing two new discovery builds
			// to be started.
			err = r.client.Delete(ctx, pr)
			if err != nil {
				return reconcile.Result{}, err
			}
			return reconcile.Result{}, r.updateDependencyBuildState(ctx, &db, v1alpha1.DependencyBuildStateNew, "build discovery failed due to memory issues")
		} else {
			db.Status.Message = buildInfo
			return reconcile.Result{}, r.updateDependencyBuildState(ctx, &db, v1alpha1.DependencyBuildStateFailed, "build discovery pipelineFailed")
		}
	} else {
		db.Status.PipelineRetries = 0
		unmarshalled := marshalledBuildInfo{}
		if err := json.Unmarshal([]byte(buildInfo), &unmarshalled); err != nil {
			r.eventRecorder.Eventf(&db, v1.EventTypeWarning, "InvalidJson", "Failed to unmarshal build info for AB %s/%s JSON: %s", db.Namespace, db.Name, buildInfo)
			db.Status.Message = "failed to unmarshal json build info: " + err.Error() + ": " + buildInfo
			return reconcile.Result{}, r.updateDependencyBuildState(ctx, &db, v1alpha1.DependencyBuildStateFailed, "failed to unmarshal json build")
		}

		//read our builder images from the config
		var allBuilderImages []BuilderImage
		allBuilderImages, err = r.processBuilderImages(ctx)
		if err != nil {
			return reconcile.Result{}, err
		}
		// for now we are ignoring the tool versions
		// and just using the supplied invocations
		buildRecipes := []*v1alpha1.BuildRecipe{}
		db.Status.CommitTime = unmarshalled.CommitTime

		if len(unmarshalled.Invocations) == 0 {
			log.Error(nil, "Unable to determine build tool", "info", unmarshalled)
			return reconcile.Result{}, r.updateDependencyBuildState(ctx, &db, v1alpha1.DependencyBuildStateFailed, "Unable to determine build tool")
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
					buildRecipes = append(buildRecipes, &v1alpha1.BuildRecipe{
						Image:               image.Image,
						CommandLine:         command.Commands,
						EnforceVersion:      unmarshalled.EnforceVersion,
						ToolVersion:         command.ToolVersion[command.Tool],
						ToolVersions:        command.ToolVersion,
						JavaVersion:         command.ToolVersion["jdk"],
						Tool:                command.Tool,
						DisabledPlugins:     command.DisabledPlugins,
						PreBuildScript:      unmarshalled.PreBuildScript,
						PostBuildScript:     unmarshalled.PostBuildScript,
						AdditionalDownloads: unmarshalled.AdditionalDownloads,
						DisableSubmodules:   unmarshalled.DisableSubmodules,
						AdditionalMemory:    unmarshalled.AdditionalMemory,
						Repositories:        unmarshalled.Repositories,
						AllowedDifferences:  unmarshalled.AllowedDifferences,
						ContextPath:         unmarshalled.ContextPath})
					break
				}
			}
		}
		db.Status.PotentialBuildRecipes = buildRecipes
		db.Status.PotentialBuildRecipesIndex = 0

		if len(unmarshalled.Image) > 0 {
			err := r.createRebuiltArtifacts(ctx, pr, &db, unmarshalled.Image, unmarshalled.Digest, unmarshalled.Gavs)
			if err != nil {
				return reconcile.Result{}, err
			}
			return reconcile.Result{}, r.updateDependencyBuildState(ctx, &db, v1alpha1.DependencyBuildStateComplete, fmt.Sprintf("Found preexisting shared build with deployed GAVs %#v from image %#v", unmarshalled.Gavs, unmarshalled.Image))
		} else {
			return reconcile.Result{}, r.updateDependencyBuildState(ctx, &db, v1alpha1.DependencyBuildStateSubmitBuild, "Starting build process")
		}
	}
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
	ContextPath         string
	Gavs                []string
	DisabledPlugins     []string
}

type invocation struct {
	Tool            string
	Commands        []string
	ToolVersion     map[string]string
	DisabledPlugins []string
}

func (r *ReconcileDependencyBuild) processBuilderImages(ctx context.Context) ([]BuilderImage, error) {
	systemConfig := v1alpha1.SystemConfig{}
	getCtx := ctx
	err := r.client.Get(getCtx, types.NamespacedName{Name: systemconfig.SystemConfigKey}, &systemConfig)
	if err != nil {
		return nil, err
	}
	result := make([]BuilderImage, 0)
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

	// Guard against inconsistent state, should not happen but we don't want to crash the controller
	if db.Status.PotentialBuildRecipesIndex == -1 {
		db.Status.PotentialBuildRecipesIndex = 0
	} else if db.Status.PotentialBuildRecipesIndex > len(db.Status.PotentialBuildRecipes) {
		db.Status.PotentialBuildRecipesIndex = len(db.Status.PotentialBuildRecipes)
	}

	//no more attempts
	if len(db.Status.PotentialBuildRecipes) == db.Status.PotentialBuildRecipesIndex {
		msg := "The DependencyBuild %s/%s moved to failed, all recipes exhausted"
		r.eventRecorder.Eventf(db, v1.EventTypeWarning, "BuildFailed", msg, db.Namespace, db.Name)
		return reconcile.Result{}, r.updateDependencyBuildState(ctx, db, v1alpha1.DependencyBuildStateFailed, fmt.Sprintf(msg, db.Namespace, db.Name))
	}
	ba := v1alpha1.BuildAttempt{}
	ba.BuildId = uuid.New().String()
	ba.Recipe = db.Status.PotentialBuildRecipes[db.Status.PotentialBuildRecipesIndex]
	pipelineName := currentDependencyBuildPipelineName(db)
	ba.Build = &v1alpha1.BuildPipelineRun{
		PipelineName: pipelineName,
		StartTime:    time.Now().Unix(),
	}
	//and remove if from the potential list via the index
	db.Status.PotentialBuildRecipesIndex++
	db.Status.BuildAttempts = append(db.Status.BuildAttempts, &ba)
	//create the pipeline run
	return reconcile.Result{}, r.updateDependencyBuildState(ctx, db, v1alpha1.DependencyBuildStateBuilding, "selected build recipe")

}

func (r *ReconcileDependencyBuild) handleStateBuilding(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {

	log, _ := logr.FromContext(ctx)
	//first we check to see if the pipeline exists
	attempt := db.Status.BuildAttempts[len(db.Status.BuildAttempts)-1]

	pr := tektonpipeline.PipelineRun{}
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
	buildRequestProcessorImage, err := r.buildRequestProcessorImage(ctx)
	if err != nil {
		return reconcile.Result{}, err
	}
	pr.Namespace = db.Namespace
	// we do not use generate name since
	// 1. it was used in creating the db and the db name has random ids
	// 2. there is a 1 to 1 relationship (but also consider potential recipe retry)
	// 3. it allows us to use the already exist error on create to short circuit the creation of dbs if owner refs
	//    updates to the db before we move the db out of building
	pr.Name = attempt.Build.PipelineName
	pr.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: db.Name, artifactbuild.PipelineRunLabel: "", PipelineTypeLabel: PipelineTypeBuild}

	jbsConfig := &v1alpha1.JBSConfig{}
	err = r.client.Get(ctx, types.NamespacedName{Namespace: db.Namespace, Name: v1alpha1.JBSConfigName}, jbsConfig)
	if err != nil && !errors.IsNotFound(err) {
		return reconcile.Result{}, err
	}
	pr.Spec.PipelineRef = nil

	contextDir := db.Spec.ScmInfo.Path
	if attempt.Recipe.ContextPath != "" {
		contextDir = attempt.Recipe.ContextPath
	}

	if err != nil {
		return reconcile.Result{}, err
	}
	scmUrl := modifyURLFragment(log, db.Spec.ScmInfo.SCMURL)
	paramValues := []tektonpipeline.Param{
		{Name: PipelineBuildId, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: db.Name}},
		{Name: PipelineParamScmUrl, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: scmUrl}},
		{Name: PipelineParamScmTag, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: db.Spec.ScmInfo.Tag}},
		{Name: PipelineParamScmHash, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: db.Spec.ScmInfo.CommitHash}},
		{Name: PipelineParamChainsGitUrl, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: scmUrl}},
		{Name: PipelineParamChainsGitCommit, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: db.Spec.ScmInfo.CommitHash}},
		{Name: PipelineParamPath, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: contextDir}},
		{Name: PipelineParamGoals, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeArray, ArrayVal: attempt.Recipe.CommandLine}},
	}

	orasOptions := ""
	if jbsConfig.Annotations != nil && jbsConfig.Annotations[jbsconfig.TestRegistry] == "true" {
		orasOptions = "--insecure --plain-http"
	}
	systemConfig := v1alpha1.SystemConfig{}
	err = r.client.Get(ctx, types.NamespacedName{Name: systemconfig.SystemConfigKey}, &systemConfig)
	if err != nil {
		return reconcile.Result{}, err
	}
	diagnosticContainerfile := ""
	// TODO: set owner, pass parameter to do verify if true, via an annoaton on the dependency build, may eed to wait for dep build to exist verify is an optional, use append on each step in build recipes
	preBuildImages := map[string]string{}
	for _, i := range db.Status.PreBuildImages {
		preBuildImages[i.BaseBuilderImage+"-"+i.Tool] = i.BuiltImageDigest
	}
	pr.Spec.Timeouts = &tektonpipeline.TimeoutFields{
		Pipeline: &v12.Duration{Duration: time.Hour * v1alpha1.DefaultTimeout},
		Tasks:    &v12.Duration{Duration: time.Hour * v1alpha1.DefaultTimeout},
	}
	pr.Spec.PipelineSpec, diagnosticContainerfile, err = createPipelineSpec(log, attempt.Recipe.Tool, db.Status.CommitTime, jbsConfig, &systemConfig, attempt.Recipe, db, paramValues, buildRequestProcessorImage, attempt.BuildId, preBuildImages, orasOptions)
	if err != nil {
		return reconcile.Result{}, err
	}

	attempt.Build.DiagnosticDockerFile = diagnosticContainerfile

	qty, _ := resource.ParseQuantity("1Gi")
	pr.Spec.Params = paramValues
	pr.Spec.Workspaces = []tektonpipeline.WorkspaceBinding{
		{Name: WorkspaceSource, VolumeClaimTemplate: &v1.PersistentVolumeClaim{
			Spec: v1.PersistentVolumeClaimSpec{
				AccessModes: []v1.PersistentVolumeAccessMode{v1.ReadWriteOnce},
				Resources: v1.VolumeResourceRequirements{
					Requests: v1.ResourceList{"storage": qty},
				},
			},
		}},
	}
	if orasOptions != "" {
		pr.Spec.TaskRunTemplate = tektonpipeline.PipelineTaskRunTemplate{
			PodTemplate: &pod.Template{
				Env: []v1.EnvVar{
					{
						Name:  "ORAS_OPTIONS",
						Value: orasOptions,
					},
				},
			},
		}
	}

	trueBool := true
	pr.Spec.TaskRunSpecs = []tektonpipeline.PipelineTaskRunSpec{{
		PipelineTaskName: DeployTaskName,
		PodTemplate: &pod.Template{
			Env: []v1.EnvVar{
				{
					Name:      "MAVEN_PASSWORD",
					ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha1.MavenSecretName}, Key: v1alpha1.MavenSecretKey, Optional: &trueBool}},
				},
			},
		},
	}}

	if jbsConfig.Annotations != nil && jbsConfig.Annotations[jbsconfig.CITests] == "true" {
		log.Info(fmt.Sprintf("Configuring resources for %#v", BuildTaskName))
		podMemR, _ := resource.ParseQuantity("1792Mi")
		podMemL, _ := resource.ParseQuantity("3584Mi")
		podCPU, _ := resource.ParseQuantity("500m")
		pr.Spec.TaskRunSpecs = append(pr.Spec.TaskRunSpecs, tektonpipeline.PipelineTaskRunSpec{
			PipelineTaskName: BuildTaskName,
			ComputeResources: &v1.ResourceRequirements{
				Requests: v1.ResourceList{"memory": podMemR, "cpu": podCPU},
				Limits:   v1.ResourceList{"memory": podMemL, "cpu": podCPU},
			},
		})
	}
	// TODO: DisableTLS defaults to true. Further the tls workspace has been removed from the build pipeline so an alternate method would be needed.
	//if !jbsConfig.Spec.CacheSettings.DisableTLS {
	//	pr.Spec.Workspaces = append(pr.Spec.Workspaces, tektonpipeline.WorkspaceBinding{Name: WorkspaceTls, ConfigMap: &v1.ConfigMapVolumeSource{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha1.TlsConfigMapName}}})
	//} else {
	//	pr.Spec.Workspaces = append(pr.Spec.Workspaces, tektonpipeline.WorkspaceBinding{Name: WorkspaceTls, EmptyDir: &v1.EmptyDirVolumeSource{}})
	//}
	pr.Spec.Timeouts = &tektonpipeline.TimeoutFields{Pipeline: &v12.Duration{Duration: time.Hour * v1alpha1.DefaultTimeout}}
	if err := controllerutil.SetOwnerReference(db, &pr, r.scheme); err != nil {
		return reconcile.Result{}, err
	}
	//now we submit the build
	if err := r.client.Create(ctx, &pr); err != nil {
		if errors.IsAlreadyExists(err) {
			log.Info(fmt.Sprintf("handleStateBuilding: pipelinerun %s:%s already exists, not retrying", pr.Namespace, pr.Name))
			return reconcile.Result{}, nil
		}
		r.eventRecorder.Eventf(db, v1.EventTypeWarning, "PipelineRunCreationFailed", "The DependencyBuild %s/%s failed to create its build pipeline run with %#v", db.Namespace, db.Name, err)
		return reconcile.Result{}, err
	}
	return reconcile.Result{}, r.client.Status().Update(ctx, db)
}

func currentDependencyBuildPipelineName(db *v1alpha1.DependencyBuild) string {
	return fmt.Sprintf("%s-build-%d", db.Name, len(db.Status.BuildAttempts))
}

func (r *ReconcileDependencyBuild) handleBuildPipelineRunReceived(ctx context.Context, pr *tektonpipeline.PipelineRun) (reconcile.Result, error) {
	log, _ := logr.FromContext(ctx)
	if pr.Status.CompletionTime != nil {
		db, err := r.dependencyBuildForPipelineRun(ctx, pr)
		if err != nil || db == nil {
			return reconcile.Result{}, err
		}

		attempt := db.Status.GetBuildPipelineRun(pr.Name)
		if attempt == nil {
			msg := "unknown build pipeline run for db %s %s:%s"
			r.eventRecorder.Eventf(pr, v1.EventTypeWarning, "UnknownPipeline", msg, db.Name, pr.Namespace, pr.Name)
			log.Info(fmt.Sprintf(msg, db.Name, pr.Namespace, pr.Name))
			return reconcile.Result{}, nil
		}
		run := attempt.Build
		run.FinishTime = pr.Status.CompletionTime.Unix()

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

		//try and save the build image name
		//this is a big perfomance optimisation, as it can be re-used on subsequent attempts
		alreadyExists := false
		for _, i := range db.Status.PreBuildImages {
			if i.BaseBuilderImage == attempt.Recipe.Image && i.Tool == attempt.Recipe.Tool {
				alreadyExists = true
			}
		}
		if !alreadyExists {
			for _, run := range pr.Status.ChildReferences {
				if strings.HasSuffix(run.Name, PreBuildTaskName) {
					tr := tektonpipeline.TaskRun{}
					err := r.client.Get(ctx, types.NamespacedName{Namespace: pr.Namespace, Name: run.Name}, &tr)
					if err != nil {
						log.Error(err, "failed to retrieve pre build task run, image cannot be re-used")
					} else {
						preBuildSuccess := tr.Status.GetCondition(apis.ConditionSucceeded).IsTrue()
						if preBuildSuccess {
							for _, res := range tr.Status.Results {
								if res.Name == PipelineResultPreBuildImageDigest && res.Value.StringVal != "" {
									db.Status.PreBuildImages = append(db.Status.PreBuildImages, v1alpha1.PreBuildImage{BaseBuilderImage: attempt.Recipe.Image, BuiltImageDigest: res.Value.StringVal, Tool: attempt.Recipe.Tool})
								}
							}
						}
					}
				}
			}
		}

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
				// Only retry up to a limit. Recipe default memory is 1024. It then uses the MemoryIncrement (2048),
				// and finally as the third try uses 4096.
				if !doRetry && attempt.Recipe.AdditionalMemory < (MemoryIncrement*2) {
					if r.failedDueToMemory(ctx, log, pr) {
						currentAdditionalMemory := attempt.Recipe.AdditionalMemory
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
						log.Info(fmt.Sprintf("OOMKilled Pod detected, retrying the build for DependencyBuild with more memory %s, PR UID: %s, Current additional memory: %d and new addtional memory: %d", db.Name, pr.UID, currentAdditionalMemory, attempt.Recipe.AdditionalMemory))
					}
				}

				if doRetry {
					db.Status.PotentialBuildRecipesIndex--
					db.Status.PipelineRetries++
					return reconcile.Result{}, r.updateDependencyBuildState(ctx, db, v1alpha1.DependencyBuildStateSubmitBuild, fmt.Sprintf("Resetting %d to retry build for %s", db.Status.PotentialBuildRecipesIndex,
						db.Name))
				}
			}

		}

		//the pr is done, lets update the run details

		if run.Succeeded {
			var image string
			var digest string
			var passedVerification bool
			var verificationResults string
			var deployed []string
			var gitArchive v1alpha1.GitArchive

			for _, i := range pr.Status.Results {
				if i.Name == PipelineResultImage {
					image = i.Value.StringVal
				} else if i.Name == PipelineResultImageDigest {
					digest = i.Value.StringVal
				} else if i.Name == PipelineResultContaminants {
					db.Status.Contaminants = []*v1alpha1.Contaminant{}
					//unmarshal directly into the contaminants field
					err := json.Unmarshal([]byte(i.Value.StringVal), &db.Status.Contaminants)
					if err != nil {
						return reconcile.Result{}, err
					}
				} else if i.Name == PipelineResultPassedVerification {
					parseBool, _ := strconv.ParseBool(i.Value.StringVal)
					passedVerification = parseBool
				} else if i.Name == PipelineResultVerificationResult {
					// Note: The TaskRun stores this as
					// 		VERIFICATION_RESULTS	{"commons-lang:commons-lang:jar:2.5":[]}
					// 	But this is now stored as
					// 		"verificationFailures": "{\"commons-lang:commons-lang:jar:2.5\":[]}"
					verificationResults = i.Value.StringVal
				} else if i.Name == PipelineResultDeployedResources && len(i.Value.StringVal) > 0 {
					//we need to create 'DeployedArtifact' resources for the objects that were deployed
					deployed = strings.Split(i.Value.StringVal, ",")
				} else if i.Name == PipelineResultGitArchive {
					err := json.Unmarshal([]byte(i.Value.StringVal), &gitArchive)
					if err != nil {
						return reconcile.Result{}, err
					}
				}
			}
			err = r.createRebuiltArtifacts(ctx, pr, db, image, digest, deployed)
			if err != nil {
				return reconcile.Result{}, err
			}
			if db.Annotations[artifactbuild.DependencyCreatedAnnotation] != "" {
				err = r.createArtifacts(ctx, pr, db, deployed)
				if err != nil {
					return reconcile.Result{}, err
				}
			}

			run.Results = &v1alpha1.BuildPipelineRunResults{
				Image:               image,
				ImageDigest:         digest,
				Verified:            passedVerification,
				VerificationResults: verificationResults,
				Gavs:                deployed,
				GitArchive:          gitArchive,
				Contaminants:        db.Status.Contaminants,
			}

			problemContaminates := db.Status.ProblemContaminates()
			if len(problemContaminates) == 0 {
				return reconcile.Result{}, r.updateDependencyBuildState(ctx, db, v1alpha1.DependencyBuildStateComplete, "build was completed")
			} else {
				msg := "The DependencyBuild %s/%s was contaminated with community dependencies"
				log.Info(fmt.Sprintf(msg, db.Namespace, db.Name))
				r.eventRecorder.Eventf(db, v1.EventTypeWarning, "BuildContaminated", msg, db.Namespace, db.Name)
				//the dependency was contaminated with community deps
				//most likely shaded in
				//we don't need to update the status here, it will be handled by the handleStateComplete method
				//even though there are contaminates they may not be in artifacts we care about
				err := r.handleBuildCompletedWithContaminants(ctx, db, problemContaminates)
				if err != nil {
					return reconcile.Result{}, err
				}
				return reconcile.Result{}, nil
			}
		} else {
			//try again, if there are no more recipes this gets handled in the submit build logic
			//its also possible this failed due to verification failures
			//in this case we still get the result on the task run
			//so we look for it to add to the build
			for _, ref := range pr.Status.ChildReferences {
				// Assumes container build is always called build here as well
				if strings.HasSuffix(ref.Name, BuildTaskName) {
					tr := tektonpipeline.TaskRun{}
					err := r.client.Get(ctx, types.NamespacedName{Namespace: pr.Namespace, Name: ref.Name}, &tr)
					if err != nil {
						log.Error(err, "failed to retrieve potential contamination results")
					} else {
						for _, res := range tr.Status.Results {
							if res.Name == PipelineResultVerificationResult && res.Value.StringVal != "" {
								run.Results = &v1alpha1.BuildPipelineRunResults{
									VerificationResults: res.Value.StringVal,
								}
							}
						}
					}
				}
			}
		}
		err = r.updateDependencyBuildState(ctx, db, v1alpha1.DependencyBuildStateSubmitBuild, "build pipeline failed")
		return reconcile.Result{}, err
	} else if pr.GetDeletionTimestamp() != nil {
		//pr is being deleted
		db, err := r.dependencyBuildForPipelineRun(ctx, pr)
		if err != nil || db == nil {
			return reconcile.Result{}, err
		}
		changed := r.handleTektonResults(db, pr)
		ba := db.Status.GetBuildPipelineRun(pr.Name)
		//the relevant run was not complete, mark it as failed
		if !ba.Build.Complete {
			ba.Build.Succeeded = false
			ba.Build.FinishTime = pr.GetDeletionTimestamp().Unix()
			ba.Build.Complete = true
			changed = true
		}
		if changed {
			err = r.updateDependencyBuildState(ctx, db, v1alpha1.DependencyBuildStateSubmitBuild, "build pipeline was removed")
			if err != nil {
				return reconcile.Result{}, err
			}
		}
		return reconcile.Result{}, nil
	}
	return reconcile.Result{}, nil

}

func (r *ReconcileDependencyBuild) dependencyBuildForPipelineRun(ctx context.Context, pr *tektonpipeline.PipelineRun) (*v1alpha1.DependencyBuild, error) {
	// get db
	log, _ := logr.FromContext(ctx)
	ownerRefs := pr.GetOwnerReferences()
	if len(ownerRefs) == 0 {
		msg := "pipelinerun missing ownerrefs %s:%s"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, "MissingOwner", msg, pr.Namespace, pr.Name)
		log.Info(fmt.Sprintf(msg, pr.Namespace, pr.Name))
		return nil, nil
	}
	if len(ownerRefs) > 1 {
		// workaround for event/logging methods that can only take string args
		count := fmt.Sprintf("%d", len(ownerRefs))
		msg := "pipelinerun %s:%s has %s ownerrefs but only using the first dependencybuild ownerref"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, "TooManyOwner", msg, pr.Namespace, pr.Name, count)
		log.Info(fmt.Sprintf(msg, pr.Namespace, pr.Name, count))
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
		msg := "pipelinerun missing dependencybuild ownerrefs %s:%s"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, "MissingOwner", msg, pr.Namespace, pr.Name)
		log.Info(fmt.Sprintf(msg, pr.Namespace, pr.Name))
		return nil, nil
	}

	key := types.NamespacedName{Namespace: pr.Namespace, Name: ownerRef.Name}
	db := v1alpha1.DependencyBuild{}
	err := r.client.Get(ctx, key, &db)
	if err != nil {
		msg := "get for pipelinerun %s:%s owning db %s:%s yielded error %s"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, "GetError", msg, pr.Namespace, pr.Name, pr.Namespace,
			ownerRef.Name, err.Error())
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
func (r *ReconcileDependencyBuild) handleBuildCompletedWithContaminants(ctx context.Context, db *v1alpha1.DependencyBuild, problemContaminates []*v1alpha1.Contaminant) error {
	log, _ := logr.FromContext(ctx)
	ownerGavs := map[string]bool{}
	log.Info("Build was contaminated, attempting to rebuild contaminants if required", "build", db.Name)
	//get all the owning artifact builds
	//if any of these are contaminated
	for _, ownerRef := range db.OwnerReferences {
		if strings.EqualFold(ownerRef.Kind, "artifactbuild") || strings.EqualFold(ownerRef.Kind, "artifactbuilds") {
			ab := v1alpha1.ArtifactBuild{}
			err := r.client.Get(ctx, types.NamespacedName{Name: ownerRef.Name, Namespace: db.Namespace}, &ab)
			if err != nil {
				log.Info(fmt.Sprintf("Unable to find owner %s to to mark as affected by contamination from %s", ownerRef.Name, db.Name), "action", "UPDATE")
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
	contaminated := false

	for _, contaminant := range problemContaminates {
		for _, artifact := range contaminant.ContaminatedArtifacts {
			if ownerGavs[artifact] {
				contaminated = true
				abrName := artifactbuild.CreateABRName(contaminant.GAV)
				abr := v1alpha1.ArtifactBuild{}
				//look for existing ABR
				err := r.client.Get(ctx, types.NamespacedName{Name: abrName, Namespace: db.Namespace}, &abr)
				suffix := util.HashString(contaminant.GAV)[0:20]
				if err != nil {
					log.Info(fmt.Sprintf("Creating ArtifactBuild %s for GAV %s to resolve contamination of %s", abrName, contaminant.GAV, artifact), "contaminate", contaminant, "owner", artifact, "action", "ADD")
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
					log.Info(fmt.Sprintf("Marking ArtifactBuild %s as a contaminant of %s", abr.Name, db.Name))
					err := r.client.Update(ctx, &abr)
					if err != nil {
						return err
					}
				}
				break
			}
		}
	}
	if contaminated {
		return r.updateDependencyBuildState(ctx, db, v1alpha1.DependencyBuildStateContaminated, "build was marked as contaminated as some required artifacts were contaminated")
	} else {
		return r.updateDependencyBuildState(ctx, db, v1alpha1.DependencyBuildStateComplete, "build was marked as complete as no contaminated artifacts were requested")
	}
}
func (r *ReconcileDependencyBuild) handleStateContaminated(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	contaminants := db.Status.Contaminants
	allOk := true
	for _, i := range contaminants {
		if !i.Allowed && !i.RebuildAvailable {
			allOk = false
			break
		}
	}
	if allOk {
		//all fixed, just set the state back to building and try again
		//this is triggered when contaminants are removed by the ABR controller
		//setting it back to building should re-try the recipe that actually worked
		db.Status.PotentialBuildRecipesIndex = 0
		db.Status.PipelineRetries = 0
		return reconcile.Result{}, r.updateDependencyBuildState(ctx, db, v1alpha1.DependencyBuildStateSubmitBuild, "retrying contaminated build")
	}
	return reconcile.Result{}, nil
}

func (r *ReconcileDependencyBuild) createRebuiltArtifacts(ctx context.Context, pr *tektonpipeline.PipelineRun, db *v1alpha1.DependencyBuild,
	image string, digest string, deployed []string) error {
	db.Status.DeployedArtifacts = deployed

	log, _ := logr.FromContext(ctx)
	for _, i := range deployed {
		ra := v1alpha1.RebuiltArtifact{}

		ra.Namespace = pr.Namespace
		ra.Name = artifactbuild.CreateABRName(i)
		if err := controllerutil.SetOwnerReference(db, &ra, r.scheme); err != nil {
			return err
		}
		ra.Spec.GAV = i
		ra.Spec.Image = image
		ra.Spec.Digest = digest
		err := r.client.Create(ctx, &ra)
		if err != nil {
			if !errors.IsAlreadyExists(err) {
				return err
			} else {
				//if it already exists we update the image field
				err = r.client.Get(ctx, types.NamespacedName{Namespace: ra.Namespace, Name: ra.Name}, &ra)
				if err != nil {
					return err
				}
				ra.Spec.Image = image
				ra.Spec.Digest = digest
				log.Info(fmt.Sprintf("Updating existing RebuiltArtifact %s to reference image %s", ra.Name, ra.Spec.Image), "action", "UPDATE")
				err = r.client.Update(ctx, &ra)
				if err != nil {
					return err
				}
			}
		}
	}
	return nil
}

func (r *ReconcileDependencyBuild) createArtifacts(ctx context.Context, pr *tektonpipeline.PipelineRun, db *v1alpha1.DependencyBuild, deployed []string) error {
	db.Status.DeployedArtifacts = deployed

	for _, i := range deployed {
		ab := v1alpha1.ArtifactBuild{}
		ab.Namespace = pr.Namespace
		ab.Name = artifactbuild.CreateABRName(i)
		ab.Annotations = map[string]string{artifactbuild.DependencyCreatedAnnotation: db.Name}
		if db.Annotations != nil && db.Annotations[artifactbuild.DependencyScmAnnotation] == "true" {
			ab.Annotations[artifactbuild.DependencyScmAnnotation] = "true"
		}
		ab.Spec.GAV = i

		err := r.client.Create(ctx, &ab)
		if err != nil && !errors.IsAlreadyExists(err) {
			return err
		}
	}
	return nil
}

func (r *ReconcileDependencyBuild) createLookupBuildInfoPipeline(ctx context.Context, db *v1alpha1.DependencyBuild, jbsConfig *v1alpha1.JBSConfig, additionalMemory int, systemConfig *v1alpha1.SystemConfig) (*tektonpipeline.PipelineSpec, error) {
	log, _ := logr.FromContext(ctx)
	image, err := r.buildRequestProcessorImage(ctx)
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
	registries := jbsconfig.ImageRegistriesToString(jbsConfig.Spec.SharedRegistries)

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
	for _, ownerRef := range db.OwnerReferences {
		if strings.EqualFold(ownerRef.Kind, "artifactbuild") || strings.EqualFold(ownerRef.Kind, "artifactbuilds") {
			other := v1alpha1.ArtifactBuild{}
			err := r.client.Get(ctx, types.NamespacedName{Name: ownerRef.Name, Namespace: db.Namespace}, &other)
			if err != nil {
				log.Error(err, "Could not lookup owner artifact")
				continue
			}
			args = append(args, "--artifact")
			args = append(args, other.Spec.GAV)
			break
		}
	}

	//don't look for existing artifacts on a rebuild
	if (db.Annotations == nil || db.Annotations[artifactbuild.RebuiltAnnotation] != "true") &&
		(jbsConfig.Spec.Registry.DontReuseExisting == nil || !*jbsConfig.Spec.Registry.DontReuseExisting) {
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
	if strings.HasSuffix(image, "dev") {
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
		// Builds or tooling mostly use the .docker/config.json directly which is updated via Tekton/Kubernetes secrets. But the
		// Java code may require the token as well.
		envVars = append(envVars, v1.EnvVar{Name: "REGISTRY_TOKEN", ValueFrom: &v1.EnvVarSource{SecretKeyRef: &v1.SecretKeySelector{LocalObjectReference: v1.LocalObjectReference{Name: jbsConfig.ImageRegistry().SecretName}, Key: v1alpha1.ImageSecretTokenKey, Optional: &secretOptional}}})
	}
	buildInfoTask := tektonpipeline.TaskSpec{
		Workspaces: []tektonpipeline.WorkspaceDeclaration{{Name: WorkspaceTls}},
		Results:    []tektonpipeline.TaskResult{{Name: BuildInfoPipelineResultBuildInfo}},
		Steps: []tektonpipeline.Step{
			{
				Name:            "process-build-requests",
				Image:           image,
				ImagePullPolicy: pullPolicy,
				SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
				ComputeResources: v1.ResourceRequirements{
					//TODO: make configurable
					Requests: v1.ResourceList{"memory": resource.MustParse(memory), "cpu": resource.MustParse("10m")},
					Limits:   v1.ResourceList{"memory": resource.MustParse(memory)},
				},
				Env: envVars,
			},
		},
	}
	if build.BuildRecipeConfigMap != "" {
		mountPath := "/build-recipe"
		args = append(args, "--build-recipe-path", mountPath+"/build.yaml")
		buildInfoTask.Steps[0].VolumeMounts = []v1.VolumeMount{{
			Name:      "build-recipe-config-map",
			MountPath: mountPath,
		}}
		buildInfoTask.Volumes = []v1.Volume{
			{Name: "build-recipe-config-map", VolumeSource: v1.VolumeSource{ConfigMap: &v1.ConfigMapVolumeSource{LocalObjectReference: v1.LocalObjectReference{Name: build.BuildRecipeConfigMap}}}},
		}
	}
	buildInfoTask.Steps[0].Script = artifactbuild.InstallKeystoreIntoBuildRequestProcessor(args)
	return &tektonpipeline.PipelineSpec{
		Workspaces: []tektonpipeline.PipelineWorkspaceDeclaration{{Name: WorkspaceTls}},
		Results:    []tektonpipeline.PipelineResult{{Name: BuildInfoPipelineResultBuildInfo, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: "$(tasks.task.results." + BuildInfoPipelineResultBuildInfo + ")"}}},
		Tasks: []tektonpipeline.PipelineTask{
			{
				Name:       "task",
				Workspaces: []tektonpipeline.WorkspacePipelineTaskBinding{{Name: WorkspaceTls, Workspace: WorkspaceTls}},
				TaskSpec: &tektonpipeline.EmbeddedTask{
					TaskSpec: buildInfoTask,
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

func (r *ReconcileDependencyBuild) failedDueToMemory(ctx context.Context, log logr.Logger, pr *tektonpipeline.PipelineRun) bool {
	for _, trs := range pr.Status.ChildReferences {
		tr := tektonpipeline.TaskRun{}
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

func (r *ReconcileDependencyBuild) buildRequestProcessorImage(ctx context.Context) (string, error) {
	image, err := util.GetImageName(ctx, r.client, "build-request-processor", "JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE")
	return image, err
}

func (r *ReconcileDependencyBuild) handleTektonResults(db *v1alpha1.DependencyBuild, pr *tektonpipeline.PipelineRun) bool {
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

func (r *ReconcileDependencyBuild) handleTektonResultsForPipeline(ba *v1alpha1.PipelineResults, pr *tektonpipeline.PipelineRun) bool {
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

func (r *ReconcileDependencyBuild) removePipelineFinalizer(ctx context.Context, pr *tektonpipeline.PipelineRun) (reconcile.Result, error) {
	//remove the finalizer
	if controllerutil.RemoveFinalizer(pr, PipelineRunFinalizer) {
		return reconcile.Result{}, r.client.Update(ctx, pr)
	}
	return reconcile.Result{}, nil
}

// TODO: ### Either remove or replace with verification step *but* the contaminants/verification is all tied to the build pipeline in dependencybuild.go
/*
func (r *ReconcileDependencyBuild) handleStateDeploying(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	log, _ := logr.FromContext(ctx)
	//first we check to see if the pipeline exists

	pr := tektonpipeline.PipelineRun{}
	prName := db.Name + DeploySuffix
	err := r.client.Get(ctx, types.NamespacedName{Name: prName, Namespace: db.Namespace}, &pr)
	if err == nil {
		//the pipeline already exists
		//do nothing
		return reconcile.Result{}, nil
	}
	if !errors.IsNotFound(err) {
		//other error
		return reconcile.Result{}, err
	}

	//now submit the pipeline
	pr.Finalizers = []string{PipelineRunFinalizer}
	buildRequestProcessorImage, err := r.buildRequestProcessorImage(ctx)
	if err != nil {
		return reconcile.Result{}, err
	}
	pr.Namespace = db.Namespace
	pr.Name = prName
	pr.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: db.Name, artifactbuild.PipelineRunLabel: "", PipelineTypeLabel: PipelineTypeDeploy}

	jbsConfig := &v1alpha1.JBSConfig{}
	err = r.client.Get(ctx, types.NamespacedName{Namespace: db.Namespace, Name: v1alpha1.JBSConfigName}, jbsConfig)
	if err != nil && !errors.IsNotFound(err) {
		return reconcile.Result{}, err
	}
	pr.Spec.PipelineRef = nil

	if err != nil {
		return reconcile.Result{}, err
	}
	attempt := db.Status.BuildAttempts[len(db.Status.BuildAttempts)-1]

	paramValues := []tektonpipeline.Param{
		{Name: PipelineResultImage, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: attempt.Build.Results.Image}},
		{Name: PipelineResultImageDigest, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: attempt.Build.Results.ImageDigest}},
		{Name: PipelineResultPreBuildImageDigest, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: db.Status.PreBuildImages[len(db.Status.PreBuildImages)-1].BuiltImageDigest}},
	}

	systemConfig := v1alpha1.SystemConfig{}
	err = r.client.Get(ctx, types.NamespacedName{Name: systemconfig.SystemConfigKey}, &systemConfig)
	if err != nil {
		return reconcile.Result{}, err
	}

	pr.Spec.Timeouts = &tektonpipeline.TimeoutFields{
		Pipeline: &v12.Duration{Duration: time.Hour * v1alpha1.DefaultTimeout},
		Tasks:    &v12.Duration{Duration: time.Hour * v1alpha1.DefaultTimeout},
	}
	pr.Spec.PipelineSpec, err = createDeployPipelineSpec(jbsConfig, buildRequestProcessorImage)
	if err != nil {
		return reconcile.Result{}, err
	}

	pr.Spec.Params = paramValues
	pr.Spec.Workspaces = []tektonpipeline.WorkspaceBinding{}

	if !jbsConfig.Spec.CacheSettings.DisableTLS {
		pr.Spec.Workspaces = append(pr.Spec.Workspaces, tektonpipeline.WorkspaceBinding{Name: WorkspaceTls, ConfigMap: &v1.ConfigMapVolumeSource{LocalObjectReference: v1.LocalObjectReference{Name: v1alpha1.TlsConfigMapName}}})
	} else {
		pr.Spec.Workspaces = append(pr.Spec.Workspaces, tektonpipeline.WorkspaceBinding{Name: WorkspaceTls, EmptyDir: &v1.EmptyDirVolumeSource{}})
	}
	pr.Spec.Timeouts = &tektonpipeline.TimeoutFields{Pipeline: &v12.Duration{Duration: time.Hour * v1alpha1.DefaultTimeout}}
	if jbsConfig.Annotations != nil && jbsConfig.Annotations[jbsconfig.TestRegistry] == "true" {
		pr.Spec.TaskRunTemplate = tektonpipeline.PipelineTaskRunTemplate{
			PodTemplate: &pod.Template{
				Env: []v1.EnvVar{
					{
						Name:  "ORAS_OPTIONS",
						Value: "--insecure --plain-http",
					},
				},
			},
		}
	}
	if jbsConfig.Annotations != nil && jbsConfig.Annotations[jbsconfig.CITests] == "true" {
		log.Info(fmt.Sprintf("Configuring resources for %#v", DeployTaskName))
		podMem, _ := resource.ParseQuantity("1024Mi")
		podCPU, _ := resource.ParseQuantity("250m")
		pr.Spec.TaskRunSpecs = []tektonpipeline.PipelineTaskRunSpec{{
			PipelineTaskName: DeployTaskName,
			ComputeResources: &v1.ResourceRequirements{
				Requests: v1.ResourceList{"memory": podMem, "cpu": podCPU},
				Limits:   v1.ResourceList{"memory": podMem, "cpu": podCPU},
			},
		}}
	}
	if err := controllerutil.SetOwnerReference(db, &pr, r.scheme); err != nil {
		return reconcile.Result{}, err
	}
	//now we submit the build
	if err := r.client.Create(ctx, &pr); err != nil {
		if errors.IsAlreadyExists(err) {
			log.Info(fmt.Sprintf("handleStateDeploy: pipelinerun %s:%s already exists, not retrying", pr.Namespace, pr.Name))
			return reconcile.Result{}, nil
		}
		r.eventRecorder.Eventf(db, v1.EventTypeWarning, "PipelineRunCreationFailed", "The DependencyBuild %s/%s failed to create its deploy pipeline run", db.Namespace, db.Name)
		return reconcile.Result{}, err
	}
	return reconcile.Result{}, r.client.Status().Update(ctx, db)
}

func (r *ReconcileDependencyBuild) handleDeployPipelineRunReceived(ctx context.Context, pr *tektonpipeline.PipelineRun) (reconcile.Result, error) {

	if pr.Status.CompletionTime != nil || pr.DeletionTimestamp != nil {
		db, err := r.dependencyBuildForPipelineRun(ctx, pr)
		if err != nil || db == nil {
			return reconcile.Result{}, err
		}

		if db.Status.DeployPipelineResults == nil {
			db.Status.DeployPipelineResults = &v1alpha1.PipelineResults{}
		}
		r.handleTektonResultsForPipeline(db.Status.DeployPipelineResults, pr)
		if db.Status.State != v1alpha1.DependencyBuildStateDeploying {
			//wrong state
			return reconcile.Result{}, nil
		}

		success := pr.Status.GetCondition(apis.ConditionSucceeded).IsTrue()
		if success {
			return reconcile.Result{}, r.updateDependencyBuildState(ctx, db, v1alpha1.DependencyBuildStateComplete, "deploy pipeline complete")
		} else {
			return reconcile.Result{}, r.updateDependencyBuildState(ctx, db, v1alpha1.DependencyBuildStateFailed, "deploy pipeline failed")
		}

	}
	return reconcile.Result{}, nil
}
*/

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

func (r *ReconcileDependencyBuild) updateDependencyBuildState(ctx context.Context, db *v1alpha1.DependencyBuild, state string, reason string) error {
	if db.Status.State != state {
		log, err := logr.FromContext(ctx)
		if err != nil {
			return err
		}
		log.Info(fmt.Sprintf("DependencyBuild %s changing state from %s to %s due to: %s", db.Name, db.Status.State, state, reason))
		db.Status.State = state
		return r.client.Status().Update(ctx, db)
	}
	return nil
}

func (r *ReconcileDependencyBuild) handleStateAnalyzeBuild(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	p := tektonpipeline.PipelineRunList{}
	listOpts := &client.ListOptions{
		Namespace:     db.Namespace,
		LabelSelector: labels.SelectorFromSet(map[string]string{artifactbuild.PipelineRunLabel: "", artifactbuild.DependencyBuildIdLabel: db.Name, PipelineTypeLabel: PipelineTypeBuildInfo}),
	}
	err := r.client.List(ctx, &p, listOpts)
	if err != nil {
		return reconcile.Result{}, err
	}
	if len(p.Items) == 0 {
		log, _ := logr.FromContext(ctx)
		log.Info("analysis pipeline not found, moving to failed")
		db.Status.State = v1alpha1.DependencyBuildStateFailed
		return reconcile.Result{}, r.client.Status().Update(ctx, db)
	}
	return reconcile.Result{}, nil
}
