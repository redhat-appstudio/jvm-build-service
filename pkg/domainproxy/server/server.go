package server

import (
	"bufio"
	"encoding/base64"
	"fmt"
	. "github.com/redhat-appstudio/jvm-build-service/pkg/domainproxy/common"
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
	DefaultProxyTargetWhitelist  = "localhost,repo.maven.apache.org,repository.jboss.org,packages.confluent.io,jitpack.io,repo.gradle.org,plugins.gradle.org"
	InternalProxyKey             = "INTERNAL_PROXY"
	DefaultInternalProxy         = false
	InternalProxyHostKey         = "INTERNAL_PROXY_HOST"
	DefaultInternalProxyHost     = "indy-generic-proxy"
	InternalProxyPortKey         = "INTERNAL_PROXY_PORT"
	DefaultInternalProxyPort     = 80
	InternalProxyUserKey         = "INTERNAL_PROXY_USER"
	DefaultInternalProxyUser     = ""
	InternalProxyPasswordKey     = "INTERNAL_PROXY_PASSWORD"
	DefaultInternalProxyPassword = ""
	InternalNonProxyHostsKey     = "INTERNAL_NON_PROXY_HOSTS"
	DefaultInternalNonProxyHosts = "localhost"
	DomainSocketToHttp           = "Domain Socket <-> HTTP"
	DomainSocketToHttps          = "Domain Socket <-> HTTPS"
)

var logger = NewLogger("Domain Proxy Server")
var common = NewCommon(logger)

type DomainProxyServer struct {
	sharedParams           SharedParams
	proxyTargetWhitelist   map[string]bool
	internalProxy          bool
	internalProxyHost      string
	internalProxyPort      int
	internalProxyUser      string
	internalProxyPassword  string
	internalNonProxyHosts  map[string]bool
	httpConnectionCounter  atomic.Uint64
	httpsConnectionCounter atomic.Uint64
	listener               net.Listener
	shutdownChan           chan struct{}
}

func NewDomainProxyServer() *DomainProxyServer {
	return &DomainProxyServer{
		sharedParams:          common.NewSharedParams(),
		proxyTargetWhitelist:  getProxyTargetWhitelist(),
		internalProxy:         getInternalProxy(),
		internalProxyHost:     getInternalProxyHost(),
		internalProxyPort:     getInternalProxyPort(),
		internalProxyUser:     getInternalProxyUser(),
		internalProxyPassword: getInternalProxyPassword(),
		internalNonProxyHosts: getInternalNonProxyHosts(),
		shutdownChan:          make(chan struct{}),
	}
}

func (dps *DomainProxyServer) Start() {
	logger.Println("Starting domain proxy server...")
	go dps.startServer()
}

func (dps *DomainProxyServer) startServer() {
	sharedParams := dps.sharedParams
	if _, err := os.Stat(sharedParams.DomainSocket); err == nil {
		if err := os.Remove(sharedParams.DomainSocket); err != nil {
			logger.Fatalf("Failed to delete existing domain socket: %v", err)
		}
	}
	var err error
	dps.listener, err = net.Listen("unix", sharedParams.DomainSocket)
	if err != nil {
		logger.Fatalf("Failed to start domain socket listener: %v", err)
	}
	logger.Printf("Domain socket server listening on %s", sharedParams.DomainSocket)
	for {
		if domainConnection, err := dps.listener.Accept(); err != nil {
			select {
			case <-dps.shutdownChan:
				return
			default:
				logger.Printf("Failed to accept domain socket connection: %v", err)
			}
		} else {
			go dps.handleConnectionRequest(domainConnection)
		}
	}
}

