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
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/rebuiltartifact"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/systemconfig"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/tektonwrapper"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/userconfig"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	apiextensionsclient "k8s.io/apiextensions-apiserver/pkg/client/clientset/clientset"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/util/wait"
	k8sscheme "k8s.io/client-go/kubernetes/scheme"
	"k8s.io/client-go/rest"
	k8scache "k8s.io/client-go/tools/cache"

	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/cache"
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

	c := rest.CopyConfig(cfg)
	cacheOptions := cache.Options{
		SelectorsByObject: cache.SelectorsByObject{
			&pipelinev1beta1.PipelineRun{}: {},
			&v1alpha1.DependencyBuild{}:    {},
			&v1alpha1.ArtifactBuild{}:      {},
			&v1alpha1.RebuiltArtifact{}:    {},
		}}
	if kcp {
		// see https://github.com/kcp-dev/controller-runtime/blob/824b15a11b186ee83a716bbc28d9b7b1ca538f6a/pkg/kcp/wrappers.go#L62-L72
		c.Host += "/clusters/*"
		controllerLog.Info(fmt.Sprintf("rest config host now %s", c.Host))
		cacheOptions.NewInformerFunc = informers.NewSharedIndexInformer
		cacheOptions.Indexers = k8scache.Indexers{
			kcpcache.ClusterIndexName:             kcpcache.ClusterIndexFunc,
			kcpcache.ClusterAndNamespaceIndexName: kcpcache.ClusterAndNamespaceIndexFunc,
		}
	}
	options.NewCache = cache.BuilderWithOptions(cacheOptions)
	mgr, err := ctrl.NewManager(c, options)
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
	if err := rebuiltartifact.SetupNewReconcilerWithManager(mgr); err != nil {
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
