package dependencybuild

import (
	"context"
	"crypto/md5" //#nosec
	"encoding/hex"
	"encoding/json"
	"fmt"
	"k8s.io/apimachinery/pkg/api/resource"
	"k8s.io/apimachinery/pkg/util/intstr"
	"os"
	"strconv"
	"strings"
	"time"

	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	v12 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/serializer"
	"k8s.io/apimachinery/pkg/types"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	"k8s.io/client-go/tools/record"

	"knative.dev/pkg/apis"

	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	"github.com/kcp-dev/logicalcluster"

	"github.com/go-logr/logr"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/configmap"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout           = 300 * time.Second
	PipelineScmUrl           = "URL"
	PipelineScmTag           = "TAG"
	PipelinePath             = "CONTEXT_DIR"
	PipelineImage            = "IMAGE"
	PipelineGoals            = "GOALS"
	PipelineEnforceVersion   = "ENFORCE_VERSION"
	PipelineIgnoredArtifacts = "IGNORED_ARTIFACTS"
	PipelineToolVersion      = "TOOL_VERSION"

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
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcileDependencyBuild{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("DependencyBuild"),
	}
}

func (r *ReconcileDependencyBuild) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	var cancel context.CancelFunc
	if request.ClusterName != "" {
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
			return r.handleStateNew(ctx, &db)
		case v1alpha1.DependencyBuildStateSubmitBuild:
			return r.handleStateSubmitBuild(ctx, &db)
		case v1alpha1.DependencyBuildStateComplete, v1alpha1.DependencyBuildStateFailed:
			return reconcile.Result{}, nil
		case v1alpha1.DependencyBuildStateBuilding:
			return r.handleStateBuilding(ctx, log, &db)
		case v1alpha1.DependencyBuildStateContaminated:
			return r.handleStateContaminated(ctx, &db)
		}

	case trerr == nil:
		pipelineType := pr.Labels[PipelineType]
		switch pipelineType {
		case PipelineTypeBuildInfo:
			return r.handleStateAnalyzeBuild(ctx, log, &pr)
		case PipelineTypeBuild:
			return r.handlePipelineRunReceived(ctx, log, &pr)
		}
	}

	return reconcile.Result{}, nil
}

func hashToString(unique string) string {
	hash := md5.Sum([]byte(unique)) //#nosec
	depId := hex.EncodeToString(hash[:])
	return depId
}

