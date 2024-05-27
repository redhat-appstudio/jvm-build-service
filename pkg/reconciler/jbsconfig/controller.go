package jbsconfig

import (
	imagecontroller "github.com/konflux-ci/image-controller/api/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/handler"
)

func SetupNewReconcilerWithManager(mgr ctrl.Manager, spiPresent bool) error {
	r := newReconciler(mgr, spiPresent)
	builder := ctrl.NewControllerManagedBy(mgr).
		For(&v1alpha1.JBSConfig{})
	if spiPresent {
		builder.Watches(&imagecontroller.ImageRepository{}, handler.EnqueueRequestForOwner(mgr.GetScheme(), mgr.GetRESTMapper(), &v1alpha1.JBSConfig{}))
	}
	return builder.Complete(r)
}
