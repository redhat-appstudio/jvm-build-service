/*
Copyright 2021-2022 Red Hat, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package e2e

import (
	"errors"
	"fmt"
	"time"

	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"

	tektonapi "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	"knative.dev/pkg/apis"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"
	//+kubebuilder:scaffold:imports
)

const (
	timeout  = time.Second * 15
	interval = time.Millisecond * 250
)

const (
	TestNamespace = "default"
	ABRGav        = "com.acme:example:1.0"
	ABRName       = "com.acme.example.1.0"
	DBName        = "acmedep1"
)

func createDB(componentLookupKey types.NamespacedName) {
	db := &v1alpha1.DependencyBuild{
		TypeMeta: metav1.TypeMeta{
			APIVersion: "jvmbuildservice.io/v1alpha1",
			Kind:       "DependencyBuild",
		},
		ObjectMeta: metav1.ObjectMeta{
			Name:      componentLookupKey.Name,
			Namespace: componentLookupKey.Namespace,
			Labels:    map[string]string{artifactbuild.DependencyBuildIdLabel: componentLookupKey.Name},
		},
		Spec: v1alpha1.DependencyBuildSpec{ScmInfo: v1alpha1.SCMInfo{
			SCMURL:  "url1",
			SCMType: "git",
			Tag:     "tag1",
			Path:    "/path1",
		}},
	}
	Expect(k8sClient.Create(ctx, db)).Should(Succeed())
}

func createAbr(componentLookupKey types.NamespacedName) {
	abr := &v1alpha1.ArtifactBuild{
		TypeMeta: metav1.TypeMeta{
			APIVersion: "jvmbuildservice.io/v1alpha1",
			Kind:       "ArtifactBuild",
		},
		ObjectMeta: metav1.ObjectMeta{
			Name:      componentLookupKey.Name,
			Namespace: componentLookupKey.Namespace,
		},
		Spec: v1alpha1.ArtifactBuildSpec{GAV: ABRGav},
	}
	Expect(k8sClient.Create(ctx, abr)).Should(Succeed())

}

func getTrAbr() *tektonapi.TaskRun {
	hash := artifactbuild.ABRLabelForGAV(ABRGav)
	listOpts := &client.ListOptions{
		Namespace:     TestNamespace,
		LabelSelector: labels.SelectorFromSet(map[string]string{artifactbuild.ArtifactBuildIdLabel: hash}),
	}
	trl := tektonapi.TaskRunList{}
	var tr *tektonapi.TaskRun
	Eventually(func() bool {
		Expect(k8sClient.List(ctx, &trl, listOpts)).ToNot(HaveOccurred())
		//there should only be one, be guard against multiple
		for _, current := range trl.Items {
			if tr == nil || tr.CreationTimestamp.Before(&current.CreationTimestamp) {
				tr = &current
			}
		}
		return tr != nil
	}, timeout, interval).Should(BeTrue())

	return tr
}

// deleteAbr deletes the specified component resource and verifies it was properly deleted
func deleteAbr(componentLookupKey types.NamespacedName) {
	// Delete
	Eventually(func() error {
		f := &v1alpha1.ArtifactBuild{}
		_ = k8sClient.Get(ctx, componentLookupKey, f)
		return k8sClient.Delete(ctx, f)
	}, timeout, interval).Should(Succeed())

	// Wait for delete to finish
	Eventually(func() error {
		f := &v1alpha1.ArtifactBuild{}
		return k8sClient.Get(ctx, componentLookupKey, f)
	}, timeout, interval).ShouldNot(Succeed())
}

func deleteDb(componentLookupKey types.NamespacedName) {
	// Delete
	Eventually(func() error {
		f := &v1alpha1.DependencyBuild{}
		_ = k8sClient.Get(ctx, componentLookupKey, f)
		return k8sClient.Delete(ctx, f)
	}, timeout, interval).Should(Succeed())

	// Wait for delete to finish
	Eventually(func() error {
		f := &v1alpha1.DependencyBuild{}
		return k8sClient.Get(ctx, componentLookupKey, f)
	}, timeout, interval).ShouldNot(Succeed())
}

func listTaskRuns() *tektonapi.TaskRunList {
	taskRuns := &tektonapi.TaskRunList{}
	labelSelectors := client.ListOptions{Raw: &metav1.ListOptions{}}
	err := k8sClient.List(ctx, taskRuns, &labelSelectors)
	Expect(err).ToNot(HaveOccurred())
	return taskRuns
}

func deleteTaskRuns() {
	for _, pipelineRun := range listTaskRuns().Items {
		Expect(k8sClient.Delete(ctx, &pipelineRun)).Should(Succeed())
	}
}

var _ = Describe("Test discovery TaskRun complete updates ABR state", func() {

	var (
		// All related to the component resources have the same key (but different type)
		abrName = types.NamespacedName{
			Name:      ABRName,
			Namespace: TestNamespace,
		}
		dbName = types.NamespacedName{
			Name:      DBName,
			Namespace: TestNamespace,
		}
	)

	Context("Test Successful discovery pipeline run", func() {

		_ = BeforeEach(func() {
			createAbr(abrName)
			createDB(dbName)
		}, 30)

		_ = AfterEach(func() {
			deleteAbr(abrName)
			deleteDb(dbName)
			deleteTaskRuns()
		}, 30)

		It("should move state to ArtifactBuildDiscovered on Success", func() {
			tr := getTrAbr()
			tr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
			tr.Status.TaskRunResults = []tektonapi.TaskRunResult{{
				Name:  artifactbuild.TaskResultScmTag,
				Value: "tag1",
			}, {
				Name:  artifactbuild.TaskResultScmUrl,
				Value: "url1",
			}, {
				Name:  artifactbuild.TaskResultScmType,
				Value: "git",
			}, {
				Name:  artifactbuild.TaskResultContextPath,
				Value: "/path1",
			}, {
				Name:  artifactbuild.TaskResultMessage,
				Value: "OK",
			}}
			Expect(k8sClient.Status().Update(ctx, tr)).Should(Succeed())
			print(tr.Name)
			tr = getTrAbr()
			Expect(tr.Status.CompletionTime).ToNot(BeNil())
			Eventually(func() error {
				abr := v1alpha1.ArtifactBuild{}
				_ = k8sClient.Get(ctx, abrName, &abr)
				if abr.Status.State == v1alpha1.ArtifactBuildStateBuilding {
					return nil
				}
				return errors.New("not updated yet")
			}, timeout, interval).Should(Succeed())

			abr := v1alpha1.ArtifactBuild{}
			Expect(k8sClient.Get(ctx, abrName, &abr)).Should(Succeed())
			Expect(abr.Status.SCMInfo.SCMURL).Should(Equal("url1"))
			Expect(abr.Status.SCMInfo.SCMType).Should(Equal("git"))
			Expect(abr.Status.SCMInfo.Tag).Should(Equal("tag1"))
			Expect(abr.Status.Message).Should(Equal("OK"))
			Expect(abr.Status.SCMInfo.Path).Should(Equal("/path1"))
		})

		It("db", func() {
			db := v1alpha1.DependencyBuild{}
			Eventually(func() error {
				Expect(k8sClient.Get(ctx, dbName, &db)).Should(Succeed())
				if db.Status.State == v1alpha1.DependencyBuildStateBuilding {
					return nil
				}
				return errors.New("not updated yet")
			}, timeout, interval).Should(Succeed())
			Expect(k8sClient.Get(ctx, dbName, &db)).Should(Succeed())

			tr := tektonapi.TaskRun{}
			trKey := types.NamespacedName{
				Namespace: TestNamespace,
				Name:      fmt.Sprintf("%s-build-%d", db.Name, len(db.Status.FailedBuildRecipes)),
			}
			Expect(k8sClient.Get(ctx, trKey, &tr)).Should(Succeed())
			tr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
			con := apis.Condition{
				Type:   apis.ConditionSucceeded,
				Status: corev1.ConditionTrue,
			}
			tr.Status.SetCondition(&con)
			Expect(k8sClient.Status().Update(ctx, &tr)).Should(Succeed())
			Eventually(func() error {
				Expect(k8sClient.Get(ctx, dbName, &db)).Should(Succeed())
				if db.Status.State == v1alpha1.DependencyBuildStateComplete {
					return nil
				}
				msg := fmt.Sprintf("not updated yet %#v", tr)
				return errors.New(msg)
			}, timeout, interval).Should(Succeed())
		})

	})
})
