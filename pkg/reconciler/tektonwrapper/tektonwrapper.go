package tektonwrapper

import (
	"context"
	"fmt"
	"os"
	"strings"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/clusterresourcequota"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/serializer"
	"k8s.io/apimachinery/pkg/types"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	"k8s.io/client-go/tools/record"
	"knative.dev/pkg/apis"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout = 300 * time.Second

	TektonWrapperId      = "jvmbuildservice.io/tektonwrapperid"
	DeleteTaskRunPodsEnv = "JVM_DELETE_TASKRUN_PODS"
)

var (
	log = ctrl.Log.WithName("tektonwrapper")
)

type ReconcileTektonWrapper struct {
	client        client.Client
	scheme        *runtime.Scheme
	eventRecorder record.EventRecorder
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcileTektonWrapper{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("TektonWrapper"),
	}
}

/*
NOTE: the use of client.IgnoreNotFound stems from our pruning based off of events, and how deletes are
propagated through the controller runtime cache, and we pattern our solution based off of what other upstream
projects have done (for example, https://github.com/kubernetes/test-infra/issues/19426#issuecomment-702368856)

Without this, we have timing windows with concurrent events where our controller logs will be littered with
ultimately benign messages like

1.661200364795973e+09	ERROR	controller.tektonwrapper	Reconciler error	{"reconciler group": "jvmbuildservice.io", "reconciler kind": "TektonWrapper", "name": "24bd7fad398a2c69b7554d237be7f129-build-0", "namespace": "ggmtest", "error": "TektonWrapper.jvmbuildservice.io \"24bd7fad398a2c69b7554d237be7f129-build-0\" not found"}
sigs.k8s.io/controller-runtime/pkg/internal/controller.(*Controller).processNextWorkItem
	/opt/app-root/src/vendor/sigs.k8s.io/controller-runtime/pkg/internal/controller/controller.go:266
sigs.k8s.io/controller-runtime/pkg/internal/controller.(*Controller).Start.func2.2
	/opt/app-root/src/vendor/sigs.k8s.io/controller-runtime/pkg/internal/controller/controller.go:227

*/

