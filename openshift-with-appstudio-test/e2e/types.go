package e2e

import (
	"fmt"
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
	"testing"
	"time"
)

type testArgs struct {
	t  *testing.T
	ns string

	timeout  time.Duration
	interval time.Duration

	gitClone *tektonpipeline.Task
	maven    *tektonpipeline.Task
	pipeline *tektonpipeline.Pipeline
	run      *tektonpipeline.PipelineRun
}

func (ta *testArgs) Logf(msg string) {
	ta.t.Logf(fmt.Sprintf("time: %s: %s", time.Now().String(), msg))
}
