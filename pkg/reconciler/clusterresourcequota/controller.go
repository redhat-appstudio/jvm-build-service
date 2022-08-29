package clusterresourcequota

import (
	"time"

	"k8s.io/client-go/rest"
	"k8s.io/client-go/tools/cache"
	"k8s.io/client-go/util/workqueue"

	quotav1 "github.com/openshift/api/quota/v1"
	quotaclientset "github.com/openshift/client-go/quota/clientset/versioned"
	quotainformer "github.com/openshift/client-go/quota/informers/externalversions"
)

/*
NOTE - controller runtime did not seem able to digest the CRD generated over in https://github.com/openshift/api
for quota (it is kind of a "courtesy" effort based as I can tell since the none of the consuming controllers in openshift
consume that API via CRD, nor our those controllers controller runtime based); as such, we are using the lower level
k8s client shared informer and rate limiting workqueue APIs to build a controller cache with a caching client.  These
are the same API pre controller runtime openshift controllers are built on.
*/

var (
	QuotaClient quotaclientset.Interface
)

func SetupNewReconciler(cfg *rest.Config) error {
	QuotaClient = quotaclientset.NewForConfigOrDie(cfg)
	c := &QuotoController{
		clientset: QuotaClient,
		quotaWorkqueue: workqueue.NewNamedRateLimitingQueue(workqueue.DefaultControllerRateLimiter(),
			"jvm-build-service-quota-changes"),
		quotaInformerFactory: quotainformer.NewSharedInformerFactoryWithOptions(QuotaClient, 15*time.Minute),
	}
	c.quotaInformer = c.quotaInformerFactory.Quota().V1().ClusterResourceQuotas().Informer()
	c.quotaInformer.AddEventHandler(c.quotaEventHandler())

	return nil
}

type QuotoController struct {
	clientset quotaclientset.Interface

	quotaWorkqueue workqueue.RateLimitingInterface

	quotaInformer cache.SharedIndexInformer

	quotaInformerFactory quotainformer.SharedInformerFactory
}

/*
So for a "fully functional" shared informer based controller that processes events, we would also employ a Run method like this to
ensure the cache is sync'ed, were we would wire a signal handler and launch a thread for this from main.go;
This concept is a bit similar to controller-runtime's 'Runnable' interface that we use for the background tektonwrapper
pruner, but that uses a context as the golang channel blocker vs. the more generic struct{} channel that shared informers
use.
For an example of how to wire this up, see:
- https://github.com/openshift/csi-driver-shared-resource/blob/f2ba9c484c228b49e08a78f772497717b7e84985/cmd/csidriver/main.go#L112
- https://github.com/openshift/csi-driver-shared-resource/blob/f2ba9c484c228b49e08a78f772497717b7e84985/cmd/csidriver/main.go#L156-L165
- https://github.com/openshift/csi-driver-shared-resource/blob/f2ba9c484c228b49e08a78f772497717b7e84985/cmd/csidriver/main.go#L156-L165

controller runtime's 'mgr.Start(ctrl.SetupSignalHandler())' that we use in main.go basically does ^^ under the covers.
*/
/*
func (c *QuotoController) Run(stopCh <-chan struct{}) error {
	defer c.quotaWorkqueue.ShutDown()

	c.quotaInformerFactory.Start(stopCh)

	if !cache.WaitForCacheSync(stopCh, c.quotaInformer.HasSynced) {
		return fmt.Errorf("failed to wait for cluster resource quota to sync")
	}

	go wait.Until(c.quotaEventProcessor, time.Second, stopCh)

	<-stopCh

	return nil
}

*/

func (c *QuotoController) quotaEventHandler() cache.ResourceEventHandlerFuncs {
	f1 := func(o interface{}) {
		switch v := o.(type) {
		case *quotav1.ClusterResourceQuota:
			c.quotaWorkqueue.Add(v)
		default:
		}
	}
	f2 := func(oldObj, newObj interface{}) {
		switch v := newObj.(type) {
		case *quotav1.ClusterResourceQuota:
			c.quotaWorkqueue.Add(v)
		default:
		}

	}
	return cache.ResourceEventHandlerFuncs{
		AddFunc:    f1,
		UpdateFunc: f2,
		DeleteFunc: f1,
	}
}

func (c *QuotoController) quotaEventProcessor() {
	for {
		obj, shutdown := c.quotaWorkqueue.Get()
		if shutdown {
			return
		}

		func() {
			defer c.quotaWorkqueue.Done(obj)

			_, ok := obj.(*quotav1.ClusterResourceQuota)
			if !ok {
				c.quotaWorkqueue.Forget(obj)
				return
			}
			//if we needed to process the ClusterResourceQuota event vs. just update our client cache,
			//we would call our "Reconcile" method and if it returned and error, we would call
			//c.quotaWorkQueue.AddRateLimited(obj); if Reconcile returns no error, we still call Forget.
			c.quotaWorkqueue.Forget(obj)
		}()
	}
}
