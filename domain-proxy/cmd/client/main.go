package main

import (
	. "github.com/redhat-appstudio/jvm-build-service/domain-proxy/pkg/client"
	. "github.com/redhat-appstudio/jvm-build-service/domain-proxy/pkg/common"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	InitLogger("Domain Proxy Client")
	client := NewDomainProxyClient(GetDomainSocket(), GetServerHttpPort(), GetByteBufferSize(), GetConnectionTimeout(), GetIdleTimeout())
	client.Start()
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	<-signals
	client.Stop()
}
