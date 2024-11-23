package main

import (
	"bufio"
	"fmt"
	"io"
	"log"
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
	proxyTargetWhitelist map[string]bool
	nonProxyHosts        map[string]bool
	counter              int
	listener             net.Listener
	executor             *sync.WaitGroup
	shutdownChan         chan struct{}
}

func NewDomainProxyServer(domainSocket string, byteBufferSize int, proxyTargetWhitelist, nonProxyHosts map[string]bool) *DomainProxyServer {
	return &DomainProxyServer{
		domainSocket:         domainSocket,
		byteBufferSize:       byteBufferSize,
		proxyTargetWhitelist: proxyTargetWhitelist,
		nonProxyHosts:        nonProxyHosts,
		counter:              0,
		executor:             &sync.WaitGroup{},
		shutdownChan:         make(chan struct{}),
	}
}

func (dps *DomainProxyServer) Start() {
	log.Println("Starting domain proxy server...")
	log.Printf("Byte buffer size %d", dps.byteBufferSize)              // TODO Remove
	log.Printf("Proxy target whitelist: %v", dps.proxyTargetWhitelist) // TODO Remove
	dps.executor.Add(1)
	go dps.startServer()
}

func (dps *DomainProxyServer) startServer() {
	defer dps.executor.Done()
	if _, err := os.Stat(dps.domainSocket); err == nil {
		if err := os.Remove(dps.domainSocket); err != nil {
			log.Fatalf("Failed to delete existing domain socket: %v", err)
		}
	}
	var err error
	dps.listener, err = net.Listen("unix", dps.domainSocket)
	if err != nil {
		log.Fatalf("Failed to start domain socket listener: %v", err)
	}
	log.Println("Domain socket server listening on", dps.domainSocket)
	for {
		conn, err := dps.listener.Accept()
		if err != nil {
			select {
			case <-dps.shutdownChan:
				return
			default:
				log.Printf("Failed to accept connection: %v", err)
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
	conn.SetReadDeadline(time.Now().Add(Timeout))
	reader := bufio.NewReader(conn)
	req, err := http.ReadRequest(reader)
	if err != nil {
		log.Printf("Failed to read request: %v", err)
		conn.Close()
		return
	}
	w := &responseWriter{conn: conn}
	if req.Method == http.MethodConnect {
		dps.handleHttpsRequest(conn, w, req)
	} else {
		dps.handleHttpRequest(w, req)
	}
}

func (dps *DomainProxyServer) handleHttpRequest(w http.ResponseWriter, r *http.Request) {
	log.Printf("Handling HTTP %s Request", r.Method)
	requestNo := dps.counter
	log.Printf("Request %d", requestNo)
	hostPort := strings.Split(r.Host, ":")
	targetHost := hostPort[0]
	if dps.isTargetWhitelisted(targetHost, w) {
		log.Printf("Target URI %s", r.RequestURI)
		startTime := time.Now()
		client := http.Client{Timeout: Timeout}
		resp, err := client.Get(r.RequestURI)
		if err != nil {
			dps.handleErrorResponse(w, err, "Failed to get response")
			return
		}
		defer resp.Body.Close()
		log.Printf("Request %d took %d ms", requestNo, time.Since(startTime).Milliseconds())
		for k, v := range resp.Header {
			for _, vv := range v {
				w.Header().Add(k, vv)
			}
		}
		w.WriteHeader(resp.StatusCode)
		if _, err := io.CopyBuffer(w, resp.Body, make([]byte, dps.byteBufferSize)); err != nil {
			log.Printf("Error copying response body: %v", err)
		}
	}
}

func (dps *DomainProxyServer) handleHttpsRequest(sourceConn net.Conn, w http.ResponseWriter, r *http.Request) {
	log.Printf("Handling HTTPS %s Request", r.Method)
	requestNo := dps.counter
	log.Printf("Request %d", requestNo)
	hostPort := strings.Split(r.Host, ":")
	targetHost := hostPort[0]
	targetPort := HttpsPort
	if len(hostPort) > 1 {
		if port, err := strconv.Atoi(hostPort[1]); err == nil {
			targetPort = port
		}
	}
	if dps.isTargetWhitelisted(targetHost, w) {
		log.Printf("Target URI %s", r.RequestURI)
		startTime := time.Now()
		targetConn, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", targetHost, targetPort), Timeout)
		if err != nil {
			dps.handleErrorResponse(w, err, "Failed to connect to target")
			return
		}
		log.Printf("Request %d took %d ms", requestNo, time.Since(startTime).Milliseconds())
		if _, err := fmt.Fprint(sourceConn, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
			targetConn.Close()
			sourceConn.Close()
			return
		}
		dps.executor.Add(1)
		go ChannelToChannelBiDirectionalHandler(dps.byteBufferSize, sourceConn, targetConn, dps.executor)
	}
}

func (dps *DomainProxyServer) isTargetWhitelisted(targetHost string, w http.ResponseWriter) bool {
	log.Printf("Target host %s", targetHost)
	if !dps.proxyTargetWhitelist[targetHost] && !dps.nonProxyHosts[targetHost] {
		log.Println("Target host is not whitelisted or a non-proxy host")
		http.Error(w, "The requested resource was not found.", http.StatusNotFound)
		return false
	}
	return true
}

func (dps *DomainProxyServer) handleErrorResponse(w http.ResponseWriter, err error, message string) {
	log.Printf("%s: %v", message, err)
	http.Error(w, message+": "+err.Error(), http.StatusBadGateway)
}

func (dps *DomainProxyServer) Stop() {
	log.Println("Shutting down domain proxy server...")
	close(dps.shutdownChan)
	if err := dps.listener.Close(); err != nil {
		log.Printf("Error closing listener: %v", err)
	}
	dps.executor.Wait()
	if _, err := os.Stat(dps.domainSocket); err == nil {
		if err := os.Remove(dps.domainSocket); err != nil {
			log.Printf("Error deleting domain socket: %v", err)
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
		log.Printf("Error writing headers to connection: %v", err)
	}
}

func main() {
	server := NewDomainProxyServer(GetDomainSocket(),
		GetByteBufferSize(),
		GetCsvEnvVariable(ProxyTargetWhitelistKey, DefaultProxyTargetWhitelist),
		GetCsvEnvVariable(InternalNonProxyHostsKey, DefaultInternalNonProxyHosts), // TODO Implement Non-proxy logic
	)
	server.Start()
	sigs := make(chan os.Signal, 1)
	signal.Notify(sigs, syscall.SIGINT, syscall.SIGTERM)
	<-sigs
	server.Stop()
}
