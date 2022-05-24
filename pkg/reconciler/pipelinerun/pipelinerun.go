package pipelinerun

import (
	"context"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"knative.dev/pkg/apis"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"strings"
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
	if pr.Status.CompletionTime != nil {
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
		for _, ref := range pr.OwnerReferences {
			dep := v1alpha1.DependencyBuild{}
			if err = r.client.Get(ctx, types.NamespacedName{Namespace: pr.Namespace, Name: ref.Name}, &dep); err != nil {
				return reconcile.Result{}, err
			}
			if dep.Status.State == v1alpha1.DependencyBuildStateBuilding {
				if success {
					if len(contaminates) == 0 {
						dep.Status.State = v1alpha1.DependencyBuildStateComplete
					} else {
						//the dependency was contaminated with community deps
						//most likely shaded in
						dep.Status.State = v1alpha1.DependencyBuildStateContaminated
						dep.Status.Contaminants = contaminates
					}
				} else {
					dep.Status.State = v1alpha1.DependencyBuildStateFailed
				}
				err = r.client.Status().Update(ctx, &dep)
				if err != nil {
					return reconcile.Result{}, err
				}
			}
		}
	}
	return reconcile.Result{}, nil
}
