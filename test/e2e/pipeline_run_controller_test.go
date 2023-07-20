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
	"context"
	"errors"
	"fmt"
	errors2 "k8s.io/apimachinery/pkg/api/errors"
	"time"

	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/dependencybuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/systemconfig"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"

	tektonapi "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"

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

func setupSystemConfig() {
	//we need some system level config
	//add a builder image
	existing := v1alpha1.SystemConfig{}
	err := k8sClient.Get(context.TODO(), types.NamespacedName{Name: systemconfig.SystemConfigKey}, &existing)
	if errors2.IsNotFound(err) {
		nm := corev1.Namespace{ObjectMeta: metav1.ObjectMeta{Name: util.ControllerNamespace}}
		Expect(k8sClient.Create(context.TODO(), &nm)).Should(Succeed())

		sysConfig := v1alpha1.SystemConfig{
			ObjectMeta: metav1.ObjectMeta{
				Name: systemconfig.SystemConfigKey,
			},
			Spec: v1alpha1.SystemConfigSpec{
				Builders: map[string]v1alpha1.JavaVersionInfo{
					v1alpha1.JDK8Builder: {
						Image: "quay.io/redhat-appstudio/hacbs-jdk8-builder:latest",
						Tag:   "jdk:8,maven:3.8,gradle:8.0.2;7.4.2;6.9.2;5.6.4;4.10.3",
					},
					v1alpha1.JDK11Builder: {
						Image: "quay.io/redhat-appstudio/hacbs-jdk11-builder:latest",
						Tag:   "jdk:11,maven:3.8,gradle:8.0.2;7.4.2;6.9.2;5.6.4;4.10.3",
					},
					v1alpha1.JDK17Builder: {
						Image: "quay.io/redhat-appstudio/hacbs-jdk17-builder:latest",
						Tag:   "jdk:17,maven:3.8,gradle:8.0.2;7.4.2;6.9.2",
					},
					v1alpha1.JDK7Builder: {
						Image: "quay.io/redhat-appstudio/hacbs-jdk7-builder:latest",
						Tag:   "jdk:7,maven:3.8",
					},
				},
			},
		}

		Expect(k8sClient.Create(context.TODO(), &sysConfig)).Should(Succeed())
	}
	jbsConfig := v1alpha1.JBSConfig{}
	err = k8sClient.Get(context.TODO(), types.NamespacedName{Namespace: metav1.NamespaceDefault, Name: v1alpha1.JBSConfigName}, &jbsConfig)
	if errors2.IsNotFound(err) {
		jbsConfig.Namespace = metav1.NamespaceDefault
		jbsConfig.Name = v1alpha1.JBSConfigName
		jbsConfig.Spec.EnableRebuilds = true
		Expect(k8sClient.Create(context.TODO(), &jbsConfig)).Should(Succeed())
	}
}

func createDB(componentLookupKey types.NamespacedName) {
	db := &v1alpha1.DependencyBuild{
		TypeMeta: metav1.TypeMeta{
			APIVersion: "jvmbuildservice.io/v1alpha1",
			Kind:       "DependencyBuild",
		},
		ObjectMeta: metav1.ObjectMeta{
			Name:      componentLookupKey.Name,
			Namespace: componentLookupKey.Namespace,
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

func getTrBuildDiscovery() *tektonapi.PipelineRun {
	listOpts := &client.ListOptions{
		Namespace:     TestNamespace,
		LabelSelector: labels.SelectorFromSet(map[string]string{dependencybuild.PipelineTypeLabel: dependencybuild.PipelineTypeBuildInfo}),
	}
	trl := tektonapi.PipelineRunList{}
	var tr *tektonapi.PipelineRun
	Eventually(func() bool {
		Expect(k8sClient.List(ctx, &trl, listOpts)).ToNot(HaveOccurred())
		//there should only be one, be guard against multiple
		for _, current := range trl.Items {
			if tr == nil || tr.CreationTimestamp.Before(&current.CreationTimestamp) {
				tmp := current
				tr = &tmp
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

func listPipelineRuns() *tektonapi.PipelineRunList {
	taskRuns := &tektonapi.PipelineRunList{}
	labelSelectors := client.ListOptions{Raw: &metav1.ListOptions{}}
	err := k8sClient.List(ctx, taskRuns, &labelSelectors)
	Expect(err).ToNot(HaveOccurred())
	return taskRuns
}

func deletePipelineRuns() {
	for _, pipelineRun := range listPipelineRuns().Items {
		Expect(k8sClient.Delete(ctx, &pipelineRun)).Should(Succeed())
	}
}

var _ = Describe("Test discovery PipelineRun complete updates ABR state", func() {

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
			setupSystemConfig()
		}, 30)

		_ = AfterEach(func() {
			deleteAbr(abrName)
			deleteDb(dbName)
			deletePipelineRuns()
		}, 30)

		It("db", func() {
			for {
				btr := getTrBuildDiscovery()
				btr.Status.CompletionTime = &metav1.Time{Time: time.Now()}
				btr.Status.SetCondition(&apis.Condition{
					Type:               apis.ConditionSucceeded,
					Status:             "True",
					LastTransitionTime: apis.VolatileTime{Inner: metav1.Time{Time: time.Now()}},
				})
				btr.Status.Results = []tektonapi.PipelineRunResult{{
					Name:  dependencybuild.BuildInfoPipelineResultMessage,
					Value: tektonapi.ResultValue{Type: tektonapi.ParamTypeString, StringVal: "OK"},
				}, {
					Name:  dependencybuild.BuildInfoPipelineResultBuildInfo,
					Value: tektonapi.ResultValue{Type: tektonapi.ParamTypeString, StringVal: `{"tools":{"jdk":{"min":"8","max":"17","preferred":"11"},"maven":{"min":"3.8","max":"3.8","preferred":"3.8"}},"invocations":[["maven","clean","install","-DskipTests","-Denforcer.skip","-Dcheckstyle.skip","-Drat.skip=true","-Dmaven.deploy.skip=false"]],"enforceVersion":null,"toolVersion":null,"javaHome":null}`},
				}}
				err := k8sClient.Status().Update(ctx, btr)
				if err == nil {
					break
				} else if !errors2.IsConflict(err) {
					//retry on conflict
					Expect(err).ToNot(HaveOccurred())
				}

			}
			db := v1alpha1.DependencyBuild{}
			Eventually(func() error {
				Expect(k8sClient.Get(ctx, dbName, &db)).Should(Succeed())
				if db.Status.State == v1alpha1.DependencyBuildStateBuilding {
					return nil
				}
				return errors.New("not updated yet: " + db.Status.State)
			}, timeout, interval).Should(Succeed())
			Expect(k8sClient.Get(ctx, dbName, &db)).Should(Succeed())

			tr := tektonapi.PipelineRun{}
			trKey := types.NamespacedName{
				Namespace: TestNamespace,
				Name:      fmt.Sprintf("%s-build-%d", db.Name, len(db.Status.FailedBuildRecipes)),
			}
			//this is slightly racey, need to use eventually
			//the status can update before the build is ready
			Eventually(func() error {
				return k8sClient.Get(ctx, trKey, &tr)
			}, timeout, interval).Should(Succeed())
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
