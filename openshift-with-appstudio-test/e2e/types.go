package e2e

import (
	"fmt"
	"testing"
	"time"

	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"

	"github.com/redhat-appstudio/application-service/api/v1alpha1"
)

type testArgs struct {
	t  *testing.T
	ns string

	timeout  time.Duration
	interval time.Duration

	gitClone *v1beta1.Task
	maven    *v1beta1.Task
	pipeline *v1beta1.Pipeline
	run      *v1beta1.PipelineRun

	appstudioApp       *v1alpha1.Application
	appstudioComponent *v1alpha1.Component
}

func (ta *testArgs) Logf(msg string) {
	ta.t.Logf(fmt.Sprintf("time: %s: %s", time.Now().String(), msg))
}
