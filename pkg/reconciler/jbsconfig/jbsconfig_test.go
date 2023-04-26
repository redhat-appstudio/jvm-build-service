package jbsconfig

import (
	"context"
	spi "github.com/redhat-appstudio/service-provider-integration-operator/api/v1beta1"
	"k8s.io/apimachinery/pkg/api/errors"
	"testing"

	. "github.com/onsi/gomega"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"

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

func setupClientAndReconciler(includeSpi bool, objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcilerJBSConfig) {
	scheme := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(scheme)
	_ = appsv1.AddToScheme(scheme)
	_ = corev1.AddToScheme(scheme)
	if includeSpi {
		_ = spi.AddToScheme(scheme)
	}
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	reconciler := &ReconcilerJBSConfig{
		client:        client,
		scheme:        scheme,
		eventRecorder: &record.FakeRecorder{},
	}
	util.ImageTag = "foo"
	return client, reconciler
}

func setupSecret() *corev1.Secret {
	return &corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{
			Namespace: metav1.NamespaceDefault,
			Name:      v1alpha1.ImageSecretName,
		},
		Data: map[string][]byte{
			v1alpha1.ImageSecretTokenKey: []byte("foo"),
		},
	}
}

func setupJBSConfig() *v1alpha1.JBSConfig {
	jbsConfig := v1alpha1.JBSConfig{}
	jbsConfig.Spec.Owner = "tests"
	jbsConfig.Namespace = metav1.NamespaceDefault
	jbsConfig.Name = v1alpha1.JBSConfigName
	return &jbsConfig
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
	jbsConfig := setupJBSConfig()
	jbsConfig.Spec.EnableRebuilds = true
	objs := []runtimeclient.Object{jbsConfig}
	_, reconciler := setupClientAndReconciler(false, objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}})
	g.Expect(err).NotTo(BeNil())
}

func TestMissingRegistrySecretRebuildFalse(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	objs := []runtimeclient.Object{setupJBSConfig()}
	_, reconciler := setupClientAndReconciler(false, objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}})
	g.Expect(err).To(BeNil())
}

func TestMissingRegistrySecretKey(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	secret := setupSecret()
	delete(secret.Data, v1alpha1.ImageSecretTokenKey)
	jbsConfig := setupJBSConfig()
	jbsConfig.Spec.EnableRebuilds = true
	objs := []runtimeclient.Object{jbsConfig, secret}
	_, reconciler := setupClientAndReconciler(false, objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}})
	g.Expect(err).NotTo(BeNil())
}

func TestSetupEmptyConfigWithSecret(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	objs := []runtimeclient.Object{setupJBSConfig(), setupSecret()}
	client, reconciler := setupClientAndReconciler(false, objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}})
	g.Expect(err).To(BeNil())
	value := readConfiguredRepositories(client, g)
	g.Expect(*value).Should(Equal("central,redhat"))
}

func TestRebuildEnabled(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	jbsConfig := setupJBSConfig()
	jbsConfig.Spec.EnableRebuilds = true
	objs := []runtimeclient.Object{jbsConfig, setupSecret()}
	client, reconciler := setupClientAndReconciler(false, objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}})
	g.Expect(err).To(BeNil())
	value := readConfiguredRepositories(client, g)
	g.Expect(*value).Should(Equal("rebuilt,central,redhat"))

}

func TestRebuildEnabledCustomRepo(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	jbsConfig := setupJBSConfig()
	jbsConfig.Spec.EnableRebuilds = true
	jbsConfig.Spec.MavenBaseLocations = map[string]string{"maven-repository-302-gradle": "https://repo.gradle.org/artifactory/libs-releases"}
	objs := []runtimeclient.Object{jbsConfig, setupSecret()}
	client, reconciler := setupClientAndReconciler(false, objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}})
	g.Expect(err).To(BeNil())
	value := readConfiguredRepositories(client, g)
	g.Expect(*value).Should(Equal("rebuilt,central,redhat,gradle"))

}

func TestCacheCreatedAndDeleted(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	jbsConfig := setupJBSConfig()
	jbsConfig.Spec.EnableRebuilds = true
	objs := []runtimeclient.Object{jbsConfig, setupSecret()}
	client, reconciler := setupClientAndReconciler(false, objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}})
	g.Expect(err).To(BeNil())
	dep := appsv1.Deployment{}
	err = client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.CacheDeploymentName}, &dep)
	g.Expect(err).To(BeNil())
	err = client.Delete(ctx, jbsConfig)
	g.Expect(err).To(BeNil())
	_, err = reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}})
	g.Expect(err).To(BeNil())
	err = client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.CacheDeploymentName}, &dep)
	g.Expect(errors.IsNotFound(err)).To(BeTrue())
}

func TestMissingRegistrySecretWithSpi(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	jbsConfig := setupJBSConfig()
	jbsConfig.Spec.EnableRebuilds = true
	objs := []runtimeclient.Object{jbsConfig}
	client, reconciler := setupClientAndReconciler(true, objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}})
	g.Expect(err).ToNot(BeNil())

	binding := spi.SPIAccessTokenBinding{}
	err = client.Get(context.TODO(), types.NamespacedName{Namespace: jbsConfig.Namespace, Name: v1alpha1.ImageSecretName}, &binding)
	g.Expect(err).To(BeNil())
	g.Expect(binding.Spec.RepoUrl).To(Equal("https://quay.io/tests/artifact-deployments"))

}
