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

	var items []string
	for _, i := range rebuiltArtifact.Items {
		items = append(items, i.Spec.GAV)
	}
	//build the bloom filter
	filter := CreateBloomFilter(items)
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
