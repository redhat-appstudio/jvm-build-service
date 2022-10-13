package rebuiltartifact

import (
	"context"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"testing"

	. "github.com/onsi/gomega"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"

	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

func setupClientAndReconciler(objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcilerRebuiltArtifact) {
	scheme := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(scheme)
	_ = appsv1.AddToScheme(scheme)
	_ = corev1.AddToScheme(scheme)
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	reconciler := &ReconcilerRebuiltArtifact{
		client:        client,
		scheme:        scheme,
		eventRecorder: &record.FakeRecorder{},
	}
	return client, reconciler
}
func setupRebuilt(gav string) *v1alpha1.RebuiltArtifact {
	rebuilt := v1alpha1.RebuiltArtifact{}
	rebuilt.Namespace = metav1.NamespaceDefault
	rebuilt.Name = artifactbuild.CreateABRName(gav)
	rebuilt.Spec.GAV = gav
	return &rebuilt
}

func TestGenerateBloomFilter(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	const gav1 = "io.test:test:1.0"
	const gav2 = "io.test-gav2:test-artifact:2.0"
	rb := setupRebuilt(gav1)
	objs := []runtimeclient.Object{rb}
	client, reconciler := setupClientAndReconciler(objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: artifactbuild.CreateABRName(gav1)}})
	g.Expect(err).To(BeNil())
	cm := corev1.ConfigMap{}
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: rb.Namespace, Name: JvmBuildServiceFilterConfigMap}, &cm)).Should(BeNil())
	filter := cm.BinaryData["filter"]
	g.Expect(filter).ShouldNot(BeEmpty())
	g.Expect(len(filter) >= 2).Should(BeTrue())
	rb2 := setupRebuilt(gav2)
	g.Expect(client.Create(ctx, rb2)).To(BeNil())
	_, err = reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: artifactbuild.CreateABRName(gav2)}})
	g.Expect(err).To(BeNil())
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: rb.Namespace, Name: JvmBuildServiceFilterConfigMap}, &cm)).Should(BeNil())
	filter2 := cm.BinaryData["filter"]
	g.Expect(filter2).ShouldNot(BeEmpty())
	g.Expect(filter2).ShouldNot(Equal(filter))
	g.Expect(client.Delete(ctx, rb)).To(BeNil())
	g.Expect(client.Delete(ctx, rb2)).To(BeNil())
	_, err = reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: artifactbuild.CreateABRName(gav2)}})
	g.Expect(err).To(BeNil())
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: rb.Namespace, Name: JvmBuildServiceFilterConfigMap}, &cm)).Should(BeNil())
	filter = cm.BinaryData["filter"]
	//should now be an empty bloom filter
	for _, i := range filter {
		g.Expect(i).Should(Equal(uint8(0)))
	}

}