func (dps *DomainProxyServer) handleConnectionRequest(domainConnection net.Conn) {
	sharedParams := dps.sharedParams
	if err := domainConnection.SetDeadline(time.Now().Add(sharedParams.IdleTimeout)); err != nil {
		common.HandleSetDeadlineError(domainConnection, err)
		return
	}
	reader := bufio.NewReader(domainConnection)
	request, err := http.ReadRequest(reader)
	if err != nil {
		logger.Printf("Failed to read request: %v", err)
		if err = domainConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	writer := &responseWriter{connection: domainConnection}
	if err = domainConnection.SetDeadline(time.Now().Add(sharedParams.IdleTimeout)); err != nil {
		common.HandleSetDeadlineError(domainConnection, err)
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
	actualTargetHost, actualTargetPort := targetHost, targetPort
	targetConnectionName := "target"
	useProxy := dps.useInternalProxy(targetHost)
	if useProxy {
		targetHost, targetPort = dps.internalProxyHost, dps.internalProxyPort
		logger.Printf("Handling %s Connection %d with internal proxy %s:%d and target %s:%d", DomainSocketToHttp, connectionNo, targetHost, targetPort, actualTargetHost, actualTargetPort)
		targetConnectionName = "internal proxy"
	} else {
		logger.Printf("Handling %s Connection %d with target %s:%d", DomainSocketToHttp, connectionNo, actualTargetHost, actualTargetPort)
	}
	if !dps.isTargetWhitelisted(actualTargetHost, writer) {
		if err := sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	startTime := time.Now()
	sharedParams := dps.sharedParams
	if useProxy {
		request.Header.Set("Host", fmt.Sprintf("%s:%d", actualTargetHost, actualTargetPort))
		request.Header.Set("Proxy-Connection", "Keep-Alive")
		if dps.internalProxyUser != "" && dps.internalProxyPassword != "" {
			request.Header.Set("Proxy-Authorization", "Basic "+dps.getBasicAuth())
		}
	}
	targetConnection, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", targetHost, targetPort), sharedParams.ConnectionTimeout)
	if err != nil {
		dps.handleErrorResponse(writer, err, fmt.Sprintf("Failed to connect to %s", targetConnectionName))
		if err = sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	} else if err = request.Write(targetConnection); err != nil {
		dps.handleErrorResponse(writer, err, fmt.Sprintf("Failed to send request to %s", targetConnectionName))
		if err = targetConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		if err = sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	go func() {
		common.BiDirectionalTransfer(sourceConnection, targetConnection, sharedParams.ByteBufferSize, sharedParams.IdleTimeout, DomainSocketToHttp, connectionNo)
		logger.Printf("%s Connection %d ended after %d ms", DomainSocketToHttp, connectionNo, time.Since(startTime).Milliseconds())
	}()
}

func (dps *DomainProxyServer) handleHttpsConnection(sourceConnection net.Conn, writer http.ResponseWriter, request *http.Request) {
	connectionNo := dps.httpsConnectionCounter.Add(1)
	targetHost, targetPort := getTargetHostAndPort(request.Host, HttpsPort)
	actualTargetHost, actualTargetPort := targetHost, targetPort
	targetConnectionName := "target"
	useProxy := dps.useInternalProxy(targetHost)
	if useProxy {
		targetHost, targetPort = dps.internalProxyHost, dps.internalProxyPort
		logger.Printf("Handling %s Connection %d with internal proxy %s:%d and target %s:%d", DomainSocketToHttps, connectionNo, targetHost, targetPort, actualTargetHost, actualTargetPort)
		targetConnectionName = "internal proxy"
	} else {
		logger.Printf("Handling %s Connection %d with target %s:%d", DomainSocketToHttps, connectionNo, actualTargetHost, actualTargetPort)
	}
	if !dps.isTargetWhitelisted(actualTargetHost, writer) {
		if err := sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	startTime := time.Now()
	sharedParams := dps.sharedParams
	targetConnection, err := net.DialTimeout("tcp", fmt.Sprintf("%s:%d", targetHost, targetPort), sharedParams.ConnectionTimeout)
	if err != nil {
		dps.handleErrorResponse(writer, err, fmt.Sprintf("Failed to connect to %s", targetConnectionName))
		if err = sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	if useProxy {
		proxyConnectRequest := fmt.Sprintf("CONNECT %s:%d HTTP/1.1\r\nHost: %s:%d\r\nProxy-Connection: Keep-Alive\r\n", actualTargetHost, actualTargetPort, actualTargetHost, actualTargetPort)
		if dps.internalProxyUser != "" && dps.internalProxyPassword != "" {
			proxyConnectRequest += fmt.Sprintf("Proxy-Authorization: Basic %s\r\n", dps.getBasicAuth())
		}
		proxyConnectRequest += "\r\n"
		if _, err = targetConnection.Write([]byte(proxyConnectRequest)); err != nil {
			dps.handleErrorResponse(writer, err, "Failed to send connect request to internal proxy")
			if err = targetConnection.Close(); err != nil {
				common.HandleConnectionCloseError(err)
			}
			if err = sourceConnection.Close(); err != nil {
				common.HandleConnectionCloseError(err)
			}
			return
		}
		proxyReader := bufio.NewReader(targetConnection)
		proxyResponse, err := http.ReadResponse(proxyReader, request)
		if err != nil || proxyResponse.StatusCode != http.StatusOK {
			dps.handleErrorResponse(writer, err, "Failed to establish tunnel with internal proxy")
			if err = targetConnection.Close(); err != nil {
				common.HandleConnectionCloseError(err)
			}
			if err = sourceConnection.Close(); err != nil {
				common.HandleConnectionCloseError(err)
			}
			return
		}
	}
	if _, err = writer.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n")); err != nil {
		dps.handleErrorResponse(writer, err, "Failed to send response to source")
		if err = targetConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		if err = sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	go func() {
		common.BiDirectionalTransfer(sourceConnection, targetConnection, sharedParams.ByteBufferSize, sharedParams.IdleTimeout, DomainSocketToHttps, connectionNo)
		logger.Printf("%s Connection %d ended after %d ms", DomainSocketToHttps, connectionNo, time.Since(startTime).Milliseconds())
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
	if !dps.proxyTargetWhitelist[targetHost] {
		message := fmt.Sprintf("Target host %s is not whitelisted", targetHost)
		logger.Println(message)
		http.Error(writer, message, http.StatusForbidden)
		return false
	}
	return true
}

func (dps *DomainProxyServer) useInternalProxy(targetHost string) bool {
	return dps.internalProxy && !dps.internalNonProxyHosts[targetHost]
}

func (dps *DomainProxyServer) getBasicAuth() string {
	return base64.StdEncoding.EncodeToString([]byte(dps.internalProxyUser + ":" + dps.internalProxyPassword))
}

func (dps *DomainProxyServer) handleErrorResponse(writer http.ResponseWriter, err error, message string) {
	logger.Printf("%s: %v", message, err)
	http.Error(writer, message+": "+err.Error(), http.StatusBadGateway)
}

func (dps *DomainProxyServer) Stop() {
	logger.Println("Shutting down domain proxy server...")
	close(dps.shutdownChan)
	if dps.listener != nil {
		if err := dps.listener.Close(); err != nil {
			common.HandleListenerCloseError(err)
		}
	}
	sharedParams := dps.sharedParams
	if _, err := os.Stat(sharedParams.DomainSocket); err == nil {
		if err := os.Remove(sharedParams.DomainSocket); err != nil {
			logger.Printf("Failed to delete domain socket: %v", err)
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
		logger.Printf("Failed to write headers to connection: %v", err)
	}
}

func getProxyTargetWhitelist() map[string]bool {
	return common.GetCsvEnvVariable(ProxyTargetWhitelistKey, DefaultProxyTargetWhitelist)
}

func getInternalProxy() bool {
	return common.GetBoolEnvVariable(InternalProxyKey, DefaultInternalProxy)
}

func getInternalProxyHost() string {
	return common.GetEnvVariable(InternalProxyHostKey, DefaultInternalProxyHost)
}

func getInternalProxyPort() int {
	return common.GetIntEnvVariable(InternalProxyPortKey, DefaultInternalProxyPort)
}

func getInternalProxyUser() string {
	return common.GetEnvVariable(InternalProxyUserKey, DefaultInternalProxyUser)
}

func getInternalProxyPassword() string {
	return common.GetEnvVariable(InternalProxyPasswordKey, DefaultInternalProxyPassword)
}

func getInternalNonProxyHosts() map[string]bool {
	return common.GetCsvEnvVariable(InternalNonProxyHostsKey, DefaultInternalNonProxyHosts)
}
