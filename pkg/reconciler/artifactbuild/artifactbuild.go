package artifactbuild

import (
	"context"
	"crypto/md5"
	"crypto/sha1"
	"encoding/hex"
	"fmt"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/configmap"
	"os"
	"strings"
	"time"
	"unicode"

	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout   = 300 * time.Second
	PipelineRunLabel = "jvmbuildservice.io/pipelinerun"
	// DependencyBuildContaminatedBy label prefix that indicates that a dependency build was contaminated by this artifact
	DependencyBuildContaminatedBy = "jvmbuildservice.io/contaminated-"
	DependencyBuildIdLabel        = "jvmbuildservice.io/dependencybuild-id"
	ArtifactBuildIdLabel          = "jvmbuildservice.io/abr-id"
	PipelineResultScmUrl          = "scm-url"
	PipelineResultScmTag          = "scm-tag"
	PipelineResultScmType         = "scm-type"
	PipelineResultContextPath     = "context"
	PipelineResultMessage         = "message"
	DeleteTaskRunPodsEnv          = "JVM_DELETE_TASKRUN_PODS"
	TaskName                      = "task"
)

var (
	log = ctrl.Log.WithName("artifactbuild")
)

type ReconcileArtifactBuild struct {
	client        client.Client
	scheme        *runtime.Scheme
	eventRecorder record.EventRecorder
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcileArtifactBuild{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("ArtifactBuild"),
	}
}

func (r *ReconcileArtifactBuild) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	ctx, cancel := context.WithTimeout(ctx, contextTimeout)
	defer cancel()
	abr := v1alpha1.ArtifactBuild{}
	abrerr := r.client.Get(ctx, request.NamespacedName, &abr)
	if abrerr != nil {
		if !errors.IsNotFound(abrerr) {
			log.Error(abrerr, "Reconcile key %s as artifactbuild unexpected error", request.NamespacedName.String())
			return ctrl.Result{}, abrerr
		}
	}

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

	if trerr != nil && dberr != nil && abrerr != nil {
		//TODO weird - during envtest the logging code panicked on the commented out log.Info call: 'com.acme.example.1.0-scm-discovery-5vjvmpanic: odd number of arguments passed as key-value pairs for logging'
		msg := "Reconcile key received not found errors for pipelineruns, dependencybuilds, artifactbuilds (probably deleted): " + request.NamespacedName.String()
		log.Info(msg)
		//log.Info("Reconcile key %s received not found errors for pipelineruns, dependencybuilds, artifactbuilds (probably deleted)", request.NamespacedName.String())
		return ctrl.Result{}, nil
	}

	switch {
	case dberr == nil:
		return r.handleDependencyBuildReceived(ctx, &db)

	case trerr == nil:
		return r.handlePipelineRunReceived(ctx, &pr)

	case abrerr == nil:
		switch abr.Status.State {
		case v1alpha1.ArtifactBuildStateNew, "":
			return r.handleStateNew(ctx, &abr)
		case v1alpha1.ArtifactBuildStateDiscovering:
			return r.handleStateDiscovering(ctx, &abr)
		case v1alpha1.ArtifactBuildStateComplete:
			return r.handleStateComplete(ctx, &abr)
		case v1alpha1.ArtifactBuildStateBuilding:
			return r.handleStateBuilding(ctx, &abr)
		}
	}

	return reconcile.Result{}, nil
}

