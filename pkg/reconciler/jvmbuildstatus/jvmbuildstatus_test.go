package jvmbuildstatus

import (
	"context"
	quotav1 "github.com/openshift/api/quota/v1"
	jbs "github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	controllerruntime "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"testing"

	. "github.com/onsi/gomega"
	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const (
	namespace   = "default"
	name        = "test"
	artifact    = "com.test:test:1.0"
	TestImage   = "test-image"
	DummyRepo   = "dummy-repo"
	DummyDomain = "dummy-domain"
	DummyOwner  = "dummy-owner"
)

func setupClientAndReconciler(objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcileJvmBuildStatus) {
	scheme := runtime.NewScheme()
	_ = jbs.AddToScheme(scheme)
	_ = v1beta1.AddToScheme(scheme)
	_ = v1.AddToScheme(scheme)
	_ = quotav1.AddToScheme(scheme)
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	reconciler := &ReconcileJvmBuildStatus{client: client, scheme: scheme, eventRecorder: &record.FakeRecorder{}}
	return client, reconciler
}

func TestArtifactBuildCreation(t *testing.T) {
	g := NewGomegaWithT(t)
	client, reconciler := setupClientAndReconciler()
	cb := defaultComponentBuild()
	ctx := context.TODO()
	err := client.Create(ctx, &cb)
	g.Expect(err).NotTo(HaveOccurred())
	var result reconcile.Result
	result, err = reconciler.Reconcile(ctx, reconcile.Request{
		NamespacedName: types.NamespacedName{
			Namespace: namespace,
			Name:      name,
		},
	})
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(result).NotTo(BeNil())

	ab := jbs.ArtifactBuild{}
	abrName := types.NamespacedName{Namespace: namespace, Name: artifactbuild.CreateABRName(artifact)}
	err = client.Get(ctx, abrName, &ab)
	g.Expect(err).NotTo(HaveOccurred())

	//now let's complete the artifact build
	ab.Status.State = jbs.ArtifactBuildStateComplete
	g.Expect(client.Status().Update(ctx, &ab)).NotTo(HaveOccurred())

	db := jbs.DependencyBuild{}
	db.Namespace = abrName.Namespace
	db.Name = "test-db"
	g.Expect(controllerutil.SetOwnerReference(&ab, &db, client.Scheme())).NotTo(HaveOccurred())
	g.Expect(client.Create(ctx, &db)).NotTo(HaveOccurred())

	ra := jbs.RebuiltArtifact{}
	ra.Name = abrName.Name
	ra.Namespace = abrName.Namespace
	ra.Spec.Image = TestImage
	ra.Spec.GAV = ab.Spec.GAV
	g.Expect(controllerutil.SetOwnerReference(&db, &ra, client.Scheme())).NotTo(HaveOccurred())
	g.Expect(client.Create(ctx, &ra)).NotTo(HaveOccurred())

	_, err = reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: abrName})
	g.Expect(err).NotTo(HaveOccurred())

	_, err = reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: namespace, Name: name}})
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: cb.Namespace, Name: cb.Name}, &cb)).NotTo(HaveOccurred())
	g.Expect(cb.Status.State).To(Equal(jbs.JvmBuildStateComplete))

}

func defaultComponentBuild() jbs.JvmBuildStatus {
	return jbs.JvmBuildStatus{
		ObjectMeta: controllerruntime.ObjectMeta{
			Name:      name,
			Namespace: namespace,
		},
		Spec: jbs.JvmBuildStatusSpec{
			SCMURL:    "https://test.com/test.git",
			Tag:       "1.0",
			Artifacts: []*jbs.JvmBuildStatusArtifact{{GAV: artifact}},
		},
	}
}
