package dependencybuild

import (
	"context"
	"encoding/json"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/systemconfig"
	"testing"
	"time"

	. "github.com/onsi/gomega"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/tektonwrapper"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	appsv1 "k8s.io/api/apps/v1"
	v1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	"knative.dev/pkg/apis"
	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const TestArtifact = "com.test:test:1.0"

func setupClientAndReconciler(objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcileDependencyBuild) {
	scheme := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(scheme)
	_ = pipelinev1beta1.AddToScheme(scheme)
	_ = v1.AddToScheme(scheme)
	_ = appsv1.AddToScheme(scheme)
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	reconciler := &ReconcileDependencyBuild{
		client:        client,
		scheme:        scheme,
		eventRecorder: &record.FakeRecorder{},
		prCreator:     &tektonwrapper.ImmediateCreate{},
	}

	sysConfig := v1alpha1.SystemConfig{
		ObjectMeta: metav1.ObjectMeta{Name: systemconfig.SystemConfigKey},
		Spec: v1alpha1.SystemConfigSpec{
			JDK8Image:  "quay.io/redhat-appstudio/hacbs-jdk8-builder:latest",
			JDK8Tags:   "jdk:8,maven:3.8,gradle:7.4.2;6.9.2;5.6.4;4.10.3",
			JDK11Image: "quay.io/redhat-appstudio/hacbs-jdk11-builder:latest",
			JDK11Tags:  "jdk:11,maven:3.8,gradle:7.4.2;6.9.2;5.6.4;4.10.3",
			JDK17Image: "quay.io/redhat-appstudio/hacbs-jdk17-builder:latest",
			JDK17Tags:  "jdk:17,maven:3.8,gradle:7.4.2;6.9.2",
		},
	}
	_ = client.Create(context.TODO(), &sysConfig)

	return client, reconciler
}

func TestStateNew(t *testing.T) {
	t.Run("Test reconcile new DependencyBuild", func(t *testing.T) {
		g := NewGomegaWithT(t)
		db := v1alpha1.DependencyBuild{}
		db.Namespace = metav1.NamespaceDefault
		db.Name = "test"
		db.Status.State = v1alpha1.DependencyBuildStateNew
		db.Spec.ScmInfo.SCMURL = "some-url"
		db.Spec.ScmInfo.Tag = "some-tag"
		db.Spec.ScmInfo.Path = "some-path"
		db.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: hashToString(db.Spec.ScmInfo.SCMURL + db.Spec.ScmInfo.Tag + db.Spec.ScmInfo.Path)}

		ctx := context.TODO()
		client, reconciler := setupClientAndReconciler(&db)

		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: db.Name}}))

		g.Expect(client.Get(ctx, types.NamespacedName{
			Namespace: metav1.NamespaceDefault,
			Name:      "test",
		}, &db))
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateAnalyzeBuild))

		runBuildDiscoveryPipeline(db, g, reconciler, client, ctx, true)

		g.Expect(client.Get(ctx, types.NamespacedName{
			Namespace: metav1.NamespaceDefault,
			Name:      "test",
		}, &db))
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateSubmitBuild))

		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: db.Name}}))

		g.Expect(client.Get(ctx, types.NamespacedName{
			Namespace: metav1.NamespaceDefault,
			Name:      "test",
		}, &db))
		g.Expect(db.Status.CurrentBuildRecipe).ShouldNot(BeNil())
		g.Expect(db.Status.CurrentBuildRecipe.Image).Should(Equal("quay.io/redhat-appstudio/hacbs-jdk11-builder:latest"))

	})
}

func runBuildDiscoveryPipeline(db v1alpha1.DependencyBuild, g *WithT, reconciler *ReconcileDependencyBuild, client runtimeclient.Client, ctx context.Context, success bool) {
	var pr *pipelinev1beta1.PipelineRun
	trList := &pipelinev1beta1.PipelineRunList{}
	g.Expect(client.List(ctx, trList))
	for _, i := range trList.Items {
		if i.Labels[PipelineType] == PipelineTypeBuildInfo {
			pr = &i
			break
		}
	}
	g.Expect(pr).ShouldNot(BeNil())
	pr.Namespace = metav1.NamespaceDefault
	if success {
		pr.Status.PipelineResults = []pipelinev1beta1.PipelineRunResult{{Name: BuildInfoPipelineBuildInfo, Value: `{"tools":{"jdk":{"min":"8","max":"17","preferred":"11"},"maven":{"min":"3.8","max":"3.8","preferred":"3.8"}},"invocations":[["testgoal"]],"enforceVersion":null,"ignoredArtifacts":[],"toolVersion":null,"javaVersion":null}`}}
	} else {
		pr.Status.PipelineResults = []pipelinev1beta1.PipelineRunResult{{Name: BuildInfoPipelineMessage, Value: "build info missing"}}
	}
	pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
	pr.Status.SetCondition(&apis.Condition{
		Type:               apis.ConditionSucceeded,
		Status:             "True",
		LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
	})
	g.Expect(client.Update(ctx, pr))
	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: pr.Name}}))
}

