package pendingpipelinerun

import (
	"context"
	"testing"
	"time"

	quotav1 "github.com/openshift/api/quota/v1"
	fakequotaclientset "github.com/openshift/client-go/quota/clientset/versioned/fake"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/clusterresourcequota"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/api/node/v1alpha1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"

	"knative.dev/pkg/apis"

	. "github.com/onsi/gomega"
)

func setupClientAndPRReconciler(useOpenshift bool, objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcilePendingPipelineRun) {
	scheme := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(scheme)
	_ = v1beta1.AddToScheme(scheme)
	_ = corev1.AddToScheme(scheme)
	_ = quotav1.AddToScheme(scheme)
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	if useOpenshift {
		clusterresourcequota.QuotaClient = fakequotaclientset.NewSimpleClientset()
	}
	reconciler := &ReconcilePendingPipelineRun{client: client, scheme: scheme, eventRecorder: &record.FakeRecorder{}}
	return client, reconciler
}

func TestPendingPipelineRun(t *testing.T) {
	g := NewGomegaWithT(t)
	client, reconciler := setupClientAndPRReconciler(false)

	pr := v1beta1.PipelineRun{}
	pr.Namespace = metav1.NamespaceDefault
	pr.Name = "test"
	pr.Spec.ServiceAccountName = "foo"
	ctx := context.TODO()
	c := &PendingCreate{}
	err := c.CreateWrapperForPipelineRun(ctx, client, &pr)
	g.Expect(err).NotTo(HaveOccurred())

	key := runtimeclient.ObjectKey{Namespace: pr.Namespace, Name: pr.Name}
	err = client.Get(ctx, key, &pr)
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(pr.Spec.Status).To(Equal(v1beta1.PipelineRunSpecStatus(v1beta1.PipelineRunSpecStatusPending)))

	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: pr.Namespace, Name: pr.Name}}))
	err = client.Get(ctx, key, &pr)
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(pr.Spec.Status).To(Equal(v1beta1.PipelineRunSpecStatus("")))
}

func TestPendingPipelinerunUnderK8sQuota(t *testing.T) {
	g := NewGomegaWithT(t)
	quota := &corev1.ResourceQuota{
		Spec: corev1.ResourceQuotaSpec{
			Hard: corev1.ResourceList{
				corev1.ResourcePods: resource.MustParse("5"),
			},
		},
	}
	quota.Namespace = metav1.NamespaceDefault
	quota.Name = "foo"
	client, reconciler := setupClientAndPRReconciler(false, quota)

	pr := v1beta1.PipelineRun{}
	pr.Namespace = metav1.NamespaceDefault
	pr.Name = "test"
	pr.Spec.ServiceAccountName = "foo"
	ctx := context.TODO()
	c := &PendingCreate{}
	err := c.CreateWrapperForPipelineRun(ctx, client, &pr)
	g.Expect(err).NotTo(HaveOccurred())

	key := runtimeclient.ObjectKey{Namespace: pr.Namespace, Name: pr.Name}
	err = client.Get(ctx, key, &pr)
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(pr.Spec.Status).To(Equal(v1beta1.PipelineRunSpecStatus(v1beta1.PipelineRunSpecStatusPending)))

	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: pr.Namespace, Name: pr.Name}}))
	err = client.Get(ctx, key, &pr)
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(pr.Spec.Status).To(Equal(v1beta1.PipelineRunSpecStatus("")))
}

