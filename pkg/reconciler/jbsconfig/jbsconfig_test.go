package jbsconfig

import (
	"context"
	"github.com/go-logr/logr"
	"testing"

	. "github.com/onsi/gomega"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/systemconfig"
	spi "github.com/redhat-appstudio/service-provider-integration-operator/api/v1beta1"
	rbacv1 "k8s.io/api/rbac/v1"
	"k8s.io/apimachinery/pkg/api/errors"

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
	_ = rbacv1.AddToScheme(scheme)
	if includeSpi {
		_ = spi.AddToScheme(scheme)
	}
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	reconciler := &ReconcilerJBSConfig{
		client:        client,
		scheme:        scheme,
		eventRecorder: &record.FakeRecorder{},
		spiPresent:    includeSpi,
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
	jbsConfig.Spec.Registry.Owner = "tests"
	jbsConfig.Namespace = metav1.NamespaceDefault
	jbsConfig.Name = v1alpha1.JBSConfigName
	return &jbsConfig
}
func setupSystemConfig() *v1alpha1.SystemConfig {
	sysConfig := v1alpha1.SystemConfig{}
	sysConfig.Name = systemconfig.SystemConfigKey
	return &sysConfig
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
	objs := []runtimeclient.Object{jbsConfig, setupSystemConfig()}
	client, reconciler := setupClientAndReconciler(false, objs...)
	name := types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: name})
	g.Expect(err).To(BeNil())
	g.Expect(client.Get(ctx, name, jbsConfig)).To(BeNil())
	g.Expect(jbsConfig.Status.RebuildsPossible).To(BeFalse())
}

func TestMissingRegistrySecretRebuildFalse(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	objs := []runtimeclient.Object{setupJBSConfig(), setupSystemConfig()}
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
	objs := []runtimeclient.Object{jbsConfig, secret, setupSystemConfig()}
	client, reconciler := setupClientAndReconciler(false, objs...)
	name := types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: name})
	g.Expect(err).To(BeNil())
	g.Expect(client.Get(ctx, name, jbsConfig)).To(BeNil())
	g.Expect(jbsConfig.Status.RebuildsPossible).To(BeFalse())
}

func TestSetupEmptyConfigWithSecret(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	objs := []runtimeclient.Object{setupJBSConfig(), setupSecret(), setupSystemConfig()}
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
	objs := []runtimeclient.Object{jbsConfig, setupSecret(), setupSystemConfig()}
	client, reconciler := setupClientAndReconciler(false, objs...)
	name := types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: name})
	g.Expect(err).To(BeNil())
	value := readConfiguredRepositories(client, g)
	g.Expect(*value).Should(Equal("rebuilt,central,redhat"))
	g.Expect(client.Get(ctx, name, jbsConfig)).To(BeNil())
	g.Expect(jbsConfig.Status.RebuildsPossible).To(BeTrue())

}

func TestRebuildEnabledCustomRepo(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	jbsConfig := setupJBSConfig()
	jbsConfig.Spec.EnableRebuilds = true
	jbsConfig.Spec.MavenBaseLocations = map[string]string{"maven-repository-302-gradle": "https://repo.gradle.org/artifactory/libs-releases"}
	objs := []runtimeclient.Object{jbsConfig, setupSecret(), setupSystemConfig()}
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
	expectedOwnerReference := metav1.OwnerReference{
		Kind:       "JBSConfig",
		APIVersion: "jvmbuildservice.io/v1alpha1",
		UID:        "",
		Name:       v1alpha1.JBSConfigName,
	}
	objs := []runtimeclient.Object{jbsConfig, setupSecret(), setupSystemConfig()}
	client, reconciler := setupClientAndReconciler(false, objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}})
	g.Expect(err).To(BeNil())

	// Verify finalizer has been added
	jbsc := v1alpha1.JBSConfig{}
	err = client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}, &jbsc)
	g.Expect(err).To(BeNil())

	// As this is not running on a real cluster no GC will be running. See https://book-v2.book.kubebuilder.io/reference/envtest.html
	// Therefore we cannot verify deletion but we should verify the correct objects have the owner references configured.
	dep := appsv1.Deployment{}
	pvc := corev1.PersistentVolumeClaim{}
	role := rbacv1.RoleBinding{}
	svc := corev1.Service{}
	svcacc := corev1.ServiceAccount{}
	tlssvc := corev1.Service{}
	err = client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.CacheDeploymentName}, &dep)
	g.Expect(err).To(BeNil())
	err = client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.CacheDeploymentName}, &pvc)
	g.Expect(err).To(BeNil())
	err = client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.CacheDeploymentName}, &role)
	g.Expect(err).To(BeNil())
	err = client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.CacheDeploymentName}, &svc)
	g.Expect(err).To(BeNil())
	err = client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.CacheDeploymentName}, &svcacc)
	g.Expect(err).To(BeNil())
	err = client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: TlsServiceName}, &tlssvc)
	g.Expect(err).To(BeNil())
	g.Expect(dep.OwnerReferences).To(ContainElement(expectedOwnerReference))
	g.Expect(pvc.OwnerReferences).To(ContainElement(expectedOwnerReference))
	g.Expect(role.OwnerReferences).To(ContainElement(expectedOwnerReference))
	g.Expect(svc.OwnerReferences).To(ContainElement(expectedOwnerReference))
	g.Expect(svcacc.OwnerReferences).To(ContainElement(expectedOwnerReference))
	g.Expect(tlssvc.OwnerReferences).To(ContainElement(expectedOwnerReference))

	err = client.Delete(ctx, jbsConfig)
	g.Expect(err).To(BeNil())

	// While the owner references are not deleted using fakeclient/envtest, the finalizers and the JBSConfig should be
	// so attempt another reconcile to verify it does not exist.
	_, err = reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}})
	g.Expect(errors.IsNotFound(err)).To(BeTrue())
}

