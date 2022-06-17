package dependencybuild

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"fmt"
	"os"
	"strings"
	"time"

	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
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

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout       = 300 * time.Second
	TaskScmUrl           = "URL"
	TaskScmTag           = "TAG"
	TaskPath             = "CONTEXT_DIR"
	TaskImage            = "IMAGE"
	TaskGoals            = "GOALS"
	TaskEnforceVersion   = "ENFORCE_VERSION"
	TaskIgnoredArtifacts = "IGNORED_ARTIFACTS"
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

	tr := pipelinev1beta1.TaskRun{}
	trerr := r.client.Get(ctx, request.NamespacedName, &tr)
	if trerr != nil {
		if !errors.IsNotFound(trerr) {
			log.Error(trerr, "Reconcile key %s as taskrun unexpected error", request.NamespacedName.String())
			return ctrl.Result{}, trerr
		}
	}

	if trerr != nil && dberr != nil {
		//TODO weird - during envtest the logging code panicked on the commented out log.Info call: 'com.acme.example.1.0-scm-discovery-5vjvmpanic: odd number of arguments passed as key-value pairs for logging'
		msg := "Reconcile key %s received not found errors for both taskruns and dependencybuilds (probably deleted)\"" + request.NamespacedName.String()
		log.Info(msg)
		//log.Info("Reconcile key %s received not found errors for taskruns, dependencybuilds, artifactbuilds (probably deleted)", request.NamespacedName.String())
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
		return r.handleTaskRunReceived(ctx, &tr)
	}

	return reconcile.Result{}, nil
}

func hashToString(unique string) string {
	hash := md5.Sum([]byte(unique))
	depId := hex.EncodeToString(hash[:])
	return depId
}

func (r *ReconcileDependencyBuild) handleStateNew(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	//TODO: this is currently a huge hard coded hack
	//we hard code 3 potential build recipes (images)
	//then move the state to DependencyBuildStateDetect
	//once this is not longer a hard coded stub it should trigger a TR/PR
	//that looks at the repository and figures out which builder to use
	db.Status.PotentialBuildRecipes = db.Spec.BuildRecipes
	db.Status.State = v1alpha1.DependencyBuildStateSubmitBuild
	return reconcile.Result{}, r.client.Status().Update(ctx, db)
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

func (r *ReconcileDependencyBuild) handleStateBuilding(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	//now submit the pipeline
	tr := pipelinev1beta1.TaskRun{}
	tr.Namespace = db.Namespace
	// we do not use generate name since a) it was used in creating the db and the db name has random ids b) there is a 1 to 1 relationship (but also consider potential recipe retry)
	// c) it allows us to use the already exist error on create to short circuit the creation of dbs if owner refs updates to the db before
	// we move the db out of building
	tr.Name = fmt.Sprintf("%s-build-%d", db.Name, len(db.Status.FailedBuildRecipes))
	tr.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: db.Labels[artifactbuild.DependencyBuildIdLabel], artifactbuild.TaskRunLabel: ""}
	tr.Spec.TaskRef = &pipelinev1beta1.TaskRef{Name: "run-maven-component-build", Kind: pipelinev1beta1.ClusterTaskKind}
	tr.Spec.Params = []pipelinev1beta1.Param{
		{Name: TaskScmUrl, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.SCMURL}},
		{Name: TaskScmTag, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.Tag}},
		{Name: TaskPath, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.ScmInfo.Path}},
		{Name: TaskImage, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Status.CurrentBuildRecipe.Image}},
		{Name: TaskGoals, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeArray, ArrayVal: db.Status.CurrentBuildRecipe.CommandLine}},
		{Name: TaskEnforceVersion, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Status.CurrentBuildRecipe.EnforceVersion}},
		{Name: TaskIgnoredArtifacts, Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: strings.Join(db.Status.CurrentBuildRecipe.IgnoredArtifacts, ",")}},
	}
	tr.Spec.Workspaces = []pipelinev1beta1.WorkspaceBinding{
		{Name: "maven-settings", EmptyDir: &v1.EmptyDirVolumeSource{}},
		{Name: "source", EmptyDir: &v1.EmptyDirVolumeSource{}},
	}
	tr.Spec.Timeout = &v12.Duration{Duration: time.Hour * 3}
	if err := controllerutil.SetOwnerReference(db, &tr, r.scheme); err != nil {
		return reconcile.Result{}, err
	}
	//now we submit the build
	if err := r.client.Create(ctx, &tr); err != nil {
		if errors.IsAlreadyExists(err) {
			log.V(4).Info("handleStateBuilding: taskrun %s:%s already exists, not retrying", tr.Namespace, tr.Name)
			return reconcile.Result{}, nil
		}
		r.eventRecorder.Eventf(db, v1.EventTypeWarning, "TaskRunCreationFailed", "The DependencyBuild %s/%s failed to create its build pipeline run", db.Namespace, db.Name)
		return reconcile.Result{}, err
	}

	return reconcile.Result{}, nil
}

