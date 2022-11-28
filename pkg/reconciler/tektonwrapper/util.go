package tektonwrapper

import (
	"bytes"
	"context"
	"k8s.io/apimachinery/pkg/runtime/serializer"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	"k8s.io/apimachinery/pkg/runtime"
	utilruntime "k8s.io/apimachinery/pkg/util/runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

type PipelineRunCreate interface {
	CreateWrapperForPipelineRun(ctx context.Context, client client.Client, run *v1beta1.PipelineRun) error
}

type BatchedCreate struct {
}

func (b *BatchedCreate) CreateWrapperForPipelineRun(ctx context.Context, client client.Client, run *v1beta1.PipelineRun) error {
	var err error
	tw := &v1alpha1.TektonWrapper{}
	tw.Namespace = run.Namespace
	tw.Name = run.Name
	tw.GenerateName = run.GenerateName
	//we use the same owner references as the pipeline
	//so these are cleaned up if the owner is deleted
	tw.OwnerReferences = run.OwnerReferences
	buffer := bytes.Buffer{}
	scheme := runtime.NewScheme()
	utilruntime.Must(v1beta1.AddToScheme(scheme))
	codecFactory := serializer.NewCodecFactory(scheme)
	encoder := codecFactory.LegacyCodec(v1beta1.SchemeGroupVersion)
	err = encoder.Encode(run, &buffer)
	if err != nil {
		return err
	}
	tw.Spec.PipelineRun = buffer.Bytes()
	//TODO make configurable; tuned for service registry build
	tw.Spec.RequeueAfter = 1 * time.Minute
	tw.Spec.AbandonAfter = 3 * time.Hour
	// cannot set status on create
	err = client.Create(ctx, tw)

	return err
}

type ImmediateCreate struct {
}

func (i *ImmediateCreate) CreateWrapperForPipelineRun(ctx context.Context, client client.Client, run *v1beta1.PipelineRun) error {
	return client.Create(ctx, run)
}

type PendingCreate struct {
}

func (p *PendingCreate) CreateWrapperForPipelineRun(ctx context.Context, client client.Client, run *v1beta1.PipelineRun) error {
	run.Spec.Status = v1beta1.PipelineRunSpecStatusPending
	return client.Create(ctx, run)
}
