package metrics

import (
	"testing"

	. "github.com/onsi/gomega"

	pmodel "github.com/prometheus/client_model/go"

	"k8s.io/apimachinery/pkg/runtime"
	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	crmetrics "sigs.k8s.io/controller-runtime/pkg/metrics"
)

//NOTE:  our default mode of running unit tests in parallel, coupled with prometheus treating
// counters as global, makes vetting precise metric counts across multiple tests problematic;
// so we have cover our various "scenarios" under one test method

func gatherMetrics(g *WithT) []*pmodel.Metric {
	metrics, err := crmetrics.Registry.Gather()
	g.Expect(err).NotTo(HaveOccurred())
	for _, metricFamily := range metrics {
		switch metricFamily.GetName() {
		case ArtifactBuildMetric:
			// call
			return metricFamily.GetMetric()
			//for _, m := range metricFamily.GetMetric() {
			//	m.GetGauge().GetValue()
			//	m.GetLabel()
			//}
		}
	}
	return nil
}

func TestMetrics(t *testing.T) {
	g := NewGomegaWithT(t)
	scheme := runtime.NewScheme()
	//TODO fill starting objects as needed
	objs := []runtimeclient.Object{}
	InitPrometheus(fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build())

	//TODO add various tests where you then call
	gatherMetrics(g)
}
