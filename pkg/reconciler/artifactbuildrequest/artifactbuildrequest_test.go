package artifactbuildrequest

import (
	"context"
	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"knative.dev/pkg/apis/duck/v1beta1"
	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"testing"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"

	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
)

func setupClientAndReconciler(objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcileArtifactBuildRequest) {
	scheme := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(scheme)
	_ = pipelinev1beta1.AddToScheme(scheme)
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	reconciler := &ReconcileArtifactBuildRequest{client: client, scheme: scheme}
	return client, reconciler
}

func TestSetup(t *testing.T) {
	RegisterFailHandler(Fail)
	RunSpecs(t, "Artifact Build Request Suite")
}

var _ = It("Test reconcile new ArtifactBuildRequest", func() {
	abr := v1alpha1.ArtifactBuildRequest{}
	abr.Namespace = metav1.NamespaceDefault
	abr.Name = "test"
	abr.Status.State = v1alpha1.ArtifactBuildRequestStateNew

	ctx := context.TODO()
	client, reconciler := setupClientAndReconciler(&abr)

	Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: abr.Namespace, Name: abr.Name}}))

	Expect(client.Get(ctx, types.NamespacedName{
		Namespace: metav1.NamespaceDefault,
		Name:      "test",
	}, &abr))
	Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateDiscovering))

	trList := &pipelinev1beta1.TaskRunList{}
	Expect(client.List(ctx, trList))
	Expect(len(trList.Items)).Should(Equal(1))
	for _, tr := range trList.Items {
		for _, or := range tr.OwnerReferences {
			Expect(or.Kind).Should(Equal(abr.Kind))
			Expect(or.Name).Should(Equal(abr.Name))
		}
	}
})

func getABR(client runtimeclient.Client) *v1alpha1.ArtifactBuildRequest {
	ctx := context.TODO()
	abr := v1alpha1.ArtifactBuildRequest{}
	Expect(client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}, &abr))
	return &abr
}

var _ = Describe("Test Reconcile with state Discovering", func() {
	ctx := context.TODO()

	fullValidation := func(client runtimeclient.Client) {
		abr := getABR(client)
		Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateBuilding))

		val, ok := abr.Labels[DependencyBuildIdLabel]
		Expect(ok).Should(Equal(true))
		Expect(val).Should(Not(BeEmpty()))

		dbList := v1alpha1.DependencyBuildList{}
		Expect(client.List(context.TODO(), &dbList))
		Expect(dbList.Items).Should(Not(BeEmpty()))
		for _, db := range dbList.Items {
			for _, or := range db.OwnerReferences {
				Expect(or.Kind).Should(Equal(abr.Kind))
				Expect(or.Name).Should(Equal(abr.Name))
			}
			Expect(db.Spec.Tag).Should(Equal("foo"))
			Expect(db.Spec.SCMURL).Should(Equal("goo"))
			Expect(db.Spec.SCMType).Should(Equal("hoo"))
			Expect(db.Spec.Path).Should(Equal("ioo"))

			Expect(abr.Status.Tag).Should(Equal("foo"))
			Expect(abr.Status.SCMURL).Should(Equal("goo"))
			Expect(abr.Status.SCMType).Should(Equal("hoo"))
			Expect(abr.Status.Path).Should(Equal("ioo"))
		}
	}

	var client runtimeclient.Client
	var reconciler *ReconcileArtifactBuildRequest
	now := metav1.Now()
	BeforeEach(func() {
		abr := &v1alpha1.ArtifactBuildRequest{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test",
				Namespace: metav1.NamespaceDefault,
				UID:       "someuid",
			},
			Spec: v1alpha1.ArtifactBuildRequestSpec{},
			Status: v1alpha1.ArtifactBuildRequestStatus{
				State: v1alpha1.ArtifactBuildRequestStateDiscovering,
			},
		}
		client, reconciler = setupClientAndReconciler(abr)
	})
	It("SCM tag cannot be determined", func() {
		Expect(client.Create(ctx, &pipelinev1beta1.TaskRun{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{ArtifactBuildRequestIdLabel: "someuid"},
			},
			Spec: pipelinev1beta1.TaskRunSpec{},
			Status: pipelinev1beta1.TaskRunStatus{
				Status:              v1beta1.Status{},
				TaskRunStatusFields: pipelinev1beta1.TaskRunStatusFields{CompletionTime: &now},
			},
		}))
		Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr := getABR(client)
		Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateMissing))
	})
	It("First ABR creates DependencyBuild", func() {
		Expect(client.Create(ctx, &pipelinev1beta1.TaskRun{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{ArtifactBuildRequestIdLabel: "someuid"},
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
		Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		fullValidation(client)
	})
	It("DependencyBuild already exists for ABR", func() {
		Expect(client.Create(ctx, &pipelinev1beta1.TaskRun{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{ArtifactBuildRequestIdLabel: "someuid"},
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
		Expect(client.Create(ctx, &v1alpha1.DependencyBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test-generated",
				Namespace: metav1.NamespaceDefault,
			},
			Spec: v1alpha1.DependencyBuildSpec{
				Tag:     "foo",
				SCMURL:  "goo",
				SCMType: "hoo",
				Path:    "ioo",
			},
		}))
		Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		fullValidation(client)
	})
})

var _ = Describe("Test reconcile state Building", func() {
	ctx := context.TODO()
	var client runtimeclient.Client
	var reconciler *ReconcileArtifactBuildRequest
	BeforeEach(func() {
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
	})
	It("Failed build", func() {
		Expect(client.Create(ctx, &v1alpha1.DependencyBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test-generated",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: "test"},
			},
			Spec:   v1alpha1.DependencyBuildSpec{},
			Status: v1alpha1.DependencyBuildStatus{State: v1alpha1.DependencyBuildStateFailed},
		}))
		Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr := getABR(client)
		Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateFailed))
	})
	It("Completed build", func() {
		Expect(client.Create(ctx, &v1alpha1.DependencyBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test-generated",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: "test"},
			},
			Spec:   v1alpha1.DependencyBuildSpec{},
			Status: v1alpha1.DependencyBuildStatus{State: v1alpha1.DependencyBuildStateComplete},
		}))
		Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr := getABR(client)
		Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateComplete))
	})
	It("Contaminated build", func() {
		Expect(client.Create(ctx, &v1alpha1.DependencyBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test-generated",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: "test"},
			},
			Spec:   v1alpha1.DependencyBuildSpec{},
			Status: v1alpha1.DependencyBuildStatus{State: v1alpha1.DependencyBuildStateContaminated, Contaminants: []string{"com.foo:acme:1.0"}},
		}))
		Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr := getABR(client)
		Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateFailed))
	})
	It("Missing (deleted) build", func() {
		Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr := getABR(client)
		Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildRequestStateNew))
	})
})
