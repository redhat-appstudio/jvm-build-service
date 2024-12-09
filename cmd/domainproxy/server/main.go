package main

import (
	. "github.com/redhat-appstudio/jvm-build-service/pkg/domainproxy/server"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	domainProxyServer := NewDomainProxyServer()
	ready := make(chan bool)
	domainProxyServer.Start(ready)
	<-ready
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	<-signals
	domainProxyServer.Stop()
}
