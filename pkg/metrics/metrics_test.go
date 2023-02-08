package metrics

import (
	"context"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	controllerruntime "sigs.k8s.io/controller-runtime"
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
		case ArtifactBuildTotalMetric:
			// call
			return metricFamily.GetMetric()
		}
	}
	return nil
}

func TestMetrics(t *testing.T) {
	g := NewGomegaWithT(t)
	scheme := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(scheme)
	//TODO fill starting objects as needed
	objs := []runtimeclient.Object{}
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	InitPrometheus(client)

	//TODO add various tests where you then call
	metric := gatherMetrics(g)
	for _, m := range metric {
		g.Expect(m.GetGauge().GetValue()).Should(Equal(0.0))
	}
	ab := v1alpha1.ArtifactBuild{
		Spec: v1alpha1.ArtifactBuildSpec{
			GAV: "com.test:test:1.0",
		},
		ObjectMeta: controllerruntime.ObjectMeta{
			Name:      "test",
			Namespace: "test",
		},
		Status: v1alpha1.ArtifactBuildStatus{State: v1alpha1.ArtifactBuildStateComplete},
	}
	g.Expect(client.Create(context.TODO(), &ab)).Should(Succeed())
	metric = gatherMetrics(g)
	g.Expect(len(metric)).Should(Equal(6))
	for _, m := range metric {
		if *m.GetLabel()[0].Value == v1alpha1.ArtifactBuildStateComplete {
			g.Expect(m.GetGauge().GetValue()).Should(Equal(1.0))
		} else {
			g.Expect(m.GetGauge().GetValue()).Should(Equal(0.0))
		}
	}
}
