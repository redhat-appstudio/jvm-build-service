package dependencybuild

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
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

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
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

var (
	log = ctrl.Log.WithName("dependencybuild")
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
	ctx, cancel := context.WithTimeout(ctx, contextTimeout)
	defer cancel()

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
		msg := "Reconcile key %s received not found errors for both pipelineruns and dependencybuilds (probably deleted)\"" + request.NamespacedName.String()
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
			return r.handleStateBuilding(ctx, &db)
		case v1alpha1.DependencyBuildStateContaminated:
			return r.handleStateContaminated(ctx, &db)
		}

	case trerr == nil:
		pipelineType := pr.Labels[PipelineType]
		switch pipelineType {
		case PipelineTypeBuildInfo:
			return r.handleStateAnalyzeBuild(ctx, &pr)
		case PipelineTypeBuild:
			return r.handlePipelineRunReceived(ctx, &pr)
		}
	}

	return reconcile.Result{}, nil
}

func hashToString(unique string) string {
	hash := md5.Sum([]byte(unique))
	depId := hex.EncodeToString(hash[:])
	return depId
}

func (r *ReconcileDependencyBuild) handleStateNew(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	// create pipeline run
	tr := pipelinev1beta1.PipelineRun{}
	tr.Spec.PipelineSpec = createLookupBuildInfoPipeline(&db.Spec)
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

func (r *ReconcileDependencyBuild) handleStateAnalyzeBuild(ctx context.Context, pr *pipelinev1beta1.PipelineRun) (reconcile.Result, error) {
	if pr.Status.CompletionTime == nil {
		return reconcile.Result{}, nil
	}
	ownerRefs := pr.GetOwnerReferences()
	if ownerRefs == nil || len(ownerRefs) == 0 {
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
	if os.Getenv(artifactbuild.DeleteTaskRunPodsEnv) == "1" {
		pod := v1.Pod{}
		poderr := r.client.Get(ctx, types.NamespacedName{Namespace: pr.Namespace, Name: pr.Status.TaskRuns[artifactbuild.TaskName].Status.PodName}, &pod)
		if poderr == nil {
			err := r.client.Delete(ctx, &pod)
			if err != nil {
				return reconcile.Result{}, err
			}
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
		}{}

		if err := json.Unmarshal([]byte(buildInfo), &unmarshalled); err != nil {
			r.eventRecorder.Eventf(&db, v1.EventTypeWarning, "InvalidJson", "Failed to unmarshal build info for AB %s/%s JSON: %s", db.Namespace, db.Name, buildInfo)
			return reconcile.Result{}, err
		}
		//for now we are ignoring the tool versions
		//and just using the supplied invocations
		buildRecipes := []*v1alpha1.BuildRecipe{}
		_, maven := unmarshalled.Tools["maven"]
		_, gradle := unmarshalled.Tools["gradle"]
		for _, image := range []string{"quay.io/sdouglas/hacbs-jdk11-builder:latest", "quay.io/sdouglas/hacbs-jdk8-builder:latest", "quay.io/sdouglas/hacbs-jdk17-builder:latest"} {
			for _, command := range unmarshalled.Invocations {
				if maven {
					buildRecipes = append(buildRecipes, &v1alpha1.BuildRecipe{Maven: true, Image: image, CommandLine: command, EnforceVersion: unmarshalled.EnforceVersion, IgnoredArtifacts: unmarshalled.IgnoredArtifacts})
				}
				if gradle {
					buildRecipes = append(buildRecipes, &v1alpha1.BuildRecipe{Gradle: true, Image: image, CommandLine: command, EnforceVersion: unmarshalled.EnforceVersion, IgnoredArtifacts: unmarshalled.IgnoredArtifacts})
				}
			}
		}
		db.Status.PotentialBuildRecipes = buildRecipes
		db.Status.State = v1alpha1.DependencyBuildStateSubmitBuild
	}
	return reconcile.Result{}, r.client.Status().Update(ctx, &db)

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

func (r *ReconcileDependencyBuild) decodeBytesToPipelineRun(bytes []byte) (*pipelinev1beta1.TaskRun, error) {
	decodingScheme := runtime.NewScheme()
	utilruntime.Must(pipelinev1beta1.AddToScheme(decodingScheme))
	decoderCodecFactory := serializer.NewCodecFactory(decodingScheme)
	decoder := decoderCodecFactory.UniversalDecoder(pipelinev1beta1.SchemeGroupVersion)
	taskRun := pipelinev1beta1.TaskRun{}
	err := runtime.DecodeInto(decoder, bytes, &taskRun)
	return &taskRun, err
}

func (r *ReconcileDependencyBuild) handleStateBuilding(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	//now submit the pipeline
	pr := pipelinev1beta1.PipelineRun{}
	pr.Namespace = db.Namespace
	// we do not use generate name since a) it was used in creating the db and the db name has random ids b) there is a 1 to 1 relationship (but also consider potential recipe retry)
	// c) it allows us to use the already exist error on create to short circuit the creation of dbs if owner refs updates to the db before
	// we move the db out of building
	pr.Name = currentDependencyBuildPipelineName(db)
	pr.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: db.Labels[artifactbuild.DependencyBuildIdLabel], artifactbuild.PipelineRunLabel: "", PipelineType: PipelineTypeBuild}

	image := os.Getenv("JVM_BUILD_SERVICE_SIDECAR_IMAGE")
	pipelineRunBytes := []byte{}
	switch {
	case db.Status.CurrentBuildRecipe.Maven:
		pipelineRunBytes = []byte(maven)
	case db.Status.CurrentBuildRecipe.Gradle:
		pipelineRunBytes = []byte(gradle)
	default:
		r.eventRecorder.Eventf(db, v1.EventTypeWarning, "MissingRecipeType", "recipe for DependencyBuild %s:%s neither maven or gradle", db.Namespace, db.Name)
		return reconcile.Result{}, fmt.Errorf("recipe for DependencyBuild %s:%s neither maven or gradle", db.Namespace, db.Name)
	}
	taskRun, err := r.decodeBytesToPipelineRun(pipelineRunBytes)
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
	pr.Spec.PipelineSpec.Tasks[0].TaskSpec.Sidecars[0].Image = image
	//TODO: this is all going away, but for now we have lost the ability to confiugure this via YAML
	//It's not worth adding a heap of env var overrides for something that will likely be gone next week
	//the actual solution will involve loading deployment config from a ConfigMap
	pr.Spec.PipelineSpec.Tasks[0].TaskSpec.Sidecars[0].Env = append(pr.Spec.PipelineSpec.Tasks[0].TaskSpec.Sidecars[0].Env, v1.EnvVar{Name: "QUARKUS_S3_ENDPOINT_OVERRIDE", Value: "http://localstack.jvm-build-service.svc.cluster.local:4572"})
	pr.Spec.PipelineSpec.Tasks[0].TaskSpec.Sidecars[0].Env = append(pr.Spec.PipelineSpec.Tasks[0].TaskSpec.Sidecars[0].Env, v1.EnvVar{Name: "QUARKUS_S3_AWS_REGION", Value: "us-east-1"})
	pr.Spec.PipelineSpec.Tasks[0].TaskSpec.Sidecars[0].Env = append(pr.Spec.PipelineSpec.Tasks[0].TaskSpec.Sidecars[0].Env, v1.EnvVar{Name: "QUARKUS_S3_AWS_CREDENTIALS_TYPE", Value: "static"})
	pr.Spec.PipelineSpec.Tasks[0].TaskSpec.Sidecars[0].Env = append(pr.Spec.PipelineSpec.Tasks[0].TaskSpec.Sidecars[0].Env, v1.EnvVar{Name: "QUARKUS_S3_AWS_CREDENTIALS_STATIC_PROVIDER_ACCESS_KEY_ID", Value: "accesskey"})
	pr.Spec.PipelineSpec.Tasks[0].TaskSpec.Sidecars[0].Env = append(pr.Spec.PipelineSpec.Tasks[0].TaskSpec.Sidecars[0].Env, v1.EnvVar{Name: "QUARKUS_S3_AWS_CREDENTIALS_STATIC_PROVIDER_SECRET_ACCESS_KEY", Value: "secretkey"})
	pr.Spec.Params = []pipelinev1beta1.Param{
		{Name: PipelineScmUrl, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.SCMURL}},
		{Name: PipelineScmTag, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.Tag}},
		{Name: PipelinePath, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.Path}},
		{Name: PipelineImage, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Status.CurrentBuildRecipe.Image}},
		{Name: PipelineGoals, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeArray, ArrayVal: db.Status.CurrentBuildRecipe.CommandLine}},
		{Name: PipelineEnforceVersion, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Status.CurrentBuildRecipe.EnforceVersion}},
		{Name: PipelineIgnoredArtifacts, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: strings.Join(db.Status.CurrentBuildRecipe.IgnoredArtifacts, ",")}},
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

func (r *ReconcileDependencyBuild) handlePipelineRunReceived(ctx context.Context, pr *pipelinev1beta1.PipelineRun) (reconcile.Result, error) {
	if pr.Status.CompletionTime != nil {
		// get db
		ownerRefs := pr.GetOwnerReferences()
		if ownerRefs == nil || len(ownerRefs) == 0 {
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
		for _, or := range ownerRefs {
			if strings.EqualFold(or.Kind, "dependencybuild") || strings.EqualFold(or.Kind, "dependencybuilds") {
				ownerRef = &or
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
		if os.Getenv(artifactbuild.DeleteTaskRunPodsEnv) == "1" {
			delerr := r.client.Delete(ctx, pr)
			if delerr != nil {
				return reconcile.Result{}, delerr
			}
		}
		return reconcile.Result{}, r.client.Status().Update(ctx, &db)
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

func createLookupBuildInfoPipeline(build *v1alpha1.DependencyBuildSpec) *pipelinev1beta1.PipelineSpec {
	image := os.Getenv("JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE")
	recipes := os.Getenv("RECIPE_DATABASE")
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
