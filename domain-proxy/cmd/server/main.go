package main

import (
	. "github.com/redhat-appstudio/jvm-build-service/domain-proxy/pkg/common"
	. "github.com/redhat-appstudio/jvm-build-service/domain-proxy/pkg/server"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	InitLogger("Domain Proxy Server")
	server := NewDomainProxyServer()
	server.Start()
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	<-signals
	server.Stop()
}
