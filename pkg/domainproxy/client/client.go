package client

import (
	"context"
	"fmt"
	. "github.com/redhat-appstudio/jvm-build-service/pkg/domainproxy/common"
	"net"
	"sync/atomic"
	"time"
)

const (
	Localhost             = "localhost"
	ServerHttpPortKey     = "SERVER_HTTP_PORT"
	DefaultServerHttpPort = 8080
	HttpToDomainSocket    = "HTTP <-> Domain Socket"
)

var logger = NewLogger("Domain Proxy Client")
var common = NewCommon(logger)

type DomainProxyClient struct {
	sharedParams          SharedParams
	serverHttpPort        int
	httpConnectionCounter atomic.Uint64
	listener              net.Listener
	shutdownContext       context.Context
	initiateShutdown      context.CancelFunc
}

func NewDomainProxyClient() *DomainProxyClient {
	shutdownContext, initiateShutdown := context.WithCancel(context.Background())
	return &DomainProxyClient{
		sharedParams:     common.NewSharedParams(),
		serverHttpPort:   getServerHttpPort(),
		shutdownContext:  shutdownContext,
		initiateShutdown: initiateShutdown,
	}
}

func (dpc *DomainProxyClient) Start(ready chan<- bool) {
	logger.Println("Starting domain proxy client...")
	var err error
	dpc.listener, err = net.Listen(TCP, fmt.Sprintf("%s:%d", Localhost, dpc.serverHttpPort))
	if err != nil {
		logger.Fatalf("Failed to start HTTP server: %v", err)
	}
	go dpc.startClient(ready)
}

func (dpc *DomainProxyClient) startClient(ready chan<- bool) {
	logger.Printf("HTTP server listening on port %d", dpc.serverHttpPort)
	ready <- true
	for {
		select {
		case <-dpc.shutdownContext.Done():
			return
		default:
			if serverConnection, err := dpc.listener.Accept(); err != nil {
				select {
				case <-dpc.shutdownContext.Done():
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
	connectionNo := dpc.httpConnectionCounter.Add(1)
	logger.Printf("Handling %s Connection %d", HttpToDomainSocket, connectionNo)
	startTime := time.Now()
	sharedParams := dpc.sharedParams
	domainConnection, err := net.DialTimeout(UNIX, sharedParams.DomainSocket, sharedParams.ConnectionTimeout)
	if err != nil {
		logger.Printf("Failed to connect to domain socket: %v", err)
		if err = serverConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	go func() {
		common.BiDirectionalTransfer(dpc.shutdownContext, serverConnection, domainConnection, sharedParams.ByteBufferSize, sharedParams.IdleTimeout, HttpToDomainSocket, connectionNo)
		logger.Printf("%s Connection %d ended after %d ms", HttpToDomainSocket, connectionNo, time.Since(startTime).Milliseconds())
	}()
}

func (dpc *DomainProxyClient) Stop() {
	logger.Println("Shutting down domain proxy client...")
	dpc.initiateShutdown()
	if dpc.listener != nil {
		if err := dpc.listener.Close(); err != nil {
			common.HandleListenerCloseError(err)
		}
	}
}

func getServerHttpPort() int {
	return common.GetIntEnvVariable(ServerHttpPortKey, DefaultServerHttpPort)
}
