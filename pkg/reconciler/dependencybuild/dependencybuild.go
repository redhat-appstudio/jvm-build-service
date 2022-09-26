package dependencybuild

import (
	"context"
	"crypto/md5" //#nosec
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"strconv"
	"strings"
	"time"

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
	"github.com/kcp-dev/logicalcluster/v2"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/systemconfig"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/tektonwrapper"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout                = 300 * time.Second
	PipelineBuildId               = "DEPENDENCY_BUILD"
	PipelineScmUrl                = "URL"
	PipelineScmTag                = "TAG"
	PipelinePath                  = "CONTEXT_DIR"
	PipelineImage                 = "IMAGE"
	PipelineGoals                 = "GOALS"
	PipelineJavaVersion           = "JAVA_VERSION"
	PipelineToolVersion           = "TOOL_VERSION"
	PipelineEnforceVersion        = "ENFORCE_VERSION"
	PipelineIgnoredArtifacts      = "IGNORED_ARTIFACTS"
	PipelineGradleManipulatorArgs = "GRADLE_MANIPULATOR_ARGS"
	PipelineCacheUrl              = "CACHE_URL"

	BuildInfoPipelineScmUrlParam  = "SCM_URL"
	BuildInfoPipelineTagParam     = "TAG"
	BuildInfoPipelineContextParam = "CONTEXT"
	BuildInfoPipelineVersionParam = "VERSION"
	BuildInfoPipelineMessage      = "message"
	BuildInfoPipelineBuildInfo    = "build-info"

	PipelineType          = "jvmbuildservice.io/pipeline-type"
	PipelineTypeBuildInfo = "build-info"
	PipelineTypeBuild     = "build"
)

type ReconcileDependencyBuild struct {
	client        client.Client
	scheme        *runtime.Scheme
	eventRecorder record.EventRecorder
	prCreator     tektonwrapper.PipelineRunCreate
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcileDependencyBuild{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("DependencyBuild"),
		prCreator:     &tektonwrapper.BatchedCreate{},
	}
}

func (r *ReconcileDependencyBuild) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	var cancel context.CancelFunc
	if request.ClusterName != "" {
		// use logicalcluster.ClusterFromContxt(ctx) to retrieve this value later on
		ctx = logicalcluster.WithCluster(ctx, logicalcluster.New(request.ClusterName))
	}
	ctx, cancel = context.WithTimeout(ctx, contextTimeout)
	defer cancel()
	log := ctrl.Log.WithName("dependencybuild").WithValues("request", request.NamespacedName).WithValues("cluster", request.ClusterName)

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
		//TODO weird - during envtest the logging code panicked on the commented out log.Info call: 'com.acme.example.1.0-scm-discovery-5vjvmpanic: odd number of arguments passed as key-value pairs for logging'
		msg := fmt.Sprintf("Reconcile key %s received not found errors for both pipelineruns and dependencybuilds (probably deleted)\"", request.NamespacedName.String())
		log.Info(msg)
		//log.Info("Reconcile key %s received not found errors for pipelineruns, dependencybuilds, artifactbuilds (probably deleted)", request.NamespacedName.String())
		return ctrl.Result{}, nil
	}

	switch {
	case dberr == nil:
		//we validate that our dep id hash is still valid
		//if a field has been modified we need to update the label
		//which may result in a new build
		depId := hashToString(db.Spec.ScmInfo.SCMURL + db.Spec.ScmInfo.Tag + db.Spec.ScmInfo.Path)
		if db.Labels == nil || len(db.Labels) == 0 {
			return reconcile.Result{}, fmt.Errorf("dependency build %s missing labels", depId)
		}
		if depId != db.Labels[artifactbuild.DependencyBuildIdLabel] {
			//if our id has changed we just update the label and set our state back to new
			//this will kick off a new build
			db.Labels[artifactbuild.DependencyBuildIdLabel] = depId
			db.Status.State = v1alpha1.DependencyBuildStateNew
			// TODO possibly abort instead, possibly allow but file event, or metric alert later on
			return reconcile.Result{}, r.client.Update(ctx, &db)
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
			return r.handleStateCompleted(ctx, &db, log)
		}

	case trerr == nil:
		pipelineType := pr.Labels[PipelineType]
		switch pipelineType {
		case PipelineTypeBuildInfo:
			return r.handleStateAnalyzeBuild(ctx, log, &pr)
		case PipelineTypeBuild:
			return r.handleBuildPipelineRunReceived(ctx, log, &pr)
		}
	}

	return reconcile.Result{}, nil
}

