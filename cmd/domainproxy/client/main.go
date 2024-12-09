package main

import (
	. "github.com/redhat-appstudio/jvm-build-service/pkg/domainproxy/client"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	domainProxyClient := NewDomainProxyClient()
	domainProxyClient.Start()
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	<-signals
	domainProxyClient.Stop()
}
