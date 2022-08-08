/*
Copyright 2022.

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
	"go/build"
	"os"
	"path/filepath"
	"testing"
	"time"

	. "github.com/onsi/ginkgo"
	. "github.com/onsi/gomega"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes/scheme"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/envtest"
	logf "sigs.k8s.io/controller-runtime/pkg/log"
	"sigs.k8s.io/controller-runtime/pkg/log/zap"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/dependencybuild"
	taskrunapi "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	//+kubebuilder:scaffold:imports
)

// These tests use Ginkgo (BDD-style Go testing framework). Refer to
// http://onsi.github.io/ginkgo/ to learn more about Ginkgo.

var (
	k8sClient client.Client
	testEnv   *envtest.Environment
	ctx       context.Context
	cancel    context.CancelFunc
)

func TestAPIs(t *testing.T) {
	RegisterFailHandler(Fail)

	RunSpecs(t, "Controller Suite")
}

var _ = BeforeSuite(func() {
	logf.SetLogger(zap.New(zap.WriteTo(GinkgoWriter), zap.UseDevMode(true)))

	ctx, cancel = context.WithCancel(context.TODO())

	By("bootstrapping test environment")
	// while GOPATH works in local testing and the gomods, it does not work in github actions in CI because the
	// source code checkout is in a different path, '/home/runner/work/jvm-build-service/jvm-build-service/deploy/crds/base'
	// also, unfortunately, there is no env var defined for the workdir to use in code so we have to do this test with
	// the hard coded value
	ourCRDs := filepath.Join(build.Default.GOPATH, "src", "github.com", "redhat-appstudio", "jvm-build-service", "deploy", "crds", "base")
	_, fileerr := os.Open(ourCRDs)
	if fileerr != nil {
		// assume this is github actions CI
		ourCRDs = filepath.Join(string(os.PathSeparator), "home", "runner", "work", "jvm-build-service", "jvm-build-service", "deploy", "crds", "base")
	}
	tektonCRDs := filepath.Join(build.Default.GOPATH, "pkg", "mod", "github.com", "tektoncd", "pipeline@v0.33.0", "config")
	_, fileerr = os.Open(tektonCRDs)
	if fileerr != nil {
		tektonCRDs = filepath.Join(string(os.PathSeparator), "home", "runner", "work", "jvm-build-service", "jvm-build-service", "vendor", "github.com", "tektoncd", "pipeline", "config")
	}
	testEnv = &envtest.Environment{
		CRDDirectoryPaths: []string{
			ourCRDs,
			tektonCRDs,
		},
		ErrorIfCRDPathMissing: true,
	}

	cfg, err := testEnv.Start()
	cfg.Timeout = 5 * time.Second
	Expect(err).NotTo(HaveOccurred())
	Expect(cfg).NotTo(BeNil())

	err = v1alpha1.AddToScheme(scheme.Scheme)
	Expect(err).NotTo(HaveOccurred())

	err = taskrunapi.AddToScheme(scheme.Scheme)
	Expect(err).NotTo(HaveOccurred())

	//+kubebuilder:scaffold:scheme

	k8sClient, err = client.New(cfg, client.Options{Scheme: scheme.Scheme})
	Expect(err).NotTo(HaveOccurred())
	Expect(k8sClient).NotTo(BeNil())

	svcAccount := corev1.ServiceAccount{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "pipeline",
			Namespace: "default",
		},
	}

	Expect(k8sClient.Create(context.Background(), &svcAccount)).Should(Succeed())

	k8sManager, err := ctrl.NewManager(cfg, ctrl.Options{
		Scheme: scheme.Scheme,
	})
	Expect(err).ToNot(HaveOccurred())

	Expect(err).ToNot(HaveOccurred())
	err = artifactbuild.SetupNewReconcilerWithManager(k8sManager)
	Expect(err).ToNot(HaveOccurred())
	err = dependencybuild.SetupNewReconcilerWithManager(k8sManager)
	Expect(err).ToNot(HaveOccurred())
	go func() {
		defer GinkgoRecover()
		err = k8sManager.Start(ctx)
		Expect(err).ToNot(HaveOccurred(), "failed to run manager")
	}()
}, 60)

var _ = AfterSuite(func() {
	cancel()
	By("tearing down the test environment")
	err := testEnv.Stop()
	Expect(err).NotTo(HaveOccurred())
})