func hashToString(unique string) string {
	hash := md5.Sum([]byte(unique)) //#nosec
	depId := hex.EncodeToString(hash[:])
	return depId
}

func (r *ReconcileDependencyBuild) handleStateNew(ctx context.Context, log logr.Logger, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	userConfig := &v1alpha1.UserConfig{}
	err := r.client.Get(ctx, types.NamespacedName{Namespace: db.Namespace, Name: v1alpha1.UserConfigName}, userConfig)
	if err != nil && !errors.IsNotFound(err) {
		return reconcile.Result{}, err
	}
	// create pipeline run
	pr := pipelinev1beta1.PipelineRun{}
	pr.Spec.PipelineSpec, err = r.createLookupBuildInfoPipeline(ctx, log, &db.Spec, userConfig)
	if err != nil {
		return reconcile.Result{}, err
	}
	pr.Namespace = db.Namespace
	pr.GenerateName = db.Name + "-build-discovery-"
	pr.Labels = map[string]string{artifactbuild.PipelineRunLabel: "", artifactbuild.DependencyBuildIdLabel: db.Name, PipelineType: PipelineTypeBuildInfo}
	if err := controllerutil.SetOwnerReference(db, &pr, r.scheme); err != nil {
		return reconcile.Result{}, err
	}
	db.Status.State = v1alpha1.DependencyBuildStateAnalyzeBuild
	if err := r.client.Status().Update(ctx, db); err != nil {
		return reconcile.Result{}, err
	}
	if err := r.prCreator.CreateWrapperForPipelineRun(ctx, r.client, &pr); err != nil {
		return reconcile.Result{}, err
	}
	return reconcile.Result{}, nil
}

func (r *ReconcileDependencyBuild) handleStateAnalyzeBuild(ctx context.Context, log logr.Logger, pr *pipelinev1beta1.PipelineRun) (reconcile.Result, error) {
	if pr.Status.CompletionTime == nil {
		return reconcile.Result{}, nil
	}
	ownerRefs := pr.GetOwnerReferences()
	if len(ownerRefs) == 0 {
		msg := "pipelinerun missing onwerrefs %s:%s"
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
		return reconcile.Result{}, err
	}
	if db.Status.State != v1alpha1.DependencyBuildStateAnalyzeBuild {
		return reconcile.Result{}, nil
	}

	var buildInfo string
	var message string
	//we grab the results here and put them on the ABR
	for _, res := range pr.Status.PipelineResults {
		switch res.Name {
		case BuildInfoPipelineBuildInfo:
			buildInfo = res.Value
		case BuildInfoPipelineMessage:
			message = res.Value
		}
	}
	success := pr.Status.GetCondition(apis.ConditionSucceeded).IsTrue()
	if !success || len(buildInfo) == 0 {
		db.Status.State = v1alpha1.DependencyBuildStateFailed
		db.Status.Message = message
	} else {
		unmarshalled := marshalledBuildInfo{}

		if err := json.Unmarshal([]byte(buildInfo), &unmarshalled); err != nil {
			r.eventRecorder.Eventf(&db, v1.EventTypeWarning, "InvalidJson", "Failed to unmarshal build info for AB %s/%s JSON: %s", db.Namespace, db.Name, buildInfo)
			return reconcile.Result{}, err
		}
		//read our builder images from the config
		var allBuilderImages []BuilderImage
		var selectedImages []BuilderImage
		allBuilderImages, err = r.processBuilderImages(ctx, log)
		if err != nil {
			return reconcile.Result{}, err
		}
		// for now we are ignoring the tool versions
		// and just using the supplied invocations
		buildRecipes := []*v1alpha1.BuildRecipe{}
		_, maven := unmarshalled.Tools["maven"]
		_, gradle := unmarshalled.Tools["gradle"]
		java := unmarshalled.Tools["jdk"]
		db.Status.CommitTime = unmarshalled.CommitTime

		for _, image := range allBuilderImages {
			//we only have one JDK version in the builder at the moment
			//other tools will potentially have multiple versions
			//we only want to use builder images that have java versions that the analyser
			//detected might be appropriate
			imageJava := image.Tools["jdk"][0]
			if java.Min != "" {
				versionResult, err := compareVersions(imageJava, java.Min)
				if err != nil {
					log.Error(err, fmt.Sprintf("Failed to compare versions %s and %s", imageJava, java.Min))
					return reconcile.Result{}, err
				}
				if versionResult < 0 {
					log.Info(fmt.Sprintf("Not building with %s because of min java version %s (image version %s)", image.Image, java.Min, imageJava))
					continue
				}
			}
			if java.Max != "" {
				versionResult, err := compareVersions(imageJava, java.Max)
				if err != nil {
					log.Error(err, fmt.Sprintf("Failed to compare versions %s and %s", imageJava, java.Max))
					return reconcile.Result{}, err
				}
				if versionResult > 0 {
					log.Info(fmt.Sprintf("Not building with %s because of max java version %s (image version %s)", image.Image, java.Min, imageJava))
					continue
				}
			}
			selectedImages = append(selectedImages, image)
		}

		for _, image := range selectedImages {
			var tooVersions []string
			if maven {
				//TODO: maven version selection
				//for now we just fake it
				tooVersions = []string{"3.8.1"}
			} else if gradle {
				//gradle has an explicit tool version, but we need to map it to what is in the image
				gradleVersionsInImage := image.Tools["gradle"]
				for _, i := range gradleVersionsInImage {
					if sameMajorVersion(i, unmarshalled.ToolVersion) {
						tooVersions = append(tooVersions, i)
					}
				}
			} else {
				log.Error(nil, "Neither maven nor gradle was found in the tools map", "json", buildInfo)
				db.Status.State = v1alpha1.DependencyBuildStateFailed
				return reconcile.Result{}, r.client.Status().Update(ctx, &db)
			}
			for _, command := range unmarshalled.Invocations {
				for _, tv := range tooVersions {
					buildRecipes = append(buildRecipes, &v1alpha1.BuildRecipe{Image: image.Image, CommandLine: command, EnforceVersion: unmarshalled.EnforceVersion, IgnoredArtifacts: unmarshalled.IgnoredArtifacts, ToolVersion: tv, JavaVersion: unmarshalled.JavaVersion, Maven: maven, Gradle: gradle})
				}
			}
		}

		db.Status.PotentialBuildRecipes = buildRecipes
		db.Status.State = v1alpha1.DependencyBuildStateSubmitBuild
	}
	err = r.client.Status().Update(ctx, &db)
	if err != nil {
		return reconcile.Result{}, err
	}
	return reconcile.Result{}, nil
}

