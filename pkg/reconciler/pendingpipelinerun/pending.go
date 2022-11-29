package pendingpipelinerun

import (
	"context"
	"fmt"
	"time"

	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

//TODO add fields for both constants to userconfig, systemconfig, or both
const (
	abandonAfter   = 3 * time.Hour
	requeueAfter   = 1 * time.Minute
	contextTimeout = 300 * time.Second
)

var (
	log = ctrl.Log.WithName("pendingpipelinerunreconciler")
)

type ReconcilePendingPipelineRun struct {
	client        client.Client
	scheme        *runtime.Scheme
	eventRecorder record.EventRecorder
}

func newPRReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcilePendingPipelineRun{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("PendingPipelineRun"),
	}
}

func (r *ReconcilePendingPipelineRun) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	var cancel context.CancelFunc
	ctx, cancel = context.WithTimeout(ctx, contextTimeout)
	defer cancel()

	pr := &v1beta1.PipelineRun{}
	prerr := r.client.Get(ctx, request.NamespacedName, pr)
	if prerr != nil {
		if !errors.IsNotFound(prerr) {
			return ctrl.Result{}, prerr
		}
	}

	if prerr != nil {
		msg := fmt.Sprintf("Reconcile key %s received not found errors for both pipelineruns and pendingpipelinerun (probably deleted)\"", request.NamespacedName.String())
		log.Info(msg)
		return reconcile.Result{}, client.IgnoreNotFound(r.unthrottleNextOnQueuePlusCleanup(ctx, pr.Namespace))
	}

	// active (not pending) or terminal state, pop a pending item off the queue
	if pr.IsDone() || pr.IsCancelled() || pr.IsGracefullyCancelled() || pr.IsGracefullyStopped() || !pr.IsPending() {
		return reconcile.Result{}, client.IgnoreNotFound(r.unthrottleNextOnQueuePlusCleanup(ctx, pr.Namespace))
	}

	// have a pending PR

	hardPodCount, pcerr := getHardPodCount(ctx, r.client, pr.Namespace)
	if pcerr != nil {
		return reconcile.Result{}, pcerr
	}

	if hardPodCount > 0 {
		activeCount, pendingCount, doneCount, totalCount, lerr := r.pipelineRunStats(ctx, pr.Namespace)
		if lerr != nil {
			return reconcile.Result{}, lerr
		}

		switch {
		case (totalCount - doneCount) < hardPodCount:
			// below hard pod count quota so remove PR pending
			break
		case totalCount == pendingCount:
			// initial race condition possible if controller starts a bunch before we get events:
			break
		case (hardPodCount - 4) <= activeCount: //TODO subtracting ` for the artifact cache, then another 3 for safety buffer, but feels like config option long term
			// pending item still has to wait because non-terminal items beyond pod limit
			if !r.timedOut(pr) {
				return reconcile.Result{RequeueAfter: requeueAfter}, nil
			}
			// pending item has waited too long, cancel with an event to explain
			pr.Spec.Status = v1beta1.PipelineRunSpecStatusCancelled
			r.eventRecorder.Eventf(pr, corev1.EventTypeWarning, "AbandonedPipelineRun", "after throttling, now past throttling timeout and have to abandon %s:%s", pr.Namespace, pr.Name)
			return reconcile.Result{}, client.IgnoreNotFound(r.client.Update(ctx, pr))
		}
	}

	// remove pending bit
	pr.Spec.Status = ""
	return reconcile.Result{}, client.IgnoreNotFound(r.client.Update(ctx, pr))
}

func (r *ReconcilePendingPipelineRun) unthrottleNextOnQueuePlusCleanup(ctx context.Context, namespace string) error {
	var err error
	prList := v1beta1.PipelineRunList{}
	opts := &client.ListOptions{Namespace: namespace}
	if err = r.client.List(ctx, &prList, opts); err != nil {
		return err
	}
	for i, pr := range prList.Items {
		if pr.IsPending() {
			prList.Items[i].Spec.Status = ""
			return r.client.Update(ctx, &prList.Items[i])
		}
	}
	return nil
}

func (r *ReconcilePendingPipelineRun) pipelineRunStats(ctx context.Context, namespace string) (activeCount, pendingCount, doneCount, totalCount int, err error) {
	prList := v1beta1.PipelineRunList{}
	opts := &client.ListOptions{Namespace: namespace}
	if err = r.client.List(ctx, &prList, opts); err != nil {
		return 0, 0, 0, 0, err
	}

	totalCount = len(prList.Items)
	activeCount = 0
	pendingCount = 0
	doneCount = 0
	for _, p := range prList.Items {
		switch {
		case p.IsPending():
			pendingCount++
		case p.IsDone() || p.IsCancelled() || p.IsGracefullyCancelled() || p.IsGracefullyStopped():
			doneCount++
		default:
			activeCount++
		}

	}
	return activeCount, pendingCount, doneCount, totalCount, err
}

func (r *ReconcilePendingPipelineRun) timedOut(pr *v1beta1.PipelineRun) bool {
	timeout := pr.ObjectMeta.CreationTimestamp.Add(abandonAfter)
	return timeout.Before(time.Now())
}
