package tektonwrapper

import (
	"bytes"
	"context"
	"fmt"
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
	log.Info(fmt.Sprintf("GGM1 buf len %d pr %#v", len(tw.Spec.PipelineRun), run))
	tw.Spec.RequeueAfter = 1 * time.Minute
	tw.Spec.AbandonAfter = 15 * time.Minute
	tw.Status.State = v1alpha1.TektonWrapperStateUnattempted

	err = client.Create(ctx, tw)

	return err
}

type ImmediateCreate struct {
}

func (i *ImmediateCreate) CreateWrapperForPipelineRun(ctx context.Context, client client.Client, run *v1beta1.PipelineRun) error {
	return client.Create(ctx, run)
}
