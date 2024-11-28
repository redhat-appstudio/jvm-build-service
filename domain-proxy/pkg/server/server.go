package server

import (
	"bufio"
	"fmt"
	. "github.com/redhat-appstudio/jvm-build-service/domain-proxy/pkg/common"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

const (
	HttpPort                     = 80
	HttpsPort                    = 443
	ProxyTargetWhitelistKey      = "PROXY_TARGET_WHITELIST"
	DefaultProxyTargetWhitelist  = "gariscus.com,neverssl.com,repo1.maven.org,repo.maven.apache.org,repository.jboss.org,packages.confluent.io,jitpack.io,repo.gradle.org,plugins.gradle.org"
	InternalNonProxyHostsKey     = "INTERNAL_NON_PROXY_HOSTS"
	DefaultInternalNonProxyHosts = "localhost"
	DomainSocketToHttp           = "Domain Socket <-> HTTP"
	DomainSocketToHttps          = "Domain Socket <-> HTTPS"
)

type DomainProxyServer struct {
	domainSocket           string
	byteBufferSize         int
	connectionTimeout      time.Duration
	idleTimeout            time.Duration
	proxyTargetWhitelist   map[string]bool
	nonProxyHosts          map[string]bool
	httpConnectionCounter  atomic.Uint64
	httpsConnectionCounter atomic.Uint64
	listener               net.Listener
	shutdownChan           chan struct{}
}

func NewDomainProxyServer(domainSocket string, byteBufferSize int, connectionTimeout, idleTimeout time.Duration, proxyTargetWhitelist, nonProxyHosts map[string]bool) *DomainProxyServer {
	return &DomainProxyServer{
		domainSocket:         domainSocket,
		byteBufferSize:       byteBufferSize,
		connectionTimeout:    connectionTimeout,
		idleTimeout:          idleTimeout,
		proxyTargetWhitelist: proxyTargetWhitelist,
		nonProxyHosts:        nonProxyHosts,
		shutdownChan:         make(chan struct{}),
	}
}

func (dps *DomainProxyServer) Start() {
	Logger.Println("Starting domain proxy server...")
	go dps.startServer()
}

func (dps *DomainProxyServer) startServer() {
	if _, err := os.Stat(dps.domainSocket); err == nil {
		if err := os.Remove(dps.domainSocket); err != nil {
			Logger.Fatalf("Failed to delete existing domain socket: %v", err)
		}
	}
	var err error
	dps.listener, err = net.Listen("unix", dps.domainSocket)
	if err != nil {
		Logger.Fatalf("Failed to start domain socket listener: %v", err)
	}
	Logger.Printf("Domain socket server listening on %s", dps.domainSocket)
	for {
		if domainConnection, err := dps.listener.Accept(); err != nil {
			select {
			case <-dps.shutdownChan:
				return
			default:
				Logger.Printf("Failed to accept domain socket connection: %v", err)
			}
		} else {
			go dps.handleConnectionRequest(domainConnection)
		}
	}
}

func (dps *DomainProxyServer) handleConnectionRequest(domainConnection net.Conn) {
	if err := domainConnection.SetDeadline(time.Now().Add(dps.idleTimeout)); err != nil {
		HandleSetDeadlineError(domainConnection, err)
		return
	}
	reader := bufio.NewReader(domainConnection)
	request, err := http.ReadRequest(reader)
	if err != nil {
		Logger.Printf("Failed to read request: %v", err)
		if err = domainConnection.Close(); err != nil {
			HandleConnectionCloseError(err)
		}
		return
	}
	writer := &responseWriter{connection: domainConnection}
	if err = domainConnection.SetDeadline(time.Now().Add(dps.idleTimeout)); err != nil {
		HandleSetDeadlineError(domainConnection, err)
		return
	}
	if request.Method == http.MethodConnect {
		dps.handleHttpsConnection(domainConnection, writer, request)
	} else {
		dps.handleHttpConnection(domainConnection, writer, request)
	}
}

func (dps *DomainProxyServer) handleHttpConnection(sourceConnection net.Conn, writer http.ResponseWriter, request *http.Request) {
	connectionNo := dps.httpConnectionCounter.Add(1)
	targetHost, targetPort := getTargetHostAndPort(request.Host, HttpPort)
	Logger.Printf("Handling %s Connection %d with target host %s and port %d", DomainSocketToHttp, connectionNo, targetHost, targetPort)
	if !dps.isTargetWhitelisted(targetHost, writer) {
		if err := sourceConnection.Close(); err != nil {
			HandleConnectionCloseError(err)
		}
		return
	}
	startTime := time.Now()
	targetConnection, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", targetHost, targetPort), dps.connectionTimeout)
	if err != nil {
		dps.handleErrorResponse(writer, err, "Failed to connect to target")
		if err = sourceConnection.Close(); err != nil {
			HandleConnectionCloseError(err)
		}
		return
	} else if err = request.Write(targetConnection); err != nil {
		dps.handleErrorResponse(writer, err, "Failed to send request to target")
		if err = targetConnection.Close(); err != nil {
			HandleConnectionCloseError(err)
		}
		if err = sourceConnection.Close(); err != nil {
			HandleConnectionCloseError(err)
		}
		return
	}
	go func() {
		BiDirectionalTransfer(sourceConnection, targetConnection, dps.byteBufferSize, dps.idleTimeout, DomainSocketToHttp, connectionNo)
		Logger.Printf("%s Connection %d ended after %d ms", DomainSocketToHttp, connectionNo, time.Since(startTime).Milliseconds())
	}()
}

func (dps *DomainProxyServer) handleHttpsConnection(sourceConnection net.Conn, writer http.ResponseWriter, request *http.Request) {
	connectionNo := dps.httpsConnectionCounter.Add(1)
	targetHost, targetPort := getTargetHostAndPort(request.Host, HttpsPort)
	Logger.Printf("Handling %s Connection %d with target host %s and port %d", DomainSocketToHttps, connectionNo, targetHost, targetPort)
	if !dps.isTargetWhitelisted(targetHost, writer) {
		if err := sourceConnection.Close(); err != nil {
			HandleConnectionCloseError(err)
		}
		return
	}
	startTime := time.Now()
	targetConnection, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", targetHost, targetPort), dps.connectionTimeout)
	if err != nil {
		dps.handleErrorResponse(writer, err, "Failed to connect to target")
		if err = sourceConnection.Close(); err != nil {
			HandleConnectionCloseError(err)
		}
		return
	} else if _, err = writer.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n")); err != nil {
		dps.handleErrorResponse(writer, err, "Failed to send request to target")
		if err = targetConnection.Close(); err != nil {
			HandleConnectionCloseError(err)
		}
		if err = sourceConnection.Close(); err != nil {
			HandleConnectionCloseError(err)
		}
		return
	}
	go func() {
		BiDirectionalTransfer(sourceConnection, targetConnection, dps.byteBufferSize, dps.idleTimeout, DomainSocketToHttps, connectionNo)
		Logger.Printf("%s Connection %d ended after %d ms", DomainSocketToHttps, connectionNo, time.Since(startTime).Milliseconds())
	}()
}

