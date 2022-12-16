package artifactbuild

import (
	"context"
	"crypto/md5"  //#nosec G501
	"crypto/sha1" //#nosec G505
	"encoding/hex"
	"fmt"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/systemconfig"
	"k8s.io/apimachinery/pkg/labels"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/kcp-dev/logicalcluster/v2"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	"github.com/go-logr/logr"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/pendingpipelinerun"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
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
	PipelineResultPrivate         = "private"
	TaskName                      = "task"
	JavaCommunityDependencies     = "JAVA_COMMUNITY_DEPENDENCIES"
	Contaminants                  = "CONTAMINANTS"
	DeployedResources             = "DEPLOYED_RESOURCES"
	PassedVerification            = "PASSED_VERIFICATION" //#nosec
	Image                         = "IMAGE"
	Rebuild                       = "jvmbuildservice.io/rebuild"
	Verify                        = "jvmbuildservice.io/verify"
)

type ReconcileArtifactBuild struct {
	client        client.Client
	scheme        *runtime.Scheme
	eventRecorder record.EventRecorder
	prCreator     pendingpipelinerun.PipelineRunCreate
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcileArtifactBuild{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("ArtifactBuild"),
		prCreator:     &pendingpipelinerun.PendingCreate{},
	}
}

func (r *ReconcileArtifactBuild) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	var cancel context.CancelFunc
	if request.ClusterName != "" {
		// use logicalcluster.ClusterFromContxt(ctx) to retrieve this value later on
		ctx = logicalcluster.WithCluster(ctx, logicalcluster.New(request.ClusterName))
	}
	ctx, cancel = context.WithTimeout(ctx, contextTimeout)
	defer cancel()
	log := ctrl.Log.WithName("artifactbuild").WithValues("request", request.NamespacedName).WithValues("cluster", request.ClusterName)
	//_, clusterSet := logicalcluster.ClusterFromContext(ctx)
	//if !clusterSet {
	//	log.Info("cluster is not set in context", request.String())
	//}

	jbsConfig := &v1alpha1.JBSConfig{}
	err := r.client.Get(ctx, types.NamespacedName{Namespace: request.Namespace, Name: v1alpha1.JBSConfigName}, jbsConfig)
	if err != nil && !errors.IsNotFound(err) {
		return reconcile.Result{}, err
	}
	//if rebuilds are not enabled we don't do anything here
	if !jbsConfig.Spec.EnableRebuilds {
		return reconcile.Result{}, nil
	}

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
	prerr := r.client.Get(ctx, request.NamespacedName, &pr)
	if prerr != nil {
		if !errors.IsNotFound(prerr) {
			log.Error(prerr, "Reconcile key %s as pipelinerun unexpected error", request.NamespacedName.String())
			return ctrl.Result{}, prerr
		}
	}

	if prerr != nil && dberr != nil && abrerr != nil {
		//TODO weird - during envtest the logging code panicked on the commented out log.Info call: 'com.acme.example.1.0-scm-discovery-5vjvmpanic: odd number of arguments passed as key-value pairs for logging'
		msg := "Reconcile key received not found errors for pipelineruns, dependencybuilds, artifactbuilds (probably deleted): " + request.NamespacedName.String()
		log.Info(msg)
		//log.Info("Reconcile key %s received not found errors for pipelineruns, dependencybuilds, artifactbuilds (probably deleted)", request.NamespacedName.String())
		return ctrl.Result{}, nil
	}

	switch {
	case dberr == nil:
		//log.Info("cluster set on obj ", r.clusterSetOnObj(&db))
		return r.handleDependencyBuildReceived(ctx, log, &db)

	case prerr == nil:
		return r.handlePipelineRunReceived(ctx, log, &pr)

	case abrerr == nil:
		// TODO: if verify = true, then find dependency build and add veify = false to dep build, add ourself to the owner references, if new dep created, also add it to that
		//log.Info("cluster set on obj ", r.clusterSetOnObj(&abr))
		//first check for a rebuild annotation
		if abr.Annotations[Rebuild] == "true" {
			if abr.Status.State != v1alpha1.ArtifactBuildStateNew {
				return r.handleRebuild(ctx, &abr)
			} else {
				delete(abr.Annotations, Rebuild)
				return reconcile.Result{}, r.client.Update(ctx, &abr)
			}
		} else if abr.Annotations[Rebuild] == "failed" {
			if abr.Status.State != v1alpha1.ArtifactBuildStateComplete && abr.Status.State != v1alpha1.ArtifactBuildStateNew {
				return r.handleRebuild(ctx, &abr)
			} else {
				delete(abr.Annotations, Rebuild)
				return reconcile.Result{}, r.client.Update(ctx, &abr)
			}
		}

		switch abr.Status.State {
		case v1alpha1.ArtifactBuildStateNew, "":
			return r.handleStateNew(ctx, log, &abr, jbsConfig)
		case v1alpha1.ArtifactBuildStateDiscovering:
			return r.handleStateDiscovering(ctx, log, &abr)
		case v1alpha1.ArtifactBuildStateComplete:
			return r.handleStateComplete(ctx, log, &abr)
		case v1alpha1.ArtifactBuildStateBuilding, v1alpha1.ArtifactBuildStateFailed: //ABR can go from failed to complete when contamination is resolved, so we treat it the same as building
			return r.handleStateBuilding(ctx, log, &abr)
		}
	}

	return reconcile.Result{}, nil
}

