package tektonwrapper

import (
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	ctrl "sigs.k8s.io/controller-runtime"
)

func SetupPRReconcilerWithManager(mgr ctrl.Manager) error {
	r := newPRReconciler(mgr)
	return ctrl.NewControllerManagedBy(mgr).For(&v1beta1.PipelineRun{}).Complete(r)
}
