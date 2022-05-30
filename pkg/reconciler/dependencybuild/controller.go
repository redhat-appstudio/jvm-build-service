package dependencybuild

import (
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/event"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/predicate"
	"sigs.k8s.io/controller-runtime/pkg/source"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
)

func SetupNewReconcilerWithManager(mgr ctrl.Manager, nonCachingClient client.Client) error {
	r := newReconciler(mgr, nonCachingClient)
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
		Watches(&source.Kind{Type: &v1beta1.PipelineRun{}}, &handler.EnqueueRequestForOwner{OwnerType: &v1alpha1.DependencyBuild{}, IsController: false}).
		Complete(r)
}
