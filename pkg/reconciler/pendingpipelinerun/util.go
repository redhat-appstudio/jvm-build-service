package pendingpipelinerun

import (
	"context"

	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/clusterresourcequota"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

type PipelineRunCreate interface {
	CreateWrapperForPipelineRun(ctx context.Context, client client.Client, run *v1beta1.PipelineRun) error
}

type ImmediateCreate struct {
}

func (i *ImmediateCreate) CreateWrapperForPipelineRun(ctx context.Context, client client.Client, run *v1beta1.PipelineRun) error {
	return client.Create(ctx, run)
}

type PendingCreate struct {
}

func (p *PendingCreate) CreateWrapperForPipelineRun(ctx context.Context, client client.Client, run *v1beta1.PipelineRun) error {
	//run.Spec.Status = v1beta1.PipelineRunSpecStatusPending
	return client.Create(ctx, run)
}

func getHardPodCount(ctx context.Context, cl client.Client, namespace string) (int, error) {
	// this should be nil in kcp
	if clusterresourcequota.QuotaClient != nil {
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
		if hardPodCount > 0 {
			return hardPodCount, nil
		}
	}
	quotaList := corev1.ResourceQuotaList{}
	err := cl.List(ctx, &quotaList)
	if err != nil {
		return 0, err
	}
	hardPodCount := 0
	for _, quota := range quotaList.Items {
		if quota.Namespace != namespace {
			continue
		}
		if quota.Spec.Hard.Pods() != nil {
			hardPodCount = int(quota.Spec.Hard.Pods().Value())
		}
		if hardPodCount > 0 {
			break
		}
	}
	return hardPodCount, nil
}
