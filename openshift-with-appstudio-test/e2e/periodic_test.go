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

	//quotav1 "github.com/openshift/api/quota/v1"
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

	//quota := &quotav1.ClusterResourceQuota{
	//	ObjectMeta: metav1.ObjectMeta{
	//		Name: fmt.Sprintf("for-%s-deployments", ta.ns),
	//	},
	//	Spec: quotav1.ClusterResourceQuotaSpec{
	//		Quota: corev1.ResourceQuotaSpec{
	//			Hard: corev1.ResourceList{
	//				corev1.ResourceName("count/pods"): resource.MustParse("50"),
	//			},
	//		},
	//		Selector: quotav1.ClusterResourceQuotaSelector{
	//			AnnotationSelector: map[string]string{
	//				"openshift.io/requester": ta.ns,
	//			},
	//		},
	//	},
	//}
	//_, err := qutoaClient.QuotaV1().ClusterResourceQuotas().Create(context.TODO(), quota, metav1.CreateOptions{})
	//if err != nil {
	//	debugAndFailTest(ta, err.Error())
	//}
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
	computeQuota := &corev1.ResourceQuota{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "compute-build",
			Namespace: ta.ns,
		},
		Spec: corev1.ResourceQuotaSpec{
			Scopes: []corev1.ResourceQuotaScope{
				corev1.ResourceQuotaScopeTerminating,
			},
			Hard: corev1.ResourceList{
				corev1.ResourceLimitsCPU:      resource.MustParse("20"),   // sandbox default is 20 or 20,000m - my cluster has 3 8000m core nodes
				corev1.ResourceLimitsMemory:   resource.MustParse("64Gi"), // sandbox default is 64Gi, my cluster has 3 32 Gi nodes
				corev1.ResourceRequestsCPU:    resource.MustParse("2"),
				corev1.ResourceRequestsMemory: resource.MustParse("32Gi"), // sandbox default is 32Gi
			},
		},
	}
	_, err = kubeClient.CoreV1().ResourceQuotas(ta.ns).Create(context.TODO(), computeQuota, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	//replicate sandbox use of limit ranges to cap mem/cpu
	limitRange := &corev1.LimitRange{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "resource-limits",
			Namespace: ta.ns,
		},
		Spec: corev1.LimitRangeSpec{
			Limits: []corev1.LimitRangeItem{
				{
					Type: corev1.LimitTypeContainer,
					Default: corev1.ResourceList{
						corev1.ResourceCPU:    resource.MustParse("2000m"),
						corev1.ResourceMemory: resource.MustParse("2Gi"), // got some OOMKilled with 2Gi
					},
					DefaultRequest: corev1.ResourceList{
						corev1.ResourceCPU:    resource.MustParse("10m"),
						corev1.ResourceMemory: resource.MustParse("256Mi"),
					},
				},
			},
		},
	}
	_, err = kubeClient.CoreV1().LimitRanges(ta.ns).Create(context.TODO(), limitRange, metav1.CreateOptions{})
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
	analyserImage := os.Getenv("JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE")
	if len(analyserImage) > 0 {
		ta.Logf(fmt.Sprintf("PR analyzer image: %s", analyserImage))
		for i, step := range ta.maven.Spec.Steps {
			if step.Name != "analyse-dependencies" {
				continue
			}
			ta.Logf(fmt.Sprintf("Updating analyse-dependencies step with image %s", analyserImage))
			ta.maven.Spec.Steps[i].Image = analyserImage
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
		defer GenerateStatusReport(ta.ns, jvmClient, kubeClient)
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
		state := sync.Map{}
		var changed uint32
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
				s, k := state.Load(db.Name)
				stableState := stable[db.Status.State] //if the DB is currently in a stable state
				var oldStableState bool
				if k {
					oldStableState = stable[s.(string)] //if the DB used to be in a stable state
				}
				state.Store(db.Name, db.Status.State)

				//if this is the first time we have seen it increment the created count
				if !k {
					atomic.AddInt32(&createdDB, 1)
				}
				//if the state has changed to/from a stable or unstable state modify the building count
				if stableState != oldStableState || !k {
					if stableState {
						atomic.AddUint32(&dbBuildingCount, ^uint32(0))
					} else {
						atomic.AddUint32(&dbBuildingCount, 1)
					}
				}
				//if the state has changed, or there is a new DB we need to check the current state
				if s != db.Status.State || !k {
					atomic.StoreUint32(&changed, 1) //mark as changed to it will be reported on, as this was a change

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
				if atomic.CompareAndSwapUint32(&changed, 1, 0) {
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