func (r *ReconcileDependencyBuild) handleStateNew(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	cm, err := configmap.ReadUserConfigMap(r.client, ctx, db.Namespace)
	if err != nil {
		return reconcile.Result{}, err
	}
	// create pipeline run
	tr := pipelinev1beta1.PipelineRun{}
	tr.Spec.PipelineSpec = createLookupBuildInfoPipeline(&db.Spec, cm)
	tr.Namespace = db.Namespace
	tr.GenerateName = db.Name + "-build-discovery-"
	tr.Labels = map[string]string{artifactbuild.PipelineRunLabel: "", artifactbuild.DependencyBuildIdLabel: db.Name, PipelineType: PipelineTypeBuildInfo}
	if err := controllerutil.SetOwnerReference(db, &tr, r.scheme); err != nil {
		return reconcile.Result{}, err
	}
	db.Status.State = v1alpha1.DependencyBuildStateAnalyzeBuild
	if err := r.client.Status().Update(ctx, db); err != nil {
		return reconcile.Result{}, err
	}
	if err := r.client.Create(ctx, &tr); err != nil {
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
		if os.Getenv(artifactbuild.DeleteTaskRunPodsEnv) == "1" {
			msg := "pruning analysis pipelinerun %s:%s for dependencybuild as it is missing owner refs"
			r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name)
			log.Info(fmt.Sprintf(msg, pr.Namespace, pr.Name))
			err := r.client.Delete(ctx, pr)
			if err != nil {
				return reconcile.Result{}, err
			}
		}
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

		if os.Getenv(artifactbuild.DeleteTaskRunPodsEnv) == "1" {
			msg := "pruning analysis pipelinerun %s:%s for dependencybuild as it is missing owner refs"
			r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name)
			log.Info(fmt.Sprintf(msg, pr.Namespace, pr.Name))
			err := r.client.Delete(ctx, pr)
			if err != nil {
				return reconcile.Result{}, err
			}
		}
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
		if os.Getenv(artifactbuild.DeleteTaskRunPodsEnv) == "1" {
			msg := "pruning analysis pipelinerun %s:%s for dependencybuild %s:%s whose state is %s sas part of jvm-build-service's attempt to not violate pod quota"
			r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name, db.Namespace, db.Name, db.Status.State)
			log.Info(fmt.Sprintf(msg, pr.Namespace, pr.Name, db.Namespace, db.Name, db.Status.State))
			err := r.client.Delete(ctx, pr)
			if err != nil {
				return reconcile.Result{}, err
			}
		}
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
		unmarshalled := struct {
			Tools map[string]struct {
				Min       string
				Max       string
				Preferred string
			}
			Invocations      [][]string
			EnforceVersion   string
			IgnoredArtifacts []string
			ToolVersion      string
			JavaVersion      string
		}{}

		if err := json.Unmarshal([]byte(buildInfo), &unmarshalled); err != nil {
			r.eventRecorder.Eventf(&db, v1.EventTypeWarning, "InvalidJson", "Failed to unmarshal build info for AB %s/%s JSON: %s", db.Namespace, db.Name, buildInfo)
			return reconcile.Result{}, err
		}
		//read our builder images from the config
		var mavenImages []BuilderImage
		var selectedImages []BuilderImage
		mavenImages, err = r.processBuilderImages(ctx, log)
		if err != nil {
			return reconcile.Result{}, err
		}
		// for now we are ignoring the tool versions
		// and just using the supplied invocations
		buildRecipes := []*v1alpha1.BuildRecipe{}
		_, maven := unmarshalled.Tools["maven"]
		_, gradle := unmarshalled.Tools["gradle"]
		java := unmarshalled.Tools["jdk"]

		for _, image := range mavenImages {
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
				return reconcile.Result{}, nil
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
	if os.Getenv(artifactbuild.DeleteTaskRunPodsEnv) == "1" {
		msg := "pruning analysis pipelinerun %s:%s for dependencybuild %s:%s whose state is %s sas part of jvm-build-service's attempt to not violate pod quota"
		r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name, db.Namespace, db.Name, db.Status.State)
		log.Info(fmt.Sprintf(msg, pr.Namespace, pr.Name, db.Namespace, db.Name, db.Status.State))
		err := r.client.Delete(ctx, pr)
		if err != nil {
			return reconcile.Result{}, err
		}
	}
	return reconcile.Result{}, nil
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
	configMap := v1.ConfigMap{}
	err := r.client.Get(ctx, types.NamespacedName{Namespace: configmap.SystemConfigMapNamespace, Name: configmap.SystemConfigMapName}, &configMap)
	if err != nil {
		return nil, err
	}
	result := []BuilderImage{}
	names := strings.Split(configMap.Data[configmap.SystemBuilderImages], ",")
	for _, i := range names {
		image := configMap.Data[fmt.Sprintf(configmap.SystemBuilderImageFormat, i)]
		tags := configMap.Data[fmt.Sprintf(configmap.SystemBuilderTagFormat, i)]
		if image == "" {
			log.Info(fmt.Sprintf("Missing system config for builder image %s, image will not be usable", image))
		} else if tags == "" {
			log.Info(fmt.Sprintf("Missing tag system config for builder image %s, image will not be usable", image))
		} else {
			tagList := strings.Split(tags, ",")
			image := BuilderImage{Image: image, Tools: map[string][]string{}}
			for _, tag := range tagList {
				split := strings.Split(tag, ":")
				key := split[0]
				val := split[1]
				image.Tools[key] = append(image.Tools[key], strings.Split(val, ";")...)
			}

			result = append(result, image)
		}
	}
	return result, nil
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

