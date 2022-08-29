package tektonwrapper

import (
	"context"
	"testing"

	. "github.com/onsi/gomega"
	quotav1 "github.com/openshift/api/quota/v1"
	fakequotaclientset "github.com/openshift/client-go/quota/clientset/versioned/fake"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/clusterresourcequota"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	v1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const gav = "com.acme:foo:1.0"

func setupClientAndReconciler(objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcileTektonWrapper) {
	scheme := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(scheme)
	_ = v1beta1.AddToScheme(scheme)
	_ = v1.AddToScheme(scheme)
	_ = quotav1.AddToScheme(scheme)
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	clusterresourcequota.QuotaClient = fakequotaclientset.NewSimpleClientset()
	reconciler := &ReconcileTektonWrapper{client: client, scheme: scheme, eventRecorder: &record.FakeRecorder{}}
	return client, reconciler
}

func TestTektonWrapper(t *testing.T) {
	g := NewGomegaWithT(t)
	client, reconciler := setupClientAndReconciler()

	pr := v1beta1.PipelineRun{}
	pr.Namespace = metav1.NamespaceDefault
	pr.Name = "test"
	pr.Spec.ServiceAccountName = "foo"
	ctx := context.TODO()
	c := &BatchedCreate{}
	err := c.CreateWrapperForPipelineRun(ctx, client, &pr)
	g.Expect(err).NotTo(HaveOccurred())

	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: pr.Namespace, Name: pr.Name}}))
	key := runtimeclient.ObjectKey{Namespace: pr.Namespace, Name: pr.Name}
	err = client.Get(ctx, key, &pr)
	g.Expect(err).NotTo(HaveOccurred())
	tw := &v1alpha1.TektonWrapper{}
	err = client.Get(ctx, key, tw)
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(tw.Status.State).To(Equal(v1alpha1.TektonWrapperStateInProgress))

	pr.Status.MarkSucceeded("done", "")
	err = client.Update(ctx, &pr)
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: pr.Namespace, Name: pr.Name}}))
	err = client.Get(ctx, key, tw)
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(tw.Status.State).To(Equal(v1alpha1.TektonWrapperStateComplete))

}
