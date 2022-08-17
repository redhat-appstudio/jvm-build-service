//go:build periodic
// +build periodic

package e2e

import (
	"context"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	corev1 "k8s.io/api/core/v1"
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
		err = wait.PollImmediate(10*time.Minute, 2*time.Hour, func() (done bool, err error) {
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
			// currently need a cluster with m*.2xlarge worker nodes (local cluster) or m*.4xlarge cluster (CI) to achieve this; testing with a) node auto scaler, b) app studio quota still pending
			if abComplete && len(dbList.Items) > 90 && len(dbList.Items) <= dbCompleteCount+dbFailedCount+dbContaminatedCount && !activePipelineRuns(ta) {
				ta.Logf(fmt.Sprintf("dependencybuild FINAL complete count: %d, failed count: %d, contaminated count: %d", dbCompleteCount, dbFailedCount, dbContaminatedCount))
				return true, nil
			}
			ta.Logf(fmt.Sprintf("dependencybuild complete count: %d, failed count: %d, contaminated count: %d", dbCompleteCount, dbFailedCount, dbContaminatedCount))
			return false, nil
		})

		ta.Logf("************** START FAILED DEPENDENCYBUILD DUMP********************")
		dbDumpForState(ta, v1alpha1.DependencyBuildStateFailed)
		ta.Logf("************** END FAILED DEPENDENCYBUILD DUMP********************")
		ta.Logf("************** START FAILED/MISSING ARTFACTBUILD DUMP********************")
		abDumpForState(ta, v1alpha1.ArtifactBuildStateFailed)
		ta.Logf("************** END FAILED ARTFACTBUILD DUMP********************")
		ta.Logf("************** START MISSING ARTFACTBUILD DUMP********************")
		abDumpForState(ta, v1alpha1.ArtifactBuildStateMissing)
		ta.Logf("************** END MISSING ARTFACTBUILD DUMP********************")

		dumpBadEvents(ta)
		dumpPods(ta, "jvm-build-service")
		dumpPodsGlob(ta, ta.ns, "localstack")
		dumpPodsGlob(ta, ta.ns, "artifact-cache")

		if err != nil {
			ta.t.Fatal("timed out waiting for some artifactbuilds and dependencybuilds to complete")
		}
	})

}

func dumpPodsGlob(ta *testArgs, namespace, glob string) {
	podClient := kubeClient.CoreV1().Pods(namespace)
	podList, err := podClient.List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		ta.Logf(fmt.Sprintf("error list pods %s", err.Error()))
		return
	}
	ta.Logf(fmt.Sprintf("dumpPods have %d items in list", len(podList.Items)))
	for _, pod := range podList.Items {
		if !strings.Contains(pod.Name, glob) {
			continue
		}
		ta.Logf(fmt.Sprintf("dumpPods looking at pod %s in phase %s", pod.Name, pod.Status.Phase))

		for _, container := range pod.Spec.Containers {
			req := podClient.GetLogs(pod.Name, &corev1.PodLogOptions{Container: container.Name})
			readCloser, err := req.Stream(context.TODO())
			if err != nil {
				ta.Logf(fmt.Sprintf("error getting pod logs for container %s: %s", container.Name, err.Error()))
				continue
			}
			b, err := ioutil.ReadAll(readCloser)
			if err != nil {
				ta.Logf(fmt.Sprintf("error reading pod stream %s", err.Error()))
				continue
			}
			podLog := string(b)
			ta.Logf(fmt.Sprintf("pod logs for container %s in pod %s:  %s", container.Name, pod.Name, podLog))

		}

	}
}

