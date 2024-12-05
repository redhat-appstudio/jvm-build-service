package main

import (
	. "github.com/redhat-appstudio/jvm-build-service/pkg/domainproxy/client"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	client := NewDomainProxyClient()
	client.Start()
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	<-signals
	client.Stop()
}