func TestMissingRegistrySecretWithSpi(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	jbsConfig := setupJBSConfig()
	jbsConfig.Spec.EnableRebuilds = true
	objs := []runtimeclient.Object{jbsConfig, setupSystemConfig()}
	client, reconciler := setupClientAndReconciler(true, objs...)
	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}})
	g.Expect(err).To(BeNil()) //no error to prevent requeue

	binding := spi.SPIAccessTokenBinding{}
	err = client.Get(context.TODO(), types.NamespacedName{Namespace: jbsConfig.Namespace, Name: v1alpha1.ImageSecretName}, &binding)
	g.Expect(err).To(BeNil())
	g.Expect(binding.Spec.RepoUrl).To(Equal("https://quay.io/tests/artifact-deployments"))

}

func TestImageRegistryToString(t *testing.T) {
	g := NewGomegaWithT(t)
	registry := v1alpha1.ImageRegistry{
		Host:       "quay.io",
		Port:       "",
		Owner:      "nobody",
		Repository: "foo",
		Insecure:   false,
		PrependTag: "",
	}
	result := ImageRegistryToString(registry)
	g.Expect(result).To(Equal("quay.io,,nobody,foo,false,"))
}

func TestImageRegistryArrayToString(t *testing.T) {
	g := NewGomegaWithT(t)
	registries1 := []v1alpha1.ImageRegistry{
		{
			Host:       "quay.io",
			Port:       "",
			Owner:      "nobody",
			Repository: "foo",
			Insecure:   false,
			PrependTag: "",
		},
	}
	registries2 := append(registries1, v1alpha1.ImageRegistry{
		Host:       "quay.io",
		Port:       "784",
		Owner:      "nobody",
		Repository: "foo",
		Insecure:   false,
		PrependTag: "foo",
	})
	g.Expect(ImageRegistriesToString(logr.Discard(), []v1alpha1.ImageRegistry{})).To(Equal(""))
	g.Expect(ImageRegistriesToString(logr.Discard(), registries1)).To(Equal("quay.io,,nobody,foo,false,"))
	g.Expect(ImageRegistriesToString(logr.Discard(), registries2)).To(Equal("quay.io,,nobody,foo,false,;quay.io,784,nobody,foo,false,foo"))
}

func TestDeprecatedRegistry(t *testing.T) {
	g := NewGomegaWithT(t)
	ctx := context.TODO()
	jbsConfig := setupJBSConfig()
	jbsConfig.Spec.Owner = "tests"
	jbsConfig.Spec.Registry.Owner = ""
	jbsConfig.Spec.EnableRebuilds = true
	objs := []runtimeclient.Object{jbsConfig, setupSystemConfig()}
	_, reconciler := setupClientAndReconciler(false, objs...)
	_, res := reconciler.handleDeprecatedRegistryDefinition(ctx, jbsConfig)
	g.Expect(res).To(Equal(true))
	g.Expect(jbsConfig.Spec.ImageRegistry.Owner).To(Equal(""))
	g.Expect(jbsConfig.Spec.Registry.Owner).To(Equal("tests"))
}
