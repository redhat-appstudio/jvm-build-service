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

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/util/wait"
	"knative.dev/pkg/apis"
)

func runTests(t *testing.T, namespace string, runYaml string) {
	ta := setup(t, namespace)

	countQuota := &corev1.ResourceQuota{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "object-counts",
			Namespace: ta.ns,
		},
		Spec: corev1.ResourceQuotaSpec{
			Hard: corev1.ResourceList{
				corev1.ResourcePods: resource.MustParse("50"),
			},
		},
	}
	_, err := kubeClient.CoreV1().ResourceQuotas(ta.ns).Create(context.TODO(), countQuota, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	path, err := os.Getwd()
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	ta.Logf(fmt.Sprintf("current working dir: %s", path))

	runYamlPath := filepath.Join(path, "..", "..", "hack", "examples", runYaml)
	ta.run = &v1beta1.PipelineRun{}
	obj := streamFileYamlToTektonObj(runYamlPath, ta.run, ta)
	var ok bool
	ta.run, ok = obj.(*v1beta1.PipelineRun)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("file %s did not produce a pipelinerun: %#v", runYamlPath, obj))
	}
	ta.run, err = tektonClient.TektonV1beta1().PipelineRuns(ta.ns).Create(context.TODO(), ta.run, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	ta.t.Run("pipelinerun completes successfully", func(t *testing.T) {
		err = wait.PollImmediate(3*ta.interval, 3*ta.timeout, func() (done bool, err error) {
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

	didNotCompleteInTime := false
	ta.t.Run("current target of artifactbuilds/dependencybuilds complete", func(t *testing.T) {
		defer GenerateStatusReport(ta.ns, jvmClient, kubeClient, tektonClient)
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
		var dbBuildingCount uint32
		var createdDB int32
		stateMap := map[string]string{}
		stateMutex := sync.Mutex{}
		exitForLoop := false
		timeoutChannel := time.After(2 * time.Hour)

		stable := map[string]bool{v1alpha1.DependencyBuildStateComplete: true, v1alpha1.DependencyBuildStateFailed: true, v1alpha1.DependencyBuildStateContaminated: true}

		for {
			select {
			case <-ctx.Done():
				//abWatch.Stop()
				dbWatch.Stop()
				ta.Logf("context done")
				exitForLoop = true
				break
			case <-timeoutChannel:
				//abWatch.Stop()
				dbWatch.Stop()
				ta.Logf("timed out waiting for dependencybuilds to reach steady state")
				ta.Logf(fmt.Sprintf("dependencybuild FINAL created count: %d complete count: %d, failed count: %d, contaminated count: %d", createdDB, dbCompleteCount, dbFailedCount, dbContaminatedCount))

				dbList, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).List(ctx, metav1.ListOptions{})
				if err != nil {
					ta.Logf("Failed to generate diagnostics")

				} else {
					stateMutex.Lock()
					for _, db := range dbList.Items {
						s, k := stateMap[db.Name]
						stableState := stable[db.Status.State] //if the DB is currently in a stable state
						var oldStableState bool
						if k {
							oldStableState = stable[s] //if the DB used to be in a stable state
						}
						if stableState != oldStableState {
							ta.Logf(fmt.Sprintf("DependencyBuild %s has incorrect state", db.Name))
						}
					}
					stateMutex.Unlock()
				}

				exitForLoop = true
				didNotCompleteInTime = true
				break
			case event := <-dbWatch.ResultChan():
				if event.Object == nil {
					continue
				}
				db, ok := event.Object.(*v1alpha1.DependencyBuild)
				if !ok {
					continue
				}
				stateMutex.Lock()
				//so there is the possibility of a race here
				//if a DB has quick state changes we can get two concurrent goroutines for the same DependencyBuild
				//if they are processed out of order then we could essentually miss the build going into its final state
				//to get around this we use a Mutex, and query the current resource version. If this matches then we know
				//the version we are using is up to date, and update the state map accordingly
				//note that we don't need the mutex for the add/delete code below, as even if these do race the end
				//net result will be correct as the state changes themselves will be processed in the correct order
				s, k := stateMap[db.Name]
				current, err := jvmClient.JvmbuildserviceV1alpha1().DependencyBuilds(ta.ns).Get(ctx, db.Name, metav1.GetOptions{})
				if err != nil {
					ta.Logf("Failed to get latest DependencyBuild version" + err.Error())
					stateMutex.Unlock()
					break
				}
				if current.ResourceVersion != db.ResourceVersion {
					ta.Logf(fmt.Sprintf("Skipping watch event as DependencyBuild %s was not the most recent version", db.Name))
					stateMutex.Unlock()
					break
				}

				stableState := stable[db.Status.State] //if the DB is currently in a stable state
				var oldStableState bool
				if k {
					oldStableState = stable[s] //if the DB used to be in a stable state
				}
				stateMap[db.Name] = db.Status.State
				stateMutex.Unlock()

				//if this is the first time we have seen it increment the created count
				if !k {
					//if this is new increase the created count
					atomic.AddInt32(&createdDB, 1)
					if !stableState {
						//if we are unstable set it to building
						//in theory we could see an already completed one if the timing is weird
						atomic.AddUint32(&dbBuildingCount, 1)
					}
				} else {
					//if the state has changed to/from a stable or unstable state modify the building count
					if stableState != oldStableState {
						if stableState {
							atomic.AddUint32(&dbBuildingCount, ^uint32(0))
						} else {
							atomic.AddUint32(&dbBuildingCount, 1)
						}
					}
				}
				//if the state has changed, or there is a new DB we need to check the current state
				changed := false
				if s != db.Status.State || !k {
					changed = true //mark as changed to it will be reported on, as this was a change

					//if it entered a stable state we increment the counter for that state
					switch db.Status.State {
					case v1alpha1.DependencyBuildStateComplete:
						atomic.AddUint32(&dbCompleteCount, 1)
					case v1alpha1.DependencyBuildStateFailed:
						atomic.AddUint32(&dbFailedCount, 1)
					case v1alpha1.DependencyBuildStateContaminated:
						atomic.AddUint32(&dbContaminatedCount, 1)
					}
					//if it left a stable state we increment the counter for that state
					switch s {
					case v1alpha1.DependencyBuildStateComplete:
						atomic.AddUint32(&dbCompleteCount, ^uint32(0))
					case v1alpha1.DependencyBuildStateFailed:
						atomic.AddUint32(&dbFailedCount, ^uint32(0))
					case v1alpha1.DependencyBuildStateContaminated:
						atomic.AddUint32(&dbContaminatedCount, ^uint32(0))
					}
				}

				dbg := false
				if changed {
					ta.Logf(fmt.Sprintf("dependencybuild created count: %d complete count: %d, failed count: %d, contaminated count: %d building count %d", createdDB, dbCompleteCount, dbFailedCount, dbContaminatedCount, dbBuildingCount))
					dbg = true

				}

				if createdDB > 90 && !activePipelineRuns(ta, dbg) && dbBuildingCount == 0 {
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
	})

	if didNotCompleteInTime {
		t.Fatalf("CHECK THE GENERATED REPORT AND SEE IF THE DEPENDENCYBUILDS IN BUILDING STATE ARE STUCK OR JUST SLOW")
	}
}
