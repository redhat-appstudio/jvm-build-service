package main

import (
	"bufio"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

const (
	HttpsPort                    = 443
	ProxyTargetWhitelistKey      = "PROXY_TARGET_WHITELIST"
	DefaultProxyTargetWhitelist  = "repo.maven.apache.org,repository.jboss.org,packages.confluent.io,jitpack.io,repo.gradle.org,plugins.gradle.org"
	InternalNonProxyHostsKey     = "INTERNAL_NON_PROXY_HOSTS"
	DefaultInternalNonProxyHosts = "localhost"
)

type DomainProxyServer struct {
	domainSocket         string
	byteBufferSize       int
	connectionTimeout    time.Duration
	idleTimeout          time.Duration
	proxyTargetWhitelist map[string]bool
	nonProxyHosts        map[string]bool
	counter              int
	listener             net.Listener
	executor             *sync.WaitGroup
	shutdownChan         chan struct{}
}

func NewDomainProxyServer(domainSocket string, byteBufferSize int, connectionTimeout, idleTimeout time.Duration, proxyTargetWhitelist, nonProxyHosts map[string]bool) *DomainProxyServer {
	return &DomainProxyServer{
		domainSocket:         domainSocket,
		byteBufferSize:       byteBufferSize,
		connectionTimeout:    connectionTimeout,
		idleTimeout:          idleTimeout,
		proxyTargetWhitelist: proxyTargetWhitelist,
		nonProxyHosts:        nonProxyHosts,
		counter:              0,
		executor:             &sync.WaitGroup{},
		shutdownChan:         make(chan struct{}),
	}
}

func (dps *DomainProxyServer) Start() {
	Logger.Println("Starting domain proxy server...")
	Logger.Printf("Byte buffer size %d", dps.byteBufferSize)              // TODO Remove
	Logger.Printf("Proxy target whitelist: %v", dps.proxyTargetWhitelist) // TODO Remove
	dps.executor.Add(1)
	go dps.startServer()
}

func (dps *DomainProxyServer) startServer() {
	defer dps.executor.Done()
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
	Logger.Println("Domain socket server listening on", dps.domainSocket)
	for {
		conn, err := dps.listener.Accept()
		if err != nil {
			select {
			case <-dps.shutdownChan:
				return
			default:
				Logger.Printf("Failed to accept connection: %v", err)
				continue
			}
		}
		dps.executor.Add(1)
		go dps.handleRequest(conn)
	}
}

func (dps *DomainProxyServer) handleRequest(conn net.Conn) {
	defer dps.executor.Done()
	dps.counter++
	conn.SetDeadline(time.Now().Add(dps.idleTimeout))
	reader := bufio.NewReader(conn)
	req, err := http.ReadRequest(reader)
	if err != nil {
		Logger.Printf("Failed to read request: %v", err)
		conn.Close()
		return
	}
	w := &responseWriter{conn: conn}
	conn.SetDeadline(time.Now().Add(dps.idleTimeout))
	if req.Method == http.MethodConnect {
		dps.handleHttpsRequest(conn, w, req)
	} else {
		dps.handleHttpRequest(w, req)
	}
}

func (dps *DomainProxyServer) handleHttpRequest(w http.ResponseWriter, r *http.Request) {
	Logger.Printf("Handling HTTP %s Request", r.Method)
	requestNo := dps.counter
	Logger.Printf("Request %d", requestNo)
	hostPort := strings.Split(r.Host, ":")
	targetHost := hostPort[0]
	if dps.isTargetWhitelisted(targetHost, w) {
		Logger.Printf("Target URI %s", r.RequestURI)
		startTime := time.Now()
		client := &http.Client{
			Transport: &http.Transport{
				IdleConnTimeout: dps.idleTimeout,
			},
		}
		req, err := http.NewRequest(r.Method, r.RequestURI, r.Body)
		if err != nil {
			dps.handleErrorResponse(w, err, "Failed to create request")
			return
		}
		req.Header = r.Header
		resp, err := client.Do(req)
		if err != nil {
			dps.handleErrorResponse(w, err, "Failed to get response")
			return
		}
		defer resp.Body.Close()
		for k, v := range resp.Header {
			for _, vv := range v {
				w.Header().Add(k, vv)
			}
		}
		w.WriteHeader(resp.StatusCode)
		if _, err = io.CopyBuffer(w, resp.Body, make([]byte, dps.byteBufferSize)); err != nil {
			Logger.Printf("Error copying response body: %v", err)
		}
		Logger.Printf("Request %d took %d ms", requestNo, time.Since(startTime).Milliseconds())
		// TODO log bytes written/read
	}
}

func (dps *DomainProxyServer) handleHttpsRequest(sourceConn net.Conn, w http.ResponseWriter, r *http.Request) {
	Logger.Printf("Handling HTTPS %s Request", r.Method)
	requestNo := dps.counter
	Logger.Printf("Request %d", requestNo)
	hostPort := strings.Split(r.Host, ":")
	targetHost := hostPort[0]
	targetPort := HttpsPort
	if len(hostPort) > 1 {
		if port, err := strconv.Atoi(hostPort[1]); err == nil {
			targetPort = port
		}
	}
	if dps.isTargetWhitelisted(targetHost, w) {
		Logger.Printf("Target URI %s", r.RequestURI)
		startTime := time.Now()
		targetConn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", targetHost, targetPort), dps.connectionTimeout)
		if err != nil {
			dps.handleErrorResponse(w, err, "Failed to connect to target")
			sourceConn.Close()
			return
		}
		if _, err = fmt.Fprint(sourceConn, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
			dps.handleErrorResponse(w, err, "Failed to send request to target")
			targetConn.Close()
			sourceConn.Close()
			return
		}
		dps.executor.Add(1)
		go func() {
			BiDirectionalTransfer(sourceConn, targetConn, dps.byteBufferSize, dps.idleTimeout, dps.executor)
			Logger.Printf("Request %d took %d ms", requestNo, (time.Since(startTime) - dps.idleTimeout).Milliseconds())
		}()
	}
}

func (dps *DomainProxyServer) isTargetWhitelisted(targetHost string, w http.ResponseWriter) bool {
	Logger.Printf("Target host %s", targetHost)
	if !dps.proxyTargetWhitelist[targetHost] && !dps.nonProxyHosts[targetHost] {
		Logger.Println("Target host is not whitelisted or a non-proxy host")
		http.Error(w, "The requested resource was not found.", http.StatusNotFound)
		return false
	}
	return true
}

func (dps *DomainProxyServer) handleErrorResponse(w http.ResponseWriter, err error, message string) {
	Logger.Printf("%s: %v", message, err)
	http.Error(w, message+": "+err.Error(), http.StatusBadGateway)
}

func (dps *DomainProxyServer) Stop() {
	Logger.Println("Shutting down domain proxy server...")
	close(dps.shutdownChan)
	if err := dps.listener.Close(); err != nil {
		Logger.Printf("Error closing listener: %v", err)
	}
	dps.executor.Wait()
	if _, err := os.Stat(dps.domainSocket); err == nil {
		if err := os.Remove(dps.domainSocket); err != nil {
			Logger.Printf("Error deleting domain socket: %v", err)
		}
	}
}

type responseWriter struct {
	conn       net.Conn
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
	return rw.conn.Write(data)
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
	if _, err := rw.conn.Write([]byte(headers)); err != nil {
		Logger.Printf("Error writing headers to connection: %v", err)
	}
}

func main() {
	InitLogger("Domain Proxy Server")
	server := NewDomainProxyServer(GetDomainSocket(),
		GetByteBufferSize(),
		GetConnectionTimeout(),
		GetIdleTimeout(),
		GetCsvEnvVariable(ProxyTargetWhitelistKey, DefaultProxyTargetWhitelist),
		GetCsvEnvVariable(InternalNonProxyHostsKey, DefaultInternalNonProxyHosts), // TODO Implement Non-proxy logic
	)
	server.Start()
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	<-sigs
	server.Stop()
}