func (r *ReconcileDependencyBuild) handleTaskRunReceived(ctx context.Context, tr *pipelinev1beta1.TaskRun) (reconcile.Result, error) {
	if tr.Status.CompletionTime != nil {
		// get db
		ownerRefs := tr.GetOwnerReferences()
		if ownerRefs == nil || len(ownerRefs) == 0 {
			msg := "taskrun missing onwerrefs %s:%s"
			r.eventRecorder.Eventf(tr, v1.EventTypeWarning, msg, tr.Namespace, tr.Name)
			log.Info(msg, tr.Namespace, tr.Name)
			return reconcile.Result{}, nil
		}
		if len(ownerRefs) > 1 {
			// workaround for event/logging methods that can only take string args
			count := fmt.Sprintf("%d", len(ownerRefs))
			msg := "taskrun %s:%s has %s ownerrefs but only using the first dependencybuild ownerfef"
			r.eventRecorder.Eventf(tr, v1.EventTypeWarning, msg, tr.Namespace, tr.Name, count)
			log.Info(msg, tr.Namespace, tr.Name, count)
		}
		// even though we filter out artifactbuild taskruns, let's check the kind and make sure
		// we use a dependencybuild ownerref
		var ownerRef *v12.OwnerReference
		for _, or := range ownerRefs {
			if strings.EqualFold(or.Kind, "dependencybuild") || strings.EqualFold(or.Kind, "dependencybuilds") {
				ownerRef = &or
			}
		}
		if ownerRef == nil {
			msg := "taskrun missing dependencybuild onwerrefs %s:%s"
			r.eventRecorder.Eventf(tr, v1.EventTypeWarning, msg, tr.Namespace, tr.Name)
			log.Info(msg, tr.Namespace, tr.Name)
			return reconcile.Result{}, nil
		}

		key := types.NamespacedName{Namespace: tr.Namespace, Name: ownerRef.Name}
		db := v1alpha1.DependencyBuild{}
		err := r.client.Get(ctx, key, &db)
		if err != nil {
			msg := "get for taskrun %s:%s owning db %s:%s yielded error %s"
			r.eventRecorder.Eventf(tr, v1.EventTypeWarning, msg, tr.Namespace, tr.Name, tr.Namespace, ownerRef.Name, err.Error())
			log.Error(err, fmt.Sprintf(msg, tr.Namespace, tr.Name, tr.Namespace, ownerRef.Name, err.Error()))
			return reconcile.Result{}, err
		}

		if tr.Name == db.Status.LastCompletedBuildTaskRun {
			//already handled
			return reconcile.Result{}, nil
		}
		db.Status.LastCompletedBuildTaskRun = tr.Name
		//the pr is done, lets potentially update the dependency build
		//we just set the state here, the ABR logic is in the ABR controller
		//this keeps as much of the logic in one place as possible

		var contaminates []string
		for _, r := range tr.Status.TaskRunResults {
			if r.Name == "contaminants" && len(r.Value) > 0 {
				contaminates = strings.Split(r.Value, ",")
			}
		}
		success := tr.Status.GetCondition(apis.ConditionSucceeded).IsTrue()
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
			pod := v1.Pod{}
			poderr := r.client.Get(ctx, types.NamespacedName{Namespace: tr.Namespace, Name: tr.Status.PodName}, &pod)
			if poderr == nil {
				r.client.Delete(ctx, &pod)
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