//func (r *ReconcileArtifactBuild) clusterSetOnObj(object logicalcluster.Object) bool {
//	return len(logicalcluster.From(object).String()) > 0
//}

func (r *ReconcileArtifactBuild) handlePipelineRunReceived(ctx context.Context, log logr.Logger, pr *pipelinev1beta1.PipelineRun) (reconcile.Result, error) {

	if pr.Status.PipelineResults != nil {
		for _, prRes := range pr.Status.PipelineResults {
			if prRes.Name == JavaCommunityDependencies {
				return reconcile.Result{}, r.handleCommunityDependencies(ctx, strings.Split(prRes.Value, ","), pr.Namespace, log)
			}
		}
	}

	if pr.Status.CompletionTime == nil {
		return reconcile.Result{}, nil
	}
	ownerRefs := pr.GetOwnerReferences()
	if len(ownerRefs) == 0 {
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
		case PipelineResultPrivate:
			private, err := strconv.ParseBool(res.Value)
			if err != nil {
				private = false
			}
			abr.Status.SCMInfo.Private = private
		}
	}

	//now let's create the dependency build object
	//once this object has been created its resolver takes over
	if abr.Status.SCMInfo.Tag == "" {
		//this is a failure
		r.eventRecorder.Eventf(&abr, corev1.EventTypeWarning, "MissingTag", "The ArtifactBuild %s/%s had an empty tag field %s", abr.Namespace, abr.Name, pr.Status.PipelineResults)
		abr.Status.State = v1alpha1.ArtifactBuildStateMissing
	}
	err = r.client.Status().Update(ctx, &abr)
	if err != nil {
		return reconcile.Result{}, err
	}

	return reconcile.Result{}, nil
}

