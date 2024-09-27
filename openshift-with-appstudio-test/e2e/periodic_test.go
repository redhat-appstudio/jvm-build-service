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
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"

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

	//limit range to mimic prod
	limitRange := &corev1.LimitRange{
		ObjectMeta: metav1.ObjectMeta{
			Name:      "resource-limits",
			Namespace: ta.ns,
		},
		Spec: corev1.LimitRangeSpec{
			Limits: []corev1.LimitRangeItem{
				{
					Type:           corev1.LimitTypeContainer,
					Default:        corev1.ResourceList{"cpu": resource.MustParse("2"), "memory": resource.MustParse("2Gi")},
					DefaultRequest: corev1.ResourceList{"cpu": resource.MustParse("10m"), "memory": resource.MustParse("256Mi")},
				},
			},
		},
	}
	_, err = kubeClient.CoreV1().LimitRanges(ta.ns).Create(context.TODO(), limitRange, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	path, err := os.Getwd()
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}
	ta.t.Logf("[%s] current working dir: %s", time.Now().Format(time.StampMilli), path)

	runYamlPath := filepath.Join(path, "..", "..", "hack", "examples", runYaml)
	ta.run = &tektonpipeline.PipelineRun{}
	obj := streamFileYamlToTektonObj(runYamlPath, ta.run, ta)
	var ok bool
	ta.run, ok = obj.(*tektonpipeline.PipelineRun)
	if !ok {
		debugAndFailTest(ta, fmt.Sprintf("file %s did not produce a pipelinerun: %#v", runYamlPath, obj))
	}
	ta.run, err = tektonClient.TektonV1().PipelineRuns(ta.ns).Create(context.TODO(), ta.run, metav1.CreateOptions{})
	if err != nil {
		debugAndFailTest(ta, err.Error())
	}

	ta.t.Run("pipelinerun completes successfully", func(t *testing.T) {
		err = wait.PollUntilContextTimeout(context.TODO(), 3*ta.interval, 3*ta.timeout, true, func(ctx context.Context) (done bool, err error) {
			pr, err := tektonClient.TektonV1().PipelineRuns(ta.ns).Get(context.TODO(), ta.run.Name, metav1.GetOptions{})
			if err != nil {
				ta.t.Logf("[%s] get pr %s produced err: %s", time.Now().Format(time.StampMilli), ta.run.Name, err.Error())
				return false, nil
			}
			if !pr.IsDone() {
				prBytes, err := json.MarshalIndent(pr, "", "  ")
				if err != nil {
					ta.t.Logf("[%s] problem marshalling in progress pipelinerun to bytes: %s", time.Now().Format(time.StampMilli), err.Error())
					return false, nil
				}
				ta.t.Logf("[%s] in flight pipeline run: %s", time.Now().Format(time.StampMilli), string(prBytes))
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
			debugAndFailTest(ta, "timed out when waiting for the pipeline run to complete")
		}
	})

	didNotCompleteInTime := false
	ta.t.Run("current target of artifactbuilds complete", func(t *testing.T) {
		//note that we just check artifact builds
		//as they track the dependency build state
		defer GenerateStatusReport(ta.ns, jvmClient, kubeClient, tektonClient)
		err = wait.PollUntilContextTimeout(context.TODO(), ta.interval, 2*time.Hour, true, func(ctx context.Context) (done bool, err error) {
			abComplete, err := artifactBuildsStable(ta)
			if err != nil {
				return false, err
			}
			if abComplete {
				time.Sleep(time.Second * 10)
				//wait 10s to see if contamination is being resolved then check again
				return artifactBuildsStable(ta)
			}
			return false, nil
		})
		if err != nil {
			debugAndFailTest(ta, "timed out waiting for some artifactbuilds and dependencybuilds to complete")
		}
	})

	if didNotCompleteInTime {
		t.Fatalf("CHECK THE GENERATED REPORT AND SEE IF THE DEPENDENCYBUILDS IN BUILDING STATE ARE STUCK OR JUST SLOW")
	}
}

func artifactBuildsStable(ta *testArgs) (bool, error) {
	abList, err := jvmClient.JvmbuildserviceV1alpha1().ArtifactBuilds(ta.ns).List(context.TODO(), metav1.ListOptions{})
	if err != nil {
		ta.t.Logf("[%s] error list artifactbuilds: %s", time.Now().Format(time.StampMilli), err.Error())
		return false, err
	}
	//we want to make sure there is more than 3 ab, and that they are all complete
	abComplete := len(abList.Items) > 3
	ta.t.Logf("[%s] number of artifactbuilds: %d", time.Now().Format(time.StampMilli), len(abList.Items))
	for _, ab := range abList.Items {
		if ab.Status.State != v1alpha1.ArtifactBuildStateComplete && ab.Status.State != v1alpha1.ArtifactBuildStateFailed && ab.Status.State != v1alpha1.ArtifactBuildStateMissing {
			ta.t.Logf("[%s] artifactbuild %s not complete", time.Now().Format(time.StampMilli), ab.Spec.GAV)
			abComplete = false
			break
		}
	}
	return abComplete, nil
}