type marshalledBuildInfo struct {
	Tools            map[string]toolInfo
	Invocations      [][]string
	EnforceVersion   string
	IgnoredArtifacts []string
	ToolVersion      string
	JavaVersion      string
	CommitTime       int64
}

type toolInfo struct {
	Min       string
	Max       string
	Preferred string
}

// compares versions, returns 0 if versions
// are equivalent, -1 if v1 < v2 and 1 if v2 < v1
// this is looking for functional equivilence, so 3.6 is considered the same as 3.6.7
func compareVersions(v1 string, v2 string) (int, error) {
	v1p := strings.Split(v1, ".")
	v2p := strings.Split(v2, ".")
	for i := range v1p {
		if len(v2p) == i {
			//we are considering them equal, as the important parts have matched
			return 0, nil
		}
		v1segment, err := strconv.ParseInt(v1p[i], 10, 64)
		if err != nil {
			return 0, err
		}
		v2segment, err := strconv.ParseInt(v2p[i], 10, 64)
		if err != nil {
			return 0, err
		}
		if v1segment < v2segment {
			return -1, nil
		}
		if v1segment > v2segment {
			return 1, nil
		}
	}
	return 0, nil
}

// compares versions, returns 0 if versions
// are equivalent, -1 if v1 < v2 and 1 if v2 < v1
// this is looking for functional equivilence, so 3.6 is considered the same as 3.6.7
func sameMajorVersion(v1 string, v2 string) bool {
	v1p := strings.Split(v1, ".")
	v2p := strings.Split(v2, ".")
	return v2p[0] == v1p[0]
}