func TestExceedK8sQuota(t *testing.T) {
	g := NewGomegaWithT(t)
	quota := &corev1.ResourceQuota{
		Spec: corev1.ResourceQuotaSpec{
			Hard: corev1.ResourceList{
				corev1.ResourcePods: resource.MustParse("5"),
			},
		},
	}
	quota.Namespace = metav1.NamespaceDefault
	quota.Name = "foo"
	client, reconciler := setupClientAndPRReconciler(false, quota)
	prs := []v1beta1.PipelineRun{
		{ObjectMeta: metav1.ObjectMeta{Namespace: metav1.NamespaceDefault, Name: "test1"}},
		{ObjectMeta: metav1.ObjectMeta{Namespace: metav1.NamespaceDefault, Name: "test2"}},
		{ObjectMeta: metav1.ObjectMeta{Namespace: metav1.NamespaceDefault, Name: "test3"}},
		{ObjectMeta: metav1.ObjectMeta{Namespace: metav1.NamespaceDefault, Name: "test4"}},
		{ObjectMeta: metav1.ObjectMeta{Namespace: metav1.NamespaceDefault, Name: "test5"}},
	}
	ctx := context.TODO()
	c := &PendingCreate{}
	for _, pr := range prs {
		err := c.CreateWrapperForPipelineRun(ctx, client, &pr)
		g.Expect(err).NotTo(HaveOccurred())
		key := runtimeclient.ObjectKey{Namespace: pr.Namespace, Name: pr.Name}
		err = client.Get(ctx, key, &pr)
		g.Expect(err).NotTo(HaveOccurred())
		g.Expect(pr.Spec.Status).To(Equal(v1beta1.PipelineRunSpecStatus(v1beta1.PipelineRunSpecStatusPending)))
	}

	// this goes down the totalCount == pendingCount path, after not falling into the below quota path
	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: prs[0].Namespace, Name: prs[0].Name}}))
	key := runtimeclient.ObjectKey{Namespace: prs[0].Namespace, Name: prs[0].Name}
	pr := v1beta1.PipelineRun{}
	err := client.Get(ctx, key, &pr)
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(pr.Spec.Status).To(Equal(v1beta1.PipelineRunSpecStatus("")))

	// now, with at least 1 non pending pr, finagle creation time to be timed out
	key = runtimeclient.ObjectKey{Namespace: prs[1].Namespace, Name: prs[1].Name}
	err = client.Get(ctx, key, &pr)
	pr.ObjectMeta.CreationTimestamp = metav1.NewTime(time.Unix(0, 0))
	err = client.Update(ctx, &pr)
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: prs[1].Namespace, Name: prs[1].Name}}))
	key = runtimeclient.ObjectKey{Namespace: prs[1].Namespace, Name: prs[1].Name}
	err = client.Get(ctx, key, &pr)
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(pr.Annotations).NotTo(BeNil())
	val, ok := pr.Annotations[AbandonedAnnotation]
	g.Expect(ok).To(BeTrue())
	g.Expect(val).To(Equal("true"))
	g.Expect(pr.Spec.Status).To(Equal(v1beta1.PipelineRunSpecStatus(v1beta1.PipelineRunSpecStatusCancelled)))
	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: prs[1].Namespace, Name: prs[1].Name}}))
	key = runtimeclient.ObjectKey{Namespace: prs[1].Namespace, Name: prs[1].Name}
	err = client.Get(ctx, key, &pr)
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(pr.Status.GetCondition(apis.ConditionSucceeded).Reason).To(Equal(AbandonedReason))

}

func TestExceedK8sQuotaNotTimedOut(t *testing.T) {
	g := NewGomegaWithT(t)
	quota := &corev1.ResourceQuota{
		Spec: corev1.ResourceQuotaSpec{
			Hard: corev1.ResourceList{
				corev1.ResourcePods: resource.MustParse("5"),
			},
		},
	}
	quota.Namespace = metav1.NamespaceDefault
	quota.Name = "foo"
	client, reconciler := setupClientAndPRReconciler(false, quota)
	prs := []v1beta1.PipelineRun{
		{ObjectMeta: metav1.ObjectMeta{Namespace: metav1.NamespaceDefault, Name: "test1", CreationTimestamp: metav1.NewTime(time.Now())}},
		{ObjectMeta: metav1.ObjectMeta{Namespace: metav1.NamespaceDefault, Name: "test2", CreationTimestamp: metav1.NewTime(time.Now())}},
		{ObjectMeta: metav1.ObjectMeta{Namespace: metav1.NamespaceDefault, Name: "test3", CreationTimestamp: metav1.NewTime(time.Now())}},
		{ObjectMeta: metav1.ObjectMeta{Namespace: metav1.NamespaceDefault, Name: "test4", CreationTimestamp: metav1.NewTime(time.Now())}},
		{ObjectMeta: metav1.ObjectMeta{Namespace: metav1.NamespaceDefault, Name: "test5", CreationTimestamp: metav1.NewTime(time.Now())}},
	}
	ctx := context.TODO()
	c := &PendingCreate{}
	for _, pr := range prs {
		err := c.CreateWrapperForPipelineRun(ctx, client, &pr)
		g.Expect(err).NotTo(HaveOccurred())
		key := runtimeclient.ObjectKey{Namespace: pr.Namespace, Name: pr.Name}
		err = client.Get(ctx, key, &pr)
		g.Expect(err).NotTo(HaveOccurred())
		g.Expect(pr.Spec.Status).To(Equal(v1beta1.PipelineRunSpecStatus(v1beta1.PipelineRunSpecStatusPending)))
	}

	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: prs[0].Namespace, Name: prs[0].Name}}))
	key := runtimeclient.ObjectKey{Namespace: prs[0].Namespace, Name: prs[0].Name}
	pr := v1beta1.PipelineRun{}
	err := client.Get(ctx, key, &pr)
	g.Expect(err).NotTo(HaveOccurred())
	g.Expect(pr.Spec.Status).To(Equal(v1beta1.PipelineRunSpecStatus("")))

	// with start time set, the PR should registered as timed out
	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: prs[1].Namespace, Name: prs[1].Name}}))
	key = runtimeclient.ObjectKey{Namespace: prs[1].Namespace, Name: prs[1].Name}
	err = client.Get(ctx, key, &pr)
	g.Expect(pr.Spec.Status).To(Equal(v1beta1.PipelineRunSpecStatus(v1beta1.PipelineRunSpecStatusPending)))

}
