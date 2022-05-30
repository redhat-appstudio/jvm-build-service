package artifactbuildrequest

import (
	"context"
	. "github.com/onsi/gomega"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	"knative.dev/pkg/apis/duck/v1beta1"
	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"testing"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"

	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
)

const gav = "com.acme:foo:1.0"

func setupClientAndReconciler(objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcileArtifactBuildRequest) {
	scheme := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(scheme)
	_ = pipelinev1beta1.AddToScheme(scheme)
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	reconciler := &ReconcileArtifactBuildRequest{client: client, scheme: scheme, eventRecorder: &record.FakeRecorder{}, nonCachingClient: client}
	return client, reconciler
}

func TestDependencyBuild(t *testing.T) {
	g := NewGomegaWithT(t)
	abr := v1alpha1.ArtifactBuildRequest{}
	abr.Namespace = metav1.NamespaceDefault
	abr.Name = "test"
	abr.Status.State = v1alpha1.ArtifactBuildRequestStateNew

	ctx := context.TODO()
	client, reconciler := setupClientAndReconciler(&abr)

	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: abr.Namespace, Name: abr.Name}}))

	g.Expect(client.Get(ctx, types.NamespacedName{
		Namespace: metav1.NamespaceDefault,
		Name:      "test",
	}, &abr))
	g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateDiscovering))

	trList := &pipelinev1beta1.TaskRunList{}
	g.Expect(client.List(ctx, trList))
	g.Expect(len(trList.Items)).Should(Equal(1))
	for _, tr := range trList.Items {
		for _, or := range tr.OwnerReferences {
			g.Expect(or.Kind).Should(Equal(abr.Kind))
			g.Expect(or.Name).Should(Equal(abr.Name))
		}
	}
}

func getABR(client runtimeclient.Client, g *WithT) *v1alpha1.ArtifactBuildRequest {
	ctx := context.TODO()
	abr := v1alpha1.ArtifactBuildRequest{}
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}, &abr))
	return &abr
}

