package e2e

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"
	portforward "github.com/swist/go-k8s-portforward"
	"io"
	"k8s.io/apimachinery/pkg/api/errors"
	"knative.dev/pkg/apis"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"sigs.k8s.io/yaml"
	"strings"
	"testing"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
	corev1 "k8s.io/api/core/v1"
	v12 "k8s.io/api/events/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/wait"
	v1 "k8s.io/client-go/kubernetes/typed/events/v1"
)

func runBasicTests(t *testing.T, doSetup func(t *testing.T, namespace string) *testArgs, namespace string) {
	runPipelineTests(t, doSetup, "run-e2e-shaded-app.yaml", namespace)
}

func runPipelineTests(t *testing.T, doSetup func(t *testing.T, namespace string) *testArgs, pipeline string, namespace string) {
	ta := doSetup(t, namespace)
	//TODO, for now at least, keeping our test project to allow for analyzing the various CRD instances both for failure
	// and successful runs (in case a run succeeds, but we find something amiss if we look at passing runs; our in repo
	// tests do now run in conjunction with say the full suite of e2e's in the e2e-tests runs, so no contention there.
	//defer projectCleanup(ta)

	path, err := os.Getwd()
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	ta.t.Logf("%s current working dir: %s with pipeline %s", time.Now().Format(time.StampMilli), path, pipeline)

	testSet := os.Getenv("DBTESTSET")
	// run dependency build tests instead if env var is set
	// otherwise run artifact build tests instead
	if len(testSet) > 0 {
		runDbTests(path, testSet, ta)
	} else {
		runAbTests(path, os.Getenv("ABTESTSET"), pipeline, ta)
	}
}

