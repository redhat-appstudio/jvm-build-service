package systemconfig

import (
	"context"
	"fmt"
	"time"

	"github.com/kcp-dev/logicalcluster/v2"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"

	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"

	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const SystemConfigKey = "cluster"

type ReconcileSystemConfig struct {
	client        client.Client
	scheme        *runtime.Scheme
	eventRecorder record.EventRecorder
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcileSystemConfig{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("ArtifactBuild"),
	}
}

func (r *ReconcileSystemConfig) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	var cancel context.CancelFunc
	if request.ClusterName != "" {
		// use logicalcluster.ClusterFromContxt(ctx) to retrieve this value later on
		ctx = logicalcluster.WithCluster(ctx, logicalcluster.New(request.ClusterName))
	}
	ctx, cancel = context.WithTimeout(ctx, 300*time.Second)
	defer cancel()
	log := ctrl.Log.WithName("systemconfig").WithValues("request", request.NamespacedName).WithValues("cluster", request.ClusterName)
	systemConfig := v1alpha1.SystemConfig{}
	err := r.client.Get(ctx, request.NamespacedName, &systemConfig)
	if err != nil {
		return reconcile.Result{}, err
	}
	if systemConfig.Name == SystemConfigKey {
		logMsg := ";"
		logChunk := "jvm-build-service 'cluster' instance of its system config has missing field %s"
		switch {
		case len(systemConfig.Spec.JDK8Image) == 0:
			logMsg = fmt.Sprintf(logChunk, "jdk8image")
		case len(systemConfig.Spec.JDK8Tags) == 0:
			logMsg = fmt.Sprintf(logChunk, "jdk8tags")
		case len(systemConfig.Spec.JDK11Image) == 0:
			logMsg = fmt.Sprintf(logChunk, "jdk11image")
		case len(systemConfig.Spec.JDK11Tags) == 0:
			logMsg = fmt.Sprintf(logChunk, "jdk11tags")
		case len(systemConfig.Spec.JDK17Image) == 0:
			logMsg = fmt.Sprintf(logChunk, "jdk17image")
		case len(systemConfig.Spec.JDK17Tags) == 0:
			logMsg = fmt.Sprintf(logChunk, "jdk17tags")
		}
		if len(logMsg) > 1 {
			return reconcile.Result{}, fmt.Errorf(logMsg)
		}
		log.Info("system config available and valid")
	}
	return reconcile.Result{}, nil
}
