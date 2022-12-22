package pendingpipelinerun

import (
	"github.com/redhat-appstudio/jvm-build-service/pkg/metrics"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	ctrl "sigs.k8s.io/controller-runtime"
)

func SetupPRReconcilerWithManager(mgr ctrl.Manager) error {
	r := newPRReconciler(mgr)
	metrics.InitPrometheus(mgr.GetClient())
	return ctrl.NewControllerManagedBy(mgr).For(&v1beta1.PipelineRun{}).Complete(r)
}
