package dependencybuild

import (
	"context"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	"time"

	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/runtime"

	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout = 300 * time.Second

	IdLabel = "jvmbuildservice.io/dependencybuild-id"
)

type ReconcileDependencyBuild struct {
	client client.Client
	scheme *runtime.Scheme
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcileDependencyBuild{
		client: mgr.GetClient(),
		scheme: mgr.GetScheme(),
	}
}

func (r *ReconcileDependencyBuild) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	ctx, cancel := context.WithTimeout(ctx, contextTimeout)
	defer cancel()

	db := v1alpha1.DependencyBuild{}
	err := r.client.Get(ctx, request.NamespacedName, &db)
	if err != nil {
		if errors.IsNotFound(err) {
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}
	if db.Status.Status == "" || db.Status.Status == v1alpha1.DependencyBuildStateNew {
		//new build, kick off a pipeline run to run the build
		//TODO: this state flow will change when we start analysing the required images etc
		//for now just to a super basic build and change the state to building

		// create task run
		tr := pipelinev1beta1.PipelineRun{}
		tr.Namespace = db.Namespace
		tr.GenerateName = db.Name + "-build-"
		tr.Labels = map[string]string{IdLabel: db.Labels[IdLabel]}
		tr.Spec.PipelineRef = &pipelinev1beta1.PipelineRef{Name: "run-component-build"}
		tr.Spec.Params = []pipelinev1beta1.Param{
			{Name: "url", Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.SCMURL}},
			{Name: "tag", Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.Tag}},
			{Name: "context", Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: db.Spec.Path}},
		}
		quantity, err := resource.ParseQuantity("1Gi")
		if err != nil {
			return reconcile.Result{}, err
		}
		tr.Spec.Workspaces = []pipelinev1beta1.WorkspaceBinding{
			{Name: "maven-settings", EmptyDir: &v1.EmptyDirVolumeSource{}},
			{Name: "shared-workspace", VolumeClaimTemplate: &v1.PersistentVolumeClaim{Spec: v1.PersistentVolumeClaimSpec{
				AccessModes: []v1.PersistentVolumeAccessMode{v1.ReadWriteOnce},
				Resources:   v1.ResourceRequirements{Requests: map[v1.ResourceName]resource.Quantity{v1.ResourceStorage: quantity}}}}},
		}
		err = r.client.Create(ctx, &tr)
		if err != nil {
			return reconcile.Result{}, err
		}
		db.Status.Status = v1alpha1.DependencyBuildStateBuilding
		return reconcile.Result{}, r.client.Status().Update(ctx, &db)
	}

	return reconcile.Result{}, nil
}