func runAbTests(path string, testSet string, pipeline string, ta *testArgs) {
	var err error

	runYamlPath := filepath.Join(path, "..", "..", "hack", "examples", pipeline)
	ta.run = &tektonpipeline.PipelineRun{}
	var ok bool
	obj := streamFileYamlToTektonObj(runYamlPath, ta.run, ta)
	ta.run, ok = obj.(*tektonpipeline.PipelineRun)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("file %s did not produce a pipelinerun: %#v", runYamlPath, obj))
	}

	//if the GAVS env var is set then we just create pre-defined GAVS
	//otherwise we do a full build of a sample project
	if len(testSet) > 0 {
		parts := readTestData(path, testSet, "minikube.yaml", ta)
		for _, s := range parts {
			ta.t.Logf("[%s] Creating ArtifactBuild for GAV: %s", time.Now().Format(time.StampMilli), s)
			ab := v1alpha1.ArtifactBuild{}
			ab.Name = artifactbuild.CreateABRName(s)
			ab.Namespace = ta.ns
			ab.Spec.GAV = s
			_, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).Create(context.TODO(), &ab, metav1.CreateOptions{})
			if err != nil {
				return
			}
		}
	} else {
		ta.run, err = tektonClient.TektonV1().PipelineRuns(ta.ns).Create(context.TODO(), ta.run, metav1.CreateOptions{})
		if err != nil {
			debugAndFailTest(ta, err.Error())
		}
		ta.t.Run("pipelinerun completes successfully", func(t *testing.T) {
			err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, ta.timeout, true, func(ctx context.Context) (done bool, err error) {
				pr, err := tektonClient.TektonV1().PipelineRuns(ta.ns).Get(context.TODO(), ta.run.Name, metav1.GetOptions{})
				if err != nil {
					ta.t.Logf("[%s] get pr %s produced err: %s", time.Now().Format(time.StampMilli), ta.run.Name, err.Error())
					return false, nil
				}
				if !pr.IsDone() {
					if err != nil {
						ta.t.Logf("[%s] problem marshalling in progress pipelinerun to bytes: %s", time.Now().Format(time.StampMilli), err.Error())
						return false, nil
					}
					ta.t.Logf("[%s] in flight pipeline run: %s", time.Now().Format(time.StampMilli), pr.Name)
					return false, nil
				}
				if !pr.GetStatusCondition().GetCondition(apis.ConditionSucceeded).IsTrue() {
					prBytes, err := json.MarshalIndent(pr, "", "  ")
					if err != nil {
						ta.t.Logf("[%s] problem marshalling failed pipelinerun to bytes: %s", time.Now().Format(time.StampMilli), err.Error())
						return false, nil
					}
					debugAndFailTest(ta, fmt.Sprintf("unsuccessful pipeline run: %s", string(prBytes)))
				}
				return true, nil
			})
			if err != nil {
				debugAndFailTest(ta, fmt.Sprintf("failure occured when waiting for the pipeline run to complete: %v", err))
			}
		})
	}

	ta.t.Run("artifactbuilds and dependencybuilds generated", func(t *testing.T) {
		err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, ta.timeout, true, func(ctx context.Context) (done bool, err error) {
			return bothABsAndDBsGenerated(ta)
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for generation of artifactbuilds and dependencybuilds")
		}
	})

	ta.t.Run("all artfactbuilds and dependencybuilds complete", func(t *testing.T) {
		err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, time.Hour, true, func(ctx context.Context) (done bool, err error) {
			abList, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.t.Logf("[%s] error list artifactbuilds: %s", time.Now().Format(time.StampMilli), err.Error())
				return false, err
			}
			//we want to make sure there is more than one ab, and that they are all complete
			abComplete := len(abList.Items) > 0
			ta.t.Logf("[%s] [all-artifact-builds] number of artifactbuilds: %d", time.Now().Format(time.StampMilli), len(abList.Items))
			for _, ab := range abList.Items {
				if ab.Status.State != v1alpha1.ArtifactBuildStateComplete {
					ta.t.Logf("[%s] artifactbuild %s not complete", time.Now().Format(time.StampMilli), ab.Spec.GAV)
					abComplete = false
					break
				}
			}
			dbList, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.t.Logf("[%s] error list dependencybuilds: %s", time.Now().Format(time.StampMilli), err.Error())
				return false, err
			}
			dbComplete := len(dbList.Items) > 0
			ta.t.Logf("[%s] [all-artifactbuild-and-dependencybuilds] number of dependencybuilds: %d", time.Now().Format(time.StampMilli), len(dbList.Items))
			dbCompleteCount := 0
			for _, db := range dbList.Items {
				if db.Status.State == v1alpha1.DependencyBuildStateFailed {
					ta.t.Logf("[%s] dependencybuild %s FAILED", time.Now().Format(time.StampMilli), db.Spec.ScmInfo.SCMURL)
					return false, fmt.Errorf("dependencybuild %s for repo %s FAILED", db.Name, db.Spec.ScmInfo.SCMURL)
				} else if db.Status.State != v1alpha1.DependencyBuildStateComplete {
					if dbComplete {
						//only print the first one
						ta.t.Logf("[%s] dependencybuild %s not complete", time.Now().Format(time.StampMilli), db.Spec.ScmInfo.SCMURL)
					}
					dbComplete = false
				} else if db.Status.State == v1alpha1.DependencyBuildStateComplete {
					dbCompleteCount++
				}
			}
			if abComplete && dbComplete {
				return true, nil
			}
			ta.t.Logf("[%s] completed %d/%d DependencyBuilds", time.Now().Format(time.StampMilli), dbCompleteCount, len(dbList.Items))
			return false, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for some artifactbuilds and dependencybuilds to complete")
		}
	})

	mavenRepoDetails, pf := getMavenRepoDetails(ta)

	ta.t.Run("Maven repo contains artifacts for dependency builds", func(t *testing.T) {
		defer GenerateStatusReport(ta.ns, jvmClient, kubeClient, tektonClient)
		err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, time.Hour, true, func(ctx context.Context) (done bool, err error) {
			dbList, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if len(dbList.Items) == 0 {
				ta.t.Logf("[%s] unable to list dependencybuilds", time.Now().Format(time.StampMilli))
				return false, nil
			}
			if err != nil {
				ta.t.Logf("[%s] error list dependencybuilds: %s", time.Now().Format(time.StampMilli), err.Error())
				return false, err
			}
			return verifyMavenRepoContainsArtifacts(mavenRepoDetails, ta, dbList.Items...)
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for maven repo to contain artifacts for dependencybuilds")
		}
	})

	pf.Stop()

	if len(testSet) > 0 {
		//no futher checks required here
		//we are just checking that the GAVs in question actually build
		return
	}

	ta.t.Run("contaminated build is resolved", func(t *testing.T) {
		//our sample repo has shaded-jdk11 which is contaminated by simple-jdk8
		var contaminated string
		var simpleJDK8 string
		err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, 3*ta.timeout, true, func(ctx context.Context) (done bool, err error) {

			dbContaminated := false
			shadedComplete := false
			contaminantBuild := false
			dbList, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.t.Logf("[%s] error list dependencybuilds: %s", time.Now().Format(time.StampMilli), err.Error())
				return false, err
			}
			ta.t.Logf("[%s] number of dependencybuilds: %d", time.Now().Format(time.StampMilli), len(dbList.Items))
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
		ta.t.Logf("[%s] contaminated dependencybuild: %s", time.Now().Format(time.StampMilli), contaminated)
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for contaminated build to appear")
		}
		//make sure simple-jdk8 was requested as a result
		err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, 2*ta.timeout, true, func(ctx context.Context) (done bool, err error) {
			abList, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.t.Logf("[%s] error list artifactbuilds: %s", time.Now().Format(time.StampMilli), err.Error())
				return false, err
			}
			found := false
			ta.t.Logf("[%s] [contaminated-build] number of artifactbuilds: %d", time.Now().Format(time.StampMilli), len(abList.Items))
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
		err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, 2*ta.timeout, true, func(ctx context.Context) (done bool, err error) {
			ab, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).Get(context.TODO(), simpleJDK8, metav1.GetOptions{})
			if err != nil {
				ta.t.Logf("[%s] error getting simple-jdk8 ArtifactBuild: %s", time.Now().Format(time.StampMilli), err.Error())
				return false, err
			}
			ta.t.Logf("[%s] simple-jdk8 State: %s", time.Now().Format(time.StampMilli), ab.Status.State)
			return ab.Status.State == v1alpha1.ArtifactBuildStateComplete, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for simple-jdk8 to complete")
		}
		//now make sure shaded-jdk11 eventually completes
		err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, 2*ta.timeout, true, func(ctx context.Context) (done bool, err error) {
			db, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).Get(context.TODO(), contaminated, metav1.GetOptions{})
			if err != nil {
				ta.t.Logf("[%s] error getting shaded-jdk11 DependencyBuild: %s", time.Now().Format(time.StampMilli), err.Error())
				return false, err
			}
			ta.t.Logf("[%s] shaded-jdk11 State: %s", time.Now().Format(time.StampMilli), db.Status.State)
			if db.Status.State == v1alpha1.DependencyBuildStateFailed {
				msg := fmt.Sprintf("[%s] contaminated db %s failed, exiting wait", time.Now().Format(time.StampMilli), contaminated)
				ta.t.Log(msg)
				return false, fmt.Errorf("%s", msg)
			}
			return db.Status.State == v1alpha1.DependencyBuildStateComplete, err
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for shaded-jdk11 to complete")
		}
	})

	ta.t.Run("make sure second build access cached dependencies", func(t *testing.T) {
		//first delete all existing PipelineRuns to free up resources
		//mostly for minikube
		runs, lerr := tektonClient.TektonV1().PipelineRuns(ta.ns).List(context.TODO(), metav1.ListOptions{})
		if lerr != nil {
			debugAndFailTest(ta, fmt.Sprintf("error listing runs %s", lerr.Error()))
		}
		for _, r := range runs.Items {
			err := tektonClient.TektonV1().PipelineRuns(ta.ns).Delete(context.TODO(), r.Name, metav1.DeleteOptions{})
			if err != nil {
				debugAndFailTest(ta, fmt.Sprintf("error deleting runs %s", err.Error()))
			}
		}

		ta.run = &tektonpipeline.PipelineRun{}
		obj = streamFileYamlToTektonObj(runYamlPath, ta.run, ta)
		ta.run, ok = obj.(*tektonpipeline.PipelineRun)
		if !ok {
			debugAndFailTest(ta, fmt.Sprintf("file %s did not produce a pipelinerun: %#v", runYamlPath, obj))
		}
		ta.run, err = tektonClient.TektonV1().PipelineRuns(ta.ns).Create(context.TODO(), ta.run, metav1.CreateOptions{})
		if err != nil {
			debugAndFailTest(ta, err.Error())
		}

		ctx := context.TODO()
		watch, werr := tektonClient.TektonV1().PipelineRuns(ta.ns).Watch(ctx, metav1.ListOptions{})
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
				ta.t.Logf("context done")
				exitForLoop = true
				break
			case <-time.After(15 * time.Minute):
				msg := fmt.Sprintf("[%s] timed out waiting for second build to complete", time.Now().Format(time.StampMilli))
				ta.t.Log(msg)
				// call stop here in case the defer is bypass by a call to t.Fatal
				watch.Stop()
				debugAndFailTest(ta, msg)
			case event := <-watch.ResultChan():
				if event.Object == nil {
					continue
				}
				pr, ok := event.Object.(*tektonpipeline.PipelineRun)
				if !ok {
					continue
				}
				if pr.Name != ta.run.Name {
					if pr.IsDone() {
						ta.t.Logf("[%s] got event for pipelinerun %s in a terminal state", time.Now().Format(time.StampMilli), pr.Name)
						continue
					}
					debugAndFailTest(ta, fmt.Sprintf("another non-completed pipeline run %s was generated when it should not", pr.Name))
				}
				ta.t.Logf("[%s] done processing event for pr %s", time.Now().Format(time.StampMilli), pr.Name)
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
							ta.t.Logf("[%s] error getting pod logs for container %s: %s", time.Now().Format(time.StampMilli), container.Name, err.Error())
							continue
						}
						b, err := io.ReadAll(readCloser)
						if err != nil {
							ta.t.Logf("[%s] error reading pod stream %s", time.Now().Format(time.StampMilli), err.Error())
							continue
						}
						cLog := string(b)
						if strings.Contains(cLog, "\"publisher\" : \"central\"") {
							debugAndFailTest(ta, fmt.Sprintf("pipelinerun %s has container %s with dep analysis still pointing to central %s", pr.Name, container.Name, cLog))
						}
						if !strings.Contains(cLog, "\"publisher\" : \"rebuilt\"") {
							debugAndFailTest(ta, fmt.Sprintf("pipelinerun %s has container %s with dep analysis that does not access rebuilt %s", pr.Name, container.Name, cLog))
						}
						if !strings.Contains(cLog, "\"java:scm-uri\" : \"https://github.com/stuartwdouglas/hacbs-test-simple-jdk8.git\"") {
							debugAndFailTest(ta, fmt.Sprintf("pipelinerun %s has container %s with dep analysis did not include java:scm-uri %s", pr.Name, container.Name, cLog))
						}
						if !strings.Contains(cLog, "\"java:scm-commit\" : \"") {
							debugAndFailTest(ta, fmt.Sprintf("pipelinerun %s has container %s with dep analysis did not include java:scm-commit %s", pr.Name, container.Name, cLog))
						}
						break
					}
					ta.t.Logf("[%s] pr %s is done and has correct analyse-dependencies output, exiting", time.Now().Format(time.StampMilli), pr.Name)
					exitForLoop = true
					break
				}
			}
			if exitForLoop {
				break
			}

		}
	})
	ta.t.Run("Correct JDK identified for JDK11 build", func(t *testing.T) {
		//test that we don't attempt to use JDK8 on a JDK11+ project
		err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, 2*ta.timeout, true, func(ctx context.Context) (done bool, err error) {

			dbList, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
			if err != nil {
				ta.t.Logf("[%s] error list dependencybuilds: %s", time.Now().Format(time.StampMilli), err.Error())
				return false, err
			}
			ta.t.Logf("[%s] [correct-jdk-identified] number of dependencybuilds: %d", time.Now().Format(time.StampMilli), len(dbList.Items))
			for _, db := range dbList.Items {
				if !strings.Contains(db.Spec.ScmInfo.SCMURL, "shaded-jdk11") ||
					db.Status.State == "" ||
					db.Status.State == v1alpha1.DependencyBuildStateNew ||
					db.Status.State == v1alpha1.DependencyBuildStateAnalyzeBuild {
					continue
				}
				jdk7 := false
				jdk8 := false
				jdk11 := false
				jdk17 := false
				for _, i := range db.Status.PotentialBuildRecipes {
					jdk7 = jdk7 || i.JavaVersion == "7"
					jdk8 = jdk8 || i.JavaVersion == "8"
					jdk11 = jdk11 || i.JavaVersion == "11"
					jdk17 = jdk17 || i.JavaVersion == "17"
				}
				for _, i := range db.Status.BuildAttempts {
					jdk7 = jdk7 || i.Recipe.JavaVersion == "7"
					jdk8 = jdk8 || i.Recipe.JavaVersion == "8"
					jdk11 = jdk11 || i.Recipe.JavaVersion == "11"
					jdk17 = jdk17 || i.Recipe.JavaVersion == "17"
				}

				if jdk7 {
					return false, fmt.Errorf("build should not have been attempted with jdk7")
				}
				if jdk8 {
					return false, fmt.Errorf("build should not have been attempted with jdk8")
				}
				if jdk17 {
					return false, fmt.Errorf("build should not have been attempted with jdk17")
				}
				if !jdk11 {
					return false, fmt.Errorf("build should have been attempted with jdk11")
				}
				return true, nil

			}
			return false, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for contaminated build to appear")
		}
	})
}

