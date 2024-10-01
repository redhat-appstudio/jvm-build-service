package e2e

import (
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
