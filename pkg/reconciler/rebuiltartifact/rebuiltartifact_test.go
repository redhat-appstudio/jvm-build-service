package rebuiltartifact

import (
	"context"
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

func setupSectet() *corev1.Secret {
	return &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{
			Namespace: metav1.NamespaceDefault,
			Name:      v1alpha1.UserSecretName,
		},
		Data: map[string][]byte{
			v1alpha1.UserSecretTokenKey: []byte("foo"),
		},
	}
}

func setupUserConfig() *v1alpha1.UserConfig {
	userConfig := v1alpha1.UserConfig{}
	userConfig.Namespace = metav1.NamespaceDefault
	userConfig.Name = v1alpha1.UserConfigName
	return &userConfig
}

func readConfiguredRepositories(client runtimeclient.Client, g *WithT) *string {
	ctx := context.TODO()
	deployment := appsv1.Deployment{}
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.CacheDeploymentName}, &deployment))

	var value *string
	for _, val := range deployment.Spec.Template.Spec.Containers[0].Env {
		if val.Name == "BUILD_POLICY_DEFAULT_STORE_LIST" {
			value = &val.Value
			break
		}
	}
	return value
}

func TestMissingRegistrySecret(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	userConfig := setupUserConfig()
	userConfig.Spec.EnableRebuilds = true
	objs := []runtimeclient.Object{userConfig}
	_, reconciler := setupClientAndReconciler(objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.UserConfigName}})
	g.Expect(err).NotTo(BeNil())
}

func TestMissingRegistrySecretRebuildFalse(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	objs := []runtimeclient.Object{setupUserConfig()}
	_, reconciler := setupClientAndReconciler(objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.UserConfigName}})
	g.Expect(err).To(BeNil())
}

func TestMissingRegistrySecretKey(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	secret := setupSectet()
	delete(secret.Data, v1alpha1.UserSecretTokenKey)
	userConfig := setupUserConfig()
	userConfig.Spec.EnableRebuilds = true
	objs := []runtimeclient.Object{userConfig, secret}
	_, reconciler := setupClientAndReconciler(objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.UserConfigName}})
	g.Expect(err).NotTo(BeNil())
}

func TestSetupEmptyConfigWithSecret(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	objs := []runtimeclient.Object{setupUserConfig(), setupSectet()}
	client, reconciler := setupClientAndReconciler(objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.UserConfigName}})
	g.Expect(err).To(BeNil())
	value := readConfiguredRepositories(client, g)
	g.Expect(*value).Should(Equal("central"))
}

func TestRebuildEnabled(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	userConfig := setupUserConfig()
	userConfig.Spec.EnableRebuilds = true
	objs := []runtimeclient.Object{userConfig, setupSectet()}
	client, reconciler := setupClientAndReconciler(objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.UserConfigName}})
	g.Expect(err).To(BeNil())
	value := readConfiguredRepositories(client, g)
	g.Expect(*value).Should(Equal("rebuilt,central"))

}

func TestRebuildEnabledCustomRepo(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	userConfig := setupUserConfig()
	userConfig.Spec.EnableRebuilds = true
	userConfig.Spec.MavenBaseLocations = map[string]string{"maven-repository-302-gradle": "https://repo.gradle.org/artifactory/libs-releases"}
	objs := []runtimeclient.Object{userConfig, setupSectet()}
	client, reconciler := setupClientAndReconciler(objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.UserConfigName}})
	g.Expect(err).To(BeNil())
	value := readConfiguredRepositories(client, g)
	g.Expect(*value).Should(Equal("rebuilt,central,gradle"))

}
