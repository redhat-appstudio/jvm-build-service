package jbsconfig

import (
	imagecontroller "github.com/redhat-appstudio/image-controller/api/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/handler"
	"sigs.k8s.io/controller-runtime/pkg/source"
)

func SetupNewReconcilerWithManager(mgr ctrl.Manager, spiPresent bool) error {
	r := newReconciler(mgr, spiPresent)
	builder := ctrl.NewControllerManagedBy(mgr).
		For(&v1alpha1.JBSConfig{})
	if spiPresent {
		builder.Watches(&source.Kind{Type: &imagecontroller.ImageRepository{}}, &handler.EnqueueRequestForOwner{OwnerType: &v1alpha1.JBSConfig{}, IsController: false})
	}
	return builder.Complete(r)
}