func (r *ReconcileArtifactBuild) handlePipelineRunReceived(ctx context.Context, pr *pipelinev1beta1.PipelineRun) (reconcile.Result, error) {
	if pr.Status.CompletionTime == nil {
		return reconcile.Result{}, nil
	}
	ownerRefs := pr.GetOwnerReferences()
	if ownerRefs == nil || len(ownerRefs) == 0 {
		msg := "pipelinerun missing onwerrefs %s:%s"
		r.eventRecorder.Eventf(pr, corev1.EventTypeWarning, msg, pr.Namespace, pr.Name)
		log.Info(msg, pr.Namespace, pr.Name)
		return reconcile.Result{}, nil
	}
	ownerName := ""
	for _, ownerRef := range ownerRefs {
		if strings.EqualFold(ownerRef.Kind, "artifactbuild") || strings.EqualFold(ownerRef.Kind, "artifactbuilds") {
			ownerName = ownerRef.Name
			break
		}
	}
	if len(ownerName) == 0 {
		msg := "pipelinerun missing artifactbuilds ownerrefs %s:%s"
		r.eventRecorder.Eventf(pr, corev1.EventTypeWarning, "MissingOwner", msg, pr.Namespace, pr.Name)
		log.Info(msg, pr.Namespace, pr.Name)
		return reconcile.Result{}, nil
	}

	key := types.NamespacedName{Namespace: pr.Namespace, Name: ownerName}
	abr := v1alpha1.ArtifactBuild{}
	err := r.client.Get(ctx, key, &abr)
	if err != nil {
		msg := "get for pipelinerun %s:%s owning abr %s:%s yielded error %s"
		r.eventRecorder.Eventf(pr, corev1.EventTypeWarning, msg, pr.Namespace, pr.Name, pr.Namespace, ownerName, err.Error())
		log.Error(err, fmt.Sprintf(msg, pr.Namespace, pr.Name, pr.Namespace, ownerName, err.Error()))
		return reconcile.Result{}, err
	}

	//we grab the results here and put them on the ABR
	for _, res := range pr.Status.PipelineResults {
		switch res.Name {
		case PipelineResultScmUrl:
			abr.Status.SCMInfo.SCMURL = res.Value
		case PipelineResultScmTag:
			abr.Status.SCMInfo.Tag = res.Value
		case PipelineResultScmType:
			abr.Status.SCMInfo.SCMType = res.Value
		case PipelineResultMessage:
			abr.Status.Message = res.Value
		case PipelineResultContextPath:
			abr.Status.SCMInfo.Path = res.Value
		}
	}
	if os.Getenv(DeleteTaskRunPodsEnv) == "1" {
		delerr := r.client.Delete(ctx, pr)
		if delerr != nil {
			return reconcile.Result{}, delerr
		}
	}

	//now let's create the dependency build object
	//once this object has been created its resolver takes over
	if abr.Status.SCMInfo.Tag == "" {
		//this is a failure
		r.eventRecorder.Eventf(&abr, corev1.EventTypeWarning, "MissingTag", "The ArtifactBuild %s/%s had an empty tag field %s", abr.Namespace, abr.Name, pr.Status.PipelineResults)
		abr.Status.State = v1alpha1.ArtifactBuildStateMissing
		return reconcile.Result{}, r.client.Status().Update(ctx, &abr)
	}
	return reconcile.Result{}, r.client.Status().Update(ctx, &abr)
}

func (r *ReconcileArtifactBuild) handleDependencyBuildReceived(ctx context.Context, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	ownerRefs := db.GetOwnerReferences()
	if ownerRefs == nil || len(ownerRefs) == 0 {
		msg := "dependencybuild missing onwerrefs %s:%s"
		r.eventRecorder.Eventf(db, corev1.EventTypeWarning, msg, db.Namespace, db.Name)
		log.Info(msg, db.Namespace, db.Name)
		return reconcile.Result{}, nil
	}
	ownerName := ""
	for _, ownerRef := range ownerRefs {
		if strings.EqualFold(ownerRef.Kind, "artifactbuild") {
			ownerName = ownerRef.Name
			break
		}
	}
	if len(ownerName) == 0 {
		msg := "dependencybuild missing artifactbuilds owner refs %s:%s"
		r.eventRecorder.Eventf(db, corev1.EventTypeWarning, msg, db.Namespace, db.Name)
		log.Info(msg, db.Namespace, db.Name)
		return reconcile.Result{}, nil
	}

	key := types.NamespacedName{Namespace: db.Namespace, Name: ownerName}
	abr := v1alpha1.ArtifactBuild{}
	err := r.client.Get(ctx, key, &abr)
	if err != nil {
		msg := "get for dependencybuild %s:%s owning abr %s:%s yielded error %s"
		r.eventRecorder.Eventf(db, corev1.EventTypeWarning, msg, db.Namespace, db.Name, db.Namespace, ownerName, err.Error())
		log.Error(err, fmt.Sprintf(msg, db.Namespace, db.Name, db.Namespace, ownerName, err.Error()))
		return reconcile.Result{}, err
	}
	if db.Status.State == v1alpha1.DependencyBuildStateFailed ||
		db.Status.State == v1alpha1.DependencyBuildStateContaminated {
		abr.Status.State = v1alpha1.ArtifactBuildStateFailed
	} else if db.Status.State == v1alpha1.DependencyBuildStateComplete {
		abr.Status.State = v1alpha1.ArtifactBuildStateComplete
		return reconcile.Result{Requeue: true}, r.client.Status().Update(ctx, &abr)
	}

	// if need be
	return reconcile.Result{}, r.client.Status().Update(ctx, &abr)
}