func runDbTests(path string, testSet string, ta *testArgs) {
	parts := readTestData(path, testSet, "minikube-cfgmap.yaml", ta)
	for _, s := range parts {
		depBuildBytes, err := os.ReadFile(filepath.Clean(filepath.Join(path, s+"-dependencybuild.yaml")))
		if err != nil {
			debugAndFailTest(ta, fmt.Sprintf("unable to read dependencybuild for %s test: %s", s, err.Error()))
			return
		}
		db := v1alpha1.DependencyBuild{}
		err = yaml.Unmarshal(depBuildBytes, &db)
		if err != nil {
			debugAndFailTest(ta, fmt.Sprintf("unable to unmarshal dependencybuild for %s test: %s", s, err.Error()))
			return
		}
		buildRecipeBytes, err := os.ReadFile(filepath.Clean(filepath.Join(path, s+"-buildrecipe.yaml")))
		if err != nil {
			debugAndFailTest(ta, fmt.Sprintf("unable to read buildrecipe for %s test: %s", s, err.Error()))
			return
		}
		buildRecipe := v1alpha1.BuildRecipe{}
		err = yaml.Unmarshal(buildRecipeBytes, &buildRecipe)
		if err != nil {
			debugAndFailTest(ta, fmt.Sprintf("unable to unmarshal buildrecipe for %s test: %s", s, err.Error()))
			return
		}
		db.Namespace = ta.ns
		db.Name = util.HashString(db.Spec.ScmInfo.SCMURL + db.Spec.ScmInfo.Tag + db.Spec.ScmInfo.Path)
		db.Spec.BuildRecipeConfigMap = db.Name + "configmap"
		cfgMap := corev1.ConfigMap{}
		cfgMap.Name = db.Spec.BuildRecipeConfigMap
		cfgMap.Namespace = ta.ns
		cfgMap.Data = map[string]string{"build.yaml": string(buildRecipeBytes)}
		_, err = kubeClient.CoreV1().ConfigMaps(ta.ns).Create(context.TODO(), &cfgMap, metav1.CreateOptions{})
		if err != nil {
			debugAndFailTest(ta, fmt.Sprintf("unable to create configmap %s for dependencybuild repo %s: %s", cfgMap.Name, db.Spec.ScmInfo.SCMURL, err.Error()))
			return
		}
		err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, ta.timeout, true, func(ctx context.Context) (done bool, err error) {
			retrievedCfgMap, err := kubeClient.CoreV1().ConfigMaps(ta.ns).Get(context.TODO(), cfgMap.Name, metav1.GetOptions{})
			if retrievedCfgMap != nil {
				ta.t.Logf("[%s] successfully retrieved configmap %s for dependencybuild repo %s", time.Now().Format(time.StampMilli), cfgMap.Name, db.Spec.ScmInfo.SCMURL)
				return true, nil
			}
			if err != nil {
				ta.t.Logf("[%s] error retrieving configmap %s for dependencybuild repo %s: %s", time.Now().Format(time.StampMilli), cfgMap.Name, db.Spec.ScmInfo.SCMURL, err.Error())
			}
			return false, nil
		})
		if err != nil {
			debugAndFailTest(ta, fmt.Sprintf("timed out waiting for creation of configmap %s for dependencybuild repo %s", cfgMap.Name, db.Spec.ScmInfo.SCMURL))
			return
		}
		_, err = jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).Create(context.TODO(), &db, metav1.CreateOptions{})
		if err != nil {
			debugAndFailTest(ta, fmt.Sprintf("unable to create dependencybuild %s for repo %s: %s", db.Name, db.Spec.ScmInfo.SCMURL, err.Error()))
			return
		}
		err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, ta.timeout, true, func(ctx context.Context) (done bool, err error) {
			retrievedDb, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).Get(context.TODO(), db.Name, metav1.GetOptions{})
			if retrievedDb != nil {
				ta.t.Logf("[%s] successfully retrieved dependencybuild %s for repo %s", time.Now().Format(time.StampMilli), retrievedDb.Name, retrievedDb.Spec.ScmInfo.SCMURL)
				return true, nil
			}
			if err != nil {
				ta.t.Logf("[%s] error retrieving dependencybuild %s for repo %s: %s", time.Now().Format(time.StampMilli), db.Name, db.Spec.ScmInfo.SCMURL, err.Error())
			}
			return false, nil
		})
		if err != nil {
			debugAndFailTest(ta, fmt.Sprintf("timed out waiting for creation of dependencybuild %s for repo %s", db.Name, db.Spec.ScmInfo.SCMURL))
			return
		}

		ta.t.Run(fmt.Sprintf("dependencybuild complete for %s", s), func(t *testing.T) {
			err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, time.Hour, true, func(ctx context.Context) (done bool, err error) {
				retrievedDb, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).Get(context.TODO(), db.Name, metav1.GetOptions{})
				if retrievedDb != nil {
					ta.t.Logf("[%s] successfully retrieved dependencybuild %s for repo %s", time.Now().Format(time.StampMilli), retrievedDb.Name, retrievedDb.Spec.ScmInfo.SCMURL)
				}
				if err != nil {
					ta.t.Logf("[%s] error retrieving dependencybuild %s for repo %s: %s", time.Now().Format(time.StampMilli), db.Name, db.Spec.ScmInfo.SCMURL, err.Error())
					return false, err
				}
				dbComplete := true
				if retrievedDb.Status.State == v1alpha1.DependencyBuildStateFailed {
					ta.t.Logf("[%s] dependencybuild %s for repo %s FAILED", time.Now().Format(time.StampMilli), retrievedDb.Name, retrievedDb.Spec.ScmInfo.SCMURL)
					return false, fmt.Errorf("dependencybuild %s for repo %s FAILED", retrievedDb.Name, retrievedDb.Spec.ScmInfo.SCMURL)
				} else if retrievedDb.Status.State != v1alpha1.DependencyBuildStateComplete {
					ta.t.Logf("[%s] dependencybuild %s for repo %s not complete", time.Now().Format(time.StampMilli), retrievedDb.Name, retrievedDb.Spec.ScmInfo.SCMURL)
					dbComplete = false
				}
				return dbComplete, nil
			})
			if err != nil {
				debugAndFailTest(ta, fmt.Sprintf("timed out waiting for dependencybuild %s for repo %s to complete", db.Name, db.Spec.ScmInfo.SCMURL))
			}
		})

		ta.t.Run(fmt.Sprintf("dependencybuild for %s contains buildrecipe", s), func(t *testing.T) {
			err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, time.Hour, true, func(ctx context.Context) (done bool, err error) {
				retrievedDb, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).Get(context.TODO(), db.Name, metav1.GetOptions{})
				if retrievedDb != nil {
					ta.t.Logf("[%s] successfully retrieved dependencybuild %s for repo %s", time.Now().Format(time.StampMilli), retrievedDb.Name, retrievedDb.Spec.ScmInfo.SCMURL)
				}
				if err != nil {
					ta.t.Logf("[%s] error retrieving dependencybuild %s for repo %s: %s", time.Now().Format(time.StampMilli), db.Name, db.Spec.ScmInfo.SCMURL, err.Error())
					return false, err
				}
				buildRecipeValue := reflect.ValueOf(buildRecipe)
				fieldsWithValues := []string{}
				for i := 0; i < buildRecipeValue.NumField(); i++ {
					field := buildRecipeValue.Field(i)
					fieldName := buildRecipeValue.Type().Field(i).Name
					// append field if it has a value in the build recipe
					if ((field.Kind() == reflect.Slice || field.Kind() == reflect.Map) && field.Len() > 0) || !reflect.DeepEqual(field.Interface(), reflect.Zero(field.Type()).Interface()) {
						fieldsWithValues = append(fieldsWithValues, fieldName)
					}
				}
				containsRecipe := len(fieldsWithValues) > 1
				for _, ba := range retrievedDb.Status.BuildAttempts {
					baBuildRecipeValue := reflect.ValueOf(*ba.Recipe)
					for _, fieldName := range fieldsWithValues {
						buildRecipeField := buildRecipeValue.FieldByName(fieldName)
						baBuildRecipeField := baBuildRecipeValue.FieldByName(fieldName)
						// all field values in build recipe must be present and identical in the corresponding build attempt recipe
						if !reflect.DeepEqual(buildRecipeField.Interface(), baBuildRecipeField.Interface()) {
							containsRecipe = false
							break
						}
					}
				}
				return containsRecipe, nil
			})
			if err != nil {
				debugAndFailTest(ta, fmt.Sprintf("timed out waiting for dependencybuild %s for repo %s to be retrieved", db.Name, db.Spec.ScmInfo.SCMURL))
			}
		})

		mavenRepoDetails, pf := getMavenRepoDetails(ta)

		ta.t.Run(fmt.Sprintf("maven repo contains artifacts for %s", s), func(t *testing.T) {
			defer GenerateStatusReport(ta.ns, jvmClient, kubeClient, tektonClient)
			err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, time.Hour, true, func(ctx context.Context) (done bool, err error) {
				retrievedDb, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).Get(context.TODO(), db.Name, metav1.GetOptions{})
				if retrievedDb != nil {
					ta.t.Logf("[%s] successfully retrieved dependencybuild %s for repo %s", time.Now().Format(time.StampMilli), retrievedDb.Name, retrievedDb.Spec.ScmInfo.SCMURL)
				}
				if err != nil {
					ta.t.Logf("[%s] error retrieving dependencybuild %s for repo %s: %s", time.Now().Format(time.StampMilli), db.Name, db.Spec.ScmInfo.SCMURL, err.Error())
					return false, err
				}
				return verifyMavenRepoContainsArtifacts(mavenRepoDetails, ta, *retrievedDb)
			})
			if err != nil {
				debugAndFailTest(ta, fmt.Sprintf("timed out waiting for maven repo to contain artifacts for dependencybuild %s for repo %s", db.Name, db.Spec.ScmInfo.SCMURL))
			}
		})

		pf.Stop()

		ta.t.Run(fmt.Sprintf("buildrecipe is deleted with dependencybuild for %s", s), func(t *testing.T) {
			// can't generate status report here because we delete dependency build
			err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, time.Hour, true, func(ctx context.Context) (done bool, err error) {
				err = jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).Delete(context.TODO(), db.Name, metav1.DeleteOptions{})
				if err != nil {
					ta.t.Logf("[%s] error deleting dependencybuild %s for repo %s: %s", time.Now().Format(time.StampMilli), db.Name, db.Spec.ScmInfo.SCMURL, err.Error())
					return false, err
				}
				retrievedDb, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).Get(context.TODO(), db.Name, metav1.GetOptions{})
				if err != nil {
					if errors.IsNotFound(err) {
						ta.t.Logf("[%s] successfully deleted dependencybuild %s for repo %s", time.Now().Format(time.StampMilli), db.Name, db.Spec.ScmInfo.SCMURL)
					} else {
						ta.t.Logf("[%s] error retrieving dependencybuild %s for repo %s: %s", time.Now().Format(time.StampMilli), db.Name, db.Spec.ScmInfo.SCMURL, err.Error())
						return false, err
					}
				} else if retrievedDb != nil {
					ta.t.Logf("[%s] failed to delete dependencybuild %s for repo %s", time.Now().Format(time.StampMilli), retrievedDb.Name, retrievedDb.Spec.ScmInfo.SCMURL)
				}
				retrievedCfgMap, err := kubeClient.CoreV1().ConfigMaps(ta.ns).Get(context.TODO(), cfgMap.Name, metav1.GetOptions{})
				if err != nil {
					if errors.IsNotFound(err) {
						ta.t.Logf("[%s] successfully deleted configmap %s for dependencybuild repo %s", time.Now().Format(time.StampMilli), cfgMap.Name, db.Spec.ScmInfo.SCMURL)
						return true, nil
					} else {
						ta.t.Logf("[%s] error retrieving configmap %s for dependencybuild repo %s: %s", time.Now().Format(time.StampMilli), cfgMap.Name, db.Spec.ScmInfo.SCMURL, err.Error())
						return false, err
					}
				} else if retrievedCfgMap != nil {
					ta.t.Logf("[%s] failed to delete configmap %s for dependencybuild repo %s", time.Now().Format(time.StampMilli), retrievedCfgMap.Name, retrievedDb.Spec.ScmInfo.SCMURL)
				}
				return false, nil
			})
			if err != nil {
				debugAndFailTest(ta, fmt.Sprintf("timed out waiting for deletion of configmap %s for dependencybuild repo %s", db.Name, db.Spec.ScmInfo.SCMURL))
			}
		})
	}
}

