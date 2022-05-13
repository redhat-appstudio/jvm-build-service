package pipelinerun

import (
	"context"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/dependencybuild"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/runtime"
	"knative.dev/pkg/apis"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout = 300 * time.Second
)

type ReconcilePipelineRunRequest struct {
	client client.Client
	scheme *runtime.Scheme
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcilePipelineRunRequest{
		client: mgr.GetClient(),
		scheme: mgr.GetScheme(),
	}
}

func (r *ReconcilePipelineRunRequest) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	ctx, cancel := context.WithTimeout(ctx, contextTimeout)
	defer cancel()
	//log := log.FromContext(ctx)
	pr := pipelinev1beta1.PipelineRun{}
	err := r.client.Get(ctx, request.NamespacedName, &pr)
	if err != nil {
		if errors.IsNotFound(err) {
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}
	//if there is no label then ignore it
	abrNameForLabel, existingLabel := pr.Labels[dependencybuild.IdLabel]
	if !existingLabel {
		return reconcile.Result{}, nil
	}
	if pr.Status.CompletionTime != nil {
		//the pr is done, lets potentially update the dependency build
		//we just set the state here, the ABR logic is in the ABR controller
		//this keeps as much of the logic in one place as possible
		list := v1alpha1.DependencyBuildList{}
		lbls := map[string]string{dependencybuild.IdLabel: abrNameForLabel}
		listOpts := &client.ListOptions{
			Namespace:     pr.Namespace,
			LabelSelector: labels.SelectorFromSet(lbls),
		}
		err = r.client.List(ctx, &list, listOpts)
		if err != nil {
			return reconcile.Result{}, err
		}
		success := pr.Status.GetCondition(apis.ConditionSucceeded).IsTrue()
		for _, abr := range list.Items {
			if abr.Status.State == v1alpha1.DependencyBuildStateBuilding {
				if success {
					abr.Status.State = v1alpha1.DependencyBuildStateComplete
				} else {
					abr.Status.State = v1alpha1.DependencyBuildStateFailed
				}
				err = r.client.Status().Update(ctx, &abr)
				if err != nil {
					return reconcile.Result{}, err
				}
			}
		}
	}
	return reconcile.Result{}, nil
}
