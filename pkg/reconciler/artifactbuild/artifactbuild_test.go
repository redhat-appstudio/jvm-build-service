package artifactbuild

import (
	"context"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/systemconfig"
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
	"testing"

	. "github.com/onsi/gomega"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"
	appsv1 "k8s.io/api/apps/v1"
	v1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const gav = "com.acme:foo:1.0"
const repo = "https://github.com/foo.git"
const version = "1.0"
const otherName = "other-artifact"
const name = "test"

func setupClientAndReconciler(objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcileArtifactBuild) {
	scheme := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(scheme)
	err := tektonpipeline.AddToScheme(scheme)
	if err != nil {
		panic(err)
	}
	_ = v1.AddToScheme(scheme)
	_ = appsv1.AddToScheme(scheme)
	sysConfig := &v1alpha1.JBSConfig{
		ObjectMeta: metav1.ObjectMeta{Name: v1alpha1.JBSConfigName, Namespace: metav1.NamespaceDefault},
		Spec: v1alpha1.JBSConfigSpec{
			EnableRebuilds:              true,
			RequireArtifactVerification: true,
		},
	}
	systemConfig := &v1alpha1.SystemConfig{
		ObjectMeta: metav1.ObjectMeta{Name: systemconfig.SystemConfigKey},
		Spec:       v1alpha1.SystemConfigSpec{},
	}
	objs = append(objs, sysConfig, systemConfig)
	client := fake.NewClientBuilder().WithStatusSubresource(&v1alpha1.JBSConfig{}, &v1alpha1.ArtifactBuild{}, &v1alpha1.DependencyBuild{}).WithScheme(scheme).WithObjects(objs...).Build()
	reconciler := &ReconcileArtifactBuild{client: client, scheme: scheme, eventRecorder: &record.FakeRecorder{}}
	util.ImageTag = "foo"
	return client, reconciler
}

func getABR(client runtimeclient.Client, g *WithT) *v1alpha1.ArtifactBuild {
	return getNamedABR(client, g, "test")
}

func getNamedABR(client runtimeclient.Client, g *WithT, name string) *v1alpha1.ArtifactBuild {
	ctx := context.TODO()
	abr := v1alpha1.ArtifactBuild{}
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: name}, &abr)).To(BeNil())
	return &abr
}

func TestStateDiscovering(t *testing.T) {
	ctx := context.TODO()

	fullValidation := func(client runtimeclient.Client, g *WithT) {
		abr := getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildStateBuilding))

		dbList := v1alpha1.DependencyBuildList{}
		g.Expect(client.List(context.TODO(), &dbList))
		g.Expect(dbList.Items).Should(Not(BeEmpty()))
		for _, db := range dbList.Items {
			for _, or := range db.OwnerReferences {
				g.Expect(or.Kind).Should(Equal("ArtifactBuild"))
				g.Expect(or.Name).Should(Equal(abr.Name))
			}
			g.Expect(db.Spec.ScmInfo.Tag).Should(Equal("foo"))
			g.Expect(db.Spec.ScmInfo.SCMURL).Should(Equal("goo"))
			g.Expect(db.Spec.ScmInfo.SCMType).Should(Equal("hoo"))
			g.Expect(db.Spec.ScmInfo.Path).Should(Equal("ioo"))
			g.Expect(db.Spec.Version).Should(Equal("1.0"))

			g.Expect(abr.Status.SCMInfo.Tag).Should(Equal("foo"))
			g.Expect(abr.Status.SCMInfo.SCMURL).Should(Equal("goo"))
			g.Expect(abr.Status.SCMInfo.SCMType).Should(Equal("hoo"))
			g.Expect(abr.Status.SCMInfo.Path).Should(Equal("ioo"))
		}
	}

	var client runtimeclient.Client
	var reconciler *ReconcileArtifactBuild
	setup := func() {
		abr := &v1alpha1.ArtifactBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{util.StatusLabel: util.StatusBuilding},
			},
			Spec: v1alpha1.ArtifactBuildSpec{GAV: gav},
			Status: v1alpha1.ArtifactBuildStatus{
				State: v1alpha1.ArtifactBuildStateDiscovering,
			},
		}
		client, reconciler = setupClientAndReconciler(abr)
	}
	t.Run("First ABR creates DependencyBuild", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		abr := getABR(client, g)
		abr.Status.SCMInfo.Tag = "foo"
		abr.Status.SCMInfo.SCMURL = "goo"
		abr.Status.SCMInfo.SCMType = "hoo"
		abr.Status.SCMInfo.Path = "ioo"
		abr.Status.State = v1alpha1.ArtifactBuildStateDiscovering
		g.Expect(client.Status().Update(ctx, abr)).Should(BeNil())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr = getABR(client, g)
		depId := util.HashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: depId}}))
		fullValidation(client, g)
	})

	t.Run("DependencyBuild already exists for ABR", func(t *testing.T) {
		g := NewGomegaWithT(t)
		abr := getABR(client, g)
		abr.Status.SCMInfo.Tag = "foo"
		abr.Status.SCMInfo.SCMURL = "goo"
		abr.Status.SCMInfo.SCMType = "hoo"
		abr.Status.SCMInfo.Path = "ioo"
		abr.Status.State = v1alpha1.ArtifactBuildStateDiscovering
		g.Expect(client.Status().Update(ctx, abr)).Should(BeNil())
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
			},
				Version: "1.0"},
		}))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		fullValidation(client, g)
	})
}

