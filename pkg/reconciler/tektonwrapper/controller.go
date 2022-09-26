package tektonwrapper

import (
	"context"
	"fmt"
	"time"

	"github.com/kcp-dev/logicalcluster/v2"
	quotav1 "github.com/openshift/api/quota/v1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/builder"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/event"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/predicate"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"sigs.k8s.io/controller-runtime/pkg/source"
)

var (
	ctrlLog = ctrl.Log.WithName("tektonwrappercontroller")
)

func SetupNewReconcilerWithManager(mgr ctrl.Manager) error {
	opts := client.Options{Scheme: runtime.NewScheme()}
	err := quotav1.AddToScheme(opts.Scheme)
	if err != nil {
		return err
	}
	r := newReconciler(mgr)
	pruner := &pruner{client: mgr.GetClient()}
	_ = mgr.Add(pruner)
	return ctrl.NewControllerManagedBy(mgr).
		For(&v1alpha1.TektonWrapper{}, builder.WithPredicates(predicate.Funcs{
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
			_, ok := pipelineRun.GetLabels()[TektonWrapperId]
			if !ok {
				return []reconcile.Request{}
			}
			return []reconcile.Request{
				{
					NamespacedName: types.NamespacedName{
						Name:      pipelineRun.Name,
						Namespace: pipelineRun.Namespace,
					},
					ClusterName: logicalcluster.From(pipelineRun).String(),
				},
			}
		})).
		Complete(r)
}

type pruner struct {
	client client.Client
}

func (p *pruner) Start(ctx context.Context) error {
	ticker := time.NewTicker(3 * time.Minute)
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			twList := v1alpha1.TektonWrapperList{}
			err := p.client.List(ctx, &twList)
			if err != nil {
				ctrlLog.Info(fmt.Sprintf("pruner list error %s", err.Error()))
				continue
			}
			for i, tw := range twList.Items {
				if tw.Status.State == v1alpha1.TektonWrapperStateComplete {
					err = p.client.Delete(ctx, &twList.Items[i])
					if err != nil {
						ctrlLog.Info(fmt.Sprintf("pruner delete err %s", err.Error()))
					}
				}
			}
		}
	}
}