func (r *ReconcileDependencyBuild) processBuilderImages(ctx context.Context, log logr.Logger) ([]BuilderImage, error) {
	systemConfig := v1alpha1.SystemConfig{}
	err := r.client.Get(ctx, types.NamespacedName{Name: systemconfig.SystemConfigKey}, &systemConfig)
	if err != nil {
		return nil, err
	}
	//TODO how important is the order here?  do we want 11,8,17 per the old form at https://github.com/redhat-appstudio/jvm-build-service/blob/b91ec6e1888e43962cba16fcaee94e0c9f64557d/deploy/operator/config/system-config.yaml#L8
	// the unit tests's imaage verification certainly assumes a order
	result := []BuilderImage{}
	for _, val := range systemConfig.Spec.Builders {
		result = append(result, BuilderImage{
			Image: val.Image,
			Tools: r.processBuilderImageTags(val.Tag),
		})
	}
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
	if db.Status.CurrentBuildRecipe != nil {
		db.Status.FailedBuildRecipes = append(db.Status.FailedBuildRecipes, db.Status.CurrentBuildRecipe)
	}
	//no more attempts
	if len(db.Status.PotentialBuildRecipes) == 0 {
		db.Status.State = v1alpha1.DependencyBuildStateFailed
		r.eventRecorder.Eventf(db, v1.EventTypeWarning, "BuildFailed", "The DependencyBuild %s/%s moved to failed, all recipes exhausted", db.Namespace, db.Name)
		return reconcile.Result{}, r.client.Status().Update(ctx, db)
	}
	db.Status.CurrentBuildRecipe = db.Status.PotentialBuildRecipes[0]
	//and remove if from the potential list
	db.Status.PotentialBuildRecipes = db.Status.PotentialBuildRecipes[1:]
	db.Status.State = v1alpha1.DependencyBuildStateBuilding
	//update the recipes
	return reconcile.Result{}, r.client.Status().Update(ctx, db)

}

func (r *ReconcileDependencyBuild) handleStateBuilding(ctx context.Context, log logr.Logger, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	//now submit the pipeline
	pr := pipelinev1beta1.PipelineRun{}
	pr.Namespace = db.Namespace
	// we do not use generate name since a) it was used in creating the db and the db name has random ids b) there is a 1 to 1 relationship (but also consider potential recipe retry)
	// c) it allows us to use the already exist error on create to short circuit the creation of dbs if owner refs updates to the db before
	// we move the db out of building
	pr.Name = currentDependencyBuildPipelineName(db)
	pr.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: db.Labels[artifactbuild.DependencyBuildIdLabel], artifactbuild.PipelineRunLabel: "", PipelineType: PipelineTypeBuild}

	if !db.Status.CurrentBuildRecipe.Maven && !db.Status.CurrentBuildRecipe.Gradle {
		r.eventRecorder.Eventf(db, v1.EventTypeWarning, "MissingRecipeType", "recipe for DependencyBuild %s:%s neither maven or gradle", db.Namespace, db.Name)
		return reconcile.Result{}, fmt.Errorf("recipe for DependencyBuild %s:%s neither maven or gradle", db.Namespace, db.Name)
	}

	pr.Spec.PipelineRef = nil
	pr.Spec.PipelineSpec = createPipelineSpec(db.Status.CurrentBuildRecipe.Maven, db.Namespace, db.Status.CommitTime)

	pr.Spec.ServiceAccountName = "pipeline"
	//TODO: this is all going away, but for now we have lost the ability to confiugure this via YAML
	//It's not worth adding a heap of env var overrides for something that will likely be gone next week
	//the actual solution will involve loading deployment config from a ConfigMap
	pr.Spec.Params = []pipelinev1beta1.Param{
		{Name: PipelineBuildId, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Name}},
		{Name: PipelineScmUrl, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.SCMURL}},
		{Name: PipelineScmTag, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.Tag}},
		{Name: PipelinePath, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.Path}},
		{Name: PipelineImage, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Status.CurrentBuildRecipe.Image}},
		{Name: PipelineGoals, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeArray, ArrayVal: db.Status.CurrentBuildRecipe.CommandLine}},
		{Name: PipelineEnforceVersion, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Status.CurrentBuildRecipe.EnforceVersion}},
		{Name: PipelineIgnoredArtifacts, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: strings.Join(db.Status.CurrentBuildRecipe.IgnoredArtifacts, ",")}},
		{Name: PipelineToolVersion, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Status.CurrentBuildRecipe.ToolVersion}},
		{Name: PipelineJavaVersion, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Status.CurrentBuildRecipe.JavaVersion}},
	}
	pr.Spec.Workspaces = []pipelinev1beta1.WorkspaceBinding{
		{Name: WorkspaceBuildSettings, EmptyDir: &v1.EmptyDirVolumeSource{}},
		{Name: WorkspaceSource, EmptyDir: &v1.EmptyDirVolumeSource{}},
	}
	pr.Spec.Timeout = &v12.Duration{Duration: time.Hour * 3}
	if err := controllerutil.SetOwnerReference(db, &pr, r.scheme); err != nil {
		return reconcile.Result{}, err
	}
	//now we submit the build
	if err := r.prCreator.CreateWrapperForPipelineRun(ctx, r.client, &pr); err != nil {
		if errors.IsAlreadyExists(err) {
			log.V(4).Info("handleStateBuilding: pipelinerun %s:%s already exists, not retrying", pr.Namespace, pr.Name)
			return reconcile.Result{}, nil
		}
		r.eventRecorder.Eventf(db, v1.EventTypeWarning, "PipelineRunCreationFailed", "The DependencyBuild %s/%s failed to create its build pipeline run", db.Namespace, db.Name)
		return reconcile.Result{}, err
	}

	return reconcile.Result{}, nil
}