func (r *ReconcileArtifactBuild) handleDependencyBuildReceived(ctx context.Context, log logr.Logger, db *v1alpha1.DependencyBuild) (reconcile.Result, error) {
	ownerRefs := db.GetOwnerReferences()
	if len(ownerRefs) == 0 {
		msg := "dependencybuild missing onwerrefs %s:%s"
		r.eventRecorder.Eventf(db, corev1.EventTypeWarning, msg, db.Namespace, db.Name)
		log.Info(msg, db.Namespace, db.Name)
		return reconcile.Result{}, nil
	}
	//update all the owners based on the current state
	for _, ownerRef := range ownerRefs {
		if strings.EqualFold(ownerRef.Kind, "artifactbuild") {
			ownerName := ownerRef.Name
			if len(ownerName) == 0 {
				msg := "dependencybuild missing artifactbuilds owner refs %s:%s"
				r.eventRecorder.Eventf(db, corev1.EventTypeWarning, msg, db.Namespace, db.Name)
				log.Info(msg, db.Namespace, db.Name)
				continue
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
			oldState := abr.Status.State
			switch db.Status.State {
			case v1alpha1.DependencyBuildStateFailed:
			case v1alpha1.DependencyBuildStateContaminated:
				abr.Status.State = v1alpha1.ArtifactBuildStateFailed
			case v1alpha1.DependencyBuildStateComplete:
				return r.handleDependencyBuildSucess(ctx, db, &abr)
			default:
				abr.Status.State = v1alpha1.ArtifactBuildStateBuilding
			}
			if oldState != abr.Status.State {
				err = r.client.Status().Update(ctx, &abr)
				if err != nil {
					return reconcile.Result{}, err
				}
			}
		}
	}
	return reconcile.Result{}, nil
}

func (r *ReconcileArtifactBuild) handleStateNew(ctx context.Context, log logr.Logger, abr *v1alpha1.ArtifactBuild, jbsConfig *v1alpha1.JBSConfig) (reconcile.Result, error) {

	// create pipeline run
	pr := pipelinev1beta1.PipelineRun{}
	pr.GenerateName = abr.Name + "-scm-discovery-"
	pr.Namespace = abr.Namespace
	systemConfig := v1alpha1.SystemConfig{}
	err := r.client.Get(ctx, types.NamespacedName{Name: systemconfig.SystemConfigKey}, &systemConfig)
	if err != nil {
		return reconcile.Result{}, err
	}
	task, err2 := r.createLookupScmInfoTask(ctx, log, abr.Spec.GAV, jbsConfig, &systemConfig)
	if err2 != nil {
		return reconcile.Result{}, err2
	}
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

	if err := r.prCreator.CreateWrapperForPipelineRun(ctx, r.client, &pr); err != nil {
		return reconcile.Result{}, err
	}
	return reconcile.Result{}, nil
}

func (r *ReconcileArtifactBuild) handleStateDiscovering(ctx context.Context, log logr.Logger, abr *v1alpha1.ArtifactBuild) (reconcile.Result, error) {
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
			return r.handleDependencyBuildSucess(ctx, db, abr)
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
			Private: abr.Status.SCMInfo.Private,
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

func (r *ReconcileArtifactBuild) handleDependencyBuildSucess(ctx context.Context, db *v1alpha1.DependencyBuild, abr *v1alpha1.ArtifactBuild) (reconcile.Result, error) {
	for _, i := range db.Status.DeployedArtifacts {
		if i == abr.Spec.GAV {
			abr.Status.State = v1alpha1.ArtifactBuildStateComplete
			return reconcile.Result{}, r.client.Status().Update(ctx, abr)
		}
	}
	abr.Status.Message = "Discovered dependency build did not deploy this artifact, check SCM information is correct"
	abr.Status.State = v1alpha1.ArtifactBuildStateFailed
	return reconcile.Result{}, r.client.Status().Update(ctx, abr)
}

func hashString(hashInput string) string {
	hash := md5.Sum([]byte(hashInput)) //#nosec
	depId := hex.EncodeToString(hash[:])
	return depId
}
func ABRLabelForGAV(hashInput string) string {
	return hashString(hashInput)
}

func (r *ReconcileArtifactBuild) handleStateComplete(ctx context.Context, log logr.Logger, abr *v1alpha1.ArtifactBuild) (reconcile.Result, error) {
	for key, value := range abr.Annotations {
		if strings.HasPrefix(key, DependencyBuildContaminatedBy) {
			log.Info("Attempting to resolve contamination", "artifactbuild", abr.Name)
			db := v1alpha1.DependencyBuild{}
			if err := r.client.Get(ctx, types.NamespacedName{Name: value, Namespace: abr.Namespace}, &db); err != nil {
				r.eventRecorder.Eventf(abr, corev1.EventTypeNormal, "CannotGetDependencyBuild", "Could not find the contaminated DependencyBuild for ArtifactBuild %s/%s: %s", abr.Namespace, abr.Name, err.Error())
				//this was not found
				continue
			}
			if db.Status.State != v1alpha1.DependencyBuildStateContaminated {
				continue
			}
			var newContaminates []v1alpha1.Contaminant
			for _, contaminant := range db.Status.Contaminants {
				if contaminant.GAV != abr.Spec.GAV {
					newContaminates = append(newContaminates, contaminant)
				}
			}
			log.Info("Attempting to resolve contamination for dependencybuild", "dependencybuild", db.Name, "old", db.Status.Contaminants, "new", newContaminates)
			db.Status.Contaminants = newContaminates
			if len(db.Status.Contaminants) == 0 {
				//TODO: we could have a situation where there are still some contamination, but not for artifacts that we care about
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

func (r *ReconcileArtifactBuild) handleStateBuilding(ctx context.Context, log logr.Logger, abr *v1alpha1.ArtifactBuild) (reconcile.Result, error) {
	depId := hashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
	db := &v1alpha1.DependencyBuild{}
	dbKey := types.NamespacedName{Namespace: abr.Namespace, Name: depId}
	err := r.client.Get(ctx, dbKey, db)

	switch {
	case err == nil:
	case errors.IsNotFound(err):
		//we don't have a build for this ABR, this is very odd
		//move back to new and start again
		r.eventRecorder.Eventf(abr, corev1.EventTypeWarning, "MissingDependencyBuild", "The ArtifactBuild %s/%s in state %s was missing a DependencyBuild", abr.Namespace, abr.Name, abr.Status.State)
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
		return r.handleDependencyBuildSucess(ctx, db, abr)
	case v1alpha1.DependencyBuildStateContaminated, v1alpha1.DependencyBuildStateFailed:
		abr.Status.State = v1alpha1.ArtifactBuildStateFailed
		return reconcile.Result{}, r.client.Status().Update(ctx, abr)
	default:
		abr.Status.State = v1alpha1.ArtifactBuildStateBuilding
		return reconcile.Result{}, r.client.Status().Update(ctx, abr)
	}
}

func (r *ReconcileArtifactBuild) handleRebuild(ctx context.Context, abr *v1alpha1.ArtifactBuild) (reconcile.Result, error) {
	//first look for a dependency build
	//and delete it if it exists
	if len(abr.Status.SCMInfo.SCMURL) > 0 {
		//now lets look for an existing dependencybuild object
		depId := hashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
		db := &v1alpha1.DependencyBuild{}
		dbKey := types.NamespacedName{Namespace: abr.Namespace, Name: depId}
		err := r.client.Get(ctx, dbKey, db)
		notFound := errors.IsNotFound(err)
		if err == nil {
			//make sure to annotate all other owners so they also see state updates
			//this won't cause a 'thundering herd' type problem as they are all deleted anyway
			for _, ownerRef := range db.OwnerReferences {
				if strings.EqualFold(ownerRef.Kind, "artifactbuild") || strings.EqualFold(ownerRef.Kind, "artifactbuilds") {
					if ownerRef.Name != abr.Name {
						other := v1alpha1.ArtifactBuild{}
						err := r.client.Get(ctx, types.NamespacedName{Name: ownerRef.Name, Namespace: abr.Namespace}, &other)
						if err != nil {
							return reconcile.Result{}, err
						}
						if other.Annotations == nil {
							other.Annotations = map[string]string{Rebuild: "true"}
						} else {
							other.Annotations[Rebuild] = "true"
						}
						err = r.client.Update(ctx, &other)
						if err != nil {
							return reconcile.Result{}, err
						}

					}

				}

			}

			//delete the dependency build object
			err := r.client.Delete(ctx, db)
			if err != nil {
				return reconcile.Result{}, err
			}
		} else if err != nil && !notFound {
			return reconcile.Result{}, err
		}
	}
	//set our state back to new
	abr.Status.State = v1alpha1.ArtifactBuildStateNew
	abr.Status.SCMInfo = v1alpha1.SCMInfo{}
	abr.Status.Message = ""
	err := r.client.Status().Update(ctx, abr)
	if err != nil {
		return ctrl.Result{}, err
	}

	//now delete old pipelines

	pr := pipelinev1beta1.PipelineRunList{}
	listOpts := &client.ListOptions{
		Namespace:     abr.Namespace,
		LabelSelector: labels.SelectorFromSet(map[string]string{ArtifactBuildIdLabel: ABRLabelForGAV(abr.Spec.GAV)}),
	}
	err = r.client.List(ctx, &pr, listOpts)
	if err != nil {
		return ctrl.Result{}, err
	}
	for _, i := range pr.Items {
		h := i
		err = r.client.Delete(ctx, &h)
		if err != nil {
			return ctrl.Result{}, err
		}
	}

	return reconcile.Result{}, err

}

func CreateABRName(gav string) string {
	hashedBytes := sha1.Sum([]byte(gav)) //#nosec
	hash := hex.EncodeToString(hashedBytes[:])[0:8]
	namePart := gav[strings.Index(gav, ":")+1:]

	//generate names based on the artifact name + version, and part of a hash
	//we only use the first 8 characters from the hash to make the name small
	var newName = strings.Builder{}
	lastDot := false
	for _, i := range namePart {
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

func (r *ReconcileArtifactBuild) createLookupScmInfoTask(ctx context.Context, log logr.Logger, gav string, jbsConfig *v1alpha1.JBSConfig, systemConfig *v1alpha1.SystemConfig) (*pipelinev1beta1.TaskSpec, error) {
	image, err := util.GetImageName(ctx, r.client, log, "build-request-processor", "JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE")

	trueBool := true
	if err != nil {
		return nil, err
	}
	recipes := ""
	additional := jbsConfig.Spec.AdditionalRecipes
	for _, recipe := range additional {
		if len(strings.TrimSpace(recipe)) > 0 {
			recipes = recipes + recipe + ","
		}
	}
	recipes = recipes + settingOrDefault(systemConfig.Spec.RecipeDatabase, v1alpha1.DefaultRecipeDatabase)

	zero := int64(0)
	return &pipelinev1beta1.TaskSpec{
		Results: []pipelinev1beta1.TaskResult{
			{Name: PipelineResultScmUrl},
			{Name: PipelineResultScmTag},
			{Name: PipelineResultScmType},
			{Name: PipelineResultContextPath},
			{Name: PipelineResultPrivate},
			{Name: PipelineResultMessage},
		},
		Steps: []pipelinev1beta1.Step{
			{
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
					"--private",
					"$(results." + PipelineResultPrivate + ".path)",
					"--gav",
					gav,
					"--cache-url",
					"http://jvm-build-workspace-artifact-cache." + jbsConfig.Namespace + ".svc.cluster.local/v1/cache/default/0",
				},
				SecurityContext: &corev1.SecurityContext{
					RunAsUser: &zero,
				},
				Env: []corev1.EnvVar{
					{Name: "GIT_TOKEN", ValueFrom: &corev1.EnvVarSource{SecretKeyRef: &corev1.SecretKeySelector{LocalObjectReference: corev1.LocalObjectReference{Name: v1alpha1.GitSecretName}, Key: v1alpha1.GitSecretTokenKey, Optional: &trueBool}}},
				},
			},
		},
	}, nil
}

func (r *ReconcileArtifactBuild) handleCommunityDependencies(ctx context.Context, split []string, namespace string, log logr.Logger) error {
	log.Info("Found pipeline run with community dependencies")
	for _, gav := range split {
		name := CreateABRName(gav)
		log.Info("Found community dependency: ", "gav", gav)
		abr := v1alpha1.ArtifactBuild{}
		err := r.client.Get(ctx, types.NamespacedName{Namespace: namespace, Name: name}, &abr)
		if err != nil {
			if errors.IsNotFound(err) {
				abr.Spec.GAV = gav
				abr.Name = name
				abr.Namespace = namespace
				err := r.client.Create(ctx, &abr)
				if err != nil {
					return err
				}
			} else {
				return err
			}
		}
	}
	return nil
}

func settingOrDefault(setting, def string) string {
	if len(strings.TrimSpace(setting)) == 0 {
		return def
	}
	return setting
}
