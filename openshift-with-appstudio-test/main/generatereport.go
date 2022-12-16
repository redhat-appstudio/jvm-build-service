package main

import (
	"flag"
	"github.com/redhat-appstudio/jvm-build-service/openshift-with-appstudio-test/e2e"
	jvmclientset "github.com/redhat-appstudio/jvm-build-service/pkg/client/clientset/versioned"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"
	"k8s.io/client-go/util/homedir"
	"path/filepath"
)

// This is a helper function to generate the HTML report locally
// It is not used in the actual tests or production
func main() {
	var kubeconfig *string
	if home := homedir.HomeDir(); home != "" {
		kubeconfig = flag.String("kube", filepath.Join(home, ".kube", "config"), "(optional) absolute path to the kubeconfig file")
	} else {
		kubeconfig = flag.String("kube", "", "absolute path to the kubeconfig file")
	}
	flag.Parse()

	// use the current context in kubeconfig
	config, err := clientcmd.BuildConfigFromFlags("", *kubeconfig)
	if err != nil {
		panic(err.Error())
	}

	// create the clientset
	clientset, err := kubernetes.NewForConfig(config)
	if err != nil {
		panic(err.Error())
	}
	jvmClient, err := jvmclientset.NewForConfig(config)
	if err != nil {
		panic(err.Error())
	}
	e2e.GenerateStatusReport("test-jvm-namespace", jvmClient, clientset)
}