func (r *ReconcileTektonWrapper) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	ctx, cancel := context.WithTimeout(ctx, contextTimeout)
	defer cancel()
	tw := v1alpha1.TektonWrapper{}
	twerr := r.client.Get(ctx, request.NamespacedName, &tw)
	if twerr != nil {
		if !errors.IsNotFound(twerr) {
			return ctrl.Result{}, twerr
		}
	}

	pr := &v1beta1.PipelineRun{}
	prerr := r.client.Get(ctx, request.NamespacedName, pr)
	if prerr != nil {
		if !errors.IsNotFound(prerr) {
			return ctrl.Result{}, prerr
		}
	}

	if prerr != nil && twerr != nil {
		msg := fmt.Sprintf("Reconcile key %s received not found errors for both pipelineruns and tektonwrapper (probably deleted)\"", request.NamespacedName.String())
		log.Info(msg)
		return ctrl.Result{}, nil
	}

	switch {
	case prerr == nil:
		if pr.IsDone() {
			twId, ok := pr.Labels[TektonWrapperId]
			if !ok || len(twId) == 0 {
				msg := fmt.Sprintf("pipelinerun %s unexpectedly missing tekton wrapper ID", request.NamespacedName.String())
				r.eventRecorder.Event(pr, corev1.EventTypeWarning, "MissingTektonWrapperID", msg)
				return reconcile.Result{}, nil
			}
			twKey := types.NamespacedName{
				Namespace: pr.Namespace,
				Name:      twId,
			}
			// best effort one time prune / not worried about retry / purge successful but leave failed for debug
			if os.Getenv(DeleteTaskRunPodsEnv) == "1" &&
				pr.Status.GetCondition(apis.ConditionSucceeded).IsTrue() {
				delerr := r.client.Delete(ctx, pr)
				if delerr != nil {
					// just log error, continue to update
					log.Info(fmt.Sprintf("error while attempting to prune pipelinerun %s:%s: %s", pr.Namespace, pr.Name, delerr.Error()))
				}
			}
			if err := r.client.Get(ctx, twKey, &tw); err != nil {
				return reconcile.Result{}, client.IgnoreNotFound(err)
			}
			tw.Status.State = v1alpha1.TektonWrapperStateComplete
			return reconcile.Result{}, client.IgnoreNotFound(r.client.Status().Update(ctx, &tw))
		}
		return reconcile.Result{}, nil
	}

	switch tw.Status.State {
	case v1alpha1.TektonWrapperStateComplete:
		return reconcile.Result{}, client.IgnoreNotFound(r.unthrottleNextOnQueuePlusCleanup(ctx, tw.Namespace))
	case v1alpha1.TektonWrapperStateAbandoned:
		//TODO could bump future metrics and initiate alerts to help notify admins and users that their projects are
		// under powered, and then prune here as well; for now, leaving abandoned wrappers around as the indicator
		return reconcile.Result{}, nil
	case v1alpha1.TektonWrapperStateInProgress:
		//TODO even though we have the PipelineRun watch, we could list on our label and make sure the PipelineRun is
		// active and we have not missed any events; for now, deferring on this
		return reconcile.Result{}, nil
	default:
		state := tw.Status.State
		if len(state) > 0 && state != v1alpha1.TektonWrapperStateThrottled && state != v1alpha1.TektonWrapperStateUnprocessed {
			msg := fmt.Sprintf("tekton wrapper %s:%s has invalid state %s", tw.Namespace, tw.Name, state)
			r.eventRecorder.Event(&tw, corev1.EventTypeWarning, "InvalidTektonWrapperState", msg)
			return reconcile.Result{}, nil
		}
	}

	// get embedded PR
	pr = &v1beta1.PipelineRun{}
	if len(tw.Spec.PipelineRun) > 0 {
		decodingScheme := runtime.NewScheme()
		utilruntime.Must(v1beta1.AddToScheme(decodingScheme))
		decoderCodecFactory := serializer.NewCodecFactory(decodingScheme)
		decoder := decoderCodecFactory.UniversalDecoder(v1beta1.SchemeGroupVersion)
		prHydrateErr := runtime.DecodeInto(decoder, tw.Spec.PipelineRun, pr)
		if prHydrateErr != nil {
			return reconcile.Result{}, prHydrateErr
		}
	} else {
		msg := fmt.Sprintf("TektonWrapper %s:%s has no embedded PipelineRun", tw.Namespace, tw.Name)
		log.Info(msg)
		r.eventRecorder.Event(&tw, corev1.EventTypeWarning, "MissingPipelineRun", msg)
		return reconcile.Result{}, nil
	}

	// we hydrate PipelineRun first to use it's namespace/name in an messaging
	// see if we have quota for this namespace/project

	// process quota
	hardPodCount, pcerr := r.getHardPodCount(ctx, tw.Namespace)
	if pcerr != nil {
		return reconcile.Result{}, pcerr
	}

	if hardPodCount > 0 {
		// see how close we are to quota
		_, activeCount, _, doneCount, totalCount, lerr := r.tektonWrapperStats(ctx, tw.Namespace)
		if lerr != nil {
			return reconcile.Result{}, lerr
		}

		if len(strings.TrimSpace(tw.Status.State)) == 0 {
			// create cannot update status, so blank means newly create or unprocessed
			tw.Status.State = v1alpha1.TektonWrapperStateUnprocessed
		}

		switch {
		case (totalCount - doneCount) < hardPodCount:
			// below hard pod count quota so create PR
			break
		case tw.Status.State == v1alpha1.TektonWrapperStateUnprocessed:
			// fresh item needs to be queued cause non-terminal items beyond pod limit
			name := pr.Name
			if len(name) == 0 {
				name = pr.GenerateName
			}
			r.eventRecorder.Eventf(&tw, corev1.EventTypeWarning, "ThrottledPipelineRun", "%s:%s", pr.Namespace, name)
			tw.Status.State = v1alpha1.TektonWrapperStateThrottled
			return reconcile.Result{RequeueAfter: (tw.Spec.RequeueAfter)}, client.IgnoreNotFound(r.client.Status().Update(ctx, &tw))
		case ((hardPodCount - 4) <= activeCount) && tw.Status.State == v1alpha1.TektonWrapperStateThrottled: //TODO subtracting 2 for the artifact cache and localstack pod, then another 2 for safety buffer, but feels like config option long term
			// previously throttled items still has to wait cause non-terminal items beyond pod limit
			if !r.timedOut(&tw) {
				return reconcile.Result{RequeueAfter: tw.Spec.RequeueAfter}, nil
			}
			tw.Status.State = v1alpha1.TektonWrapperStateAbandoned
			r.eventRecorder.Eventf(&tw, corev1.EventTypeWarning, "AbandonedPipelineRun", "after throttling now past throttling timeout and have to abandon %s:%s", pr.Namespace, pr.Name)
			return reconcile.Result{}, client.IgnoreNotFound(r.client.Status().Update(ctx, &tw))
		}
	}

	// must clear out resource version for create
	pr.ResourceVersion = ""
	if pr.Labels == nil {
		pr.Labels = map[string]string{}
	}
	pr.Labels[TektonWrapperId] = tw.Name
	// we intentionally do not establish owner refs between the tekton wrapper and the pipeline run;  we want as loose
	// a coupling as possible, as transparent as possible, etc.
	prerr = r.client.Create(ctx, pr)
	if prerr != nil {
		if r.timedOut(&tw) {
			r.eventRecorder.Eventf(&tw, corev1.EventTypeWarning, "AbandonedPipelineRun", "after failed create had to abandon %s:%s", pr.Namespace, pr.Name)
			tw.Status.State = v1alpha1.TektonWrapperStateAbandoned
			return reconcile.Result{}, client.IgnoreNotFound(r.client.Status().Update(ctx, &tw))
		}
		return reconcile.Result{}, prerr
	}
	tw.Status.State = v1alpha1.TektonWrapperStateInProgress
	return reconcile.Result{}, client.IgnoreNotFound(r.client.Status().Update(ctx, &tw))
}