func (r *ReconcileDependencyBuild) decodeBytesToTaskRun(bytes []byte) (*pipelinev1beta1.TaskRun, error) {
	decodingScheme := runtime.NewScheme()
	utilruntime.Must(pipelinev1beta1.AddToScheme(decodingScheme))
	decoderCodecFactory := serializer.NewCodecFactory(decodingScheme)
	decoder := decoderCodecFactory.UniversalDecoder(pipelinev1beta1.SchemeGroupVersion)
	taskRun := pipelinev1beta1.TaskRun{}
	err := runtime.DecodeInto(decoder, bytes, &taskRun)
	return &taskRun, err
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

	image := os.Getenv("JVM_BUILD_SERVICE_SIDECAR_IMAGE")
	var taskRunBytes []byte
	switch {
	case db.Status.CurrentBuildRecipe.Maven:
		taskRunBytes = []byte(maven)
	case db.Status.CurrentBuildRecipe.Gradle:
		taskRunBytes = []byte(gradle)
	default:
		r.eventRecorder.Eventf(db, v1.EventTypeWarning, "MissingRecipeType", "recipe for DependencyBuild %s:%s neither maven or gradle", db.Namespace, db.Name)
		return reconcile.Result{}, fmt.Errorf("recipe for DependencyBuild %s:%s neither maven or gradle", db.Namespace, db.Name)
	}
	taskRun, err := r.decodeBytesToTaskRun(taskRunBytes)
	if err != nil {
		return reconcile.Result{}, err
	}

	pr.Spec.PipelineRef = nil
	pr.Spec.PipelineSpec = &pipelinev1beta1.PipelineSpec{
		Results: []pipelinev1beta1.PipelineResult{},
		Tasks: []pipelinev1beta1.PipelineTask{
			{
				Name: artifactbuild.TaskName,
				TaskSpec: &pipelinev1beta1.EmbeddedTask{
					TaskSpec: *taskRun.Spec.TaskSpec,
				},
				Params: []pipelinev1beta1.Param{},
			},
		},
	}
	for _, i := range taskRun.Spec.TaskSpec.Results {
		pr.Spec.PipelineSpec.Results = append(pr.Spec.PipelineSpec.Results, pipelinev1beta1.PipelineResult{Name: i.Name, Description: i.Description, Value: "$(tasks." + artifactbuild.TaskName + ".results." + i.Name + ")"})
	}
	for _, i := range taskRun.Spec.TaskSpec.Params {
		pr.Spec.PipelineSpec.Params = append(pr.Spec.PipelineSpec.Params, pipelinev1beta1.ParamSpec{Name: i.Name, Description: i.Description, Default: i.Default})
		if i.Type == pipelinev1beta1.ParamTypeString {
			pr.Spec.PipelineSpec.Tasks[0].Params = append(pr.Spec.PipelineSpec.Tasks[0].Params, pipelinev1beta1.Param{Name: i.Name, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: "$(params." + i.Name + ")"}})
		} else {
			//TODO: this should really be pulled from the pipelines params but it does not seem to work
			pr.Spec.PipelineSpec.Tasks[0].Params = append(pr.Spec.PipelineSpec.Tasks[0].Params, pipelinev1beta1.Param{Name: i.Name, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeArray, ArrayVal: db.Status.CurrentBuildRecipe.CommandLine}})
		}
	}
	pr.Spec.PipelineSpec.Tasks[0].TaskSpec.Sidecars = []pipelinev1beta1.Sidecar{createSidecar(image, db.Namespace)}
	pr.Spec.ServiceAccountName = "pipeline"
	//TODO: this is all going away, but for now we have lost the ability to confiugure this via YAML
	//It's not worth adding a heap of env var overrides for something that will likely be gone next week
	//the actual solution will involve loading deployment config from a ConfigMap
	pr.Spec.ServiceAccountName = "pipeline"
	pr.Spec.Params = []pipelinev1beta1.Param{
		{Name: PipelineScmUrl, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.SCMURL}},
		{Name: PipelineScmTag, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.Tag}},
		{Name: PipelinePath, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.Path}},
		{Name: PipelineImage, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Status.CurrentBuildRecipe.Image}},
		{Name: PipelineGoals, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeArray, ArrayVal: db.Status.CurrentBuildRecipe.CommandLine}},
		{Name: PipelineEnforceVersion, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Status.CurrentBuildRecipe.EnforceVersion}},
		{Name: PipelineIgnoredArtifacts, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: strings.Join(db.Status.CurrentBuildRecipe.IgnoredArtifacts, ",")}},
		{Name: PipelineToolVersion, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Status.CurrentBuildRecipe.ToolVersion}},
	}
	pr.Spec.PipelineSpec.Workspaces = []pipelinev1beta1.PipelineWorkspaceDeclaration{{Name: "source"}, {Name: "maven-settings"}}
	pr.Spec.Workspaces = []pipelinev1beta1.WorkspaceBinding{
		{Name: "maven-settings", EmptyDir: &v1.EmptyDirVolumeSource{}},
		{Name: "source", EmptyDir: &v1.EmptyDirVolumeSource{}},
	}
	pr.Spec.PipelineSpec.Tasks[0].Workspaces = []pipelinev1beta1.WorkspacePipelineTaskBinding{
		{Name: "maven-settings", Workspace: "maven-settings"},
		{Name: "source", Workspace: "source"}}
	pr.Spec.Timeout = &v12.Duration{Duration: time.Hour * 3}
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

	return reconcile.Result{}, nil
}