func (r *ReconcileArtifactBuild) handleStateNew(ctx context.Context, abr *v1alpha1.ArtifactBuild) (reconcile.Result, error) {

	// create pipeline run
	pr := pipelinev1beta1.PipelineRun{}
	pr.GenerateName = abr.Name + "-scm-discovery-"
	pr.Namespace = abr.Namespace
	cm, err := configmap.ReadUserConfigMap(r.client, ctx, abr.Namespace)
	if err != nil {
		return reconcile.Result{}, err
	}
	task := createLookupScmInfoTask(abr.Spec.GAV, cm)
	pr.Spec.PipelineSpec = &pipelinev1beta1.PipelineSpec{
		Tasks: []pipelinev1beta1.PipelineTask{{
			Name: TaskName,
			TaskSpec: &pipelinev1beta1.EmbeddedTask{
				TaskSpec: *task,
			},
		}},
		Results: []pipelinev1beta1.PipelineResult{},
	}
	for _, i := range task.Results {
		pr.Spec.PipelineSpec.Results = append(pr.Spec.PipelineSpec.Results, pipelinev1beta1.PipelineResult{Name: i.Name, Description: i.Description, Value: "$(tasks." + TaskName + ".results." + i.Name + ")"})
	}

	pr.Labels = map[string]string{ArtifactBuildIdLabel: ABRLabelForGAV(abr.Spec.GAV), PipelineRunLabel: ""}
	if err := controllerutil.SetOwnerReference(abr, &pr, r.scheme); err != nil {
		return reconcile.Result{}, err
	}
	abr.Status.State = v1alpha1.ArtifactBuildStateDiscovering
	if err := r.client.Status().Update(ctx, abr); err != nil {
		return reconcile.Result{}, err
	}
	if err := r.client.Create(ctx, &pr); err != nil {
		return reconcile.Result{}, err
	}
	return reconcile.Result{}, nil
}

