package artifactbuildrequest

import (
	"context"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/types"
	"knative.dev/pkg/apis"
	"sigs.k8s.io/controller-runtime/pkg/log"
	"time"

	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/runtime"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout = 300 * time.Second
)

type ReconcileArtifactBuildRequest struct {
	client client.Client
	scheme *runtime.Scheme
	queue  []queuedRequest
	timer  *time.Timer
}

type queuedRequest struct {
	ctx    *context.Context
	name   types.NamespacedName
	cancel context.CancelFunc
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcileArtifactBuildRequest{
		client: mgr.GetClient(),
		scheme: mgr.GetScheme(),
	}
}

func (r *ReconcileArtifactBuildRequest) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	ctx, cancel := context.WithTimeout(ctx, contextTimeout)
	log := log.FromContext(ctx)
	abr := v1alpha1.ArtifactBuildRequest{}
	err := r.client.Get(ctx, request.NamespacedName, &abr)
	if err != nil {
		cancel()
		if errors.IsNotFound(err) {
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}
	//TODO skeleton for now; start seeing what parts if any of build-request-processor or dependency-analyzer need to move here

	if abr.Status.State == v1alpha1.ArtifactBuildRequestStateNew || abr.Status.State == "" {

		r.queue = append(r.queue, queuedRequest{
			ctx:    &ctx,
			name:   request.NamespacedName,
			cancel: cancel,
		})
		log.Info("State: " + abr.Spec.GAV)
		if r.timer == nil {
			r.timer = time.NewTimer(2 * time.Second)
			go func() {
				<-r.timer.C
				for _, queued := range r.queue {
					func() {
						defer queued.cancel()
						queuedBr := v1alpha1.ArtifactBuildRequest{}
						err := r.client.Get(*queued.ctx, queued.name, &queuedBr)
						if err != nil {
							log.Error(err, "Failed to process queued", "resource", queued.name.Name)
							return
						}
						queuedBr.Status.State = v1alpha1.ArtifactBuildRequestStateDiscovering
						err = r.client.Status().Update(*queued.ctx, &queuedBr)
						if err != nil {
							log.Error(err, "Failed to update queued resource", "resource", queued.name.Name)
						}
					}()
				}
				r.timer = nil
				r.queue = []queuedRequest{}
			}()
		}
	} else {
		defer cancel()
	}
	//
	//// rough approximation of what is in https://github.com/redhat-appstudio/jvm-build-service/blob/main/build-request-processor/src/main/java/com/redhat/hacbs/container/analyser/ProcessCommand.java
	//// where we replace the list done there, using the watch / relist induced event we get here
	//if abr.Status.State == v1alpha1.ArtifactBuildRequestStateNew {
	//	//if the ABR is new then we want to kick of a pipeline to
	//	abr.Status.State = v1alpha1.ArtifactBuildRequestStateMissing
	//	err = r.client.Update(ctx, &abr)
	//	if err != nil {
	//		return reconcile.Result{}, err
	//	}
	//	return reconcile.Result{}, nil
	//}
	//
	////TODO need some golang approximation of RecipeRepositoryManager and RecipeGroupManager that takes
	//// the gav and abr and produces the needed result
	//// Per last team meeting: may make sense to capture the RecipeRepositoryManager and RecipeGroupManager
	//// as steps in a PipelineRun that we launch here.  We then analyze the results/output of the PipelineRun
	//// (where we figure what those results/output are stored so this reconciler can retrieve them) and then
	//// move onto the next step below.
	//
	//dbName := "someNamDerivedFromArtifactBuildRequestAndGa"
	//dbNamespace := abr.Namespace
	//key := types.NamespacedName{Namespace: dbNamespace, Name: dbName}
	//db := v1alpha1.DependencyBuild{ObjectMeta: metav1.ObjectMeta{Name: dbName}}
	//err = r.client.Get(ctx, key, &db)
	//if errors.IsNotFound(err) {
	//	db.Spec.SCMURL = "someurl"
	//	db.Spec.SCMType = "git"
	//	db.Spec.Tag = "selectedTag"
	//	db.Spec.Version = "someVersion"
	//
	//	//err = r.client.Create(ctx, &db)
	//	//if err != nil {
	//	//	return reconcile.Result{}, err
	//	//}
	//}
	//if err != nil {
	//	return reconcile.Result{}, err
	//}
	//
	//abr.Status.State = v1alpha1.ArtifactBuildRequestStateBuilding
	//err = r.client.Update(ctx, &abr)
	//if err != nil {
	//	return reconcile.Result{}, err
	//}

	// see if we already launched a PipelineRun associated with this ABR
	list := &pipelinev1beta1.PipelineRunList{}
	abrNameForLabel := types.NamespacedName{Namespace: abr.Namespace, Name: abr.Name}.String()
	lbls := map[string]string{"jvmbuildservice.io/artifactbuildrequet": abrNameForLabel}
	listOpts := &client.ListOptions{
		Namespace:     abr.Namespace,
		LabelSelector: labels.SelectorFromSet(lbls),
	}
	err = r.client.List(ctx, list, listOpts)
	if err != nil {
		return reconcile.Result{}, err
	}
	if len(list.Items) == 0 {
		// create pipelinerun
		pr := &pipelinev1beta1.PipelineRun{}
		pr.Namespace = abr.Namespace
		pr.GenerateName = abr.Name + "-"
		//TODO fill in other needed fields of PR
		err = r.client.Create(ctx, pr)
		if err != nil {
			return reconcile.Result{}, err
		}
	} else {
		pr := list.Items[0]
		if pr.Status.CompletionTime != nil {
			abr.Status.State = v1alpha1.ArtifactBuildRequestStateComplete
			condition := pr.Status.GetCondition(apis.ConditionSucceeded)
			if !condition.IsTrue() {
				abr.Status.State = v1alpha1.ArtifactBuildRequestStateFailed
			}

		}
	}

	return reconcile.Result{}, nil
}