func (r *ReconcileTektonWrapper) timedOut(tw *v1alpha1.TektonWrapper) bool {
	timeout := tw.ObjectMeta.CreationTimestamp.Add(tw.Spec.AbandonAfter)
	return timeout.Before(time.Now())
}

func (r *ReconcileTektonWrapper) tektonWrapperStats(ctx context.Context, namespace string) (unprocessedCount, activeCount, throttledCount, doneCount, totalCount int, err error) {
	twList := v1alpha1.TektonWrapperList{}
	opts := &client.ListOptions{Namespace: namespace}
	if err := r.client.List(ctx, &twList, opts); err != nil {
		return 0, 0, 0, 0, 0, err
	}

	totalCount = len(twList.Items)
	activeCount = 0
	throttledCount = 0
	unprocessedCount = 0
	doneCount = 0
	for _, t := range twList.Items {
		switch t.Status.State {
		case v1alpha1.TektonWrapperStateUnprocessed:
			unprocessedCount++
		case v1alpha1.TektonWrapperStateInProgress:
			activeCount++
		case v1alpha1.TektonWrapperStateThrottled:
			throttledCount++
		case v1alpha1.TektonWrapperStateAbandoned:
			fallthrough
		case v1alpha1.TektonWrapperStateComplete:
			doneCount++
		default:
			if len(strings.TrimSpace(t.Status.State)) > 0 {
				log.Info(fmt.Sprintf("tektonwrapper %s:%s has unexpected state %s", t.Namespace, t.Name, t.Status.State))
				continue
			}
			// create cannot update status, so blank means newly create or unprocessed
			unprocessedCount++
		}
	}
	return unprocessedCount, activeCount, throttledCount, doneCount, totalCount, nil
}

func (r *ReconcileTektonWrapper) unthrottleNextOnQueuePlusCleanup(ctx context.Context, namespace string) error {
	var err error
	twList := v1alpha1.TektonWrapperList{}
	opts := &client.ListOptions{Namespace: namespace}
	if err = r.client.List(ctx, &twList, opts); err != nil {
		return err
	}
	for i, t := range twList.Items {
		if t.Status.State == v1alpha1.TektonWrapperStateComplete {
			// no error checks; best effort prune is sufficient;
			// do this is separate loop to maximize amount cleaned up
			_ = r.client.Delete(ctx, &twList.Items[i])
		}
	}
	for i, t := range twList.Items {
		if t.Status.State == v1alpha1.TektonWrapperStateThrottled {
			t.Status.State = v1alpha1.TektonWrapperStateUnprocessed
			// release only 1 throttled item when 1 item is completed
			return r.client.Status().Update(ctx, &twList.Items[i])
		}
	}

	return nil
}

func (r *ReconcileTektonWrapper) getHardPodCount(ctx context.Context, namespace string) (int, error) {
	//TODO controller runtime seemed unable to deal with openshift API and its attempt at mapping to CRDs; we were using
	// a non caching client; but now we've switched to a shared informer based controller and its caching client
	quotaList, qerr := clusterresourcequota.QuotaClient.QuotaV1().ClusterResourceQuotas().List(ctx, metav1.ListOptions{})
	if qerr != nil {
		return 0, qerr
	}
	hardPodCount := 0
	for _, quota := range quotaList.Items {
		// find applicable quota for this namespace
		ns, ok := quota.Spec.Selector.AnnotationSelector["openshift.io/requester"]

		if !ok {
			continue
		}
		if ns != namespace {
			continue
		}
		//TODO the current assumption here is serial TaskRuns for the PipelineRun, hence, 1 Pod
		// per PipelineRun; if we employ concurrent TaskRuns, we'll need to account for that and
		// increase the "pod count" for a PipelineRun
		if quota.Spec.Quota.Hard.Pods() != nil {
			hardPodCount = int(quota.Spec.Quota.Hard.Pods().Value())
		}

		if hardPodCount <= 0 {
			var quant resource.Quantity
			quant, ok = quota.Spec.Quota.Hard[corev1.ResourceName("count/pods")]
			if ok {
				hardPodCount = int(quant.Value())
			}
		}
		if hardPodCount > 0 {
			break
		}
	}
	return hardPodCount, nil
}