func (r *ReconcileArtifactBuild) handleStateDiscovering(ctx context.Context, abr *v1alpha1.ArtifactBuild) (reconcile.Result, error) {
	// if pipelinerun to update SCM/Message has not completed, just return
	if len(abr.Status.SCMInfo.SCMURL) == 0 &&
		len(abr.Status.SCMInfo.Tag) == 0 &&
		len(abr.Status.SCMInfo.SCMType) == 0 &&
		len(abr.Status.SCMInfo.Path) == 0 &&
		len(abr.Status.Message) == 0 {
		return reconcile.Result{}, nil
	}
	if len(abr.Status.SCMInfo.SCMURL) == 0 || len(abr.Status.SCMInfo.Tag) == 0 {
		//discovery failed
		abr.Status.State = v1alpha1.ArtifactBuildStateMissing
		return reconcile.Result{}, r.client.Status().Update(ctx, abr)
	}

	//now lets look for an existing dependencybuild object
	depId := hashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
	db := &v1alpha1.DependencyBuild{}
	dbKey := types.NamespacedName{Namespace: abr.Namespace, Name: depId}
	err := r.client.Get(ctx, dbKey, db)

	switch {
	case err == nil:
		//move the state to building
		abr.Status.State = v1alpha1.ArtifactBuildStateBuilding
		//build already exists, add us to the owner references
		found := false
		for _, or := range db.OwnerReferences {
			if or.UID == abr.UID {
				found = true
				break
			}
		}
		if !found {
			if err := controllerutil.SetOwnerReference(abr, db, r.scheme); err != nil {
				return reconcile.Result{}, err
			}
			if err := r.client.Update(ctx, db); err != nil {
				return reconcile.Result{}, err
			}
		}

		//if the build is done update our state accordingly
		switch db.Status.State {
		case v1alpha1.DependencyBuildStateComplete:
			abr.Status.State = v1alpha1.ArtifactBuildStateComplete
		case v1alpha1.DependencyBuildStateContaminated, v1alpha1.DependencyBuildStateFailed:
			abr.Status.State = v1alpha1.ArtifactBuildStateFailed
		}
		if err := r.client.Status().Update(ctx, abr); err != nil {
			return reconcile.Result{}, err
		}
	case errors.IsNotFound(err):
		//move the state to building
		abr.Status.State = v1alpha1.ArtifactBuildStateBuilding

		//no existing build object found, lets create one
		db := &v1alpha1.DependencyBuild{}
		db.Namespace = abr.Namespace
		db.Labels = map[string]string{
			DependencyBuildIdLabel: depId,
		}
		//TODO: do we in fact need to put depId through GenerateName sanitation algorithm for the name? label value restrictions are more stringent than obj name
		db.Name = depId
		if err := controllerutil.SetOwnerReference(abr, db, r.scheme); err != nil {
			return reconcile.Result{}, err
		}
		db.Spec = v1alpha1.DependencyBuildSpec{ScmInfo: v1alpha1.SCMInfo{
			SCMURL:  abr.Status.SCMInfo.SCMURL,
			SCMType: abr.Status.SCMInfo.SCMType,
			Tag:     abr.Status.SCMInfo.Tag,
			Path:    abr.Status.SCMInfo.Path,
		}, Version: abr.Spec.GAV[strings.LastIndex(abr.Spec.GAV, ":")+1:]}
		if err := r.client.Status().Update(ctx, abr); err != nil {
			return reconcile.Result{}, err
		}
		return reconcile.Result{}, r.client.Create(ctx, db)

	default:
		log.Error(err, "for artifactbuild %s:%s", abr.Namespace, abr.Name)
		return reconcile.Result{}, err
	}

	return reconcile.Result{}, nil

}

func hashString(hashInput string) string {
	hash := md5.Sum([]byte(hashInput))
	depId := hex.EncodeToString(hash[:])
	return depId
}
func ABRLabelForGAV(hashInput string) string {
	return hashString(hashInput)
}

func (r *ReconcileArtifactBuild) handleStateComplete(ctx context.Context, abr *v1alpha1.ArtifactBuild) (reconcile.Result, error) {
	for key, value := range abr.Annotations {
		if strings.HasPrefix(key, DependencyBuildContaminatedBy) {
			db := v1alpha1.DependencyBuild{}
			if err := r.client.Get(ctx, types.NamespacedName{Name: value, Namespace: abr.Namespace}, &db); err != nil {
				r.eventRecorder.Eventf(abr, corev1.EventTypeNormal, "CannotGetDependencyBuild", "Could not find the contaminated DependencyBuild for ArtifactBuild %s/%s: %s", abr.Namespace, abr.Name, err.Error())
				//this was not found
				continue
			}
			if db.Status.State != v1alpha1.DependencyBuildStateContaminated {
				continue
			}
			var newContaminates []string
			for _, contaminant := range db.Status.Contaminants {
				if contaminant != abr.Spec.GAV {
					newContaminates = append(newContaminates, contaminant)
				}
			}
			db.Status.Contaminants = newContaminates
			if len(db.Status.Contaminants) == 0 {
				//kick off the build again
				db.Status.State = v1alpha1.DependencyBuildStateNew
			}
			if err := r.client.Status().Update(ctx, &db); err != nil {
				return reconcile.Result{}, err
			}
		}
	}
	return reconcile.Result{}, nil
}

