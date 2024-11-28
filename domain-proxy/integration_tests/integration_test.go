package integration_tests

import (
	. "github.com/redhat-appstudio/jvm-build-service/domain-proxy/pkg/client"
	. "github.com/redhat-appstudio/jvm-build-service/domain-proxy/pkg/server"
	"testing"
)

func TestIntegration(t *testing.T) {
	domainProxyServer := NewDomainProxyServer()
	go domainProxyServer.Start()
	domainProxyClient := NewDomainProxyClient()
	go domainProxyClient.Start()
	domainProxyClient.Stop()
	domainProxyServer.Stop()
}
