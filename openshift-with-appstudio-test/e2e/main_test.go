package e2e

import (
	"fmt"
	"os"
	"testing"

	kubeset "k8s.io/client-go/kubernetes"
)

func TestMain(m *testing.M) {
	kubeconfig, err := getConfig()
	if err != nil {
		fmt.Printf("%#v", err)
		os.Exit(1)
	}

	_, err = kubeset.NewForConfig(kubeconfig)
	if err != nil {
		fmt.Printf("%#v", err)
		os.Exit(1)
	}

	os.Exit(m.Run())
}
