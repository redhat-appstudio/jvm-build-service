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
	StateLabel                 string = "state"
	ArtifactBuildTotalMetric   string = "stonesoup_jvmbuildservice_artifactbuilds_total_by_state_count"
	DependencyBuildTotalMetric string = "stonesoup_jvmbuildservice_dependencybuilds_total_by_state_count"
)

var (
	artifactBuildDesc   *prometheus.Desc
	dependencyBuildDesc *prometheus.Desc
	registered          = false
	sc                  buildContCollector
	regLock             = sync.Mutex{}
)

func InitPrometheus(client client.Client) {
	regLock.Lock()
	defer regLock.Unlock()

	if registered {
		return
	}

	registered = true

	labels := []string{StateLabel}
	artifactBuildDesc = prometheus.NewDesc(ArtifactBuildTotalMetric,
		"Number of total ArtifactBuilds by state.",
		labels,
		nil)
	dependencyBuildDesc = prometheus.NewDesc(DependencyBuildTotalMetric,
		"Number of total ArtifactBuilds by state.",
		labels,
		nil)

	//TODO based on our openshift builds experience, we have talked about the notion of tracking adoption
	// of various stonesoup features (i.e. product mgmt is curious how much has feature X been used for the life of this cluster),
	// or even stonesoup "overall", based on PipelineRun counts that are incremented
	// each time a PipelineRun comes through the reconciler for the first time (i.e. we label the PipelineRun as
	// part of bumping the metric so we only bump once), and then this metric is immune to PipelineRuns getting pruned.
	// i.e. newStat = prometheus.NewGaugeVec(...) and then newStat.Inc() if first time through
	// Conversely, for "devops" concerns, the collections of existing PipelineRuns is typically more of what is needed.

	sc = buildContCollector{
		client: client,
	}

	metrics.Registry.MustRegister(&sc)
}

type buildContCollector struct {
	client client.Client
}

func (sc *buildContCollector) Describe(ch chan<- *prometheus.Desc) {
	ch <- artifactBuildDesc
	ch <- dependencyBuildDesc
}

func (sc *buildContCollector) Collect(ch chan<- prometheus.Metric) {
	abs := &v1alpha1.ArtifactBuildList{}

	err := sc.client.List(context.Background(), abs)
	if err != nil {
		//TODO add log / event
		return
	}
	byState := map[string]int{
		v1alpha1.ArtifactBuildStateNew:         0,
		v1alpha1.ArtifactBuildStateDiscovering: 0,
		v1alpha1.ArtifactBuildStateMissing:     0,
		v1alpha1.ArtifactBuildStateComplete:    0,
		v1alpha1.ArtifactBuildStateFailed:      0,
		v1alpha1.ArtifactBuildStateBuilding:    0,
	}

	for _, i := range abs.Items {
		state := i.Status.State
		if state == "" {
			state = v1alpha1.ArtifactBuildStateNew
		}
		byState[state]++
	}
	for k, v := range byState {
		ch <- prometheus.MustNewConstMetric(artifactBuildDesc, prometheus.GaugeValue, float64(v), k)
	}

	dbs := &v1alpha1.DependencyBuildList{}

	err = sc.client.List(context.Background(), dbs)
	if err != nil {
		//TODO add log / event
		return
	}
	byState = map[string]int{
		v1alpha1.DependencyBuildStateNew:          0,
		v1alpha1.DependencyBuildStateAnalyzeBuild: 0,
		v1alpha1.DependencyBuildStateSubmitBuild:  0,
		v1alpha1.DependencyBuildStateBuilding:     0,
		v1alpha1.DependencyBuildStateContaminated: 0,
		v1alpha1.DependencyBuildStateComplete:     0,
		v1alpha1.DependencyBuildStateFailed:       0,
	}

	for _, i := range dbs.Items {
		state := i.Status.State
		if state == "" {
			state = v1alpha1.DependencyBuildStateNew
		}
		byState[state]++
	}
	for k, v := range byState {
		ch <- prometheus.MustNewConstMetric(dependencyBuildDesc, prometheus.GaugeValue, float64(v), k)
	}
}
