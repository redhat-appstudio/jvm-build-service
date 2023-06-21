//go:build periodic
// +build periodic

package e2e

import (
	"testing"
)

func TestServiceRegistry(t *testing.T) {
	runTests(t, "apicurio-", "run-service-registry.yaml")
}
