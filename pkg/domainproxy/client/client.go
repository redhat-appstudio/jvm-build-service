package client

import (
	"fmt"
	. "github.com/redhat-appstudio/jvm-build-service/pkg/domainproxy/common"
	"net"
	"time"
)

const (
	Localhost          = "localhost"
	HttpPortKey        = "DOMAIN_PROXY_HTTP_PORT"
	DefaultHttpPort    = 8080
	HttpToDomainSocket = "HTTP <-> Domain Socket"
)

var logger = NewLogger("Domain Proxy Client")
var common = NewCommon(logger)

type DomainProxyClient struct {
	sharedParams *SharedParams
	httpPort     int
}

func NewDomainProxyClient() *DomainProxyClient {
	return &DomainProxyClient{
		sharedParams: common.NewSharedParams(),
		httpPort:     getHttpPort(),
	}
}

func (dpc *DomainProxyClient) Start(ready chan<- bool) {
	sharedParams := dpc.sharedParams
	logger.Println("Starting domain proxy client...")
	var err error
	sharedParams.Listener, err = net.Listen(TCP, fmt.Sprintf("%s:%d", Localhost, dpc.httpPort))
	if err != nil {
		logger.Fatalf("Failed to start HTTP server: %v", err)
	}
	go dpc.startClient(ready)
}

func (dpc *DomainProxyClient) startClient(ready chan<- bool) {
	sharedParams := dpc.sharedParams
	logger.Printf("HTTP server listening on port %d", dpc.httpPort)
	ready <- true
	for {
		select {
		case <-sharedParams.RunningContext.Done():
			return
		default:
			if serverConnection, err := sharedParams.Listener.Accept(); err != nil {
				select {
				case <-sharedParams.RunningContext.Done():
					return
				default:
					logger.Printf("Failed to accept server connection: %v", err)
				}
			} else {
				go dpc.handleConnectionRequest(serverConnection)
			}
		}
	}
}

func (dpc *DomainProxyClient) handleConnectionRequest(serverConnection net.Conn) {
	sharedParams := dpc.sharedParams
	if err := serverConnection.SetDeadline(time.Now().Add(sharedParams.IdleTimeout)); err != nil {
		common.HandleSetDeadlineError(serverConnection, err)
		return
	}
	connectionNo := sharedParams.HttpConnectionCounter.Add(1)
	logger.Printf("Handling %s Connection %d", HttpToDomainSocket, connectionNo)
	startTime := time.Now()
	domainConnection, err := net.DialTimeout(UNIX, sharedParams.DomainSocket, sharedParams.ConnectionTimeout)
	if err != nil {
		logger.Printf("Failed to connect to domain socket: %v", err)
		if err = serverConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	if err := domainConnection.SetDeadline(time.Now().Add(sharedParams.IdleTimeout)); err != nil {
		common.HandleSetDeadlineError(domainConnection, err)
		if err = serverConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	// Initiate transfer between server and domain
	go func() {
		common.BiDirectionalTransfer(sharedParams.RunningContext, serverConnection, domainConnection, sharedParams.ByteBufferSize, HttpToDomainSocket, connectionNo)
		logger.Printf("%s Connection %d ended after %d ms", HttpToDomainSocket, connectionNo, time.Since(startTime).Milliseconds())
	}()
}

func (dpc *DomainProxyClient) Stop() {
	sharedParams := dpc.sharedParams
	logger.Println("Shutting down domain proxy client...")
	sharedParams.InitiateShutdown()
	if sharedParams.Listener != nil {
		if err := sharedParams.Listener.Close(); err != nil {
			common.HandleListenerCloseError(err)
		}
	}
}

func getHttpPort() int {
	return common.GetIntEnvVariable(HttpPortKey, DefaultHttpPort)
}