func TestStateDetect(t *testing.T) {

	setup := func(g *WithT) (v1alpha1.DependencyBuild, runtimeclient.Client, *ReconcileDependencyBuild, context.Context) {
		db := v1alpha1.DependencyBuild{}
		db.Namespace = metav1.NamespaceDefault
		db.Name = "test"
		db.Status.State = v1alpha1.DependencyBuildStateNew
		db.Spec.ScmInfo.SCMURL = "some-url"
		db.Spec.ScmInfo.Tag = "some-tag"
		db.Spec.ScmInfo.Path = "some-path"
		db.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: hashToString(db.Spec.ScmInfo.SCMURL + db.Spec.ScmInfo.Tag + db.Spec.ScmInfo.Path)}

		ctx := context.TODO()
		client, reconciler := setupClientAndReconciler(&db)

		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: db.Name}}))

		g.Expect(client.Get(ctx, types.NamespacedName{
			Namespace: metav1.NamespaceDefault,
			Name:      "test",
		}, &db)).Should(BeNil())
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateAnalyzeBuild))
		return db, client, reconciler, ctx
	}

	t.Run("Test reconcile new DependencyBuild", func(t *testing.T) {
		g := NewGomegaWithT(t)
		db, client, reconciler, ctx := setup(g)
		runBuildDiscoveryPipeline(db, g, reconciler, client, ctx, true)

		g.Expect(getBuild(client, g).Status.State).Should(Equal(v1alpha1.DependencyBuildStateSubmitBuild))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: db.Name}}))

		g.Expect(client.Get(ctx, types.NamespacedName{
			Namespace: metav1.NamespaceDefault,
			Name:      "test",
		}, &db))
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateBuilding))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: db.Name}}))

		trList := &pipelinev1beta1.PipelineRunList{}
		g.Expect(client.List(ctx, trList))

		g.Expect(len(trList.Items)).Should(Equal(2))
		for _, pr := range trList.Items {
			if pr.Labels[PipelineType] != PipelineTypeBuild {
				continue
			}
			g.Expect(pr.Labels[artifactbuild.DependencyBuildIdLabel]).Should(Equal(db.Labels[artifactbuild.DependencyBuildIdLabel]))
			for _, or := range pr.OwnerReferences {
				if or.Kind != db.Kind || or.Name != db.Name {
					g.Expect(or.Kind).Should(Equal(db.Kind))
					g.Expect(or.Name).Should(Equal(db.Name))
				}
			}
			g.Expect(len(pr.Spec.Params)).Should(Equal(10))
			for _, param := range pr.Spec.Params {
				switch param.Name {
				case PipelineScmTag:
					g.Expect(param.Value.StringVal).Should(Equal("some-tag"))
				case PipelinePath:
					g.Expect(param.Value.StringVal).Should(Equal("some-path"))
				case PipelineScmUrl:
					g.Expect(param.Value.StringVal).Should(Equal("some-url"))
				case PipelineImage:
					g.Expect(param.Value.StringVal).Should(Equal("quay.io/redhat-appstudio/hacbs-jdk11-builder:latest"))
				case PipelineGoals:
					g.Expect(param.Value.ArrayVal).Should(ContainElement("testgoal"))
				case PipelineEnforceVersion:
					g.Expect(param.Value.StringVal).Should(BeEmpty())
				case PipelineIgnoredArtifacts:
					g.Expect(param.Value.StringVal).Should(BeEmpty())
				case PipelineToolVersion:
					g.Expect(param.Value.StringVal).Should(Equal("3.8.1"))
				case PipelineJavaVersion:
					g.Expect(param.Value.StringVal).Should(BeEmpty())
				}
			}
		}
	})
	t.Run("Test reconcile build info discovery fails", func(t *testing.T) {
		g := NewGomegaWithT(t)
		db, client, reconciler, ctx := setup(g)
		runBuildDiscoveryPipeline(db, g, reconciler, client, ctx, false)

		g.Expect(getBuild(client, g).Status.State).Should(Equal(v1alpha1.DependencyBuildStateFailed))
	})
}

