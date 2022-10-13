package userconfig

import (
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	ctrl "sigs.k8s.io/controller-runtime"
)

func SetupNewReconcilerWithManager(mgr ctrl.Manager, kcp bool) error {
	r := newReconciler(mgr, kcp)
	return ctrl.NewControllerManagedBy(mgr).For(&v1alpha1.UserConfig{}).Complete(r)
}
