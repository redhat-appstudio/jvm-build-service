package artifactbuildrequest

import (
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/source"
)

func SetupNewReconcilerWithManager(mgr ctrl.Manager, nonCachingClient client.Client) error {
	r := newReconciler(mgr, nonCachingClient)
	return ctrl.NewControllerManagedBy(mgr).For(&v1alpha1.ArtifactBuildRequest{}).
		//we can't use .Owns() here as controller is not true in the owner ref
		Watches(&source.Kind{Type: &v1beta1.TaskRun{}}, &handler.EnqueueRequestForOwner{OwnerType: &v1alpha1.ArtifactBuildRequest{}, IsController: false}).
		Watches(&source.Kind{Type: &v1alpha1.DependencyBuild{}}, &handler.EnqueueRequestForOwner{OwnerType: &v1alpha1.ArtifactBuildRequest{}, IsController: false}).
		Complete(r)
}
