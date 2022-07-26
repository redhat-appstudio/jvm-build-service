package dependencybuild

import (
	"k8s.io/apimachinery/pkg/types"

	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/event"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/predicate"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"sigs.k8s.io/controller-runtime/pkg/source"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
)

func SetupNewReconcilerWithManager(mgr ctrl.Manager) error {
	r := newReconciler(mgr)
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
		Watches(&source.Kind{Type: &v1beta1.PipelineRun{}}, handler.EnqueueRequestsFromMapFunc(func(o client.Object) []reconcile.Request {
			pipelineRun := o.(*v1beta1.PipelineRun)

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
