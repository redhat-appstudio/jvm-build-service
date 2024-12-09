package main

import (
	. "github.com/redhat-appstudio/jvm-build-service/pkg/domainproxy/server"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	domainProxyServer := NewDomainProxyServer()
	domainProxyServer.Start()
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	<-signals
	domainProxyServer.Stop()
}
