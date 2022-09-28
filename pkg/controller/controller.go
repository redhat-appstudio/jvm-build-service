package controller

import (
	"context"
	"fmt"
	"time"

	kcpcache "github.com/kcp-dev/apimachinery/pkg/cache"
	"github.com/kcp-dev/apimachinery/third_party/informers"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/clusterresourcequota"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/dependencybuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/systemconfig"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/tektonwrapper"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/userconfig"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	v1 "k8s.io/api/core/v1"
	apiextensionsclient "k8s.io/apiextensions-apiserver/pkg/client/clientset/clientset"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/util/wait"
	k8sscheme "k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/rest"
	k8scache "k8s.io/client-go/tools/cache"

	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/cache"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/cluster"
	ctrlkcp "sigs.k8s.io/controller-runtime/pkg/kcp"
)

var (
	controllerLog = ctrl.Log.WithName("controller")
)

func NewManager(cfg *rest.Config, options ctrl.Options, kcp bool) (ctrl.Manager, error) {
	// do not check tekton in kcp
	if !kcp {
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

	var mgr ctrl.Manager
	var err error
	if kcp {
		newClusterAwareCacheFunc := func(config *rest.Config, opts cache.Options) (cache.Cache, error) {
			// copy from https://github.com/kcp-dev/controller-runtime/blob/824b15a11b186ee83a716bbc28d9b7b1ca538f6a/pkg/kcp/wrappers.go#L62-L72
			c := rest.CopyConfig(config)
			c.Host += "/clusters/*"
			opts.NewInformerFunc = informers.NewSharedIndexInformer
			opts.Indexers = k8scache.Indexers{
				kcpcache.ClusterIndexName:             kcpcache.ClusterIndexFunc,
				kcpcache.ClusterAndNamespaceIndexName: kcpcache.ClusterAndNamespaceIndexFunc,
			}

			// addition beyond ctrlkcp.NewClusterAwareCache that we need for our watches
			opts.SelectorsByObject = cache.SelectorsByObject{
				&pipelinev1beta1.PipelineRun{}: {Label: labels.SelectorFromSet(map[string]string{artifactbuild.PipelineRunLabel: ""})},
				&v1alpha1.DependencyBuild{}:    {},
				&v1alpha1.ArtifactBuild{}:      {},
				&v1.ConfigMap{}:                {},
			}
			return cache.New(c, opts)
		}
		options.NewCache = newClusterAwareCacheFunc
		newClusterAwareClientFunc := func(cache cache.Cache, config *rest.Config, opts client.Options, uncachedObjects ...client.Object) (client.Client, error) {
			httpClient, err := ctrlkcp.ClusterAwareHTTPClient(config)
			if err != nil {
				return nil, err
			}
			opts.HTTPClient = httpClient
			secretObj := v1.Secret{}
			// we fetch the image registry secret for validation, but we don't want to cache / establish watches for that
			uncachedObjects = append(uncachedObjects, &secretObj)
			return cluster.DefaultNewClient(cache, config, opts, uncachedObjects...)

		}
		options.NewClient = newClusterAwareClientFunc
		// set up mgr with all the kcp overrides
		mgr, err = ctrlkcp.NewClusterAwareManager(cfg, options)
	} else {
		options.NewCache = cache.BuilderWithOptions(cache.Options{
			SelectorsByObject: cache.SelectorsByObject{
				&pipelinev1beta1.PipelineRun{}: {Label: labels.SelectorFromSet(map[string]string{artifactbuild.PipelineRunLabel: ""})},
				&v1alpha1.DependencyBuild{}:    {},
				&v1alpha1.ArtifactBuild{}:      {},
				&v1.ConfigMap{}:                {},
			}})

		mgr, err = ctrl.NewManager(cfg, options)
	}
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

	if err := userconfig.SetupNewReconcilerWithManager(mgr); err != nil {
		return nil, err
	}

	// TODO we are not syncing these types here at all ... do we need the kcp modified cfg?
	if err := clusterresourcequota.SetupNewReconciler(cfg); err != nil {
		return nil, err
	}

	if err := tektonwrapper.SetupNewReconcilerWithManager(mgr); err != nil {
		return nil, err
	}

	return mgr, nil
}
