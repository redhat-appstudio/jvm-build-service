package main

import (
	"context"
	"flag"
	"fmt"
	"os"

	// needed for hack/update-codegen.sh
	_ "k8s.io/code-generator"

	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/discovery"
	"k8s.io/client-go/rest"
	"k8s.io/klog/v2"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/healthz"
	"sigs.k8s.io/controller-runtime/pkg/kcp"
	"sigs.k8s.io/controller-runtime/pkg/log/zap"

	//+kubebuilder:scaffold:imports
	"github.com/go-logr/logr"
	apisv1alpha1 "github.com/kcp-dev/kcp/pkg/apis/apis/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/controller"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"
)

var (
	mainLog logr.Logger
)

func main() {
	var metricsAddr string
	var enableLeaderElection bool
	var probeAddr string
	var abAPIExportName string
	flag.StringVar(&metricsAddr, "metrics-bind-address", ":8080", "The address the metric endpoint binds to.")
	flag.StringVar(&probeAddr, "health-probe-bind-address", ":8081", "The address the probe endpoint binds to.")
	flag.StringVar(&abAPIExportName, "api-export-name", "jvm-build-service", "The name of the jvm-build-service APIExport.")

	flag.BoolVar(&enableLeaderElection, "leader-elect", false,
		"Enable leader election for controller manager. "+
			"Enabling this will ensure there is only one active controller manager.")
	opts := zap.Options{
		Development: true,
	}
	opts.BindFlags(flag.CommandLine)
	klog.InitFlags(flag.CommandLine)
	flag.Parse()

	logger := zap.New(zap.UseFlagOptions(&opts))
	ctrl.SetLogger(logger)
	mainLog = ctrl.Log.WithName("main")
	ctx := ctrl.SetupSignalHandler()
	restConfig := ctrl.GetConfigOrDie()

	var mgr ctrl.Manager
	var err error
	mopts := ctrl.Options{
		MetricsBindAddress:     metricsAddr,
		Port:                   9443,
		HealthProbeBindAddress: probeAddr,
		LeaderElection:         enableLeaderElection,
		LeaderElectionID:       "5483be8f.redhat.com",
	}

	util.ImageTag = os.Getenv("IMAGE_TAG")
	util.ImageRepo = os.Getenv("IMAGE_REPO")
	if kcpAPIsGroupPresent(restConfig) {
		mainLog.Info("Looking up virtual workspace URL")
		var cfg *rest.Config
		cfg, err = restConfigForAPIExport(ctx, restConfig, abAPIExportName)
		if err != nil {
			mainLog.Error(err, "error looking up virtual workspace URL")
			os.Exit(1)
		}

		mainLog.Info("Using virtual workspace URL", "url", cfg.Host)
		util.KCP = true

		mopts.LeaderElectionConfig = restConfig
		// see kcp.NewClusterAwareManager; do not call that directly given the additional items we do in
		// controller.NewManager
		// also, we handle the setting of NewCache in the controller.NewManager call
		mopts.NewClient = kcp.NewClusterAwareClient
		mopts.MapperProvider = kcp.NewClusterAwareMapperProvider
		mgr, err = controller.NewManager(cfg, mopts, true)
		if err != nil {
			mainLog.Error(err, "unable to start cluster aware manager")
			os.Exit(1)
		}
	} else {
		mainLog.Info("The apis.kcp.dev group is not present - creating standard manager")
		mgr, err = controller.NewManager(restConfig, mopts, false)
		if err != nil {
			mainLog.Error(err, "unable to start manager")
			os.Exit(1)
		}
	}

	//+kubebuilder:scaffold:builder

	if err := mgr.AddHealthzCheck("healthz", healthz.Ping); err != nil {
		mainLog.Error(err, "unable to set up health check")
		os.Exit(1)
	}
	if err := mgr.AddReadyzCheck("readyz", healthz.Ping); err != nil {
		mainLog.Error(err, "unable to set up ready check")
		os.Exit(1)
	}

	mainLog.Info("starting manager")
	if err := mgr.Start(ctx); err != nil {
		mainLog.Error(err, "problem running manager")
		os.Exit(1)
	}
}

// restConfigForAPIExport returns a *rest.Config properly configured to communicate with the endpoint for the
// APIExport's virtual workspace.
func restConfigForAPIExport(ctx context.Context, cfg *rest.Config, apiExportName string) (*rest.Config, error) {
	scheme := runtime.NewScheme()
	if err := apisv1alpha1.AddToScheme(scheme); err != nil {
		return nil, fmt.Errorf("error adding apis.kcp.dev/v1alpha1 to scheme: %w", err)
	}

	apiExportClient, err := client.New(cfg, client.Options{Scheme: scheme})
	if err != nil {
		return nil, fmt.Errorf("error creating APIExport client: %w", err)
	}

	var apiExport apisv1alpha1.APIExport

	if len(apiExportName) > 0 {
		if err := apiExportClient.Get(ctx, types.NamespacedName{Name: apiExportName}, &apiExport); err != nil {
			return nil, fmt.Errorf("error getting APIExport %q: %w", apiExportName, err)
		}
		mainLog.Info("found our apiexport")

	} else {
		mainLog.Info("api-export-name is empty - listing")
		exports := &apisv1alpha1.APIExportList{}
		if err := apiExportClient.List(ctx, exports); err != nil {
			return nil, fmt.Errorf("error listing APIExports: %w", err)
		}
		if len(exports.Items) == 0 {
			return nil, fmt.Errorf("no APIExport found")
		}
		if len(exports.Items) > 1 {
			return nil, fmt.Errorf("more than one APIExport found")
		}
		apiExport = exports.Items[0]
	}

	if len(apiExport.Status.VirtualWorkspaces) < 1 {
		return nil, fmt.Errorf("APIExport %s status.virtualWorkspaces is empty", apiExportName)
	}

	for _, vws := range apiExport.Status.VirtualWorkspaces {
		mainLog.Info(fmt.Sprintf("found virtual workspace %s", vws.URL))
	}

	cfg = rest.CopyConfig(cfg)
	// TODO(ncdc): sharding support
	cfg.Host = apiExport.Status.VirtualWorkspaces[0].URL

	return cfg, nil
}

func kcpAPIsGroupPresent(restConfig *rest.Config) bool {
	discoveryClient, err := discovery.NewDiscoveryClientForConfig(restConfig)
	if err != nil {
		mainLog.Error(err, "failed to create discovery client")
		os.Exit(1)
	}
	apiGroupList, err := discoveryClient.ServerGroups()
	if err != nil {
		mainLog.Error(err, "failed to get server groups")
		os.Exit(1)
	}

	for _, group := range apiGroupList.Groups {
		if group.Name == apisv1alpha1.SchemeGroupVersion.Group {
			for _, version := range group.Versions {
				if version.Version == apisv1alpha1.SchemeGroupVersion.Version {
					return true
				}
			}
		}
	}
	return false
}