func currentDependencyBuildPipelineName(db *v1alpha1.DependencyBuild) string {
	return fmt.Sprintf("%s-build-%d", db.Name, len(db.Status.FailedBuildRecipes))
}

func (r *ReconcileDependencyBuild) handleBuildPipelineRunReceived(ctx context.Context, log logr.Logger, pr *pipelinev1beta1.PipelineRun) (reconcile.Result, error) {
	if pr.Status.CompletionTime != nil {
		// get db
		ownerRefs := pr.GetOwnerReferences()
		if len(ownerRefs) == 0 {
			msg := "pipelinerun missing onwerrefs %s:%s"
			r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name)
			log.Info(msg, pr.Namespace, pr.Name)

			return reconcile.Result{}, nil
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

			return reconcile.Result{}, nil
		}

		key := types.NamespacedName{Namespace: pr.Namespace, Name: ownerRef.Name}
		db := v1alpha1.DependencyBuild{}
		err := r.client.Get(ctx, key, &db)
		if err != nil {
			msg := "get for pipelinerun %s:%s owning db %s:%s yielded error %s"
			r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name, pr.Namespace, ownerRef.Name, err.Error())
			log.Error(err, fmt.Sprintf(msg, pr.Namespace, pr.Name, pr.Namespace, ownerRef.Name, err.Error()))
			return reconcile.Result{}, err
		}

		if pr.Name == db.Status.LastCompletedBuildPipelineRun || pr.Name != currentDependencyBuildPipelineName(&db) {
			//already handled
			return reconcile.Result{}, nil
		}
		db.Status.LastCompletedBuildPipelineRun = pr.Name
		//the pr is done, lets potentially update the dependency build
		//we just set the state here, the ABR logic is in the ABR controller
		//this keeps as much of the logic in one place as possible

		success := pr.Status.GetCondition(apis.ConditionSucceeded).IsTrue()
		if success {
			if len(db.Status.Contaminants) == 0 {
				db.Status.State = v1alpha1.DependencyBuildStateComplete
			} else {
				r.eventRecorder.Eventf(&db, v1.EventTypeWarning, "BuildContaminated", "The DependencyBuild %s/%s was contaminated with community dependencies", db.Namespace, db.Name)
				//the dependency was contaminated with community deps
				//most likely shaded in
				err = r.client.Status().Update(ctx, &db)
				if err != nil {
					return reconcile.Result{}, err
				}
				//even though there are contaminates they may not be in artifacts we care about
				return r.handleStateCompleted(ctx, &db, log)
			}
		} else {
			//try again, if there are no more recipes this gets handled in the submit build logic
			db.Status.State = v1alpha1.DependencyBuildStateSubmitBuild
		}
		err = r.client.Status().Update(ctx, &db)
		if err != nil {
			return reconcile.Result{}, err
		}

	}
	return reconcile.Result{}, nil
}

