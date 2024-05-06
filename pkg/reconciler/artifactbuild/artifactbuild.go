package artifactbuild

import (
	"context"
	"crypto/sha1" //#nosec G505
	_ "embed"
	"encoding/hex"
	"fmt"
	"strconv"
	"strings"
	"time"
	"unicode"

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
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout     = 300 * time.Second
	ComponentFinalizer = "jvmbuildservice.io/component-finalizer"
	// DependencyBuildContaminatedByAnnotation label prefix that indicates that a dependency build was contaminated by this artifact
	DependencyBuildContaminatedByAnnotation = "jvmbuildservice.io/contaminated-"
	DependencyBuildIdLabel                  = "jvmbuildservice.io/dependencybuild-id"
	PipelineRunLabel                        = "jvmbuildservice.io/pipelinerun"
	HermeticBuildTaskName                   = "hermetic-build"
	TagTaskName                             = "tag"

	PipelineResultJavaCommunityDependencies = "JAVA_COMMUNITY_DEPENDENCIES"

	DependencyAnnotation = "jvmbuildservice.io/dependencycreated"

	RebuildAnnotation = "jvmbuildservice.io/rebuild"
	// RebuiltAnnotation annotation that is applied after a rebuild, it will affect the dependencybuild behaviour
	RebuiltAnnotation = "jvmbuildservice.io/rebuilt"
	// HoursToLive if this annotation is present it will be deleted after a set time to live
	//useful when doing builds that are being deployed to maven, and you don't want to accumulate them in the cluster
	HoursToLive = "jvmbuildservice.io/hours-to-live"
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

//go:embed scripts/keystore.sh
var keystore string

func (r *ReconcileArtifactBuild) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	var cancel context.CancelFunc
	ctx, cancel = context.WithTimeout(ctx, contextTimeout)
	defer cancel()
	log := ctrl.Log.WithName("artifactbuild").WithValues("namespace", request.NamespacedName.Namespace, "resource", request.Name)

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
	labelR, err := r.updateLabel(ctx, log, &abr)
	if err != nil {
		return reconcile.Result{}, err
	} else if labelR {
		return reconcile.Result{}, nil
	}

	pr := tektonpipeline.PipelineRun{}
	prerr := r.client.Get(ctx, request.NamespacedName, &pr)
	if prerr != nil {
		if !errors.IsNotFound(prerr) {
			log.Error(prerr, "Reconcile key %s as pipelinerun unexpected error", request.NamespacedName.String())
			return ctrl.Result{}, prerr
		}
	}

	if prerr != nil && abrerr != nil {
		log.Info(fmt.Sprintf("Reconcile key %s received not found errors for pipelineruns and artifactbuilds (probably deleted)", request.NamespacedName.String()))
		return ctrl.Result{}, nil
	}

	switch {
	case prerr == nil:
		log = log.WithValues("kind", "PipelineRun")
		return r.handlePipelineRunReceived(ctx, log, &pr)

	case abrerr == nil:
		log = log.WithValues("kind", "ArtifactBuild", "ab-gav", abr.Spec.GAV, "ab-initial-state", abr.Status.State)
		done, err := r.handleS3SyncArtifactBuild(ctx, &abr, log)
		if done || err != nil {
			return reconcile.Result{}, err
		}
		result, err := r.handleArtifactBuildReceived(ctx, abr, log, jbsConfig)
		if err != nil {
			log.Error(err, "failure reconciling ArtifactBuild")
		}
		return result, err
	}

	return reconcile.Result{}, nil
}

