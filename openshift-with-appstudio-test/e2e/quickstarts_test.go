//go:build quickstarts
// +build quickstarts

package e2e

import (
	"testing"
)

func TestQuarkusQuickstart(t *testing.T) {
	runPipelineTests(t, setup, "run-quarkus-quickstart.yaml", "quarkus")
}

func TestSpringBootQuickstart(t *testing.T) {
	runPipelineTests(t, setup, "run-spring-boot-quickstart.yaml", "spring")
}