func TestStateBuilding(t *testing.T) {
	ctx := context.TODO()
	var client runtimeclient.Client
	var reconciler *ReconcileArtifactBuild
	setup := func() {
		abr := &v1alpha1.ArtifactBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      name,
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: "test", util.StatusLabel: util.StatusBuilding},
			},
			Spec: v1alpha1.ArtifactBuildSpec{},
			Status: v1alpha1.ArtifactBuildStatus{
				State: v1alpha1.ArtifactBuildStateBuilding,
			},
		}
		other := &v1alpha1.ArtifactBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      otherName,
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: "test", util.StatusLabel: util.StatusBuilding},
			},
			Spec: v1alpha1.ArtifactBuildSpec{},
			Status: v1alpha1.ArtifactBuildStatus{
				State: v1alpha1.ArtifactBuildStateBuilding,
			},
		}
		client, reconciler = setupClientAndReconciler(abr, other)
	}
	t.Run("UpdateArtifactState", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		abr := getABR(client, g)
		result := reconciler.updateArtifactState(ctx, abr, v1alpha1.ArtifactBuildStateBuilding)
		g.Expect(result).Should(BeNil())
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildStateBuilding))
		result = reconciler.updateArtifactState(ctx, abr, v1alpha1.ArtifactBuildStateMissing)
		g.Expect(result).Should(BeNil())
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildStateMissing))
	})
	t.Run("Failed build", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		abr := getABR(client, g)
		depId := util.HashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
		db := &v1alpha1.DependencyBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      depId,
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: util.HashString("")},
			},
			Spec:   v1alpha1.DependencyBuildSpec{},
			Status: v1alpha1.DependencyBuildStatus{State: v1alpha1.DependencyBuildStateFailed},
		}
		g.Expect(controllerutil.SetOwnerReference(abr, db, reconciler.scheme))
		g.Expect(client.Create(ctx, db)).Should(Succeed())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr = getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildStateFailed))
	})
	t.Run("Completed build", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		abr := getABR(client, g)
		depId := util.HashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
		db := &v1alpha1.DependencyBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      depId,
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: util.HashString(""), util.StatusLabel: util.StatusSucceeded},
			},
			Spec:   v1alpha1.DependencyBuildSpec{},
			Status: v1alpha1.DependencyBuildStatus{State: v1alpha1.DependencyBuildStateComplete, DeployedArtifacts: []string{abr.Spec.GAV}},
		}
		g.Expect(controllerutil.SetOwnerReference(abr, db, reconciler.scheme))
		g.Expect(client.Create(ctx, db)).Should(Succeed())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr = getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildStateComplete))
	})
	t.Run("Failed build that is reset", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		abr := getABR(client, g)
		abr.Spec.GAV = "com.foo:gax:2.0"
		abr.Status.SCMInfo.SCMURL = repo
		abr.Status.SCMInfo.Tag = version
		g.Expect(client.Status().Update(ctx, abr)).Should(Succeed())

		other := getNamedABR(client, g, otherName)
		other.Spec.GAV = "org.other:args:1.0"
		other.Status.SCMInfo.SCMURL = repo
		other.Status.SCMInfo.Tag = version
		g.Expect(client.Update(ctx, other)).Should(Succeed())
		depId := util.HashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
		db := v1alpha1.DependencyBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      depId,
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: util.HashString("")},
			},
			Spec:   v1alpha1.DependencyBuildSpec{},
			Status: v1alpha1.DependencyBuildStatus{State: v1alpha1.DependencyBuildStateFailed},
		}
		g.Expect(controllerutil.SetOwnerReference(abr, &db, reconciler.scheme))
		g.Expect(controllerutil.SetOwnerReference(other, &db, reconciler.scheme))
		g.Expect(client.Create(ctx, &db)).Should(Succeed())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr = getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildStateFailed))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: otherName}}))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: otherName}}))
		abr = getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildStateFailed))
		abr.Annotations = map[string]string{RebuildAnnotation: "true"}
		g.Expect(client.Update(ctx, abr))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: otherName}}))
		abr = getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildStateNew))
		g.Expect(abr.Annotations[RebuildAnnotation]).Should(Equal("true")) //first reconcile does not remove the annotation

		abr.Labels[util.StatusLabel] = util.StatusBuilding
		g.Expect(client.Update(ctx, abr)).Should(Succeed())

		err := client.Get(ctx, types.NamespacedName{Name: db.Name, Namespace: db.Namespace}, &db)
		g.Expect(errors.IsNotFound(err)).Should(BeTrue())
		other = getNamedABR(client, g, otherName)
		g.Expect(other.Status.State).Should(Equal(v1alpha1.ArtifactBuildStateNew))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr = getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildStateNew))
		g.Expect(abr.Annotations[RebuildAnnotation]).Should(Equal("")) //second reconcile removes the annotation
	})
	t.Run("Contaminated build", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		abr := getABR(client, g)
		depId := util.HashString(abr.Status.SCMInfo.SCMURL + abr.Status.SCMInfo.Tag + abr.Status.SCMInfo.Path)
		db := &v1alpha1.DependencyBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      depId,
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: util.HashString("")},
			},
			Spec:   v1alpha1.DependencyBuildSpec{},
			Status: v1alpha1.DependencyBuildStatus{State: v1alpha1.DependencyBuildStateContaminated, Contaminants: []*v1alpha1.Contaminant{{GAV: "com.test:test:1.0", ContaminatedArtifacts: []string{"a:b:1"}}}},
		}
		g.Expect(controllerutil.SetOwnerReference(abr, db, reconciler.scheme))
		g.Expect(client.Create(ctx, db))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr = getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildStateFailed))
	})
	t.Run("Missing (deleted) build", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		abr := getABR(client, g)
		g.Expect(abr.Status.State).Should(Equal(v1alpha1.ArtifactBuildStateNew))
	})
}

