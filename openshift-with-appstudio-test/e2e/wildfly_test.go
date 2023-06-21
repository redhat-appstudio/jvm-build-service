//go:build wildfly
// +build wildfly

package e2e

import (
	"testing"
)

func TestWildfly(t *testing.T) {
	runTests(t, "wildfly-", "run-wildfly.yaml")
}