func getBuild(client runtimeclient.Client, g *WithT) *v1alpha1.DependencyBuild {
	ctx := context.TODO()
	build := v1alpha1.DependencyBuild{}
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}, &build)).Should(BeNil())
	return &build
}
func getBuildPipeline(client runtimeclient.Client, g *WithT) *pipelinev1beta1.PipelineRun {
	ctx := context.TODO()
	build := pipelinev1beta1.PipelineRun{}
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test-build-0"}, &build)).Should(BeNil())
	return &build
}
func getBuildInfoPipeline(client runtimeclient.Client, g *WithT) *pipelinev1beta1.PipelineRun {
	ctx := context.TODO()
	build := pipelinev1beta1.PipelineRun{}
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test-build-discovery"}, &build)).Should(BeNil())
	return &build
}
func TestStateBuilding(t *testing.T) {
	ctx := context.TODO()

	var client runtimeclient.Client
	var reconciler *ReconcileDependencyBuild
	buildName := types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}
	taskRunName := types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test-build-0"}
	setup := func(g *WithT) {
		client, reconciler = setupClientAndReconciler()

		ab := v1alpha1.ArtifactBuild{Spec: v1alpha1.ArtifactBuildSpec{GAV: TestArtifact}}
		ab.Name = TestArtifact
		ab.Namespace = metav1.NamespaceDefault
		g.Expect(client.Create(ctx, &ab)).Should(BeNil())

		db := v1alpha1.DependencyBuild{}
		db.Namespace = metav1.NamespaceDefault
		db.Name = "test"
		db.Status.State = v1alpha1.DependencyBuildStateBuilding
		db.Status.CurrentBuildRecipe = &v1alpha1.BuildRecipe{Image: "quay.io/redhat-appstudio/hacbs-jdk11-builder:latest"}
		db.Spec.ScmInfo.SCMURL = "some-url"
		db.Spec.ScmInfo.Tag = "some-tag"
		db.Spec.ScmInfo.Path = "some-path"
		db.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: hashToString(db.Spec.ScmInfo.SCMURL + db.Spec.ScmInfo.Tag + db.Spec.ScmInfo.Path)}
		g.Expect(controllerutil.SetOwnerReference(&ab, &db, reconciler.scheme)).Should(BeNil())
		g.Expect(client.Create(ctx, &db)).Should(BeNil())

		pr := pipelinev1beta1.PipelineRun{}
		pr.Namespace = metav1.NamespaceDefault
		pr.Name = "test-build-0"
		pr.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: hashToString(db.Spec.ScmInfo.SCMURL + db.Spec.ScmInfo.Tag + db.Spec.ScmInfo.Path), PipelineType: PipelineTypeBuild}
		g.Expect(controllerutil.SetOwnerReference(&db, &pr, reconciler.scheme)).Should(BeNil())
		g.Expect(client.Create(ctx, &pr)).Should(BeNil())

	}

	t.Run("Test reconcile building DependencyBuild with running pipeline, PipelineRun arrives first", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: taskRunName}))
		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateBuilding))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: buildName}))
		db = getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateBuilding))
		getBuildPipeline(client, g)
	})
	t.Run("Test reconcile building DependencyBuild with running pipeline, DependencyBuild arrives first", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: buildName}))
		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateBuilding))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: taskRunName}))
		db = getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateBuilding))
		getBuildPipeline(client, g)
	})
	t.Run("Test reconcile building DependencyBuild with succeeded pipeline", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		pr := getBuildPipeline(client, g)
		pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "True",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
		g.Expect(client.Update(ctx, pr)).Should(BeNil())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: taskRunName}))
		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateComplete))
	})
	t.Run("Test reconcile building DependencyBuild with failed pipeline", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		pr := getBuildPipeline(client, g)
		pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "False",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
		g.Expect(client.Update(ctx, pr)).Should(BeNil())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: taskRunName}))
		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateSubmitBuild))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: buildName}))
		db = getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateFailed))
	})
	t.Run("Test reconcile building DependencyBuild with contaminants", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		pr := getBuildPipeline(client, g)
		pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "True",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
		pr.Status.PipelineResults = []pipelinev1beta1.PipelineRunResult{{Name: "contaminants", Value: "com.acme:foo:1.0,com.acme:bar:1.0"}}
		g.Expect(client.Update(ctx, pr)).Should(BeNil())
		db := getBuild(client, g)
		db.Status.Contaminants = []v1alpha1.Contaminant{{GAV: "com.acme:foo:1.0", ContaminatedArtifacts: []string{TestArtifact}}}
		g.Expect(client.Update(ctx, db))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: taskRunName}))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: buildName}))
		db = getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateContaminated))
	})

}

