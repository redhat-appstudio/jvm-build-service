package systemconfig

import (
	"context"
	"testing"

	. "github.com/onsi/gomega"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"

	v1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

func setupClientAndReconciler(objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcilerSystemConfig) {
	scheme := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(scheme)
	_ = tektonpipeline.AddToScheme(scheme)
	_ = v1.AddToScheme(scheme)
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	reconciler := &ReconcilerSystemConfig{client: client, scheme: scheme, eventRecorder: &record.FakeRecorder{}}
	return client, reconciler
}

func TestValidSystemConfig(t *testing.T) {
	g := NewGomegaWithT(t)
	client, reconciler := setupClientAndReconciler()

	validCfg := v1alpha1.SystemConfig{
		Spec: v1alpha1.SystemConfigSpec{
			Builders: map[string]v1alpha1.BuilderImageInfo{
				"ubi7": {
					Image: "foo",
					Tag:   "bar",
				},
				"ubi8": {
					Image: "foo",
					Tag:   "bar",
				},
			},
		},
	}
	validCfg.Namespace = metav1.NamespaceDefault
	validCfg.Name = SystemConfigKey

	ctx := context.TODO()
	err := client.Create(ctx, &validCfg)
	g.Expect(err).NotTo(HaveOccurred())
	var result reconcile.Result
	result, err = reconciler.Reconcile(ctx, reconcile.Request{
		NamespacedName: types.NamespacedName{
			Namespace: validCfg.Namespace,
			Name:      validCfg.Name,
		},
	})
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(result).NotTo(BeNil())
}

func TestSystemConfigMissingImage(t *testing.T) {
	g := NewGomegaWithT(t)
	client, reconciler := setupClientAndReconciler()

	validCfg := v1alpha1.SystemConfig{
		Spec: v1alpha1.SystemConfigSpec{
			Builders: map[string]v1alpha1.BuilderImageInfo{
				"ubi7": {
					Tag: "bar",
				},
				"ubi8": {
					Image: "foo",
					Tag:   "bar",
				},
			},
		},
	}
	validCfg.Namespace = metav1.NamespaceDefault
	validCfg.Name = SystemConfigKey

	ctx := context.TODO()
	err := client.Create(ctx, &validCfg)
	g.Expect(err).NotTo(HaveOccurred())
	var result reconcile.Result
	result, err = reconciler.Reconcile(ctx, reconcile.Request{
		NamespacedName: types.NamespacedName{
			Namespace: validCfg.Namespace,
			Name:      validCfg.Name,
		},
	})
	g.Expect(err).To(HaveOccurred())
	g.Expect(result).NotTo(BeNil())
}

func TestSystemConfigMissingTag(t *testing.T) {
	g := NewGomegaWithT(t)
	client, reconciler := setupClientAndReconciler()

	validCfg := v1alpha1.SystemConfig{
		Spec: v1alpha1.SystemConfigSpec{
			Builders: map[string]v1alpha1.BuilderImageInfo{
				"ubi7": {
					Image: "foo",
				},
				"ubi8": {
					Image: "foo",
					Tag:   "bar",
				},
			},
		},
	}
	validCfg.Namespace = metav1.NamespaceDefault
	validCfg.Name = SystemConfigKey

	ctx := context.TODO()
	err := client.Create(ctx, &validCfg)
	g.Expect(err).NotTo(HaveOccurred())
	var result reconcile.Result
	result, err = reconciler.Reconcile(ctx, reconcile.Request{
		NamespacedName: types.NamespacedName{
			Namespace: validCfg.Namespace,
			Name:      validCfg.Name,
		},
	})
	g.Expect(err).To(HaveOccurred())
	g.Expect(result).NotTo(BeNil())
}
