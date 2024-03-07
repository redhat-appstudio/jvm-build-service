//go:build normal
// +build normal

package e2e

import (
	"testing"
)

func TestExampleRun(t *testing.T) {
	runBasicTests(t, setup, testNamespace)
}
