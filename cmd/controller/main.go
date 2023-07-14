package main

import (
	"flag"
	zap2 "go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"net/http"
	"os"
	"strings"

	// needed for hack/update-codegen.sh
	_ "k8s.io/code-generator"

	"k8s.io/klog/v2"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/healthz"
	"sigs.k8s.io/controller-runtime/pkg/log/zap"

	//+kubebuilder:scaffold:imports
	"github.com/go-logr/logr"
	"github.com/redhat-appstudio/image-controller/pkg/quay"
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
		TimeEncoder: zapcore.RFC3339TimeEncoder,
		ZapOpts:     []zap2.Option{zap2.WithCaller(true)},
	}
	opts.BindFlags(flag.CommandLine)
	klog.InitFlags(flag.CommandLine)

	flag.Parse()

	logger := zap.New(zap.UseFlagOptions(&opts))

	ctrl.SetLogger(logger)
	mainLog = ctrl.Log.WithName("main")
	ctx := ctrl.SetupSignalHandler()
	restConfig := ctrl.GetConfigOrDie()
	klog.SetLogger(mainLog)

	tokenPath := "/workspace/quaytoken" //#nosec
	tokenContent, err := os.ReadFile(tokenPath)
	if err != nil {
		mainLog.Error(err, "unable to read quay token")
	}
	orgPath := "/workspace/organization"
	orgContent, err := os.ReadFile(orgPath)
	if err != nil {
		mainLog.Error(err, "unable to read quay organization")
	}
	var quayClient *quay.QuayClient
	if orgContent != nil && tokenContent != nil {
		client := quay.NewQuayClient(&http.Client{Transport: &http.Transport{}}, strings.TrimSpace(string(tokenContent)), "https://quay.io/api/v1")
		quayClient = client
	}

	var mgr ctrl.Manager
	mopts := ctrl.Options{
		MetricsBindAddress:     metricsAddr,
		Port:                   9443,
		HealthProbeBindAddress: probeAddr,
		LeaderElection:         enableLeaderElection,
		LeaderElectionID:       "5483be8f.redhat.com",
	}

	util.ImageTag = os.Getenv("IMAGE_TAG")
	util.ImageRepo = os.Getenv("IMAGE_REPO")

	mgr, err = controller.NewManager(restConfig, mopts, quayClient, string(orgContent))
	if err != nil {
		mainLog.Error(err, "unable to start manager")
		os.Exit(1)
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
