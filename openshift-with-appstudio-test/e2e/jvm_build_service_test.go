//go:build normal
// +build normal

package e2e

import (
	"context"
	"encoding/json"
	"fmt"
	"io/ioutil"
	corev1 "k8s.io/api/core/v1"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/wait"
	"knative.dev/pkg/apis"
)

func TestExampleRun(t *testing.T) {
	ta := setup(t, nil)
	//TODO, for now at least, keeping our test project to allow for analyzing the various CRD instances both for failure
	// and successful runs (in case a run succeeds, but we find something amiss if we look at passing runs; our in repo
	// tests do now run in conjunction with say the full suite of e2e's in the e2e-tests runs, so no contention there.
	//defer projectCleanup(ta)

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

	runYamlPath := filepath.Join(path, "..", "..", "hack", "examples", "run-e2e-shaded-app.yaml")
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
				if err != nil {
					ta.Logf(fmt.Sprintf("problem marshalling in progress pipelinerun to bytes: %s", err.Error()))
					return false, nil
				}
				ta.Logf(fmt.Sprintf("in flight pipeline run: %s", pr.Name))
				return false, nil
			}
			if pr.IsDone() && !pr.GetStatusCondition().GetCondition(apis.ConditionSucceeded).IsTrue() {
				prBytes, err := json.MarshalIndent(pr, "", "  ")
				if err != nil {
					ta.Logf(fmt.Sprintf("problem marshalling failed pipelinerun to bytes: %s", err.Error()))
					return false, nil
				}
				return false, fmt.Errorf("pipeline run did not succeed: %s", string(prBytes))
			}
			return true, nil
		})
		if err != nil {
			debugAndFailTest(ta, fmt.Sprintf("failure occured when waiting for the pipeline run to complete: %v", err))
		}
	})

	ta.t.Run("artifactbuilds and dependencybuilds generated", func(t *testing.T) {
		err = wait.PollImmediate(ta.interval, ta.timeout, func() (done bool, err error) {
			return bothABsAndDBsGenerated(ta)
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for generation of artifactbuilds and dependencybuilds")
		}
	})

	ta.t.Run("some artfactbuilds and dependencybuilds complete", func(t *testing.T) {
		err = wait.PollImmediate(ta.interval, 2*ta.timeout, func() (done bool, err error) {
			abList, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error list artifactbuilds: %s", err.Error()))
				return false, nil
			}
			//we want to make sure there is more than one ab, and that they are all complete
			abComplete := len(abList.Items) > 0
			ta.Logf(fmt.Sprintf("number of artifactbuilds: %d", len(abList.Items)))
			for _, ab := range abList.Items {
				if ab.Status.State != v1alpha1.ArtifactBuildStateComplete {
					ta.Logf(fmt.Sprintf("artifactbuild %s not complete", ab.Spec.GAV))
					abComplete = false
					break
				}
			}
			dbList, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error list dependencybuilds: %s", err.Error()))
				return false, nil
			}
			dbComplete := len(dbList.Items) > 0
			ta.Logf(fmt.Sprintf("number of dependencybuilds: %d", len(dbList.Items)))
			for _, db := range dbList.Items {
				if db.Status.State != v1alpha1.DependencyBuildStateComplete {
					ta.Logf(fmt.Sprintf("depedencybuild %s not complete", db.Spec.ScmInfo.SCMURL))
					dbComplete = false
					break
				} else if db.Status.State == v1alpha1.DependencyBuildStateFailed {
					ta.Logf(fmt.Sprintf("depedencybuild %s FAILED", db.Spec.ScmInfo.SCMURL))
					return false, fmt.Errorf("depedencybuild %s for repo %s FAILED", db.Name, db.Spec.ScmInfo.SCMURL)
				}
			}
			if abComplete && dbComplete {
				return true, nil
			}
			return false, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for some artifactbuilds and dependencybuilds to complete")
		}
	})

	ta.t.Run("contaminated build is resolved", func(t *testing.T) {
		//our sample repo has shaded-jdk11 which is contaminated by simple-jdk8
		var contaminated string
		var simpleJDK8 string
		err = wait.PollImmediate(ta.interval, 2*ta.timeout, func() (done bool, err error) {

			dbContaminated := false
			shadedComplete := false
			contaminantBuild := false
			dbList, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error list dependencybuilds: %s", err.Error()))
				return false, err
			}
			ta.Logf(fmt.Sprintf("number of dependencybuilds: %d", len(dbList.Items)))
			for _, db := range dbList.Items {
				if db.Status.State == v1alpha1.DependencyBuildStateContaminated {
					dbContaminated = true
					contaminated = db.Name
					break
				} else if strings.Contains(db.Spec.ScmInfo.SCMURL, "shaded-jdk11") && db.Status.State == v1alpha1.DependencyBuildStateComplete {
					//its also possible that the build has already resolved itself
					contaminated = db.Name
					shadedComplete = true
				} else if strings.Contains(db.Spec.ScmInfo.SCMURL, "simple-jdk8") {
					contaminantBuild = true
				}
			}
			if dbContaminated || (shadedComplete && contaminantBuild) {
				return true, nil
			}
			return false, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for contaminated build to appear")
		}
		ta.Logf(fmt.Sprintf("contaminated dependencybuild: %s", contaminated))
		//make sure simple-jdk8 was requested as a result
		err = wait.PollImmediate(ta.interval, 2*ta.timeout, func() (done bool, err error) {
			abList, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error list artifactbuilds: %s", err.Error()))
				return false, err
			}
			found := false
			ta.Logf(fmt.Sprintf("number of artifactbuilds: %d", len(abList.Items)))
			for _, ab := range abList.Items {
				if strings.Contains(ab.Spec.GAV, "simple-jdk8") {
					simpleJDK8 = ab.Name
					found = true
					break
				}
			}
			return found, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for simple-jdk8 to appear as an artifactbuild")
		}
		//now make sure simple-jdk8 eventually completes
		err = wait.PollImmediate(ta.interval, 2*ta.timeout, func() (done bool, err error) {
			ab, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).Get(context.TODO(), simpleJDK8, metav1.GetOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error getting simple-jdk8 ArtifactBuild: %s", err.Error()))
				return false, err
			}
			ta.Logf(fmt.Sprintf("simple-jdk8 State: %s", ab.Status.State))
			return ab.Status.State == v1alpha1.ArtifactBuildStateComplete, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for simple-jdk8 to complete")
		}
		//now make sure shaded-jdk11 eventually completes
		err = wait.PollImmediate(ta.interval, 2*ta.timeout, func() (done bool, err error) {
			db, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).Get(context.TODO(), contaminated, metav1.GetOptions{})
			if err != nil {
				ta.Logf(fmt.Sprintf("error getting shaded-jdk11 DependencyBuild: %s", err.Error()))
				return false, err
			}
			ta.Logf(fmt.Sprintf("shaded-jdk11 State: %s", db.Status.State))
			if db.Status.State == v1alpha1.DependencyBuildStateFailed {
				msg := fmt.Sprintf("contaminated db %s failed, exitting wait", contaminated)
				ta.Logf(msg)
				return false, fmt.Errorf(msg)
			}
			return db.Status.State == v1alpha1.DependencyBuildStateComplete, err
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for shaded-jdk11 to complete")
		}
	})

	ta.t.Run("make sure second build access cached dependencies", func(t *testing.T) {
		defer dumpBadEvents(ta)
		defer dumpPods(ta, "jvm-build-service")
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

		ctx := context.TODO()
		watch, werr := tektonClient.TektonV1beta1().PipelineRuns(ta.ns).Watch(ctx, metav1.ListOptions{})
		if werr != nil {
			debugAndFailTest(ta, fmt.Sprintf("error creating watch %s", werr.Error()))
		}
		defer watch.Stop()

		exitForLoop := false
		podClient := kubeClient.CoreV1().Pods(ta.ns)

		for {
			select {
			// technically this is not needed, since we just created the context above; but if go testing changes
			// such that it carries a context, we'll want to use that here
			case <-ctx.Done():
				ta.Logf("context done")
				exitForLoop = true
				break
			case <-time.After(15 * time.Minute):
				msg := "timed out waiting for second build to complete"
				ta.Logf(msg)
				// call stop here in case the defer is bypass by a call to t.Fatal
				watch.Stop()
				debugAndFailTest(ta, msg)
			case event := <-watch.ResultChan():
				if event.Object == nil {
					continue
				}
				pr, ok := event.Object.(*v1beta1.PipelineRun)
				if !ok {
					continue
				}
				if pr.Name != ta.run.Name {
					if pr.IsDone() {
						ta.Logf(fmt.Sprintf("got event for pipelinerun %s in a terminal state", pr.Name))
						continue
					}
					debugAndFailTest(ta, fmt.Sprintf("another non-completed pipeline run %s was generated when it should not", pr.Name))
				}
				ta.Logf(fmt.Sprintf("done processing event for pr %s", pr.Name))
				if pr.IsDone() {
					pods := prPods(ta, pr.Name)
					if len(pods) == 0 {
						debugAndFailTest(ta, fmt.Sprintf("pod for pipelinerun %s unexpectedly missing", pr.Name))
					}
					containers := []corev1.Container{}
					containers = append(containers, pods[0].Spec.InitContainers...)
					containers = append(containers, pods[0].Spec.Containers...)
					for _, container := range containers {
						if !strings.Contains(container.Name, "analyse-dependencies") {
							continue
						}
						req := podClient.GetLogs(pods[0].Name, &corev1.PodLogOptions{Container: container.Name})
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
						cLog := string(b)
						if strings.Contains(cLog, "\"publisher\" : \"central\"") {
							debugAndFailTest(ta, fmt.Sprintf("pipelinerun %s has container %s with dep analysis still pointing to central %s", pr.Name, container.Name, cLog))
						}
						if !strings.Contains(cLog, "\"publisher\" : \"rebuilt\"") {
							debugAndFailTest(ta, fmt.Sprintf("pipelinerun %s has container %s with dep analysis that does not access rebuilt %s", pr.Name, container.Name, cLog))
						}
						break
					}
					ta.Logf(fmt.Sprintf("pr %s is done and has correct analyse-dependencies output, exiting", pr.Name))
					exitForLoop = true
					break
				}
			}
			if exitForLoop {
				break
			}
		}
	})
}
