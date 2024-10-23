package dependencybuild

import (
	"context"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/kubernetes"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/event"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/predicate"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

func SetupNewReconcilerWithManager(mgr ctrl.Manager) error {

	clientset, err := kubernetes.NewForConfig(mgr.GetConfig())
	if err != nil {
		return err
	}
	r := newReconciler(mgr, clientset)
	return ctrl.NewControllerManagedBy(mgr).
		For(&v1alpha1.DependencyBuild{}, builder.WithPredicates(predicate.Funcs{
			CreateFunc: func(e event.CreateEvent) bool {
				return true
			},
			UpdateFunc: func(e event.UpdateEvent) bool {
				return true
			},
			DeleteFunc: func(e event.DeleteEvent) bool {
				//TODO possibly change to false over time
				return true
			},
			GenericFunc: func(e event.GenericEvent) bool {
				//TODO possibly change to false over time
				return true
			},
		})).
		Watches(&tektonpipeline.PipelineRun{}, handler.EnqueueRequestsFromMapFunc(func(ctx context.Context, o client.Object) []reconcile.Request {
			pipelineRun := o.(*tektonpipeline.PipelineRun)

			// check if the TaskRun is related to DependencyBuild
			if pipelineRun.GetLabels() == nil {
				return []reconcile.Request{}
			}
			_, ok := pipelineRun.GetLabels()[artifactbuild.PipelineRunLabel]
			if !ok {
				return []reconcile.Request{}
			}
			_, ok = pipelineRun.GetLabels()[artifactbuild.DependencyBuildIdLabel]
			if !ok {
				return []reconcile.Request{}
			}
			return []reconcile.Request{
				{
					NamespacedName: types.NamespacedName{
						Name:      pipelineRun.Name,
						Namespace: pipelineRun.Namespace,
					},
				},
			}
		})).
		Complete(r)
}