func getTargetHostAndPort(host string, defaultPort int) (string, int) {
	hostAndPort := strings.Split(host, ":")
	targetHost := hostAndPort[0]
	targetPort := defaultPort
	if len(hostAndPort) > 1 {
		if port, err := strconv.Atoi(hostAndPort[1]); err == nil {
			targetPort = port
		}
	}
	return targetHost, targetPort
}

func (dps *DomainProxyServer) isTargetWhitelisted(targetHost string, writer http.ResponseWriter) bool {
	if !dps.proxyTargetWhitelist[targetHost] && !dps.nonProxyHosts[targetHost] {
		message := fmt.Sprintf("Target host %s is not whitelisted nor a non-proxy host", targetHost)
		Logger.Println(message)
		http.Error(writer, message, http.StatusForbidden)
		return false
	}
	return true
}

func (dps *DomainProxyServer) handleErrorResponse(writer http.ResponseWriter, err error, message string) {
	Logger.Printf("%s: %v", message, err)
	http.Error(writer, message+": "+err.Error(), http.StatusBadGateway)
}

func (dps *DomainProxyServer) Stop() {
	Logger.Println("Shutting down domain proxy server...")
	close(dps.shutdownChan)
	if err := dps.listener.Close(); err != nil {
		HandleListenerCloseError(err)
	}
	if _, err := os.Stat(dps.domainSocket); err == nil {
		if err := os.Remove(dps.domainSocket); err != nil {
			Logger.Printf("Failed to delete domain socket: %v", err)
		}
	}
}

type responseWriter struct {
	connection net.Conn
	header     http.Header
	statusCode int
}

func (rw *responseWriter) Header() http.Header {
	if rw.header == nil {
		rw.header = make(http.Header)
	}
	return rw.header
}

func (rw *responseWriter) Write(data []byte) (int, error) {
	return rw.connection.Write(data)
}

func (rw *responseWriter) WriteHeader(statusCode int) {
	rw.statusCode = statusCode
	headers := fmt.Sprintf("HTTP/1.1 %d %s\r\n", statusCode, http.StatusText(statusCode))
	for k, v := range rw.Header() {
		for _, vv := range v {
			headers += fmt.Sprintf("%s: %s\r\n", k, vv)
		}
	}
	headers += "\r\n"
	if _, err := rw.connection.Write([]byte(headers)); err != nil {
		Logger.Printf("Failed to write headers to connection: %v", err)
	}
}