func (r *ReconcileArtifactBuild) handleArtifactBuildReceived(ctx context.Context, abr v1alpha1.ArtifactBuild, log logr.Logger, jbsConfig *v1alpha1.JBSConfig) (reconcile.Result, error) {
	result := reconcile.Result{}
	if abr.Annotations != nil {
		if abr.Annotations[HoursToLive] != "" {
			hours, err := strconv.Atoi(abr.Annotations[HoursToLive])
			if err == nil {
				duration := time.Hour * time.Duration(hours)
				if time.Now().After(abr.CreationTimestamp.Add(duration)) {
					log.Info("deleting artifact build that has exceeded its time to live")
					return reconcile.Result{}, r.client.Delete(ctx, &abr)
				} else {

					result = reconcile.Result{RequeueAfter: time.Until(abr.CreationTimestamp.Add(duration).Add(time.Second))}
				}
			}
		}

		// TODO: if verify = true, then find dependency build and add veify = false to dep build, add ourself to the owner references, if new dep created, also add it to that
		//log.Info("cluster set on obj ", r.clusterSetOnObj(&abr))
		//first check for a rebuild annotation
		if abr.Annotations[RebuildAnnotation] == "true" {
			if abr.Status.State != v1alpha1.ArtifactBuildStateNew {
				return result, r.handleRebuild(log, ctx, &abr)
			} else {
				delete(abr.Annotations, RebuildAnnotation)
				abr.Annotations[RebuiltAnnotation] = "true"
				return result, r.client.Update(ctx, &abr)
			}
		} else if abr.Annotations[RebuildAnnotation] == "failed" {
			if abr.Status.State != v1alpha1.ArtifactBuildStateComplete && abr.Status.State != v1alpha1.ArtifactBuildStateNew {
				return result, r.handleRebuild(log, ctx, &abr)

			} else {
				delete(abr.Annotations, RebuildAnnotation)
				return result, r.client.Update(ctx, &abr)
			}
		}
	}

	switch abr.Status.State {
	case v1alpha1.ArtifactBuildStateNew, "":
		return result, r.handleStateNew(ctx, log, &abr, jbsConfig)
	case v1alpha1.ArtifactBuildStateDiscovering:
		return result, r.handleStateDiscovering(ctx, log, &abr)
	case v1alpha1.ArtifactBuildStateComplete:
		return result, r.handleStateComplete(ctx, log, &abr)
	case v1alpha1.ArtifactBuildStateBuilding:
		return result, r.handleStateBuilding(ctx, log, &abr)
	case v1alpha1.ArtifactBuildStateFailed:
		return result, r.handleStateFailed(ctx, log, &abr)
	}
	return reconcile.Result{}, nil
}

func (r *ReconcileArtifactBuild) handlePipelineRunReceived(ctx context.Context, log logr.Logger, pr *tektonpipeline.PipelineRun) (reconcile.Result, error) {

	if pr.DeletionTimestamp != nil {
		//always remove the finalizer if it is deleted
		//but continue with the method
		//if the PR is deleted while it is running then we want to allow that
		result, err := r.removePipelineFinalizer(ctx, pr)
		if err != nil {
			return result, err
		}
	} else if pr.Status.CompletionTime == nil {
		//not finished, add the finalizer if needed
		//these PRs can be aggressively pruned, we need the finalizer to
		//make sure we see the results
		if controllerutil.AddFinalizer(pr, ComponentFinalizer) {
			return reconcile.Result{}, r.client.Update(ctx, pr)
		}
		return reconcile.Result{}, nil
	}

	if pr.Status.Results != nil {
		for _, prRes := range pr.Status.Results {
			if prRes.Name == PipelineResultJavaCommunityDependencies {
				return reconcile.Result{}, r.handleCommunityDependencies(ctx, strings.Split(prRes.Value.StringVal, ","), pr.Namespace, log)
			}
		}
	}
	return reconcile.Result{}, nil
}

func (r *ReconcileArtifactBuild) removePipelineFinalizer(ctx context.Context, pr *tektonpipeline.PipelineRun) (reconcile.Result, error) {
	//remove the finalizer
	if controllerutil.RemoveFinalizer(pr, ComponentFinalizer) {
		return reconcile.Result{}, r.client.Update(ctx, pr)
	}
	return reconcile.Result{}, nil
}

func (r *ReconcileArtifactBuild) updateArtifactState(ctx context.Context, log logr.Logger, abr *v1alpha1.ArtifactBuild, state string) error {
	if abr.Status.State != state {
		log.Info(fmt.Sprintf("ArtifactBuild %s changing state from %s to %s", abr.Name, abr.Status.State, state))
		abr.Status.State = state
		return r.client.Status().Update(ctx, abr)
	}
	return nil
}

func (r *ReconcileArtifactBuild) handleStateNew(ctx context.Context, log logr.Logger, abr *v1alpha1.ArtifactBuild, jbsConfig *v1alpha1.JBSConfig) error {
	//this is now handled directly by the cache
	//which massively reduces the number of pipelines created
	return nil
}