func TestStateDiscovering(t *testing.T) {
	ctx := context.TODO()

	fullValidation := func(client runtimeclient.Client, g *WithT) {
		abr := getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateBuilding))

		dbList := v1alpha1.DependencyBuildList{}
		g.Expect(client.List(context.TODO(), &dbList))
		g.Expect(dbList.Items).Should(Not(BeEmpty()))
		for _, db := range dbList.Items {
			for _, or := range db.OwnerReferences {
				g.Expect(or.Kind).Should(Equal(abr.Kind))
				g.Expect(or.Name).Should(Equal(abr.Name))
			}
			g.Expect(db.Spec.ScmInfo.Tag).Should(Equal("foo"))
			g.Expect(db.Spec.ScmInfo.SCMURL).Should(Equal("goo"))
			g.Expect(db.Spec.ScmInfo.SCMType).Should(Equal("hoo"))
			g.Expect(db.Spec.ScmInfo.Path).Should(Equal("ioo"))

			g.Expect(abr.Status.SCMInfo.Tag).Should(Equal("foo"))
			g.Expect(abr.Status.SCMInfo.SCMURL).Should(Equal("goo"))
			g.Expect(abr.Status.SCMInfo.SCMType).Should(Equal("hoo"))
			g.Expect(abr.Status.SCMInfo.Path).Should(Equal("ioo"))
		}
	}

	var client runtimeclient.Client
	var reconciler *ReconcileArtifactBuildRequest
	now := metav1.Now()
	setup := func() {
		abr := &v1alpha1.ArtifactBuildRequest{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test",
				Namespace: metav1.NamespaceDefault,
			},
			Spec: v1alpha1.ArtifactBuildRequestSpec{GAV: gav},
			Status: v1alpha1.ArtifactBuildRequestStatus{
				State: v1alpha1.ArtifactBuildRequestStateDiscovering,
			},
		}
		client, reconciler = setupClientAndReconciler(abr)
	}
	t.Run("SCM tag cannot be determined", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		g.Expect(client.Create(ctx, &pipelinev1beta1.TaskRun{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{ArtifactBuildRequestIdLabel: ABRLabelForGAV(gav)},
			},
			Spec: pipelinev1beta1.TaskRunSpec{},
			Status: pipelinev1beta1.TaskRunStatus{
				Status:              v1beta1.Status{},
				TaskRunStatusFields: pipelinev1beta1.TaskRunStatusFields{CompletionTime: &now},
			},
		}))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr := getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateMissing))
	})
	t.Run("First ABR creates DependencyBuild", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		g.Expect(client.Create(ctx, &pipelinev1beta1.TaskRun{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{ArtifactBuildRequestIdLabel: ABRLabelForGAV(gav)},
			},
			Spec: pipelinev1beta1.TaskRunSpec{},
			Status: pipelinev1beta1.TaskRunStatus{
				Status: v1beta1.Status{},
				TaskRunStatusFields: pipelinev1beta1.TaskRunStatusFields{CompletionTime: &now, TaskRunResults: []pipelinev1beta1.TaskRunResult{
					{Name: TaskResultScmTag, Value: "foo"},
					{Name: TaskResultScmUrl, Value: "goo"},
					{Name: TaskResultScmType, Value: "hoo"},
					{Name: TaskResultContextPath, Value: "ioo"}}},
			},
		}))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		fullValidation(client, g)
	})
	t.Run("DependencyBuild already exists for ABR", func(t *testing.T) {
		g := NewGomegaWithT(t)
		g.Expect(client.Create(ctx, &pipelinev1beta1.TaskRun{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{ArtifactBuildRequestIdLabel: ABRLabelForGAV(gav)},
			},
			Spec: pipelinev1beta1.TaskRunSpec{},
			Status: pipelinev1beta1.TaskRunStatus{
				Status: v1beta1.Status{},
				TaskRunStatusFields: pipelinev1beta1.TaskRunStatusFields{CompletionTime: &now, TaskRunResults: []pipelinev1beta1.TaskRunResult{
					{Name: TaskResultScmTag, Value: "foo"},
					{Name: TaskResultScmUrl, Value: "goo"},
					{Name: TaskResultScmType, Value: "hoo"},
					{Name: TaskResultContextPath, Value: "ioo"}}},
			},
		}))
		g.Expect(client.Create(ctx, &v1alpha1.DependencyBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test-generated",
				Namespace: metav1.NamespaceDefault,
			},
			Spec: v1alpha1.DependencyBuildSpec{ScmInfo: v1alpha1.SCMInfo{
				Tag:     "foo",
				SCMURL:  "goo",
				SCMType: "hoo",
				Path:    "ioo",
			}},
		}))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		fullValidation(client, g)
	})
}

func TestStateBuilding(t *testing.T) {
	ctx := context.TODO()
	var client runtimeclient.Client
	var reconciler *ReconcileArtifactBuildRequest
	setup := func() {
		abr := &v1alpha1.ArtifactBuildRequest{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: "test"},
			},
			Spec: v1alpha1.ArtifactBuildRequestSpec{},
			Status: v1alpha1.ArtifactBuildRequestStatus{
				State: v1alpha1.ArtifactBuildRequestStateBuilding,
			},
		}
		client, reconciler = setupClientAndReconciler(abr)
	}
	t.Run("Failed build", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		g.Expect(client.Create(ctx, &v1alpha1.DependencyBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test-generated",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: hashString("")},
			},
			Spec:   v1alpha1.DependencyBuildSpec{},
			Status: v1alpha1.DependencyBuildStatus{State: v1alpha1.DependencyBuildStateFailed},
		}))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr := getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateFailed))
	})
	t.Run("Completed build", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		g.Expect(client.Create(ctx, &v1alpha1.DependencyBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test-generated",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: hashString("")},
			},
			Spec:   v1alpha1.DependencyBuildSpec{},
			Status: v1alpha1.DependencyBuildStatus{State: v1alpha1.DependencyBuildStateComplete},
		}))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr := getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateComplete))
	})
	t.Run("Contaminated build", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		g.Expect(client.Create(ctx, &v1alpha1.DependencyBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test-generated",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: hashString("")},
			},
			Spec:   v1alpha1.DependencyBuildSpec{},
			Status: v1alpha1.DependencyBuildStatus{State: v1alpha1.DependencyBuildStateContaminated, Contaminants: []string{"com.foo:acme:1.0"}},
		}))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr := getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateFailed))
	})
	t.Run("Missing (deleted) build", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr := getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateNew))
	})
}
