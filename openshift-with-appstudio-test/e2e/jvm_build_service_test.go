//go:build normal
// +build normal

package e2e

import (
	"os"
	"testing"
)

func TestExampleRun(t *testing.T) {
	//test to make sure prow artifacts work the way I think the docs say they do
	d1 := []byte("hello\ntest  artifacts\n")
	_ = os.WriteFile(os.Getenv("ARTIFACTS")+"/TEST_ARTIFACT.txt", d1, 0644)

	t.Run("pipelinerun completes successfully", func(t *testing.T) {

	})

}
