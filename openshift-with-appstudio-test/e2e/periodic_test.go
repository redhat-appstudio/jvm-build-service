//go:build periodic
// +build periodic

package e2e

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	quotav1 "github.com/openshift/api/quota/v1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/wait"
	"knative.dev/pkg/apis"
)

func TestServiceRegistry(t *testing.T) {
	ta := setup(t, nil)

	// set up quota to enable throttling
	quota := &quotav1.ClusterResourceQuota{
		ObjectMeta: metav1.ObjectMeta{
			Name: fmt.Sprintf("for-%s-deployments", ta.ns),
		},
		Spec: quotav1.ClusterResourceQuotaSpec{
			Quota: corev1.ResourceQuotaSpec{
				Hard: corev1.ResourceList{
					corev1.ResourceName("count/pods"): resource.MustParse("50"),
				},
			},
			Selector: quotav1.ClusterResourceQuotaSelector{
				AnnotationSelector: map[string]string{
					"openshift.io/requester": ta.ns,
				},
			},
		},
	}
	_, err := qutoaClient.QuotaV1().ClusterResourceQuotas().Create(context.TODO(), quota, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

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
				return false, nil
			}
			if !pr.GetStatusCondition().GetCondition(apis.ConditionSucceeded).IsTrue() {
				prBytes, err := json.MarshalIndent(pr, "", "  ")
				if err != nil {
					ta.Logf(fmt.Sprintf("problem marshalling failed pipelinerun to bytes: %s", err.Error()))
					return false, nil
				}
				debugAndFailTest(ta, fmt.Sprintf("unsuccessful pipeline run: %s", string(prBytes)))
			}
			return true, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out when waiting for the pipeline run to complete")
		}
	})

	ta.t.Run("current target of artifactbuilds/dependencybuilds complete", func(t *testing.T) {
		defer dumpBadEvents(ta)
		defer dumpPods(ta, "jvm-build-service")
		defer dumpPodsGlob(ta, ta.ns, "localstack")
		defer dumpPodsGlob(ta, ta.ns, "artifact-cache")
		// ab pods I do not think get pruned, let's see, along with this many defers
		defer abDumpForState(ta, v1alpha1.ArtifactBuildStateFailed)
		defer abDumpForState(ta, v1alpha1.ArtifactBuildStateMissing)

		ctx := context.TODO()
		//abWatch, awerr := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).Watch(ctx, metav1.ListOptions{})
		//if awerr != nil {
		//	debugAndFailTest(ta, fmt.Sprintf("error creating abWatch: %s", awerr.Error()))
		//}
		dbWatch, dwerr := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).Watch(ctx, metav1.ListOptions{})
		if dwerr != nil {
			debugAndFailTest(ta, fmt.Sprintf("error creating dbWatch: %s", dwerr.Error()))
		}

		//var abCompleteCount uint32
		//var abFailedCount uint32
		//var abMissingCount uint32
		//var createdAB int32
		var dbCompleteCount uint32
		var dbFailedCount uint32
		var dbContaminatedCount uint32
		var createdDB int32
		state := sync.Map{}
		var changed uint32
		exitForLoop := false

		for {
			select {
			case <-ctx.Done():
				//abWatch.Stop()
				dbWatch.Stop()
				ta.Logf("context done")
				exitForLoop = true
				break
			case <-time.After(2 * time.Hour):
				//abWatch.Stop()
				dbWatch.Stop()
				ta.Logf("timed out waiting for dependencybuilds to reach steady state")
				exitForLoop = true
				break

				//case event := <-abWatch.ResultChan():
				//	if event.Object == nil {
				//		continue
				//	}
				//	ab, ok := event.Object.(*v1alpha1.ArtifactBuild)
				//	if !ok {
				//		continue
				//	}
				//	switch {
				//	case ab.Status.State == v1alpha1.ArtifactBuildStateComplete:
				//		s, k := state.Load(ab.Name)
				//		if !k || s != v1alpha1.ArtifactBuildStateComplete {
				//			atomic.AddUint32(&abCompleteCount, 1)
				//			atomic.StoreUint32(&changed, 1)
				//			state.Store(ab.Name, v1alpha1.ArtifactBuildStateComplete)
				//		}
				//	case ab.Status.State == v1alpha1.ArtifactBuildStateMissing:
				//		s, k := state.Load(ab.Name)
				//		if !k || s != v1alpha1.ArtifactBuildStateMissing {
				//			atomic.AddUint32(&abMissingCount, 1)
				//			atomic.StoreUint32(&changed, 1)
				//			state.Store(ab.Name, v1alpha1.ArtifactBuildStateMissing)
				//		}
				//	case ab.Status.State == v1alpha1.ArtifactBuildStateFailed:
				//		s, k := state.Load(ab.Name)
				//		if !k || s != v1alpha1.ArtifactBuildStateFailed {
				//			atomic.AddUint32(&abFailedCount, 1)
				//			atomic.StoreUint32(&changed, 1)
				//			state.Store(ab.Name, v1alpha1.ArtifactBuildStateFailed)
				//			dumpABPods(ta, ab.Name, ab.Spec.GAV)
				//		}
				//	default:
				//		s, k := state.Load(ab.Name)
				//		if k {
				//			switch {
				//			case s == v1alpha1.ArtifactBuildStateMissing:
				//				// decrement
				//				atomic.AddUint32(&abMissingCount, ^uint32(0))
				//				atomic.StoreUint32(&changed, 1)
				//				// reset since must be rebuild
				//				state.Store(ab.Name, ab.Status.State)
				//			case s == v1alpha1.ArtifactBuildStateComplete:
				//				//decrement
				//				atomic.AddUint32(&abCompleteCount, ^uint32(0))
				//				atomic.StoreUint32(&changed, 1)
				//			case s == v1alpha1.ArtifactBuildStateFailed:
				//				//decrement
				//				atomic.AddUint32(&abFailedCount, ^uint32(0))
				//				atomic.StoreUint32(&changed, 1)
				//				// reset since must be rebuild
				//				state.Store(ab.Name, ab.Status.State)
				//			}
				//		} else {
				//			atomic.AddInt32(&createdAB, 1)
				//			atomic.StoreUint32(&changed, 1)
				//			state.Store(ab.Name, ab.Status.State)
				//		}
				//	}
				//
				//	dbg := false
				//	if atomic.CompareAndSwapUint32(&changed, 1, 0) {
				//		ta.Logf(fmt.Sprintf("artifactbuild created count: %d complete count: %d, failed count: %d, missing count: %d", createdAB, abCompleteCount, abFailedCount, abMissingCount))
				//		dbg = true
				//
				//	}
				//
				//	if createdAB > 200 && !activePipelineRuns(ta, dbg) {
				//		ta.Logf(fmt.Sprintf("artifactbuild FINAL created count: %d complete count: %d, failed count: %d, missing count: %d", createdAB, abCompleteCount, abFailedCount, abMissingCount))
				//		exitForLoop = true
				//		abWatch.Stop()
				//		dbWatch.Stop()
				//		break
				//	}

			case event := <-dbWatch.ResultChan():
				if event.Object == nil {
					continue
				}
				db, ok := event.Object.(*v1alpha1.DependencyBuild)
				if !ok {
					continue
				}
				switch {
				case db.Status.State == v1alpha1.DependencyBuildStateComplete:
					s, k := state.Load(db.Name)
					if !k || s != v1alpha1.DependencyBuildStateComplete {
						atomic.AddUint32(&dbCompleteCount, 1)
						atomic.StoreUint32(&changed, 1)
						state.Store(db.Name, v1alpha1.DependencyBuildStateComplete)
					}
				case db.Status.State == v1alpha1.DependencyBuildStateFailed:
					s, k := state.Load(db.Name)
					if !k || s != v1alpha1.DependencyBuildStateFailed {
						atomic.AddUint32(&dbFailedCount, 1)
						atomic.StoreUint32(&changed, 1)
						state.Store(db.Name, v1alpha1.DependencyBuildStateFailed)
						dumpDBPods(ta, db)
					}
				case db.Status.State == v1alpha1.DependencyBuildStateContaminated:
					s, k := state.Load(db.Name)
					if !k || s != v1alpha1.DependencyBuildStateContaminated {
						atomic.AddUint32(&dbContaminatedCount, 1)
						atomic.StoreUint32(&changed, 1)
						state.Store(db.Name, v1alpha1.DependencyBuildStateContaminated)
						dumpDBPods(ta, db)
					}
				default:
					s, k := state.Load(db.Name)
					if k {
						switch {
						case s == v1alpha1.DependencyBuildStateContaminated:
							// decrement
							atomic.AddUint32(&dbContaminatedCount, ^uint32(0))
							atomic.StoreUint32(&changed, 1)
							// reset since must be rebuild
							state.Store(db.Name, db.Status.State)
						case s == v1alpha1.DependencyBuildStateComplete:
							//decrement
							atomic.AddUint32(&dbCompleteCount, ^uint32(0))
							atomic.StoreUint32(&changed, 1)
						case s == v1alpha1.DependencyBuildStateFailed:
							//decrement
							atomic.AddUint32(&dbFailedCount, ^uint32(0))
							atomic.StoreUint32(&changed, 1)
							// reset since must be rebuild
							state.Store(db.Name, db.Status.State)
						}
					} else {
						atomic.AddInt32(&createdDB, 1)
						atomic.StoreUint32(&changed, 1)
						state.Store(db.Name, db.Status.State)
					}
				}

				dbg := false
				if atomic.CompareAndSwapUint32(&changed, 1, 0) {
					ta.Logf(fmt.Sprintf("dependencybuild created count: %d complete count: %d, failed count: %d, contaminated count: %d", createdDB, dbCompleteCount, dbFailedCount, dbContaminatedCount))
					dbg = true

				}

				if createdDB > 90 && !activePipelineRuns(ta, dbg) {
					ta.Logf(fmt.Sprintf("dependencybuild FINAL created count: %d complete count: %d, failed count: %d, contaminated count: %d", createdDB, dbCompleteCount, dbFailedCount, dbContaminatedCount))
					exitForLoop = true
					dbWatch.Stop()
					//abWatch.Stop()
					break
				}
			}
			if exitForLoop {
				break
			}
		}
		_ = GenerateStatusReport(ta.ns, jvmClient, kubeClient)
	})

}