func (r *ReconcileArtifactBuild) handleStateDiscovering(ctx context.Context, log logr.Logger, abr *v1alpha1.ArtifactBuild) error {
	// if pipelinerun to update SCM/Message has not completed, just return
	if len(abr.Status.SCMInfo.SCMURL) == 0 &&
		len(abr.Status.SCMInfo.Tag) == 0 &&
		len(abr.Status.SCMInfo.SCMType) == 0 &&
		len(abr.Status.SCMInfo.Path) == 0 &&
		len(abr.Status.Message) == 0 {
		return nil
	}
	if len(abr.Status.SCMInfo.SCMURL) == 0 || len(abr.Status.SCMInfo.Tag) == 0 {
		//discovery failed
		return r.updateArtifactState(ctx, log, abr, v1alpha1.ArtifactBuildStateMissing)
	}

	//now lets look for an existing dependencybuild object
	depId := util.HashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
	db := &v1alpha1.DependencyBuild{}
	dbKey := types.NamespacedName{Namespace: abr.Namespace, Name: depId}
	err := r.client.Get(ctx, dbKey, db)

	switch {
	case err == nil:
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
				return err
			}
			if abr.Annotations != nil && abr.Annotations[RebuiltAnnotation] == "true" {
				if db.Annotations == nil {
					db.Annotations = map[string]string{}
				}
				db.Annotations[RebuiltAnnotation] = "true"
			}
			if err := r.client.Update(ctx, db); err != nil {
				return err
			}
		}

		//if the build is done update our state accordingly
		switch db.Status.State {
		case v1alpha1.DependencyBuildStateComplete:
			return r.handleDependencyBuildSuccess(log, ctx, db, abr)
		case v1alpha1.DependencyBuildStateContaminated, v1alpha1.DependencyBuildStateFailed:
			return r.updateArtifactState(ctx, log, abr, v1alpha1.ArtifactBuildStateFailed)
		default:
			//move the state to building
			return r.updateArtifactState(ctx, log, abr, v1alpha1.ArtifactBuildStateBuilding)
		}
	case errors.IsNotFound(err):
		//no existing build object found, lets create one
		db := &v1alpha1.DependencyBuild{}
		db.Annotations = map[string]string{}
		db.Namespace = abr.Namespace
		db.Name = depId
		if err := controllerutil.SetOwnerReference(abr, db, r.scheme); err != nil {
			return err
		}
		db.Spec = v1alpha1.DependencyBuildSpec{ScmInfo: v1alpha1.SCMInfo{
			SCMURL:     abr.Status.SCMInfo.SCMURL,
			SCMType:    abr.Status.SCMInfo.SCMType,
			Tag:        abr.Status.SCMInfo.Tag,
			CommitHash: abr.Status.SCMInfo.CommitHash,
			Path:       abr.Status.SCMInfo.Path,
			Private:    abr.Status.SCMInfo.Private,
		}, Version: abr.Spec.GAV[strings.LastIndex(abr.Spec.GAV, ":")+1:]}

		if abr.Annotations != nil && abr.Annotations[RebuiltAnnotation] == "true" {
			db.Annotations[RebuiltAnnotation] = "true"
		}
		//move the state to building
		if err := r.updateArtifactState(ctx, log, abr, v1alpha1.ArtifactBuildStateBuilding); err != nil {
			return err
		}
		return r.client.Create(ctx, db)

	default:
		log.Error(err, "for artifactbuild %s:%s", abr.Namespace, abr.Name)
		return err
	}
}

func (r *ReconcileArtifactBuild) handleDependencyBuildSuccess(log logr.Logger, ctx context.Context, db *v1alpha1.DependencyBuild, abr *v1alpha1.ArtifactBuild) error {
	for _, i := range db.Status.DeployedArtifacts {
		if i == abr.Spec.GAV {
			return r.updateArtifactState(ctx, log, abr, v1alpha1.ArtifactBuildStateComplete)
		}
	}
	abr.Status.Message = "Discovered dependency build did not deploy this artifact, check SCM information is correct"
	return r.updateArtifactState(ctx, log, abr, v1alpha1.ArtifactBuildStateFailed)
}