func (r *ReconcileArtifactBuild) handleStateBuilding(ctx context.Context, abr *v1alpha1.ArtifactBuild) (reconcile.Result, error) {
	depId := hashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
	db := &v1alpha1.DependencyBuild{}
	dbKey := types.NamespacedName{Namespace: abr.Namespace, Name: depId}
	err := r.client.Get(ctx, dbKey, db)

	switch {
	case err == nil:
	case errors.IsNotFound(err):
		//we don't have a build for this ABR, this is very odd
		//move back to new and start again
		r.eventRecorder.Eventf(abr, corev1.EventTypeWarning, "MissingDependencyBuild", "The ArtifactBuild %s/%s in state Building was missing a DependencyBuild", abr.Namespace, abr.Name)
		abr.Status.State = v1alpha1.ArtifactBuildStateNew
		return reconcile.Result{}, r.client.Status().Update(ctx, abr)
	default:
		log.Error(err, "for artifactbuild %s:%s", abr.Namespace, abr.Name)
		return reconcile.Result{}, err
	}

	// just in case check owner refs
	found := false
	for _, owner := range db.OwnerReferences {
		if owner.UID == abr.UID {
			found = true
			break
		}
	}
	if !found {
		if err := controllerutil.SetOwnerReference(abr, db, r.scheme); err != nil {
			return reconcile.Result{}, err
		}
		if err := r.client.Update(ctx, db); err != nil {
			return reconcile.Result{}, err
		}
	}

	//if the build is done update our state accordingly
	switch db.Status.State {
	case v1alpha1.DependencyBuildStateComplete:
		abr.Status.State = v1alpha1.ArtifactBuildStateComplete
		return reconcile.Result{}, r.client.Status().Update(ctx, abr)
	case v1alpha1.DependencyBuildStateContaminated, v1alpha1.DependencyBuildStateFailed:
		abr.Status.State = v1alpha1.ArtifactBuildStateFailed
		return reconcile.Result{}, r.client.Status().Update(ctx, abr)
	}
	return reconcile.Result{}, nil
}

func CreateABRName(gav string) string {
	hashedBytes := sha1.Sum([]byte(gav))
	hash := hex.EncodeToString(hashedBytes[:])[0:8]
	namePart := gav[strings.Index(gav, ":")+1:]

	//generate names based on the artifact name + version, and part of a hash
	//we only use the first 8 characters from the hash to make the name small
	var newName = strings.Builder{}
	lastDot := false
	for _, i := range []rune(namePart) {
		if unicode.IsLetter(i) || unicode.IsDigit(i) {
			newName.WriteRune(i)
			lastDot = false
		} else {
			if !lastDot {
				newName.WriteString(".")
			}
			lastDot = true
		}
	}
	newName.WriteString("-")
	newName.WriteString(hash)
	return strings.ToLower(newName.String())
}

func createLookupScmInfoTask(gav string, config map[string]string) *pipelinev1beta1.TaskSpec {
	image := os.Getenv("JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE")
	recipes := os.Getenv("RECIPE_DATABASE")
	additional, ok := config[configmap.UserConfigAdditionalRecipes]
	if ok {
		recipes = recipes + "," + additional
	}

	return &pipelinev1beta1.TaskSpec{
		Results: []pipelinev1beta1.TaskResult{
			{Name: PipelineResultScmUrl},
			{Name: PipelineResultScmTag},
			{Name: PipelineResultScmType},
			{Name: PipelineResultContextPath},
			{Name: PipelineResultMessage},
		},
		Steps: []pipelinev1beta1.Step{
			{
				Container: corev1.Container{
					Name:  "lookup-artifact-location",
					Image: image,
					Args: []string{
						"lookup-scm",
						"--recipes",
						recipes,
						"--scm-url",
						"$(results." + PipelineResultScmUrl + ".path)",
						"--scm-tag",
						"$(results." + PipelineResultScmTag + ".path)",
						"--scm-type",
						"$(results." + PipelineResultScmType + ".path)",
						"--message",
						"$(results." + PipelineResultMessage + ".path)",
						"--context",
						"$(results." + PipelineResultContextPath + ".path)",
						"--gav",
						gav,
					},
				},
			},
		},
	}
}