func verifyMavenRepoContainsArtifacts(mavenRepoDetails *MavenRepoDetails, ta *testArgs, dbs ...v1alpha1.DependencyBuild) (bool, error) {
	for _, db := range dbs {
		gavs := []string{}
		for _, ba := range db.Status.BuildAttempts {
			if ba.Build.Results != nil {
				gavs = append(gavs, ba.Build.Results.Gavs...)
			}
		}
		if len(gavs) == 0 {
			ta.t.Logf("[%s] gavs do not exist for dependencybuild %s for repo %s", time.Now().Format(time.StampMilli), db.Name, db.Spec.ScmInfo.SCMURL)
			return false, nil
		} else {
			for _, gav := range gavs {
				body, err := downloadArtifact(mavenRepoDetails, gav, "pom")
				if err != nil {
					ta.t.Logf("[%s] error downloading artifact pom %s for dependencybuild %s for repo %s: %s", time.Now().Format(time.StampMilli), gav, db.Name, db.Spec.ScmInfo.SCMURL, err.Error())
					return false, err
				}
				if len(body) > 0 {
					ta.t.Logf("[%s] successfully downloaded artifact pom %s for dependencybuild %s for repo %s", time.Now().Format(time.StampMilli), gav, db.Name, db.Spec.ScmInfo.SCMURL)
				} else {
					ta.t.Logf("[%s] failed to download artifact pom %s for dependencybuild %s for repo %s", time.Now().Format(time.StampMilli), gav, db.Name, db.Spec.ScmInfo.SCMURL)
					return false, nil
				}
			}
		}
	}
	return true, nil
}