func currentDependencyBuildPipelineName(db *v1alpha1.DependencyBuild) string {
	return fmt.Sprintf("%s-build-%d", db.Name, len(db.Status.FailedBuildRecipes))
}

func (r *ReconcileDependencyBuild) handlePipelineRunReceived(ctx context.Context, log logr.Logger, pr *pipelinev1beta1.PipelineRun) (reconcile.Result, error) {
	if pr.Status.CompletionTime != nil {
		// get db
		ownerRefs := pr.GetOwnerReferences()
		if len(ownerRefs) == 0 {
			msg := "pipelinerun missing onwerrefs %s:%s"
			r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name)
			log.Info(msg, pr.Namespace, pr.Name)

			if os.Getenv(artifactbuild.DeleteTaskRunPodsEnv) == "1" {
				msg := "pruning build pipelinerun %s:%s for dependencybuild as it is missing owner refs"
				r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name)
				log.Info(fmt.Sprintf(msg, pr.Namespace, pr.Name))
				delerr := r.client.Delete(ctx, pr)
				if delerr != nil {
					return reconcile.Result{}, delerr
				}
			}
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

			if os.Getenv(artifactbuild.DeleteTaskRunPodsEnv) == "1" {
				msg := "pruning build pipelinerun %s:%s for dependencybuild as it is missing owner refs"
				r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name)
				log.Info(fmt.Sprintf(msg, pr.Namespace, pr.Name))
				delerr := r.client.Delete(ctx, pr)
				if delerr != nil {
					return reconcile.Result{}, delerr
				}
			}
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
			//prune if required
			if os.Getenv(artifactbuild.DeleteTaskRunPodsEnv) == "1" {
				msg := "pruning old build pipelinerun %s:%s from previous run for dependencybuild %s:%s whose state is %s sas part of jvm-build-service's attempt to not violate pod quota"
				r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name, db.Namespace, db.Name, db.Status.State)
				log.Info(fmt.Sprintf(msg, pr.Namespace, pr.Name, db.Namespace, db.Name, db.Status.State))
				delerr := r.client.Delete(ctx, pr)
				if delerr != nil {
					return reconcile.Result{}, delerr
				}
			}
			return reconcile.Result{}, nil
		}
		db.Status.LastCompletedBuildPipelineRun = pr.Name
		//the pr is done, lets potentially update the dependency build
		//we just set the state here, the ABR logic is in the ABR controller
		//this keeps as much of the logic in one place as possible

		var contaminates []string
		for _, r := range pr.Status.PipelineResults {
			if r.Name == "contaminants" && len(r.Value) > 0 {
				contaminates = strings.Split(r.Value, ",")
			}
		}
		success := pr.Status.GetCondition(apis.ConditionSucceeded).IsTrue()
		if success {
			if len(contaminates) == 0 {
				db.Status.State = v1alpha1.DependencyBuildStateComplete
			} else {
				r.eventRecorder.Eventf(&db, v1.EventTypeWarning, "BuildContaminated", "The DependencyBuild %s/%s was contaminated with community dependencies", db.Namespace, db.Name)
				//the dependency was contaminated with community deps
				//most likely shaded in
				db.Status.State = v1alpha1.DependencyBuildStateContaminated
				db.Status.Contaminants = contaminates
			}
		} else {
			//try again, if there are no more recipes this gets handled in the submit build logic
			db.Status.State = v1alpha1.DependencyBuildStateSubmitBuild
		}
		err = r.client.Status().Update(ctx, &db)
		if err != nil {
			return reconcile.Result{}, err
		}

		if os.Getenv(artifactbuild.DeleteTaskRunPodsEnv) == "1" {
			msg := "pruning build pipelinerun %s:%s for dependencybuild %s:%s whose state is %s sas part of jvm-build-service's attempt to not violate pod quota"
			r.eventRecorder.Eventf(pr, v1.EventTypeWarning, msg, pr.Namespace, pr.Name, db.Namespace, db.Name, db.Status.State)
			log.Info(fmt.Sprintf(msg, pr.Namespace, pr.Name, db.Namespace, db.Name, db.Status.State))
			delerr := r.client.Delete(ctx, pr)
			if delerr != nil {
				return reconcile.Result{}, delerr
			}
		}
	}
	return reconcile.Result{}, nil
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
	//we want to rebuild the contaminants from source
	//so we create ABRs for them
	//if they already exist we link to the ABR
	for _, contaminant := range contaminants {
		if len(contaminant) == 0 {
			continue
		}
		abrName := artifactbuild.CreateABRName(contaminant)
		abr := v1alpha1.ArtifactBuild{}
		//look for existing ABR
		err := r.client.Get(ctx, types.NamespacedName{Name: abrName, Namespace: db.Namespace}, &abr)
		suffix := hashToString(contaminant)[0:20]
		if err != nil {
			//we just assume this is because it does not exist
			//TODO: how to check the type of the error?
			abr.Spec = v1alpha1.ArtifactBuildSpec{GAV: contaminant}
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
	}
	return reconcile.Result{}, nil
}

