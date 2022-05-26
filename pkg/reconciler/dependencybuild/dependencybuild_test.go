package dependencybuild

import (
	"context"
	. "github.com/onsi/gomega"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuildrequest"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"knative.dev/pkg/apis"
	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"testing"
	"time"

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

func TestStateNew(t *testing.T) {
	t.Run("Test reconcile new DependencyBuild", func(t *testing.T) {
		g := NewGomegaWithT(t)
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

		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: db.Name}}))

		g.Expect(client.Get(ctx, types.NamespacedName{
			Namespace: metav1.NamespaceDefault,
			Name:      "test",
		}, &db))
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateBuilding))

		prList := &pipelinev1beta1.PipelineRunList{}
		g.Expect(client.List(ctx, prList))

		g.Expect(len(prList.Items)).Should(Equal(1))
		for _, pr := range prList.Items {
			g.Expect(pr.Labels[artifactbuildrequest.DependencyBuildIdLabel]).Should(Equal(db.Labels[artifactbuildrequest.DependencyBuildIdLabel]))
			for _, or := range pr.OwnerReferences {
				if or.Kind != db.Kind || or.Name != db.Name {
					g.Expect(or.Kind).Should(Equal(db.Kind))
					g.Expect(or.Name).Should(Equal(db.Name))
				}
			}
			g.Expect(len(pr.Spec.Params)).Should(Equal(3))
			for _, param := range pr.Spec.Params {
				switch param.Name {
				case PipelineScmTag:
					g.Expect(param.Value.StringVal).Should(Equal("some-tag"))
				case PipelinePath:
					g.Expect(param.Value.StringVal).Should(Equal("some-path"))
				case PipelineScmUrl:
					g.Expect(param.Value.StringVal).Should(Equal("some-url"))
				}
			}
		}
	})
}

func getBuild(client runtimeclient.Client, g *WithT) *v1alpha1.DependencyBuild {
	ctx := context.TODO()
	build := v1alpha1.DependencyBuild{}
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}, &build))
	return &build
}
func getPr(client runtimeclient.Client, g *WithT) *pipelinev1beta1.PipelineRun {
	ctx := context.TODO()
	build := pipelinev1beta1.PipelineRun{}
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}, &build))
	return &build
}
func TestStateBuilding(t *testing.T) {
	ctx := context.TODO()

	var client runtimeclient.Client
	var reconciler *ReconcileDependencyBuild
	buildName := types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}
	setup := func(g *WithT) {
		client, reconciler = setupClientAndReconciler()
		db := v1alpha1.DependencyBuild{}
		db.Namespace = metav1.NamespaceDefault
		db.Name = "test"
		db.Status.State = v1alpha1.DependencyBuildStateBuilding
		db.Spec.SCMURL = "some-url"
		db.Spec.Tag = "some-tag"
		db.Spec.Path = "some-path"
		db.Labels = map[string]string{artifactbuildrequest.DependencyBuildIdLabel: hashToString(db.Spec.SCMURL + db.Spec.Tag + db.Spec.Path)}
		g.Expect(client.Create(ctx, &db))

		pr := pipelinev1beta1.PipelineRun{}
		pr.Namespace = metav1.NamespaceDefault
		pr.Name = "test"
		pr.Labels = map[string]string{artifactbuildrequest.DependencyBuildIdLabel: hashToString(db.Spec.SCMURL + db.Spec.Tag + db.Spec.Path)}
		g.Expect(client.Create(ctx, &pr))

	}

	t.Run("Test reconcile building DependencyBuild with running pipeline", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: buildName}))
		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateBuilding))
	})
	t.Run("Test reconcile building DependencyBuild with succeeded pipeline", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		pr := getPr(client, g)
		pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "True",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
		g.Expect(client.Update(ctx, pr))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: buildName}))
		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateComplete))
	})
	t.Run("Test reconcile building DependencyBuild with failed pipeline", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		pr := getPr(client, g)
		pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "False",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
		g.Expect(client.Update(ctx, pr))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: buildName}))
		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateFailed))
	})
	t.Run("Test reconcile building DependencyBuild with contaminants", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		pr := getPr(client, g)
		pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "True",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
		pr.Status.PipelineResults = []pipelinev1beta1.PipelineRunResult{{Name: "contaminants", Value: "com.acme:foo:1.0,com.acme:bar:1.0"}}
		g.Expect(client.Update(ctx, pr))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: buildName}))
		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateContaminated))
		g.Expect(db.Status.Contaminants).Should(ContainElement("com.acme:foo:1.0"))
		g.Expect(db.Status.Contaminants).Should(ContainElement("com.acme:bar:1.0"))
	})
}
