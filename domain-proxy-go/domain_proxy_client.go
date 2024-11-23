package main

import (
	"fmt"
	"log"
	"net"
	"os"
	"os/signal"
	"sync"
	"syscall"
)

const (
	Localhost             = "localhost"
	ServerHttpPortKey     = "SERVER_HTTP_PORT"
	DefaultServerHttpPort = 8080
)

type DomainProxyClient struct {
	domainSocket   string
	serverHttpPort int
	byteBufferSize int
	listener       net.Listener
	executor       *sync.WaitGroup
	shutdownChan   chan struct{}
}

func NewDomainProxyClient(domainSocket string, serverHttpPort, byteBufferSize int) *DomainProxyClient {
	return &DomainProxyClient{
		domainSocket:   domainSocket,
		serverHttpPort: serverHttpPort,
		byteBufferSize: byteBufferSize,
		executor:       &sync.WaitGroup{},
		shutdownChan:   make(chan struct{}),
	}
}

func (dpc *DomainProxyClient) Start() {
	log.Println("Starting domain proxy client...")
	log.Printf("Byte buffer size %d", dpc.byteBufferSize) // TODO Remove
	var err error
	dpc.listener, err = net.Listen("tcp", fmt.Sprintf("%s:%d", Localhost, dpc.serverHttpPort))
	if err != nil {
		log.Fatalf("Failed to start HTTP server: %v", err)
	}
	dpc.executor.Add(1)
	go dpc.startClient()
}

func (dpc *DomainProxyClient) startClient() {
	defer dpc.executor.Done()
	log.Println("HTTP server listening on port", dpc.serverHttpPort)
	for {
		conn, err := dpc.listener.Accept()
		if err != nil {
			select {
			case <-dpc.shutdownChan:
				return
			default:
				log.Printf("Failed to accept connection: %v", err)
				continue
			}
		}
		domainConn, err := net.DialTimeout("unix", dpc.domainSocket, Timeout)
		if err != nil {
			log.Printf("Failed to connect to domain socket: %v", err)
			conn.Close()
			continue
		}
		dpc.executor.Add(1)
		go ChannelToChannelBiDirectionalHandler(dpc.byteBufferSize, conn, domainConn, dpc.executor)
	}
}

func (dpc *DomainProxyClient) Stop() {
	log.Println("Shutting down domain proxy client...")
	close(dpc.shutdownChan)
	if err := dpc.listener.Close(); err != nil {
		log.Printf("Error closing listener: %v", err)
	}
	dpc.executor.Wait()
}

func GetServerHttpPort() int {
	return GetIntEnvVariable(ServerHttpPortKey, DefaultServerHttpPort)
}

func main() {
	client := NewDomainProxyClient(GetDomainSocket(), GetServerHttpPort(), GetByteBufferSize())
	client.Start()
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	<-sigs
	client.Stop()
}
