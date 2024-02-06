package dependencybuild

import (
	"context"
	"encoding/json"
	"fmt"
	"testing"
	"time"

	. "github.com/onsi/gomega"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/systemconfig"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"

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
const MaxAdditionalMemory = 700

func setupClientAndReconciler(objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcileDependencyBuild) {
	scheme := runtime.NewScheme()
	_ = v1alpha1.AddToScheme(scheme)
	_ = tektonpipeline.AddToScheme(scheme)
	_ = v1.AddToScheme(scheme)
	_ = appsv1.AddToScheme(scheme)
	client := fake.NewClientBuilder().WithStatusSubresource(&v1alpha1.JBSConfig{}, &v1alpha1.ArtifactBuild{}, &v1alpha1.DependencyBuild{}, &tektonpipeline.PipelineRun{}).WithScheme(scheme).WithObjects(objs...).Build()
	reconciler := &ReconcileDependencyBuild{
		client:        client,
		scheme:        scheme,
		eventRecorder: &record.FakeRecorder{},
	}

	sysConfig := v1alpha1.SystemConfig{
		ObjectMeta: metav1.ObjectMeta{Name: systemconfig.SystemConfigKey},
		Spec: v1alpha1.SystemConfigSpec{
			MaxAdditionalMemory: MaxAdditionalMemory,
			Builders: map[string]v1alpha1.BuilderImageInfo{
				"jdk8": {
					Image: "quay.io/redhat-appstudio/hacbs-jdk8-builder:latest",
					Tag:   "jdk:8,maven:3.8,gradle:8.0.2;7.4.2;6.9.2;5.6.4;4.10.3",
				},
				"jdk11": {
					Image: "quay.io/redhat-appstudio/hacbs-jdk11-builder:latest",
					Tag:   "jdk:11,maven:3.8,gradle:8.0.2;7.4.2;6.9.2;5.6.4;4.10.3",
				},
				"jdk17": {
					Image: "quay.io/redhat-appstudio/hacbs-jdk17-builder:latest",
					Tag:   "jdk:17,maven:3.8,gradle:8.0.2;7.4.2;6.9.2",
				},
				"jdk7": {
					Image: "quay.io/redhat-appstudio/hacbs-jdk7-builder:latest",
					Tag:   "jdk:7,maven:3.8",
				},
			},
		},
	}
	_ = client.Create(context.TODO(), &sysConfig)
	usrConfig := v1alpha1.JBSConfig{
		ObjectMeta: metav1.ObjectMeta{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName},
		Spec: v1alpha1.JBSConfigSpec{
			EnableRebuilds: true,
		},
	}
	_ = client.Create(context.TODO(), &usrConfig)
	util.ImageTag = "foo"
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
		g.Expect(len(db.Status.BuildAttempts)).Should(Equal(1))
		g.Expect(db.Status.BuildAttempts[0].Recipe.Image).Should(HavePrefix("quay.io/redhat-appstudio/hacbs-jdk"))
		g.Expect(db.Status.BuildAttempts[0].Recipe.Repositories).Should(Equal([]string{"jboss", "gradle"}))

	})
}

func runBuildDiscoveryPipeline(db v1alpha1.DependencyBuild, g *WithT, reconciler *ReconcileDependencyBuild, client runtimeclient.Client, ctx context.Context, success bool) {
	runBuildDiscoveryPipelineForResult(db, g, reconciler, client, ctx, success, `{"invocations":[{"commands":["maven","testgoal"],"toolVersion":{"maven":"3.8", "jdk": "11"},"tool": "maven"}],"enforceVersion":null,"repositories":["jboss","gradle"]}`)
}
func runBuildDiscoveryPipelineForResult(db v1alpha1.DependencyBuild, g *WithT, reconciler *ReconcileDependencyBuild, client runtimeclient.Client, ctx context.Context, success bool, result string) {
	var pr *tektonpipeline.PipelineRun
	trList := &tektonpipeline.PipelineRunList{}
	g.Expect(client.List(ctx, trList))
	for _, i := range trList.Items {
		if i.Labels[PipelineTypeLabel] == PipelineTypeBuildInfo {
			pr = &i
			break
		}
	}
	g.Expect(pr).ShouldNot(BeNil())
	g.Expect(len(pr.Finalizers)).Should(Equal(1))
	pr.Namespace = metav1.NamespaceDefault
	if success {
		pr.Status.Results = []tektonpipeline.PipelineRunResult{{Name: BuildInfoPipelineResultBuildInfo, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: result}}}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "True",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
	} else {
		pr.Status.Results = []tektonpipeline.PipelineRunResult{
			{Name: BuildInfoPipelineResultBuildInfo, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: "build info missing"}}}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "False",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
	}
	pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
	g.Expect(client.Status().Update(ctx, pr)).Should(BeNil())
	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: pr.Name}}))
	g.Expect(client.Get(ctx, types.NamespacedName{Name: pr.Name, Namespace: pr.Namespace}, pr)).Should(Succeed())
	g.Expect(len(pr.Finalizers)).Should(Equal(1))

	g.Expect(client.Delete(ctx, pr)).Should(BeNil())
	g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: pr.Name}}))
	g.Expect(client.Get(ctx, types.NamespacedName{Name: pr.Name, Namespace: pr.Namespace}, pr)).ShouldNot(Succeed())
}

