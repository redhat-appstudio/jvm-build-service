package artifactbuildrequest

import (
	"context"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"knative.dev/pkg/apis/duck/v1beta1"
	runtimeclient "sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
	"strings"
	"testing"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"

	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
)

func setupClientAndReconciler(objs ...runtimeclient.Object) (runtimeclient.Client, *ReconcileArtifactBuildRequest) {
	scheme := runtime.NewScheme()
	v1alpha1.AddToScheme(scheme)
	pipelinev1beta1.AddToScheme(scheme)
	client := fake.NewClientBuilder().WithScheme(scheme).WithObjects(objs...).Build()
	reconciler := &ReconcileArtifactBuildRequest{client: client, scheme: scheme}
	return client, reconciler
}

func TestReconcileNew(t *testing.T) {
	abr := v1alpha1.ArtifactBuildRequest{}
	abr.Namespace = metav1.NamespaceDefault
	abr.Name = "test"
	abr.Status.State = v1alpha1.ArtifactBuildRequestStateNew

	ctx := context.TODO()
	client, reconciler := setupClientAndReconciler(&abr)

	_, err := reconciler.handleStateNew(ctx, &abr)
	if err != nil {
		t.Fatalf("%s", err.Error())
	}

	err = client.Get(ctx, types.NamespacedName{
		Namespace: metav1.NamespaceDefault,
		Name:      "test",
	}, &abr)
	if err != nil {
		t.Fatalf("%s", err.Error())
	}
	if abr.Status.State != v1alpha1.ArtifactBuildRequestStateDiscovering {
		t.Fatalf("abr at incorrect state: %s", abr.Status.State)
	}

	trList := &pipelinev1beta1.TaskRunList{}
	err = client.List(ctx, trList)
	if err != nil {
		t.Fatalf("%s", err.Error())
	}
	for _, tr := range trList.Items {
		if !strings.HasPrefix(tr.Name, abr.Name) {
			t.Fatalf("tr/abr name mismatch: %s and %s", tr.Name, abr.Name)
		}
		for _, or := range tr.OwnerReferences {
			if or.Kind != abr.Kind || or.Name != abr.Name {
				t.Fatalf("tr/abr owner ref mismatch: %s %s %s %s", or.Kind, abr.Kind, or.Name, abr.Name)
			}
		}
	}
	if len(trList.Items) == 0 {
		t.Fatalf("no tr found")
	}
}

func getABR(t *testing.T, client runtimeclient.Client, abr *v1alpha1.ArtifactBuildRequest) *v1alpha1.ArtifactBuildRequest {
	ctx := context.TODO()
	err := client.Get(ctx, types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: "test"}, abr)
	if err != nil {
		t.Errorf("%s", err.Error())
	}
	return abr
}

func TestReconcileDiscovered(t *testing.T) {
	fullValidation := func(t *testing.T, client runtimeclient.Client, abr *v1alpha1.ArtifactBuildRequest) {
		abr = getABR(t, client, abr)
		if abr.Status.State != v1alpha1.ArtifactBuildRequestStateBuilding {
			t.Errorf("incorrect no dependency builds yet state: %s", abr.Status.State)
		}

		val, ok := abr.Labels[DependencyBuildIdLabel]
		if !ok || len(val) == 0 {
			t.Errorf("missing label: %s %v", val, ok)
		}

		dbList := v1alpha1.DependencyBuildList{}
		err := client.List(context.TODO(), &dbList)
		if err != nil {
			t.Errorf("db list err: %s", err.Error())
		}
		for _, db := range dbList.Items {
			if !strings.HasPrefix(db.Name, abr.Name) {
				t.Errorf("db / abr name mismatch: %s and %s", db.Name, abr.Name)
			}
			for _, or := range db.OwnerReferences {
				if or.Kind != abr.Kind || or.Name != abr.Name {
					t.Errorf("db / abr owner ref mismatch: %s %s %s %s", or.Kind, abr.Kind, or.Name, abr.Name)
				}
			}
			if db.Spec.Tag != "foo" ||
				db.Spec.SCMURL != "goo" ||
				db.Spec.SCMType != "hoo" ||
				db.Spec.Path != "ioo" {
				t.Errorf("db spec / abr status mismatch: %#v vs. %#v", db.Spec, abr.Status)
			}
			if db.Spec.SCMURL != abr.Status.SCMURL ||
				db.Spec.SCMType != abr.Status.SCMType ||
				db.Spec.Tag != abr.Status.Tag ||
				db.Spec.Path != abr.Status.Path {
				t.Errorf("db spec / abr status mismatch: %#v vs. %#v", db.Spec, abr.Status)
			}
		}
		if len(dbList.Items) == 0 {
			t.Errorf("no dbs present")
		}
	}

	now := metav1.Now()
	tests := []struct {
		name       string
		tr         *pipelinev1beta1.TaskRun
		abr        *v1alpha1.ArtifactBuildRequest
		db         *v1alpha1.DependencyBuild
		validation func(t *testing.T, client runtimeclient.Client, abr *v1alpha1.ArtifactBuildRequest)
	}{
		{
			name: "missing tag",
			abr: &v1alpha1.ArtifactBuildRequest{
				TypeMeta: metav1.TypeMeta{},
				ObjectMeta: metav1.ObjectMeta{
					Name:      "test",
					Namespace: metav1.NamespaceDefault,
					UID:       "someuid",
				},
				Spec: v1alpha1.ArtifactBuildRequestSpec{},
				Status: v1alpha1.ArtifactBuildRequestStatus{
					State: v1alpha1.DependencyBuildStateBuilding,
				},
			},
			tr: &pipelinev1beta1.TaskRun{
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
			},
			validation: func(t *testing.T, client runtimeclient.Client, abr *v1alpha1.ArtifactBuildRequest) {
				abr = getABR(t, client, abr)
				if abr.Status.State != v1alpha1.ArtifactBuildRequestStateMissing {
					t.Errorf("incorrect missing tag state: %s", abr.Status.State)
				}
			},
		},
		{
			name: "no dependency builds yet",
			abr: &v1alpha1.ArtifactBuildRequest{
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
			},
			tr: &pipelinev1beta1.TaskRun{
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
			},
			validation: fullValidation,
		},
		{
			name: "dependency builds already exist",
			abr: &v1alpha1.ArtifactBuildRequest{
				TypeMeta: metav1.TypeMeta{},
				ObjectMeta: metav1.ObjectMeta{
					Name:      "test",
					Namespace: metav1.NamespaceDefault,
					UID:       "someuid",
				},
				Spec: v1alpha1.ArtifactBuildRequestSpec{},
				Status: v1alpha1.ArtifactBuildRequestStatus{
					State:   v1alpha1.DependencyBuildStateBuilding,
					Tag:     "foo",
					SCMURL:  "goo",
					SCMType: "hoo",
					Path:    "ioo",
				},
			},
			db: &v1alpha1.DependencyBuild{
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
			},
			tr: &pipelinev1beta1.TaskRun{
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
			},
			validation: fullValidation,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			objs := []runtimeclient.Object{}
			if tt.abr != nil {
				objs = append(objs, tt.abr)
			}
			if tt.db != nil {
				objs = append(objs, tt.db)
			}
			if tt.tr != nil {
				objs = append(objs, tt.tr)
			}
			client, reconciler := setupClientAndReconciler(objs...)
			ctx := context.TODO()
			_, err := reconciler.handleStateDiscovering(ctx, tt.abr)
			if err != nil {
				t.Errorf("handleStateDiscovering error: %s", err.Error())
			}
			tt.validation(t, client, tt.abr)
		})
	}
}
