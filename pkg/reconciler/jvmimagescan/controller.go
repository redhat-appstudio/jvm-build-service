package jvmimagescan

import (
	"context"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
)

func SetupNewReconcilerWithManager(mgr ctrl.Manager) error {
	r := newReconciler(mgr)
	return ctrl.NewControllerManagedBy(mgr).For(&v1alpha1.JvmImageScan{}).
		Watches(&tektonpipeline.PipelineRun{}, handler.EnqueueRequestsFromMapFunc(func(ctx context.Context, o client.Object) []reconcile.Request {
			pipelineRun := o.(*tektonpipeline.PipelineRun)

			ours := false
			if controllerutil.ContainsFinalizer(pipelineRun, ImageScanFinalizer) {
				ours = true
			} else if pipelineRun.Labels != nil && pipelineRun.Labels[ImageScanPipelineRunLabel] != "" {
				ours = true
			}
			if !ours {
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
