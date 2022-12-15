package metrics

import (
	"context"
	"sync"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"

	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/metrics"
)

const (
	NamespaceLabel      string = "namespace"
	StateLabel          string = "state"
	ArtifactBuildMetric string = "stonesoup_jvmbuildservice_artifactbuilds_total"
)

var (
	artifactbuildDesc *prometheus.Desc
	registered        = false
	sc                statsCollector
	regLock           = sync.Mutex{}
)

func InitPrometheus(client client.Client) {
	regLock.Lock()
	defer regLock.Unlock()

	if registered {
		return
	}

	registered = true

	labels := []string{NamespaceLabel, StateLabel}
	artifactbuildDesc = prometheus.NewDesc(ArtifactBuildMetric,
		"Number of total ArtifactBuilds by namespace and state.",
		labels,
		nil)

	//TODO based on our openshift builds experience, we have talked about the notion of tracking adoption
	// of various stonesoup features (i.e. product mgmt is curious how much has feature X been used for the life of this cluster),
	// or even stonesoup "overall", based on PipelineRun counts that are incremented
	// each time a PipelineRun comes through the reconciler for the first time (i.e. we label the PipelineRun as
	// part of bumping the metric so we only bump once), and then this metric is immune to PipelineRuns getting pruned.
	// i.e. newStat = prometheus.NewGaugeVec(...) and then newStat.Inc() if first time through
	// Conversely, for "devops" concerns, the collections of existing PipelineRuns is typically more of what is needed.

	sc = statsCollector{
		client: client,
	}

	metrics.Registry.MustRegister(&sc)
}

type statsCollector struct {
	client client.Client
}

func (sc *statsCollector) Describe(ch chan<- *prometheus.Desc) {
	ch <- artifactbuildDesc
}

func (sc *statsCollector) Collect(ch chan<- prometheus.Metric) {
	abs := &v1alpha1.ArtifactBuildList{}
	err := sc.client.List(context.Background(), abs)
	if err != nil {
		//TODO add log / event
		return
	}
	for _, ab := range abs.Items {
		ch <- prometheus.MustNewConstMetric(artifactbuildDesc, prometheus.GaugeValue, float64(1), ab.Namespace, ab.Status.State)
	}
}
