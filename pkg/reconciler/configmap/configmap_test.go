package configmap

import (
	"context"
	. "github.com/onsi/gomega"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	v12 "k8s.io/api/apps/v1"
	v1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"testing"
)

func setupClientAndReconciler(objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcileConfigMap) {
	scheme := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(scheme)
	_ = pipelinev1beta1.AddToScheme(scheme)
	_ = v1.AddToScheme(scheme)
	_ = v12.AddToScheme(scheme)
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	reconciler := &ReconcileConfigMap{client: client, scheme: scheme, eventRecorder: &record.FakeRecorder{}}
	return client, reconciler
}

func TestSetupEmptyConfigMap(t *testing.T) {
	g := NewGomegaWithT(t)
	configMap := v1.ConfigMap{}
	configMap.Namespace = metav1.NamespaceDefault
	configMap.Name = UserConfigMapName
	value := readConfiguredRepositories(configMap, g)
	g.Expect(*value).Should(Equal("central"))
}

func TestSetupRebuildsEnabled(t *testing.T) {
	g := NewGomegaWithT(t)
	configMap := v1.ConfigMap{}
	configMap.Namespace = metav1.NamespaceDefault
	configMap.Name = UserConfigMapName
	configMap.Data = map[string]string{EnableRebuilds: "true"}
	value := readConfiguredRepositories(configMap, g)
	g.Expect(*value).Should(Equal("rebuilt,central"))
}
func TestSetupRebuildsEnabledCustomRepo(t *testing.T) {
	g := NewGomegaWithT(t)
	configMap := v1.ConfigMap{}
	configMap.Namespace = metav1.NamespaceDefault
	configMap.Name = UserConfigMapName
	configMap.Data = map[string]string{EnableRebuilds: "true", "maven-repository-302-gradle": "https://repo.gradle.org/artifactory/libs-releases"}
	value := readConfiguredRepositories(configMap, g)
	g.Expect(*value).Should(Equal("rebuilt,central,gradle"))
}

func readConfiguredRepositories(configMap v1.ConfigMap, g *WithT) *string {
	ctx := context.TODO()
	client, reconciler := setupClientAndReconciler(&configMap)

	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: configMap.Namespace, Name: configMap.Name}}))

	deployment := v12.Deployment{}
	g.Expect(client.Get(ctx, types.NamespacedName{
		Namespace: metav1.NamespaceDefault,
		Name:      CacheDeploymentName,
	}, &deployment))

	var value *string
	for _, val := range deployment.Spec.Template.Spec.Containers[0].Env {
		if val.Name == "BUILD_POLICY_DEFAULT_STORE_LIST" {
			value = &val.Value
			break
		}
	}
	return value
}
