package configmap

import (
	v1 "k8s.io/api/core/v1"
	ctrl "sigs.k8s.io/controller-runtime"
)

func SetupNewReconcilerWithManager(mgr ctrl.Manager, systemConfig map[string]string, bi *BuilderImageConfig) error {
	r := newReconciler(mgr, systemConfig, bi)
	return ctrl.NewControllerManagedBy(mgr).For(&v1.ConfigMap{}).
		Complete(r)
}