func TestStateDetect(t *testing.T) {

	setup := func(g *WithT) (v1alpha1.DependencyBuild, runtimeclient.Client, *ReconcileDependencyBuild, context.Context) {
		db := v1alpha1.DependencyBuild{}
		db.Namespace = metav1.NamespaceDefault
		db.Name = "test"
		db.Status.State = v1alpha1.DependencyBuildStateNew
		db.Spec.ScmInfo.SCMURL = "some-url"
		db.Spec.ScmInfo.Tag = "some-tag"
		db.Spec.ScmInfo.CommitHash = "some-hash"
		db.Spec.ScmInfo.Path = "some-path"

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

		trList := &tektonpipeline.PipelineRunList{}
		g.Expect(client.List(ctx, trList))

		g.Expect(len(trList.Items)).Should(Equal(1))
		for _, pr := range trList.Items {
			if pr.Labels[PipelineTypeLabel] != PipelineTypeBuild {
				continue
			}
			g.Expect(pr.Labels[artifactbuild.DependencyBuildIdLabel]).Should(Equal(db.Name))
			for _, or := range pr.OwnerReferences {
				if or.Kind != db.Kind || or.Name != db.Name {
					g.Expect(or.Kind).Should(Equal(db.Kind))
					g.Expect(or.Name).Should(Equal(db.Name))
				}
			}
			g.Expect(len(pr.Spec.Params)).Should(Equal(12))
			for _, param := range pr.Spec.Params {
				switch param.Name {
				case PipelineParamScmHash:
				case PipelineParamChainsGitCommit:

					g.Expect(param.Value.StringVal).Should(Equal("some-hash"))
				case PipelineParamScmTag:
					g.Expect(param.Value.StringVal).Should(Equal("some-tag"))
				case PipelineParamPath:
					g.Expect(param.Value.StringVal).Should(Equal("some-path"))
				case PipelineParamScmUrl:
				case PipelineParamChainsGitUrl:
					g.Expect(param.Value.StringVal).Should(Equal("some-url"))
				case PipelineParamImage:
					g.Expect(param.Value.StringVal).Should(HavePrefix("quay.io/redhat-appstudio/hacbs-jdk"))
				case PipelineParamGoals:
					g.Expect(param.Value.ArrayVal).Should(ContainElement("testgoal"))
				case PipelineParamEnforceVersion:
					g.Expect(param.Value.StringVal).Should(BeEmpty())
				case PipelineParamToolVersion:
					g.Expect(param.Value.StringVal).Should(Equal("3.8"))
				}
			}
		}
	})
	t.Run("Test reconcile build info discovery fails", func(t *testing.T) {
		g := NewGomegaWithT(t)
		db, client, reconciler, ctx := setup(g)
		runBuildDiscoveryPipeline(db, g, reconciler, client, ctx, false)
		g.Expect(getBuild(client, g).Status.State).Should(Equal(v1alpha1.DependencyBuildStateFailed))
		g.Expect(getBuild(client, g).Status.Message).Should(Equal("build info missing"))
	})
	t.Run("Test reconcile build info discovery invalid result", func(t *testing.T) {
		g := NewGomegaWithT(t)
		db, client, reconciler, ctx := setup(g)
		runBuildDiscoveryPipelineForResult(db, g, reconciler, client, ctx, true, "invalid json")
		g.Expect(getBuild(client, g).Status.State).Should(Equal(v1alpha1.DependencyBuildStateFailed))
		g.Expect(getBuild(client, g).Status.Message).Should(ContainSubstring("invalid json"))
	})
}