func TestStateCompleteFixingContamination(t *testing.T) {
	ctx := context.TODO()
	var client runtimeclient.Client
	var reconciler *ReconcileArtifactBuild
	contaminatedName := "contaminated-build"
	setup := func() {
		abr := &v1alpha1.ArtifactBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      "test",
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: "test", util.StatusLabel: util.StatusSucceeded},
				Annotations: map[string]string{
					DependencyBuildContaminatedByAnnotation + "suffix": contaminatedName,
				},
			},
			Spec: v1alpha1.ArtifactBuildSpec{GAV: "com.test:test:1.0"},
			Status: v1alpha1.ArtifactBuildStatus{
				State: v1alpha1.ArtifactBuildStateComplete,
			},
		}
		contaiminated := &v1alpha1.DependencyBuild{
			TypeMeta: metav1.TypeMeta{},
			ObjectMeta: metav1.ObjectMeta{
				Name:      contaminatedName,
				Namespace: metav1.NamespaceDefault,
				Labels:    map[string]string{DependencyBuildIdLabel: util.HashString("")},
			},
			Spec:   v1alpha1.DependencyBuildSpec{},
			Status: v1alpha1.DependencyBuildStatus{State: v1alpha1.DependencyBuildStateContaminated, Contaminants: []*v1alpha1.Contaminant{{GAV: "com.test:test:1.0", ContaminatedArtifacts: []string{"a:b:1"}}}},
		}
		client, reconciler = setupClientAndReconciler(abr, contaiminated)
	}
	t.Run("Test contamination cleared", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup()
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}}))
		db := v1alpha1.DependencyBuild{}
		g.Expect(client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: contaminatedName}, &db))
		allOk := true
		for _, i := range db.Status.Contaminants {
			if !i.Allowed && !i.RebuildAvailable {
				allOk = false
			}
		}
		g.Expect(allOk).Should(BeTrue())
	})
}
