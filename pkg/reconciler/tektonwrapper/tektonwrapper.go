package tektonwrapper

import (
	"context"
	"fmt"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/runtime/serializer"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout = 300 * time.Second
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

func (r *ReconcileTektonWrapper) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	ctx, cancel := context.WithTimeout(ctx, contextTimeout)
	defer cancel()
	tw := v1alpha1.TektonWrapper{}
	twerr := r.client.Get(ctx, request.NamespacedName, &tw)
	if twerr != nil {
		if errors.IsNotFound(twerr) {
			// tw deleted since event was generated, or this was a delete event, but there is not
			// delete processing needed at this time
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, twerr
	}
	switch tw.Status.State {
	case v1alpha1.TektonWrapperStateComplete:
		return reconcile.Result{}, nil
	case v1alpha1.TektonWrapperStateAbandoned:
		return reconcile.Result{}, nil
	}

	pr := &v1beta1.PipelineRun{}
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
	if pr == nil {
		msg := fmt.Sprintf("TektonWrapper %s:%s could not produced a pipeline run with buf %#v", tw.Namespace, tw.Name, tw.Spec.PipelineRun)
		log.Info(msg)
		r.eventRecorder.Event(&tw, corev1.EventTypeWarning, "MissingPipelineRun", msg)
		return reconcile.Result{}, nil
	}
	// must clear out resource version for create
	pr.ResourceVersion = ""
	//TODO in theory we could set ourselves as an ownerfer, but dependencybuild does that and currently makes assumptions
	// that it is the only one; for now, we well let ab's/db's cleanup driver pr's cleanup.
	prerr := r.client.Create(ctx, pr)
	if prerr != nil {
		timeout := tw.ObjectMeta.CreationTimestamp.Add(tw.Spec.AbandonAfter)
		if timeout.After(time.Now()) {
			r.eventRecorder.Eventf(&tw, corev1.EventTypeWarning, "AbandonedPipelineRun", "had to abandon %s:%s", pr.Namespace, pr.Name)
			tw.Status.State = v1alpha1.TektonWrapperStateAbandoned
			return reconcile.Result{}, r.client.Status().Update(ctx, &tw)
		}
		return reconcile.Result{}, prerr
	}
	tw.Status.State = v1alpha1.TektonWrapperStateComplete
	return reconcile.Result{}, r.client.Status().Update(ctx, &tw)
}
