package artifactbuild

import (
	"context"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

func SetupNewReconcilerWithManager(mgr ctrl.Manager) error {
	r := newReconciler(mgr)
	return ctrl.NewControllerManagedBy(mgr).For(&v1alpha1.ArtifactBuild{}).
		Watches(&tektonpipeline.PipelineRun{}, handler.EnqueueRequestsFromMapFunc(func(ctx context.Context, o client.Object) []reconcile.Request {
			pipelineRun := o.(*tektonpipeline.PipelineRun)
			communityArtifacts := false
			if controllerutil.ContainsFinalizer(pipelineRun, ComponentFinalizer) {
				communityArtifacts = true
			} else {
				if pipelineRun.Status.PipelineSpec != nil && pipelineRun.Status.PipelineSpec.Results != nil {
					for _, r := range pipelineRun.Status.PipelineSpec.Results {
						if r.Name == PipelineResultJavaCommunityDependencies {
							communityArtifacts = true
						}
					}
				}
			}
			if !communityArtifacts {
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
		Watches(&v1alpha1.DependencyBuild{}, handler.EnqueueRequestForOwner(mgr.GetScheme(), mgr.GetRESTMapper(), &v1alpha1.ArtifactBuild{})).
		Complete(r)
}