func (r *ReconcileArtifactBuild) handleStateComplete(ctx context.Context, log logr.Logger, abr *v1alpha1.ArtifactBuild) error {
	for key, value := range abr.Annotations {
		if strings.HasPrefix(key, DependencyBuildContaminatedByAnnotation) {
			db := v1alpha1.DependencyBuild{}
			if err := r.client.Get(ctx, types.NamespacedName{Name: value, Namespace: abr.Namespace}, &db); err != nil {
				r.eventRecorder.Eventf(abr, corev1.EventTypeNormal, "CannotGetDependencyBuild", "Could not find the contaminated DependencyBuild for ArtifactBuild %s/%s: %s", abr.Namespace, abr.Name, err.Error())
				//this was not found
				continue
			}
			if db.Status.State != v1alpha1.DependencyBuildStateContaminated {
				continue
			}
			allOk := true
			for i := range db.Status.Contaminants {
				contaminant := db.Status.Contaminants[i]
				if contaminant.GAV == abr.Spec.GAV {
					contaminant.RebuildAvailable = true
				} else if !contaminant.Allowed && !contaminant.RebuildAvailable {
					allOk = false
				}
			}
			if allOk {
				log.Info("Attempting to resolve contamination for dependencybuild as all contaminates are ready", "dependencybuild", db.Name+"-"+db.Spec.ScmInfo.SCMURL+"-"+db.Spec.ScmInfo.Tag)
				//TODO: we could have a situation where there are still some contamination, but not for artifacts that we care about
				//kick off the build again
				log.Info("Contamination resolved, moving to state SubmitBuild", "dependencybuild", db.Name+"-"+db.Spec.ScmInfo.SCMURL+"-"+db.Spec.ScmInfo.Tag)
				db.Status.State = v1alpha1.DependencyBuildStateSubmitBuild
				db.Status.PotentialBuildRecipesIndex = 0
			}
			if err := r.client.Status().Update(ctx, &db); err != nil {
				return err
			}
		}
	}
	return nil
}

func (r *ReconcileArtifactBuild) handleStateBuilding(ctx context.Context, log logr.Logger, abr *v1alpha1.ArtifactBuild) error {
	depId := util.HashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
	db := &v1alpha1.DependencyBuild{}
	dbKey := types.NamespacedName{Namespace: abr.Namespace, Name: depId}
	err := r.client.Get(ctx, dbKey, db)

	switch {
	case err == nil:
	case errors.IsNotFound(err):
		//we don't have a build for this ABR, this is very odd
		//move back to new and start again
		r.eventRecorder.Eventf(abr, corev1.EventTypeWarning, "MissingDependencyBuild", "The ArtifactBuild %s/%s in state %s was missing a DependencyBuild", abr.Namespace, abr.Name, abr.Status.State)
		return r.updateArtifactState(ctx, log, abr, v1alpha1.ArtifactBuildStateNew)
	default:
		log.Error(err, "for artifactbuild %s:%s", abr.Namespace, abr.Name)
		return err
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
			return err
		}
		if err := r.client.Update(ctx, db); err != nil {
			return err
		}
	}

	//if the build is done update our state accordingly
	switch db.Status.State {
	case v1alpha1.DependencyBuildStateComplete:
		return r.handleDependencyBuildSuccess(log, ctx, db, abr)
	case v1alpha1.DependencyBuildStateContaminated, v1alpha1.DependencyBuildStateFailed:
		return r.updateArtifactState(ctx, log, abr, v1alpha1.ArtifactBuildStateFailed)
	default:
		return nil
	}
}

func (r *ReconcileArtifactBuild) handleStateFailed(ctx context.Context, log logr.Logger, abr *v1alpha1.ArtifactBuild) error {
	depId := util.HashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
	db := &v1alpha1.DependencyBuild{}
	dbKey := types.NamespacedName{Namespace: abr.Namespace, Name: depId}
	err := r.client.Get(ctx, dbKey, db)

	switch {
	case err == nil:
	case errors.IsNotFound(err):
		//we don't have a build for this ABR, this is very odd
		//it already failed though, so just return
		return nil
	default:
		log.Error(err, "for artifactbuild %s:%s", abr.Namespace, abr.Name)
		return err
	}

	//if the build is done update our state accordingly
	switch db.Status.State {
	case v1alpha1.DependencyBuildStateContaminated, v1alpha1.DependencyBuildStateFailed:
		//do nothing, this is expected
	case v1alpha1.DependencyBuildStateComplete:
		return r.handleDependencyBuildSuccess(log, ctx, db, abr)
	default:
		return r.updateArtifactState(ctx, log, abr, v1alpha1.ArtifactBuildStateBuilding)
	}
	return nil
}