func dbDumpForState(ta *testArgs, state string) {
	dbList, dberr := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
	podClient := kubeClient.CoreV1().Pods(ta.ns)
	if dberr != nil {
		ta.Logf(fmt.Sprintf("DB list error %s", dberr.Error()))
	} else {
		for _, db := range dbList.Items {
			if db.Status.State != state {
				continue
			}
			ta.Logf(fmt.Sprintf("*****Examining failed db %s", db.Name))
			prList := pipelineRuns(ta, db.Name, artifactbuild.DependencyBuildIdLabel)
			for _, pr := range prList {
				podList := prPods(ta, pr.Name)
				for _, pod := range podList {
					for _, container := range pod.Spec.Containers {
						req := podClient.GetLogs(pod.Name, &corev1.PodLogOptions{Container: container.Name})
						readCloser, err2 := req.Stream(context.TODO())
						if err2 != nil {
							ta.Logf(fmt.Sprintf("error getting pod logs for container %s: %s", container.Name, err2.Error()))
							continue
						}
						b, err2 := ioutil.ReadAll(readCloser)
						if err2 != nil {
							ta.Logf(fmt.Sprintf("error reading pod stream %s", err2.Error()))
							continue
						}
						podLog := string(b)
						ta.Logf(fmt.Sprintf("pod logs for container %s in pod %s:  %s", container.Name, pod.Name, podLog))

					}
				}
			}
			ta.Logf(fmt.Sprintf("******Done with db %s", db.Name))
		}
	}
}

func abDumpForState(ta *testArgs, state string) {
	abList, aberr := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
	podClient := kubeClient.CoreV1().Pods(ta.ns)
	if aberr != nil {
		ta.Logf(fmt.Sprintf("AB list error %s", aberr.Error()))
	} else {
		for _, ab := range abList.Items {
			if ab.Status.State != state {
				continue
			}
			ta.Logf(fmt.Sprintf("*****Examining failed ab %s", ab.Name))
			prList := pipelineRuns(ta, artifactbuild.ABRLabelForGAV(ab.Spec.GAV), artifactbuild.ArtifactBuildIdLabel)
			for _, pr := range prList {
				podList := prPods(ta, pr.Name)
				for _, pod := range podList {
					for _, container := range pod.Spec.Containers {
						req := podClient.GetLogs(pod.Name, &corev1.PodLogOptions{Container: container.Name})
						readCloser, err2 := req.Stream(context.TODO())
						if err2 != nil {
							ta.Logf(fmt.Sprintf("error getting pod logs for container %s: %s", container.Name, err2.Error()))
							continue
						}
						b, err2 := ioutil.ReadAll(readCloser)
						if err2 != nil {
							ta.Logf(fmt.Sprintf("error reading pod stream %s", err2.Error()))
							continue
						}
						podLog := string(b)
						ta.Logf(fmt.Sprintf("pod logs for container %s in pod %s:  %s", container.Name, pod.Name, podLog))

					}
				}
			}
			ta.Logf(fmt.Sprintf("******Done with ab %s", ab.Name))
		}
	}
}

func activePipelineRuns(ta *testArgs) bool {
	prClient := tektonClient.TektonV1beta1().PipelineRuns(ta.ns)
	listOptions := metav1.ListOptions{
		LabelSelector: fmt.Sprintf("%s=", artifactbuild.PipelineRunLabel),
	}
	prList, err := prClient.List(context.TODO(), listOptions)
	if err != nil {
		ta.Logf(fmt.Sprintf("error listing pipelineruns: %s", err.Error()))
		return true
	}
	for _, pr := range prList.Items {
		if !pr.IsDone() {
			return true
		}
	}
	return false
}

func pipelineRuns(ta *testArgs, name, label string) []v1beta1.PipelineRun {
	prClient := tektonClient.TektonV1beta1().PipelineRuns(ta.ns)
	listOptions := metav1.ListOptions{
		LabelSelector: fmt.Sprintf("%s=%s", label, name),
	}
	dbList, err := prClient.List(context.TODO(), listOptions)
	if err != nil {
		ta.Logf(fmt.Sprintf("error listing prs %s", err.Error()))
		return []v1beta1.PipelineRun{}
	}
	return dbList.Items
}

func prPods(ta *testArgs, name string) []corev1.Pod {
	podClient := kubeClient.CoreV1().Pods(ta.ns)
	listOptions := metav1.ListOptions{
		LabelSelector: fmt.Sprintf("tekton.dev/pipelineRun=%s", name),
	}
	podList, err := podClient.List(context.TODO(), listOptions)
	if err != nil {
		ta.Logf(fmt.Sprintf("error listing pr pods %s", err.Error()))
		return []corev1.Pod{}
	}
	return podList.Items
}
