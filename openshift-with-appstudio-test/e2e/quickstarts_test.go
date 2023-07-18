//go:build quickstarts
// +build quickstarts

package e2e

import (
	"testing"
)

func TestQuarkusQuickstart(t *testing.T) {
	t.Parallel()
	runTests(t, "quarkus-", "run-quarkus-quickstart.yaml")
}

func TestSpringBootQuickstart(t *testing.T) {
	t.Parallel()
	runTests(t, "spring-", "run-spring-boot-quickstart.yaml")
}
