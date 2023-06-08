package controller

import (
	"context"
	"fmt"
	"github.com/redhat-appstudio/image-controller/pkg/quay"
	"github.com/redhat-appstudio/jvm-build-service/pkg/metrics"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/selection"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/dependencybuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/jbsconfig"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/systemconfig"
	spi "github.com/redhat-appstudio/service-provider-integration-operator/api/v1beta1"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	appsv1 "k8s.io/api/apps/v1"
	v1 "k8s.io/api/core/v1"
	rbacv1 "k8s.io/api/rbac/v1"
	apiextensionsclient "k8s.io/apiextensions-apiserver/pkg/client/clientset/clientset"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/util/wait"
	k8sscheme "k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/rest"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/cache"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

var (
	controllerLog = ctrl.Log.WithName("controller")
)

func NewManager(cfg *rest.Config, options ctrl.Options, quayClient *quay.QuayClient, quayOrgName string) (ctrl.Manager, error) {

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

	//check for the SPI to be present
	spiPresent := true
	_, err := apiextensionsClient.ApiextensionsV1().CustomResourceDefinitions().Get(context.TODO(), "spiaccesstokenbindings.appstudio.redhat.com", metav1.GetOptions{})
	if err != nil {
		spiPresent = false
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

	if spiPresent {
		if err := spi.AddToScheme(options.Scheme); err != nil {
			return nil, err
		}
	}

	var mgr ctrl.Manager
	// this replaces the need for creating a non-caching client to access these various types
	options.ClientDisableCacheFor = []client.Object{
		&v1.ConfigMap{},
		&v1.Secret{},
		&v1.Service{},
		&v1.ServiceAccount{},
		&v1.PersistentVolumeClaim{},
		&rbacv1.RoleBinding{},
		&appsv1.Deployment{},
	}

	//we only want to watch the runs we create
	ourPipelines := labels.NewSelector()
	requirement, lerr := labels.NewRequirement(artifactbuild.PipelineRunLabel, selection.Exists, []string{})
	if lerr != nil {
		return nil, lerr
	}
	ourPipelines.Add(*requirement)
	//we only want to watch the runs we create
	cachePods := labels.NewSelector()
	cacheRequirement, lerr := labels.NewRequirement("app", selection.Equals, []string{v1alpha1.CacheDeploymentName})
	if lerr != nil {
		return nil, lerr
	}
	cachePods.Add(*cacheRequirement)
	options.NewCache = cache.BuilderWithOptions(cache.Options{
		SelectorsByObject: cache.SelectorsByObject{
			&pipelinev1beta1.PipelineRun{}: {Label: ourPipelines},
			&v1alpha1.DependencyBuild{}:    {},
			&v1alpha1.ArtifactBuild{}:      {},
			&v1alpha1.RebuiltArtifact{}:    {},
			&v1.Pod{}:                      {Label: cachePods},
		}})

	mgr, err = ctrl.NewManager(cfg, options)

	if err != nil {
		return nil, err
	}

	if err := artifactbuild.SetupNewReconcilerWithManager(mgr); err != nil {
		return nil, err
	}

	if err := dependencybuild.SetupNewReconcilerWithManager(mgr); err != nil {
		return nil, err
	}

	if err := systemconfig.SetupNewReconcilerWithManager(mgr); err != nil {
		return nil, err
	}

	if err := jbsconfig.SetupNewReconcilerWithManager(mgr, spiPresent, quayClient, quayOrgName); err != nil {
		return nil, err
	}

	metrics.InitPrometheus(mgr.GetClient())
	return mgr, nil
}
