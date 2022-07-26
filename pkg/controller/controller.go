package controller

import (
	"context"
	"fmt"
	"time"

	apiextensionsclient "k8s.io/apiextensions-apiserver/pkg/client/clientset/clientset"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/util/wait"
	k8sscheme "k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/rest"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/cache"
	"sigs.k8s.io/controller-runtime/pkg/manager"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/dependencybuild"

	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
)

var (
	controllerLog = ctrl.Log.WithName("controller")
)

func NewManager(cfg *rest.Config, options manager.Options) (manager.Manager, error) {
	// we have seen in e2e testing that this path can get invoked prior to the TaskRun CRD getting generated,
	// and controller-runtime does not retry on missing CRDs.
	// so we are going to wait on the CRDs existing before moving forward.
	apiextensionsClient := apiextensionsclient.NewForConfigOrDie(cfg)
	if err := wait.PollImmediate(time.Second*5, time.Minute*5, func() (done bool, err error) {
		_, err = apiextensionsClient.ApiextensionsV1().CustomResourceDefinitions().Get(context.TODO(), "taskruns.tekton.dev", metav1.GetOptions{})
		if err != nil {
			controllerLog.Info(fmt.Sprintf("get of taskrun CRD failed with: %s", err.Error()))
			return false, nil
		}
		controllerLog.Info("get of taskrun CRD returned successfully")
		return true, nil
	}); err != nil {
		controllerLog.Error(err, "timed out waiting for taskrun CRD to be created")
		return nil, err
	}

	options.Scheme = runtime.NewScheme()

	// pretty sure this is there by default but we will be explicit like build-service
	if err := k8sscheme.AddToScheme(options.Scheme); err != nil {
		return nil, err
	}

	if err := v1alpha1.AddToScheme(options.Scheme); err != nil {
		return nil, err
	}

	if err := pipelinev1beta1.AddToScheme(options.Scheme); err != nil {
		return nil, err
	}
	options.NewCache = cache.BuilderWithOptions(cache.Options{
		SelectorsByObject: cache.SelectorsByObject{
			&pipelinev1beta1.PipelineRun{}: {Label: labels.SelectorFromSet(map[string]string{artifactbuild.PipelineRunLabel: ""})},
			&v1alpha1.DependencyBuild{}:    {},
			&v1alpha1.ArtifactBuild{}:      {},
		}})
	mgr, err := manager.New(cfg, options)
	if err != nil {
		return nil, err
	}

	if err := artifactbuild.SetupNewReconcilerWithManager(mgr); err != nil {
		return nil, err
	}

	if err := dependencybuild.SetupNewReconcilerWithManager(mgr); err != nil {
		return nil, err
	}

	return mgr, nil
}