func downloadArtifact(mavenRepoDetails *MavenRepoDetails, gav string, ext string) ([]byte, error) {
	split := strings.Split(gav, ":")
	group := split[0]
	artifact := split[1]
	version := split[2]
	req, err := http.NewRequest(http.MethodGet, mavenRepoDetails.Url+"/"+strings.ReplaceAll(group, ".", "/")+"/"+artifact+"/"+version+"/"+artifact+"-"+version+"."+ext, nil)
	if err != nil {
		return nil, err
	}
	req.SetBasicAuth(mavenRepoDetails.Username, mavenRepoDetails.Password)
	req.Header.Add("Content-Type", "application/json")
	req.Close = true
	httpClient := http.Client{}
	resp, err := httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("unable to download artifact %s: %s", gav, resp.Status)
	}
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, err
	}
	return body, nil
}

func getMavenRepoDetails(ta *testArgs) (*MavenRepoDetails, *portforward.PortForward) {
	pf := portforward.PortForward{
		Config:          kubeConfig,
		Clientset:       kubeClient,
		Labels:          metav1.LabelSelector{MatchLabels: map[string]string{"app": v1alpha1.RepoDeploymentName}},
		DestinationPort: 8080,
		Namespace:       ta.ns,
	}
	localPort, err := pf.Start(context.TODO())
	if err != nil {
		debugAndFailTest(ta, fmt.Sprintf("unable to port forward maven repo %s", err.Error()))
		return nil, nil
	}
	mavenUsername := os.Getenv("MAVEN_USERNAME")
	mavenRepository := os.Getenv("MAVEN_REPOSITORY")
	mavenRepository = strings.ReplaceAll(mavenRepository, "http://jvm-build-maven-repo."+ta.ns+".svc.cluster.local", fmt.Sprintf("http://127.0.0.1:%d", localPort))
	mavenPassword := os.Getenv("MAVEN_PASSWORD")
	ta.t.Logf("retrieved maven repository %#v\n", mavenRepository)
	return &MavenRepoDetails{Username: mavenUsername, Url: mavenRepository, Password: mavenPassword}, &pf
}