func getBuild(client runtimeclient.Client, g *WithT) *v1alpha1.DependencyBuild {
	ctx := context.TODO()
	build := v1alpha1.DependencyBuild{}
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}, &build)).Should(BeNil())
	return &build
}
func getBuildPipeline(client runtimeclient.Client, g *WithT) *tektonpipeline.PipelineRun {
	return getBuildPipelineNo(client, g, 0)
}
func getBuildPipelineNo(client runtimeclient.Client, g *WithT, no int) *tektonpipeline.PipelineRun {
	ctx := context.TODO()
	build := tektonpipeline.PipelineRun{}
	g.Expect(client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: fmt.Sprintf("test-build-%d", no)}, &build)).Should(BeNil())
	return &build
}
func getBuildInfoPipeline(client runtimeclient.Client, g *WithT) *tektonpipeline.PipelineRun {
	ctx := context.TODO()
	build := tektonpipeline.PipelineRun{}
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
		db.Status.BuildAttempts = []*v1alpha1.BuildAttempt{
			{
				Recipe: &v1alpha1.BuildRecipe{
					Image: "quay.io/redhat-appstudio/hacbs-jdk11-builder:latest",
				},
				Build: &v1alpha1.BuildPipelineRun{
					PipelineName: "test-build-0",
				},
			},
		}
		db.Status.PotentialBuildRecipes = []*v1alpha1.BuildRecipe{db.Status.BuildAttempts[0].Recipe}
		db.Status.PotentialBuildRecipesIndex = 1
		db.Spec.ScmInfo.SCMURL = "some-url"
		db.Spec.ScmInfo.Tag = "some-tag"
		db.Spec.ScmInfo.Path = "some-path"
		g.Expect(controllerutil.SetOwnerReference(&ab, &db, reconciler.scheme)).Should(BeNil())
		g.Expect(client.Create(ctx, &db)).Should(BeNil())

		pr := tektonpipeline.PipelineRun{}
		pr.Namespace = metav1.NamespaceDefault
		pr.Finalizers = []string{PipelineRunFinalizer}
		pr.Name = "test-build-0"
		pr.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: db.Name, PipelineTypeLabel: PipelineTypeBuild}
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
		pr.Status.Results = []tektonpipeline.PipelineRunResult{{Name: artifactbuild.PipelineResultDeployedResources, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: TestArtifact}}}
		g.Expect(client.Status().Update(ctx, pr)).Should(BeNil())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: taskRunName}))
		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateComplete))
		g.Expect(db.Status.DeployedArtifacts).Should(ContainElement(TestArtifact))
		ra := v1alpha1.RebuiltArtifact{}
		g.Expect(client.Get(ctx, types.NamespacedName{Name: artifactbuild.CreateABRName(TestArtifact), Namespace: metav1.NamespaceDefault}, &ra)).Should(Succeed())
		g.Expect(ra.Spec.GAV).Should(Equal(TestArtifact))
		g.Expect(ra.Spec.Image).ShouldNot(BeNil())
		pr = getBuildPipeline(client, g)
		g.Expect(len(pr.Finalizers)).Should(Equal(1))

		g.Expect(client.Delete(ctx, pr)).Should(BeNil())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: pr.Name}}))
		g.Expect(client.Get(ctx, types.NamespacedName{Name: pr.Name, Namespace: pr.Namespace}, pr)).ShouldNot(Succeed())
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
		g.Expect(client.Status().Update(ctx, pr)).Should(BeNil())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: taskRunName}))
		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateSubmitBuild))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: buildName}))
		db = getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateFailed))
	})
	t.Run("Test reconcile building DependencyBuild with OOMKilled Pipeline", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		pr := getBuildPipeline(client, g)
		pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "False",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
		newTr := tektonpipeline.TaskRun{
			ObjectMeta: metav1.ObjectMeta{Name: "task", Namespace: pr.Namespace},
			Status: tektonpipeline.TaskRunStatus{
				TaskRunStatusFields: tektonpipeline.TaskRunStatusFields{
					Steps: []tektonpipeline.StepState{{ContainerState: v1.ContainerState{Terminated: &v1.ContainerStateTerminated{Reason: "OOMKilled"}}}}},
			},
		}
		g.Expect(client.Create(ctx, &newTr)).Should(BeNil())

		pr.Status.ChildReferences = []tektonpipeline.ChildStatusReference{{Name: "task"}}

		g.Expect(client.Status().Update(ctx, pr)).Should(BeNil())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: taskRunName}))
		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateSubmitBuild))
		g.Expect(db.Status.BuildAttempts[len(db.Status.BuildAttempts)-1].Recipe.AdditionalMemory).Should(Equal(MemoryIncrement))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: db.Name}}))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: db.Name}}))

		pr = getBuildPipelineNo(client, g, 1)
		pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "False",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
		newTr = tektonpipeline.TaskRun{
			ObjectMeta: metav1.ObjectMeta{Name: "task2", Namespace: pr.Namespace},
			Status: tektonpipeline.TaskRunStatus{
				TaskRunStatusFields: tektonpipeline.TaskRunStatusFields{Steps: []tektonpipeline.StepState{{ContainerState: v1.ContainerState{Terminated: &v1.ContainerStateTerminated{Reason: "OOMKilled"}}}}},
			},
		}
		g.Expect(client.Create(ctx, &newTr)).Should(BeNil())

		pr.Status.ChildReferences = []tektonpipeline.ChildStatusReference{{Name: "task2"}}

		g.Expect(client.Status().Update(ctx, pr)).Should(BeNil())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: pr.Namespace, Name: pr.Name}}))
		db = getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateSubmitBuild))
		g.Expect(db.Status.BuildAttempts[len(db.Status.BuildAttempts)-1].Recipe.AdditionalMemory).Should(Equal(2048))

		//now verify that the system wide limit kicks in
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: db.Name}}))
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: types.NamespacedName{Namespace: db.Namespace, Name: db.Name}}))

		pr = getBuildPipelineNo(client, g, 2)

		found := false
		for _, task := range pr.Spec.PipelineSpec.Tasks {
			for _, step := range task.TaskSpec.Steps {
				if step.Name == "build" {
					//default is 1024 + the 700 limit
					g.Expect(step.ComputeResources.Requests.Memory().String()).Should(Equal("1724Mi"))
					found = true
				}
			}
		}

		g.Expect(found).Should(BeTrue())
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
		ab := v1alpha1.ArtifactBuild{}
		//we need an abr for this to be considered contaminated
		ab.Name = artifactbuild.CreateABRName(TestArtifact)
		ab.Namespace = pr.Namespace
		ab.Spec.GAV = TestArtifact
		g.Expect(client.Create(ctx, &ab)).Should(BeNil())
		pr.Status.Results = []tektonpipeline.PipelineRunResult{{Name: "contaminants", Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: "[{\"gav\": \"com.acme:foo:1.0\", \"contaminatedArtifacts\": [\"" + TestArtifact + "\"]}]"}}}
		g.Expect(client.Status().Update(ctx, pr)).Should(BeNil())
		db := getBuild(client, g)
		g.Expect(controllerutil.SetOwnerReference(&ab, db, reconciler.scheme)).Should(BeNil())
		db.Status.Contaminants = []*v1alpha1.Contaminant{{GAV: "com.acme:foo:1.0", ContaminatedArtifacts: []string{TestArtifact}}}
		g.Expect(client.Status().Update(ctx, db))
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
		g.Expect(client.Create(ctx, &db)).Should(BeNil())

		pr := tektonpipeline.PipelineRun{}
		pr.Namespace = metav1.NamespaceDefault
		pr.Name = "test-build-discovery"
		pr.Labels = map[string]string{artifactbuild.DependencyBuildIdLabel: db.Name, PipelineTypeLabel: PipelineTypeBuildInfo}
		g.Expect(controllerutil.SetOwnerReference(&db, &pr, reconciler.scheme))
		g.Expect(client.Create(ctx, &pr)).Should(BeNil())

	}
	t.Run("Test build info discovery for gradle build", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		buildInfoJson, err := json.Marshal(marshalledBuildInfo{Invocations: []invocation{{Tool: "gradle", Commands: []string{"gradle"}, ToolVersion: map[string]string{"gradle": "4.10.3", "jdk": "11"}}}})
		g.Expect(err).Should(BeNil())
		pr := getBuildInfoPipeline(client, g)
		pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
		pr.Status.Results = []tektonpipeline.PipelineRunResult{{Name: BuildInfoPipelineResultBuildInfo, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: string(buildInfoJson)}}}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "True",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
		g.Expect(client.Status().Update(ctx, pr)).Should(BeNil())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: taskRunName}))

		db := getBuild(client, g)
		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateSubmitBuild))
		g.Expect(len(db.Status.PotentialBuildRecipes)).Should(Equal(1))
		find11 := false
		for _, recipe := range db.Status.PotentialBuildRecipes {
			switch recipe.Image {
			case "quay.io/redhat-appstudio/hacbs-jdk11-builder:latest":
				find11 = true
			}
		}
		g.Expect(find11).To(BeTrue())
	})

	t.Run("Test build info discovery for gradle build 2", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		buildInfoJson, err := json.Marshal(marshalledBuildInfo{Invocations: []invocation{{Tool: "gradle", Commands: []string{"gradle"}, ToolVersion: map[string]string{"gradle": "5.6.4", "jdk": "11"}}, {Tool: "gradle", Commands: []string{"gradle"}, ToolVersion: map[string]string{"gradle": "5.6.4", "jdk": "8"}}}})
		g.Expect(err).Should(BeNil())
		pr := getBuildInfoPipeline(client, g)
		pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
		pr.Status.Results = []tektonpipeline.PipelineRunResult{{Name: BuildInfoPipelineResultBuildInfo, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: string(buildInfoJson)}}}
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
		find11 := false
		find8 := false
		for _, recipe := range db.Status.PotentialBuildRecipes {
			switch recipe.Image {
			case "quay.io/redhat-appstudio/hacbs-jdk11-builder:latest":
				find11 = true
			case "quay.io/redhat-appstudio/hacbs-jdk8-builder:latest":
				find8 = true
			}
		}
		g.Expect(find11).To(BeTrue())
		g.Expect(find8).To(BeTrue())
	})

	t.Run("Test build info discovery with shared registry", func(t *testing.T) {
		g := NewGomegaWithT(t)
		setup(g)
		buildInfoJson, err := json.Marshal(marshalledBuildInfo{Invocations: []invocation{{Tool: "gradle", Commands: []string{"gradle"}, ToolVersion: map[string]string{"gradle": "5.6.4", "jdk": "11"}}}, Image: "quay.io/dummy-namespace/jvm-build-service-artifacts:4f8a8179ceadcde76e4cbc037dd7c9fd",
			Digest: "sha256:0a959a76264f7a34f1d6793ce3fb0d37b8a12768483f075cd644265b258477e3", Gavs: []string{"commons-lang:commons-lang:2.6"}})
		g.Expect(err).Should(BeNil())
		pr := getBuildInfoPipeline(client, g)
		pr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
		pr.Status.Results = []tektonpipeline.PipelineRunResult{{Name: BuildInfoPipelineResultBuildInfo, Value: tektonpipeline.ResultValue{Type: tektonpipeline.ParamTypeString, StringVal: string(buildInfoJson)}}}
		pr.Status.SetCondition(&apis.Condition{
			Type:               apis.ConditionSucceeded,
			Status:             "True",
			LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
		})
		g.Expect(client.Status().Update(ctx, pr)).Should(BeNil())
		g.Expect(reconciler.Reconcile(ctx, reconcile.Request{NamespacedName: taskRunName}))

		db := getBuild(client, g)
		ra := v1alpha1.RebuiltArtifact{}
		g.Expect(client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "commons.lang.2.6-82c211d5"}, &ra)).Should(BeNil())

		g.Expect(db.Status.State).Should(Equal(v1alpha1.DependencyBuildStateComplete))
		g.Expect(len(db.Status.PotentialBuildRecipes)).Should(Equal(1))
		g.Expect(ra.Spec.GAV).Should(Equal("commons-lang:commons-lang:2.6"))
		g.Expect(ra.Spec.Digest).Should(Equal("sha256:0a959a76264f7a34f1d6793ce3fb0d37b8a12768483f075cd644265b258477e3"))
		g.Expect(ra.Spec.Image).Should(Equal("quay.io/dummy-namespace/jvm-build-service-artifacts:4f8a8179ceadcde76e4cbc037dd7c9fd"))
	})
}