func (r *ReconcileArtifactBuild) handleRebuild(log logr.Logger, ctx context.Context, abr *v1alpha1.ArtifactBuild) error {
	//first look for a dependency build
	//and delete it if it exists
	if len(abr.Status.SCMInfo.SCMURL) > 0 {
		//now lets look for an existing dependencybuild object
		depId := util.HashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
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
							if !errors.IsNotFound(err) {
								return err
							}
							//on not found we don't return the error
							//no need to retry it would just result in an infinite loop
							return nil
						}
						if other.Annotations == nil {
							other.Annotations = map[string]string{RebuildAnnotation: "true"}
						} else {
							other.Annotations[RebuildAnnotation] = "true"
						}
						err = r.client.Update(ctx, &other)
						if err != nil {
							return err
						}
					}
				}
			}
			//delete the dependency build object
			err := r.client.Delete(ctx, db)
			if err != nil {
				return err
			}
		} else if err != nil && !notFound {
			return err
		}
	}
	//set our state back to new
	abr.Status.Message = ""
	return r.updateArtifactState(ctx, log, abr, v1alpha1.ArtifactBuildStateNew)
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

func (r *ReconcileArtifactBuild) handleCommunityDependencies(ctx context.Context, split []string, namespace string, log logr.Logger) error {
	log.Info("Found pipeline run with community dependencies")
	for _, gav := range split {
		if len(gav) == 0 {
			continue
		}
		name := CreateABRName(gav)
		abr := v1alpha1.ArtifactBuild{}
		err := r.client.Get(ctx, types.NamespacedName{Namespace: namespace, Name: name}, &abr)
		if err != nil {
			if errors.IsNotFound(err) {
				log.Info("Found community dependency, creating ArtifactBuild", "gav", gav, "artifactbuild", name, "action", "ADD")
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

func (r *ReconcileArtifactBuild) updateLabel(ctx context.Context, log logr.Logger, abr *v1alpha1.ArtifactBuild) (bool, error) {
	result := false

	if abr.Labels == nil {
		abr.Labels = make(map[string]string)
	}
	originalLabel := abr.Labels[util.StatusLabel]
	switch abr.Status.State {
	case v1alpha1.ArtifactBuildStateNew, v1alpha1.ArtifactBuildStateDiscovering, v1alpha1.ArtifactBuildStateBuilding:
		if abr.Labels[util.StatusLabel] != util.StatusBuilding {
			abr.Labels[util.StatusLabel] = util.StatusBuilding
			result = true
		}
	case v1alpha1.ArtifactBuildStateFailed, v1alpha1.ArtifactBuildStateMissing:
		if abr.Labels[util.StatusLabel] != util.StatusFailed {
			abr.Labels[util.StatusLabel] = util.StatusFailed
			result = true
		}
	case v1alpha1.ArtifactBuildStateComplete:
		if abr.Labels[util.StatusLabel] != util.StatusSucceeded {
			abr.Labels[util.StatusLabel] = util.StatusSucceeded
			result = true
		}
	}
	if result {
		log.Info(fmt.Sprintf("Updating label from %s to %s to match %s", originalLabel, abr.Labels[util.StatusLabel], abr.Status.State))
		if err := r.client.Update(ctx, abr); err != nil {
			return result, err
		}
	}
	return result, nil
}

func InstallKeystoreIntoBuildRequestProcessor(args ...[]string) string {
	ret := keystore
	for _, cmd := range args {
		ret = ret + "\n/opt/jboss/container/java/run/run-java.sh"
		for _, i := range cmd {
			ret += " \"" + i + "\""
		}
		ret += "\n"
	}
	return ret
}

func InstallKeystoreScript() string {
	return keystore
}
