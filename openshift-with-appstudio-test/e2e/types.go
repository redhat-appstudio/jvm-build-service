package e2e

import (
	"fmt"
	v1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
	"testing"
	"time"
)

type testArgs struct {
	t  *testing.T
	ns string

	timeout  time.Duration
	interval time.Duration

	gitClone *v1.Task
	maven    *v1.Task
	pipeline *v1.Pipeline
	run      *v1.PipelineRun
}

func (ta *testArgs) Logf(msg string) {
	ta.t.Logf(fmt.Sprintf("time: %s: %s", time.Now().String(), msg))
}