func TestStateDependencyBuildStateAnalyzeBuild(t *testing.T) {
	ctx := context.TODO()

	var client runtimeclient.Client
	var reconciler *ReconcileDependencyBuild
	taskRunName := types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test-build-discovery"}
	setup := func(g *WithT) {
		client, reconciler = setupClientAndReconciler()
		db := v1alpha1.DependencyBuild{}
		db.Namespace = metav1.NamespaceDefault
		db.Name = "test"
		db.Status.State = v1alpha1.DependencyBuildStateAnalyzeBuild
		db.Spec.ScmInfo.SCMURL = "some-url"
		db.Spec.ScmInfo.Tag = "some-tag"
		db.Spec.ScmInfo.Path = "some-path"
		db.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: hashToString(db.Spec.ScmInfo.SCMURL + db.Spec.ScmInfo.Tag + db.Spec.ScmInfo.Path)}
		g.Expect(client.Create(ctx, &db)).Should(BeNil())

		pr := pipelinev1beta1.PipelineRun{}
		pr.Namespace = metav1.NamespaceDefault
		pr.Name = "test-build-discovery"
		pr.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: hashToString(db.Spec.ScmInfo.SCMURL + db.Spec.ScmInfo.Tag + db.Spec.ScmInfo.Path), PipelineType: PipelineTypeBuildInfo}
		g.Expect(controllerutil.SetOwnerReference(&db, &pr, reconciler.scheme))
		g.Expect(client.Create(ctx, &pr)).Should(BeNil())

	}
	t.Run("Test build info discovery for gradle build", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		buildInfoJson, err := json.Marshal(marshalledBuildInfo{ToolVersion: "4.9", JavaVersion: "11", Tools: map[string]toolInfo{"gradle": {Min: "4.9", Max: "4.9", Preferred: "4.9"}, "jdk": {Min: "8", Max: "17", Preferred: "11"}}, Invocations: [][]string{{""}}})
		g.Expect(err).Should(BeNil())
		pr := getBuildInfoPipeline(client, g)
		pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
		pr.Status.PipelineResults = []pipelinev1beta1.PipelineRunResult{{Name: BuildInfoPipelineBuildInfo, Value: string(buildInfoJson)}}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "True",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
		g.Expect(client.Status().Update(ctx, pr)).Should(BeNil())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: taskRunName}))

		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateSubmitBuild))
		g.Expect(len(db.Status.PotentialBuildRecipes)).Should(Equal(2))
		g.Expect(db.Status.PotentialBuildRecipes[0].Image).Should(Equal("quay.io/redhat-appstudio/hacbs-jdk11-builder:latest"))
		g.Expect(db.Status.PotentialBuildRecipes[1].Image).Should(Equal("quay.io/redhat-appstudio/hacbs-jdk8-builder:latest"))
	})

	t.Run("Test build info discovery for gradle build 2", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		buildInfoJson, err := json.Marshal(marshalledBuildInfo{ToolVersion: "5.8.7", Tools: map[string]toolInfo{"gradle": {}}, Invocations: [][]string{{""}}})
		g.Expect(err).Should(BeNil())
		pr := getBuildInfoPipeline(client, g)
		pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
		pr.Status.PipelineResults = []pipelinev1beta1.PipelineRunResult{{Name: BuildInfoPipelineBuildInfo, Value: string(buildInfoJson)}}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "True",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
		g.Expect(client.Status().Update(ctx, pr)).Should(BeNil())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: taskRunName}))

		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateSubmitBuild))
		g.Expect(len(db.Status.PotentialBuildRecipes)).Should(Equal(2))
		g.Expect(db.Status.PotentialBuildRecipes[0].Image).Should(Equal("quay.io/redhat-appstudio/hacbs-jdk11-builder:latest"))
		g.Expect(db.Status.PotentialBuildRecipes[1].Image).Should(Equal("quay.io/redhat-appstudio/hacbs-jdk8-builder:latest"))
	})
}