func createLookupBuildInfoPipeline(build *v1alpha1.DependencyBuildSpec, config map[string]string) *pipelinev1beta1.PipelineSpec {
	image := os.Getenv("JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE")
	recipes := os.Getenv("RECIPE_DATABASE")
	additional, ok := config[configmap.UserConfigAdditionalRecipes]
	if ok {
		recipes = recipes + "," + additional
	}
	path := build.ScmInfo.Path
	//TODO should the buidl request process require context to be set ?
	if len(path) == 0 {
		path = "."
	}
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
								},
							},
						},
					},
				},
			},
		},
	}
}

type BuilderImage struct {
	Image string
	Tools map[string][]string
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
				{Name: "QUARKUS_LOG_FILE_PATH", Value: "$(workspaces.maven-settings.path)/sidecar.log"},
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
				{Name: "$(workspaces.maven-settings.volume)", MountPath: "$(workspaces.maven-settings.path)"}},
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
				Requests: map[v1.ResourceName]resource.Quantity{"memory": resource.MustParse("128Mi"), "cpu": resource.MustParse("10m")},
				Limits:   map[v1.ResourceName]resource.Quantity{"memory": resource.MustParse("8Gi"), "cpu": resource.MustParse("2")}},
			SecurityContext: &v1.SecurityContext{RunAsUser: &zero},
		}}

	if !strings.HasPrefix(image, "quay.io/redhat-appstudio") {
		// work around for developer mode while we are hard coding the task spec in the controller
		sidecar.ImagePullPolicy = v1.PullAlways
	}
	return sidecar
}
