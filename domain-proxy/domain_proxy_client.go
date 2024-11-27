package main

import (
	"fmt"
	"net"
	"os"
	"os/signal"
	"sync/atomic"
	"syscall"
	"time"
)

const (
	Localhost             = "localhost"
	ServerHttpPortKey     = "SERVER_HTTP_PORT"
	DefaultServerHttpPort = 8080
	HttpToDomainSocket    = "HTTP <-> Domain Socket"
)

type DomainProxyClient struct {
	domainSocket          string
	serverHttpPort        int
	byteBufferSize        int
	connectionTimeout     time.Duration
	idleTimeout           time.Duration
	httpConnectionCounter atomic.Uint64
	listener              net.Listener
	shutdownChan          chan struct{}
}

func NewDomainProxyClient(domainSocket string, serverHttpPort, byteBufferSize int, connectionTimeout, idleTimeout time.Duration) *DomainProxyClient {
	return &DomainProxyClient{
		domainSocket:      domainSocket,
		serverHttpPort:    serverHttpPort,
		byteBufferSize:    byteBufferSize,
		connectionTimeout: connectionTimeout,
		idleTimeout:       idleTimeout,
		shutdownChan:      make(chan struct{}),
	}
}

func (dpc *DomainProxyClient) Start() {
	Logger.Println("Starting domain proxy client...")
	Logger.Printf("Byte buffer size %d", dpc.byteBufferSize) // TODO Remove
	var err error
	dpc.listener, err = net.Listen("tcp", fmt.Sprintf("%s:%d", Localhost, dpc.serverHttpPort))
	if err != nil {
		Logger.Fatalf("Failed to start HTTP server: %v", err)
	}
	go dpc.startClient()
}

func (dpc *DomainProxyClient) startClient() {
	Logger.Printf("HTTP server listening on port %d", dpc.serverHttpPort)
	for {
		if serverConnection, err := dpc.listener.Accept(); err != nil {
			select {
			case <-dpc.shutdownChan:
				return
			default:
				Logger.Printf("Failed to accept server connection: %v", err)
			}
		} else {
			go dpc.handleConnectionRequest(serverConnection)
		}
	}
}

func (dpc *DomainProxyClient) handleConnectionRequest(serverConnection net.Conn) {
	connectionNo := dpc.httpConnectionCounter.Add(1)
	Logger.Printf("Handling %s Connection %d", HttpToDomainSocket, connectionNo)
	startTime := time.Now()
	domainConnection, err := net.DialTimeout("unix", dpc.domainSocket, dpc.connectionTimeout)
	if err != nil {
		Logger.Printf("Failed to connect to domain socket: %v", err)
		if err = serverConnection.Close(); err != nil {
			HandleConnectionCloseError(err)
		}
		return
	}
	go func() {
		BiDirectionalTransfer(serverConnection, domainConnection, dpc.byteBufferSize, dpc.idleTimeout, HttpToDomainSocket, connectionNo)
		Logger.Printf("%s Connection %d ended after %d ms", HttpToDomainSocket, connectionNo, time.Since(startTime).Milliseconds())
	}()
}

func (dpc *DomainProxyClient) Stop() {
	Logger.Println("Shutting down domain proxy client...")
	close(dpc.shutdownChan)
	if err := dpc.listener.Close(); err != nil {
		HandleListenerCloseError(err)
	}
}

func GetServerHttpPort() int {
	return GetIntEnvVariable(ServerHttpPortKey, DefaultServerHttpPort)
}

func main() {
	InitLogger("Domain Proxy Client")
	client := NewDomainProxyClient(GetDomainSocket(), GetServerHttpPort(), GetByteBufferSize(), GetConnectionTimeout(), GetIdleTimeout())
	client.Start()
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	<-signals
	client.Stop()
}
