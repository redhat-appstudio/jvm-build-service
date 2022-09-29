package rebuiltartifact

import (
	"context"
	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/types"
	"time"

	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"

	"github.com/kcp-dev/logicalcluster/v2"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const JvmBuildServiceFilterConfigMap = "jvm-build-service-filter"

type ReconcilerRebuiltArtifact struct {
	client           client.Client
	scheme           *runtime.Scheme
	eventRecorder    record.EventRecorder
	nonCachingClient client.Client
}

func newReconciler(mgr ctrl.Manager) (reconcile.Reconciler, error) {
	nonCachingClient, err := client.New(mgr.GetConfig(), client.Options{Scheme: mgr.GetScheme()})
	ret := &ReconcilerRebuiltArtifact{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("RebuiltArtifact"),
	}
	if err == nil {
		ret.nonCachingClient = nonCachingClient
	}
	return ret, err
}

func (r *ReconcilerRebuiltArtifact) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	var cancel context.CancelFunc
	if request.ClusterName != "" {
		// use logicalcluster.ClusterFromContxt(ctx) to retrieve this value later on
		ctx = logicalcluster.WithCluster(ctx, logicalcluster.New(request.ClusterName))
	}
	ctx, cancel = context.WithTimeout(ctx, 300*time.Second)
	defer cancel()
	log := ctrl.Log.WithName("rebuiltartifacts").WithValues("request", request.NamespacedName).WithValues("cluster", request.ClusterName)
	rebuiltArtifact := v1alpha1.RebuiltArtifactList{}
	err := r.client.List(ctx, &rebuiltArtifact)
	if err != nil {
		return reconcile.Result{}, err
	}

	//create a bloom filter
	//max size for the filter, will easily fit in a config map
	const max = 1024 * 1000
	size := len(rebuiltArtifact.Items) * 2 //16 bits per item, should give very low error rates
	if size > max {
		size = max
	} else if size < 100 {
		size = 100
	}
	//build the bloom filter
	filter := make([]byte, size)
	for _, item := range rebuiltArtifact.Items {
		for i := int32(1); i <= 10; i++ {
			hash := doHash(i, item.Spec.GAV)
			var totalBits = int32(size * 8)
			hash = hash % totalBits
			if hash < 0 {
				hash = hash * -1
			}
			var pos = hash / 8
			var bit = hash % 8
			filter[pos] = filter[pos] | (1 << bit)
		}
	}
	log.Info("Constructed bloom filter", "filterLength", len(filter))
	cm := v1.ConfigMap{}
	err = r.nonCachingClient.Get(ctx, types.NamespacedName{Namespace: request.Namespace, Name: JvmBuildServiceFilterConfigMap}, &cm)
	if err != nil {
		if errors.IsNotFound(err) {
			cm.BinaryData = map[string][]byte{"filter": filter}
			cm.Name = JvmBuildServiceFilterConfigMap
			cm.Namespace = request.Namespace
			return reconcile.Result{}, r.nonCachingClient.Create(ctx, &cm)
		}
		return reconcile.Result{}, err
	}
	cm.BinaryData["filter"] = filter

	return reconcile.Result{}, r.nonCachingClient.Update(ctx, &cm)
}

func doHash(multiplicand int32, gav string) int32 {
	//super simple hash function
	multiplicand = multiplicand * 7
	hash := int32(0)
	for _, i := range gav {
		hash = multiplicand*hash + i
	}
	return hash
}
