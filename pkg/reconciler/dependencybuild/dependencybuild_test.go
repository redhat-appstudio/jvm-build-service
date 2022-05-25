package dependencybuild

import (
	"context"
	"github.com/onsi/gomega"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuildrequest"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"testing"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"

	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
)

func setupClientAndReconciler(objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcileDependencyBuild) {
	scheme := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(scheme)
	_ = pipelinev1beta1.AddToScheme(scheme)
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	reconciler := &ReconcileDependencyBuild{client: client, scheme: scheme}
	return client, reconciler
}

func TestReconcileNew(t *testing.T) {
	gomega.RegisterFailHandler(func(message string, callerSkip ...int) {
		t.Fatal(message)
	})
	db := v1alpha1.DependencyBuild{}
	db.Namespace = metav1.NamespaceDefault
	db.Name = "test"
	db.Status.State = v1alpha1.DependencyBuildStateNew
	db.Spec.SCMURL = "some-url"
	db.Spec.Tag = "some-tag"
	db.Spec.Path = "some-path"
	db.Labels = map[string]string{artifactbuildrequest.DependencyBuildIdLabel: hashToString(db.Spec.SCMURL + db.Spec.Tag + db.Spec.Path)}

	ctx := context.TODO()
	client, reconciler := setupClientAndReconciler(&db)

	_, err := reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: db.Name}})
	if err != nil {
		t.Fatalf("%s", err.Error())
	}

	err = client.Get(ctx, types.NamespacedName{
		Namespace: metav1.NamespaceDefault,
		Name:      "test",
	}, &db)
	if err != nil {
		t.Fatalf("%s", err.Error())
	}
	if db.Status.State != v1alpha1.DependencyBuildStateBuilding {
		t.Fatalf("db at incorrect state: %s", db.Status.State)
	}

	prList := &pipelinev1beta1.PipelineRunList{}
	err = client.List(ctx, prList)
	if err != nil {
		t.Fatalf("%s", err.Error())
	}
	for _, pr := range prList.Items {
		if pr.Labels[artifactbuildrequest.DependencyBuildIdLabel] != db.Labels[artifactbuildrequest.DependencyBuildIdLabel] {
			t.Fatalf("db/pr label mismatch: %s and %s", pr.Labels[artifactbuildrequest.DependencyBuildIdLabel], db.Labels[artifactbuildrequest.DependencyBuildIdLabel])
		}
		for _, or := range pr.OwnerReferences {
			if or.Kind != db.Kind || or.Name != db.Name {
				t.Fatalf("db/pr owner ref mismatch: %s %s %s %s", or.Kind, db.Kind, or.Name, db.Name)
			}
		}
		gomega.Expect(len(pr.Spec.Params)).Should(gomega.Equal(3))
		for _, param := range pr.Spec.Params {
			switch param.Name {
			case PipelineScmTag:
				gomega.Expect(param.Value.StringVal).Should(gomega.Equal("some-tag"))
			case PipelinePath:
				gomega.Expect(param.Value.StringVal).Should(gomega.Equal("some-path"))
			case PipelineScmUrl:
				gomega.Expect(param.Value.StringVal).Should(gomega.Equal("some-url"))
			}
		}
	}
	if len(prList.Items) == 0 {
		t.Fatalf("no pr found")
	}
}
