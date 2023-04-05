//go:build minikube
// +build minikube

package e2e

import (
	"testing"
)

func TestExampleMinikubeRun(t *testing.T) {
	runBasicTests(t, setupMinikube)
}