func watchEvents(eventClient v1.EventInterface, ta *testArgs) {
	ctx := context.TODO()
	watch, err := eventClient.Watch(ctx, metav1.ListOptions{})
	if err != nil {
		panic(err)
	}
	for {
		res := <-watch.ResultChan()
		if res.Object == nil {
			continue
		}
		event, ok := res.Object.(*v12.Event)
		if !ok {
			continue
		}
		if event.Type == corev1.EventTypeNormal {
			continue
		}
		ta.t.Logf("[%s] non-normal event reason %s about obj %s:%s message %s", time.Now().Format(time.StampMilli), event.Reason, event.Regarding.Kind, event.Regarding.Name, event.Note)
	}
}

func readTestData(path string, testSet string, fileName string, ta *testArgs) []string {
	bytes, err := os.ReadFile(filepath.Clean(filepath.Join(path, fileName)))
	if err != nil {
		debugAndFailTest(ta, err.Error())
		return nil
	}
	testData := map[string][]string{}
	err = yaml.Unmarshal(bytes, &testData)
	if err != nil {
		debugAndFailTest(ta, err.Error())
		return nil
	}

	parts := testData[testSet]
	if len(parts) == 0 {
		debugAndFailTest(ta, "No test data for "+testSet)
		return nil
	}
	return parts
}