// This checks that the build is still considered uncontaminated
// even if some artifacts in the build were contaminated it may still be considered a success if there was
// no actual request for these artifacts. This can change if new artifacts are requested, so even when complete
// we still need to verify that hte build is ok
func (r *ReconcileDependencyBuild) handleStateCompleted(ctx context.Context, db *v1alpha1.DependencyBuild, l logr.Logger) (reconcile.Result, error) {

	ownerGavs := map[string]bool{}
	db.Status.State = v1alpha1.DependencyBuildStateComplete
	if len(db.Status.Contaminants) == 0 {
		return reconcile.Result{}, r.client.Status().Update(ctx, db)
	}
	l.Info("Resolving contaminates for build", "build", db.Name)
	//get all the owning artifact builds
	//if any of these are contaminated
	for _, ownerRef := range db.OwnerReferences {
		if strings.EqualFold(ownerRef.Kind, "artifactbuild") || strings.EqualFold(ownerRef.Kind, "artifactbuilds") {
			ab := v1alpha1.ArtifactBuild{}
			err := r.client.Get(ctx, types.NamespacedName{Name: ownerRef.Name, Namespace: db.Namespace}, &ab)
			if err != nil {
				return reconcile.Result{}, err
			}
			ownerGavs[ab.Spec.GAV] = true
		}
	}
	l.Info("Found owner gavs", "gavs", ownerGavs)
	for _, contaminant := range db.Status.Contaminants {
		for _, artifact := range contaminant.ContaminatedArtifacts {
			if ownerGavs[artifact] {
				l.Info("Found contaminant affecting owner", "contaminate", contaminant, "owner", artifact)
				db.Status.State = v1alpha1.DependencyBuildStateContaminated
				abrName := artifactbuild.CreateABRName(contaminant.GAV)
				abr := v1alpha1.ArtifactBuild{}
				//look for existing ABR
				err := r.client.Get(ctx, types.NamespacedName{Name: abrName, Namespace: db.Namespace}, &abr)
				suffix := hashToString(contaminant.GAV)[0:20]
				if err != nil {
					//we just assume this is because it does not exist
					//TODO: how to check the type of the error?
					abr.Spec = v1alpha1.ArtifactBuildSpec{GAV: contaminant.GAV}
					abr.Name = abrName
					abr.Namespace = db.Namespace
					abr.Annotations = map[string]string{}
					//use this annotation to link back to the dependency build
					abr.Annotations[artifactbuild.DependencyBuildContaminatedBy+suffix] = db.Name
					err := r.client.Create(ctx, &abr)
					if err != nil {
						return reconcile.Result{}, err
					}
				} else {
					abr.Annotations = map[string]string{}
					abr.Annotations[artifactbuild.DependencyBuildContaminatedBy+suffix] = db.Name
					err := r.client.Update(ctx, &abr)
					if err != nil {
						return reconcile.Result{}, err
					}
				}
				break
			}
		}
	}
	return reconcile.Result{}, r.client.Status().Update(ctx, db)
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

func (r *ReconcileDependencyBuild) createLookupBuildInfoPipeline(ctx context.Context, log logr.Logger, build *v1alpha1.DependencyBuildSpec, userConfig *v1alpha1.UserConfig) (*pipelinev1beta1.PipelineSpec, error) {
	image, err := util.GetImageName(ctx, r.client, log, "build-request-processor", "JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE")
	if err != nil {
		return nil, err
	}
	recipes := ""
	additional := userConfig.Spec.AdditionalRecipes
	for _, recipe := range additional {
		if len(strings.TrimSpace(recipe)) > 0 {
			recipes = recipes + recipe + ","
		}
	}
	recipes = recipes + os.Getenv("RECIPE_DATABASE")
	path := build.ScmInfo.Path
	//TODO should the buidl request process require context to be set ?
	if len(path) == 0 {
		path = "."
	}
	zero := int64(0)
	return &pipelinev1beta1.PipelineSpec{
		Results: []pipelinev1beta1.PipelineResult{{Name: BuildInfoPipelineMessage, Value: "$(tasks." + artifactbuild.TaskName + ".results." + BuildInfoPipelineMessage + ")"}, {Name: BuildInfoPipelineBuildInfo, Value: "$(tasks." + artifactbuild.TaskName + ".results." + BuildInfoPipelineBuildInfo + ")"}},
		Tasks: []pipelinev1beta1.PipelineTask{
			{
				Name: artifactbuild.TaskName,
				TaskSpec: &pipelinev1beta1.EmbeddedTask{
					TaskSpec: pipelinev1beta1.TaskSpec{
						Results: []pipelinev1beta1.TaskResult{{Name: BuildInfoPipelineMessage}, {Name: BuildInfoPipelineBuildInfo}},
						Steps: []pipelinev1beta1.Step{
							{
								Container: v1.Container{
									Name:  "process-build-requests",
									Image: image,
									Args: []string{
										"lookup-build-info",
										"--recipes",
										recipes,
										"--scm-url",
										build.ScmInfo.SCMURL,
										"--scm-tag",
										build.ScmInfo.Tag,
										"--context",
										path,
										"--version",
										build.Version,
										"--message",
										"$(results." + BuildInfoPipelineMessage + ".path)",
										"--build-info",
										"$(results." + BuildInfoPipelineBuildInfo + ".path)",
									},
									SecurityContext: &v1.SecurityContext{
										RunAsUser: &zero,
									},
								},
							},
						},
					},
				},
			},
		},
	}, nil
}

type BuilderImage struct {
	Image string
	Tools map[string][]string
}
