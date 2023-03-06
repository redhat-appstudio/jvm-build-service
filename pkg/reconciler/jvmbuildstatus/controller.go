package jvmbuildstatus

import (
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"sigs.k8s.io/controller-runtime/pkg/source"

	jvmbs "github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
)

func SetupNewReconcilerWithManager(mgr ctrl.Manager) error {
	r := newReconciler(mgr)
	return ctrl.NewControllerManagedBy(mgr).For(&jvmbs.JvmBuildStatus{}).
		Watches(&source.Kind{Type: &jvmbs.ArtifactBuild{}}, handler.EnqueueRequestsFromMapFunc(func(o client.Object) []reconcile.Request {
			artifactBuild := o.(*jvmbs.ArtifactBuild)
			return []reconcile.Request{
				{
					NamespacedName: types.NamespacedName{
						Name:      artifactBuild.Name,
						Namespace: artifactBuild.Namespace,
					},
				},
			}
		})).Watches(&source.Kind{Type: &v1beta1.PipelineRun{}}, handler.EnqueueRequestsFromMapFunc(func(o client.Object) []reconcile.Request {
		pipelineRun := o.(*v1beta1.PipelineRun)
		if pipelineRun.Status.PipelineResults != nil {
			for _, r := range pipelineRun.Status.PipelineResults {
				if r.Name == artifactbuild.JavaDependencies {
					return []reconcile.Request{
						{
							NamespacedName: types.NamespacedName{
								Name:      pipelineRun.Name,
								Namespace: pipelineRun.Namespace,
							},
						},
					}
				}
			}
		}
		return []reconcile.Request{}
	})).
		Complete(r)
}
