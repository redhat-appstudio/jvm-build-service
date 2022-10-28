package main

import (
	"context"
	"flag"
	tektonclientset "github.com/tektoncd/pipeline/pkg/client/clientset/versioned"
	v1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/client-go/kubernetes"
	"k8s.io/client-go/tools/clientcmd"
	"k8s.io/client-go/util/homedir"
	"path/filepath"
	"strings"
)

// There is currently a bug in tekton chains that can prevent pipeline runs from being deleted
// Run this from the IDE to fix it
// Do not use this in production
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
	plnClient, err := tektonclientset.NewForConfig(config)
	if err != nil {
		panic(err)
	}
	namespaceList, err := clientset.CoreV1().Namespaces().List(context.TODO(), v1.ListOptions{})
	if err != nil {
		return
	}
	for _, namespace := range namespaceList.Items {
		println("Namespace: " + namespace.Name)
		if strings.Contains(namespace.Name, "jvm") || strings.Contains(namespace.Name, "kcp") {

			tRuns := plnClient.TektonV1beta1().TaskRuns(namespace.Name)
			trList, err := tRuns.List(context.TODO(), v1.ListOptions{})
			if err != nil {
				panic(err)
			}
			for i := range trList.Items {

				taskRun := &trList.Items[i]
				println(taskRun.Name)
				var newFinalizers []string
				for _, finalizer := range taskRun.Finalizers {
					if !strings.Contains(finalizer, "chains.tekton.dev") {
						newFinalizers = append(newFinalizers, finalizer)
					}
				}
				taskRun.Finalizers = newFinalizers
				_, err = tRuns.Update(context.TODO(), taskRun, v1.UpdateOptions{})
				if err != nil {
					panic(err)
				}
			}
			pRuns := plnClient.TektonV1beta1().PipelineRuns(namespace.Name)
			prList, err := pRuns.List(context.TODO(), v1.ListOptions{})
			if err != nil {
				panic(err)
			}
			for i := range prList.Items {
				pipelineRun := &prList.Items[i]
				println(pipelineRun.Name)
				var newFinalizers []string
				for _, finalizer := range pipelineRun.Finalizers {
					if !strings.Contains(finalizer, "chains.tekton.dev") {
						newFinalizers = append(newFinalizers, finalizer)
					}
				}
				pipelineRun.Finalizers = newFinalizers
				_, err = pRuns.Update(context.TODO(), pipelineRun, v1.UpdateOptions{})
				if err != nil {
					panic(err)
				}
			}
		}
	}

}
