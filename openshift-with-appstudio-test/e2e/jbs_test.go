//go:build jbs
// +build jbs

package e2e

import (
	"testing"
)

func TestJBS(t *testing.T) {
	runTests(t, "jbs-", "run-jvm-build-service.yaml")
}
