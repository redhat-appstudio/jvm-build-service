//go:build periodic
// +build periodic

package e2e

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/wait"
	"knative.dev/pkg/apis"
)

func TestServiceRegistry(t *testing.T) {
	ta := setup(t, nil)

	//TODO start of more common logic to split into commonly used logic between
	// TestExampleRun and TestServiceRegistry.  Not doing that yet because of
	// active PRs by other team members, including the component based test which
	// will probably greatly rework this anyway.
	path, err := os.Getwd()
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	ta.Logf(fmt.Sprintf("current working dir: %s", path))

	mavenYamlPath := filepath.Join(path, "..", "..", "deploy", "base", "maven-v0.2.yaml")
	ta.maven = &v1beta1.Task{}
	obj := streamFileYamlToTektonObj(mavenYamlPath, ta.maven, ta)
	var ok bool
	ta.maven, ok = obj.(*v1beta1.Task)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("file %s did not produce a task: %#v", mavenYamlPath, obj))
	}
	// override images if need be
	analyserImage := os.Getenv("JVM_BUILD_SERVICE_ANALYZER_IMAGE")
	if len(analyserImage) > 0 {
		ta.Logf(fmt.Sprintf("PR analyzer image: %s", analyserImage))
		for _, step := range ta.maven.Spec.Steps {
			if step.Name != "analyse-dependencies" {
				continue
			}
			ta.Logf(fmt.Sprintf("Updating analyse-dependencies step with image %s", analyserImage))
			step.Image = analyserImage
		}
	}
	sidecarImage := os.Getenv("JVM_BUILD_SERVICE_SIDECAR_IMAGE")
	if len(sidecarImage) > 0 {
		ta.Logf(fmt.Sprintf("PR sidecar image: %s", sidecarImage))
		for _, sidecar := range ta.maven.Spec.Sidecars {
			if sidecar.Name != "proxy" {
				continue
			}
			ta.Logf(fmt.Sprintf("Updating proxy sidecar with image %s", sidecarImage))
			sidecar.Image = sidecarImage
		}
	}
	ta.maven, err = tektonClient.TektonV1beta1().Tasks(ta.ns).Create(context.TODO(), ta.maven, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	pipelineYamlPath := filepath.Join(path, "..", "..", "hack", "examples", "pipeline.yaml")
	ta.pipeline = &v1beta1.Pipeline{}
	obj = streamFileYamlToTektonObj(pipelineYamlPath, ta.pipeline, ta)
	ta.pipeline, ok = obj.(*v1beta1.Pipeline)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("file %s did not produce a pipeline: %#v", pipelineYamlPath, obj))
	}
	ta.pipeline, err = tektonClient.TektonV1beta1().Pipelines(ta.ns).Create(context.TODO(), ta.pipeline, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	runYamlPath := filepath.Join(path, "..", "..", "hack", "examples", "run-service-registry.yaml")
	ta.run = &v1beta1.PipelineRun{}
	obj = streamFileYamlToTektonObj(runYamlPath, ta.run, ta)
	ta.run, ok = obj.(*v1beta1.PipelineRun)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("file %s did not produce a pipelinerun: %#v", runYamlPath, obj))
	}
	ta.run, err = tektonClient.TektonV1beta1().PipelineRuns(ta.ns).Create(context.TODO(), ta.run, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	ta.t.Run("pipelinerun completes successfully", func(t *testing.T) {
		err = wait.PollImmediate(ta.interval, ta.timeout, func() (done bool, err error) {
			pr, err := tektonClient.TektonV1beta1().PipelineRuns(ta.ns).Get(context.TODO(), ta.run.Name, metav1.GetOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("get pr %s produced err: %s", ta.run.Name, err.Error()))
				return false, nil
			}
			if !pr.IsDone() {
				prBytes, err := json.MarshalIndent(pr, "", "  ")
				if err != nil {
					ta.Logf(fmt.Sprintf("problem marshalling in progress pipelinerun to bytes: %s", err.Error()))
					return false, nil
				}
				ta.Logf(fmt.Sprintf("in flight pipeline run: %s", string(prBytes)))
			}
			if !pr.GetStatusCondition().GetCondition(apis.ConditionSucceeded).IsTrue() {
				prBytes, err := json.MarshalIndent(pr, "", "  ")
				if err != nil {
					ta.Logf(fmt.Sprintf("problem marshalling failed pipelinerun to bytes: %s", err.Error()))
					return false, nil
				}
				ta.Logf(fmt.Sprintf("not yet successful pipeline run: %s", string(prBytes)))

			}
			return true, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out when waiting for the pipeline run to complete")
		}
	})

	ta.t.Run("artifactbuilds and dependencybuilds generated", func(t *testing.T) {
		err = wait.PollImmediate(4*time.Minute, 24*time.Minute, func() (done bool, err error) {
			return bothABsAndDBsGenerated(ta)
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for generation of artifactbuilds and dependencybuilds")
		}
	})

	ta.t.Run("current target of artfactbuilds and dependencybuilds complete", func(t *testing.T) {
		err = wait.PollImmediate(10*time.Minute, 90*time.Minute, func() (done bool, err error) {
			abList, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error list artifactbuilds: %s", err.Error()))
				return false, nil
			}
			abComplete := false
			ta.Logf(fmt.Sprintf("number of artifactbuilds: %d", len(abList.Items)))
			for _, ab := range abList.Items {
				if ab.Status.State == v1alpha1.ArtifactBuildStateComplete {
					abComplete = true
					break
				}
			}
			dbCompleteCount := 0
			dbFailedCount := 0
			dbContaminatedCount := 0
			dbList, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			ta.Logf(fmt.Sprintf("number of dependencybuilds: %d", len(dbList.Items)))
			for _, db := range dbList.Items {
				switch {
				case db.Status.State == v1alpha1.DependencyBuildStateComplete:
					dbCompleteCount++
				case db.Status.State == v1alpha1.DependencyBuildStateFailed:
					dbFailedCount++
				case db.Status.State == v1alpha1.DependencyBuildStateContaminated:
					dbContaminatedCount++
				}
			}
			// currently need a cluster with m*.2xlarge worker nodes to achieve this; testing with a) node auto scaler, b) app studio quota still pending
			if abComplete && dbCompleteCount > 74 {
				return true, nil
			}
			ta.Logf(fmt.Sprintf("dependencybuild complete count: %d, failed count: %d, contaminated count: %d", dbCompleteCount, dbFailedCount, dbContaminatedCount))
			return false, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for some artifactbuilds and dependencybuilds to complete")
		}
	})

}
