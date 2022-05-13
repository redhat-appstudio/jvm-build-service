package controller

import (
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/taskrun"
	k8sscheme "k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/rest"

	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/manager"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuildrequest"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/dependencybuild"

	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
)

var (
	controllerLog = ctrl.Log.WithName("controller")
)

func NewManager(cfg *rest.Config, options manager.Options) (manager.Manager, error) {
	mgr, err := manager.New(cfg, options)
	if err != nil {
		return nil, err
	}

	controllerLog.Info("Registering Components.")

	// pretty sure this is there by default but we will be explicit like build-service
	if err := k8sscheme.AddToScheme(mgr.GetScheme()); err != nil {
		return nil, err
	}

	if err := v1alpha1.AddToScheme(mgr.GetScheme()); err != nil {
		return nil, err
	}

	if err := pipelinev1beta1.AddToScheme(mgr.GetScheme()); err != nil {
		return nil, err
	}

	// Add Reconcilers
	if err := artifactbuildrequest.SetupNewReconcilerWithManager(mgr); err != nil {
		return nil, err
	}

	if err := dependencybuild.SetupNewReconcilerWithManager(mgr); err != nil {
		return nil, err
	}

	if err := taskrun.SetupNewReconcilerWithManager(mgr); err != nil {
		return nil, err
	}

	return mgr, nil
}
