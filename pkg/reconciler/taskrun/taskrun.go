package taskrun

import (
	"context"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout        = 300 * time.Second
	TaskResultScmUrl      = "scm-url"
	TaskResultScmTag      = "scm-tag"
	TaskResultScmType     = "scm-type"
	TaskResultContextPath = "context"
	TaskResultMessage     = "message"
)

type ReconcileTaskRunRequest struct {
	client client.Client
	scheme *runtime.Scheme
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcileTaskRunRequest{
		client: mgr.GetClient(),
		scheme: mgr.GetScheme(),
	}
}

func (r *ReconcileTaskRunRequest) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	ctx, cancel := context.WithTimeout(ctx, contextTimeout)
	defer cancel()
	//log := log.FromContext(ctx)
	tr := pipelinev1beta1.TaskRun{}
	err := r.client.Get(ctx, request.NamespacedName, &tr)
	if err != nil {
		if errors.IsNotFound(err) {
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}
	var scmUrl string
	var scmTag string
	var scmType string
	var message string
	var path string

	//we grab the results here and put them on the ABR
	for _, res := range tr.Status.TaskRunResults {
		switch res.Name {
		case TaskResultScmUrl:
			scmUrl = res.Value
		case TaskResultScmTag:
			scmTag = res.Value
		case TaskResultScmType:
			scmType = res.Value
		case TaskResultMessage:
			message = res.Value
		case TaskResultContextPath:
			path = res.Value
		}
	}
	if tr.Status.CompletionTime != nil {
		//the tr is done, lets potentially update the ABR's
		//we just set the state here, the ABR logic is in the ABR controller
		//this keeps as much of the logic in one place as possible
		for _, ref := range tr.OwnerReferences {
			abr := v1alpha1.ArtifactBuildRequest{}
			if err = r.client.Get(ctx, types.NamespacedName{Namespace: tr.Namespace, Name: ref.Name}, &abr); err != nil {
				return reconcile.Result{}, err
			}
			if abr.Status.State == v1alpha1.ArtifactBuildRequestStateDiscovering {
				abr.Spec.SCMURL = scmUrl
				abr.Spec.Tag = scmTag
				abr.Spec.Message = message
				abr.Spec.SCMType = scmType
				abr.Spec.Path = path
				err = r.client.Update(ctx, &abr)
				if err != nil {
					return reconcile.Result{}, err
				}
			}
		}
	}
	return reconcile.Result{}, nil
}
