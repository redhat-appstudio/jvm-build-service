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
	"time"

	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuildrequest"

	tektonapi "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
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
)

// createComponent creates sample component resource and verifies it was properly created
func createAbr(componentLookupKey types.NamespacedName) {
	abr := &v1alpha1.ArtifactBuildRequest{
		TypeMeta: metav1.TypeMeta{
			APIVersion: "jvmbuildservice.io/v1alpha1",
			Kind:       "ArtifactBuildRequest",
		},
		ObjectMeta: metav1.ObjectMeta{
			Name:      componentLookupKey.Name,
			Namespace: componentLookupKey.Namespace,
		},
		Spec: v1alpha1.ArtifactBuildRequestSpec{},
	}
	Expect(k8sClient.Create(ctx, abr)).Should(Succeed())

	created := getAbr(componentLookupKey)
	created.Status.State = v1alpha1.ArtifactBuildRequestStateDiscovering
	Expect(k8sClient.Status().Update(ctx, created)).Should(Succeed())
}

func createTaskRun(componentLookupKey types.NamespacedName, labels map[string]string) {
	abr := getAbr(componentLookupKey)
	abr.Status.State = v1alpha1.ArtifactBuildRequestStateDiscovering
	Expect(k8sClient.Status().Update(ctx, abr)).Should(Succeed())
	tr := &tektonapi.TaskRun{TypeMeta: metav1.TypeMeta{
		APIVersion: "tekton.dev/v1beta1",
		Kind:       "TaskRun",
	},
		ObjectMeta: metav1.ObjectMeta{
			Name:            componentLookupKey.Name,
			Namespace:       componentLookupKey.Namespace,
			Labels:          labels,
			OwnerReferences: []metav1.OwnerReference{{APIVersion: v1alpha1.SchemeGroupVersion.String(), Kind: artifactbuildrequest.RequestKind, Name: abr.Name, UID: abr.UID}},
		},
	}
	Expect(k8sClient.Create(ctx, tr)).Should(Succeed())
}

func getAbr(componentLookupKey types.NamespacedName) *v1alpha1.ArtifactBuildRequest {
	component := &v1alpha1.ArtifactBuildRequest{}
	Eventually(func() bool {
		_ = k8sClient.Get(ctx, componentLookupKey, component)
		return component.ResourceVersion != ""
	}, timeout, interval).Should(BeTrue())
	return component
}
func getTr(componentLookupKey types.NamespacedName) *tektonapi.TaskRun {
	component := &tektonapi.TaskRun{}
	Eventually(func() bool {
		_ = k8sClient.Get(ctx, componentLookupKey, component)
		return component.ResourceVersion != ""
	}, timeout, interval).Should(BeTrue())
	return component
}

// deleteAbr deletes the specified component resource and verifies it was properly deleted
func deleteAbr(componentLookupKey types.NamespacedName) {
	// Delete
	Eventually(func() error {
		f := &v1alpha1.ArtifactBuildRequest{}
		_ = k8sClient.Get(ctx, componentLookupKey, f)
		return k8sClient.Delete(ctx, f)
	}, timeout, interval).Should(Succeed())

	// Wait for delete to finish
	Eventually(func() error {
		f := &v1alpha1.ArtifactBuildRequest{}
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
	)

	Context("Test Successful discovery pipeline run", func() {

		_ = BeforeEach(func() {
			createAbr(abrName)
		}, 30)

		_ = AfterEach(func() {
			deleteAbr(abrName)
			deleteTaskRuns()
		}, 30)

		It("should move state to ArtifactBuildRequestDiscovered on Success", func() {
			createTaskRun(abrName, map[string]string{artifactbuildrequest.TaskRunLabel: "", artifactbuildrequest.ArtifactBuildRequestIdLabel: string(getAbr(abrName).UID)})
			tr := getTr(abrName)
			tr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
			tr.Status.TaskRunResults = []tektonapi.TaskRunResult{{
				Name:  artifactbuildrequest.TaskResultScmTag,
				Value: "tag1",
			}, {
				Name:  artifactbuildrequest.TaskResultScmUrl,
				Value: "url1",
			}, {
				Name:  artifactbuildrequest.TaskResultScmType,
				Value: "git",
			}, {
				Name:  artifactbuildrequest.TaskResultContextPath,
				Value: "/path1",
			}, {
				Name:  artifactbuildrequest.TaskResultMessage,
				Value: "OK",
			}}
			Expect(k8sClient.Status().Update(ctx, tr)).Should(Succeed())
			Eventually(func() error {
				abr := v1alpha1.ArtifactBuildRequest{}
				_ = k8sClient.Get(ctx, abrName, &abr)
				if abr.Status.State == v1alpha1.ArtifactBuildRequestStateBuilding {
					return nil
				}
				return errors.New("not updated yet")
			}, timeout, interval).Should(Succeed())

			abr := v1alpha1.ArtifactBuildRequest{}
			Expect(k8sClient.Get(ctx, abrName, &abr)).Should(Succeed())
			Expect(abr.Status.ScmInfo.SCMURL).Should(Equal("url1"))
			Expect(abr.Status.ScmInfo.SCMType).Should(Equal("git"))
			Expect(abr.Status.ScmInfo.Tag).Should(Equal("tag1"))
			Expect(abr.Status.Message).Should(Equal("OK"))
			Expect(abr.Status.ScmInfo.Path).Should(Equal("/path1"))
		})

	})
})
