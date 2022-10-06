package systemconfig

import (
	"context"
	"fmt"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/clusterresourcequota"
	"strings"
	"time"

	"github.com/kcp-dev/logicalcluster/v2"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/k8sresourcequota"

	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/record"

	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const SystemConfigKey = "cluster"

type ReconcilerSystemConfig struct {
	client        client.Client
	scheme        *runtime.Scheme
	eventRecorder record.EventRecorder
	config        *rest.Config
	mgr           ctrl.Manager
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcilerSystemConfig{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("ArtifactBuild"),
		config:        mgr.GetConfig(),
		mgr:           mgr,
	}
}

func (r *ReconcilerSystemConfig) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
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
		foundJDK8 := false
		foundJDK11 := false
		foundJDK17 := false
		logMsg := ""
		logChunk := "jvm-build-service 'cluster' instance of its system config has incorrect builder related information %s\n"
		for key, bldr := range systemConfig.Spec.Builders {
			switch key {
			case v1alpha1.JDK8Builder:
				foundJDK8 = true
			case v1alpha1.JDK11Builder:
				foundJDK11 = true
			case v1alpha1.JDK17Builder:
				foundJDK17 = true
			default:
				logMsg = logMsg + fmt.Sprintf(logChunk, "unrecognized builder "+key+"\n")
			}
			if len(strings.TrimSpace(bldr.Image)) == 0 {
				logMsg = logMsg + fmt.Sprintf(logChunk, key+" has missing image\n")
			}
			if len(strings.TrimSpace(bldr.Tag)) == 0 {
				logMsg = logMsg + fmt.Sprintf(logChunk, key+" has missing tags\n")
			}
		}
		switch {
		case !foundJDK8:
			logMsg = logMsg + v1alpha1.JDK8Builder + " builder is missing\n"
		case !foundJDK11:
			logMsg = logMsg + v1alpha1.JDK11Builder + " builder is missing\n"
		case !foundJDK17:
			logMsg = logMsg + v1alpha1.JDK17Builder + " builder is missing\n"
		}
		if len(logMsg) > 1 {
			return reconcile.Result{}, fmt.Errorf(logMsg)
		}

		switch systemConfig.Spec.Quota {
		case v1alpha1.K8SQuota:
			err = k8sresourcequota.SetupNewReconcilerWithManager(r.mgr)
			if err != nil {
				return reconcile.Result{}, err
			}
		case v1alpha1.OpenShiftQuota:
			fallthrough
		default:
			if r.config != nil {
				err = clusterresourcequota.SetupNewReconciler(r.config)
				if err != nil {
					return reconcile.Result{}, err
				}
			}
		}
		log.Info("system config available and valid")
	}
	return reconcile.Result{}, nil
}
