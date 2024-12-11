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
	TargetWhitelistKey           = "DOMAIN_PROXY_TARGET_WHITELIST"
	DefaultTargetWhitelist       = "localhost,repo.maven.apache.org,repository.jboss.org,packages.confluent.io,jitpack.io,repo.gradle.org,plugins.gradle.org"
	EnableInternalProxyKey       = "DOMAIN_PROXY_ENABLE_INTERNAL_PROXY"
	DefaultEnableInternalProxy   = false
	InternalProxyHostKey         = "DOMAIN_PROXY_INTERNAL_PROXY_HOST"
	DefaultInternalProxyHost     = "indy-generic-proxy"
	InternalProxyPortKey         = "DOMAIN_PROXY_INTERNAL_PROXY_PORT"
	DefaultInternalProxyPort     = 80
	InternalProxyUserKey         = "DOMAIN_PROXY_INTERNAL_PROXY_USER"
	DefaultInternalProxyUser     = ""
	InternalProxyPasswordKey     = "DOMAIN_PROXY_INTERNAL_PROXY_PASSWORD"
	DefaultInternalProxyPassword = ""
	InternalNonProxyHostsKey     = "DOMAIN_PROXY_INTERNAL_NON_PROXY_HOSTS"
	DefaultInternalNonProxyHosts = "localhost"
	DomainSocketToHttp           = "Domain Socket <-> HTTP"
	DomainSocketToHttps          = "Domain Socket <-> HTTPS"
)

var logger = NewLogger("Domain Proxy Server")
var common = NewCommon(logger)

type DomainProxyServer struct {
	sharedParams           *SharedParams
	targetWhitelist        map[string]bool
	enableInternalProxy    bool
	internalProxyHost      string
	internalProxyPort      int
	internalProxyUser      string
	internalProxyPassword  string
	internalNonProxyHosts  map[string]bool
	httpsConnectionCounter atomic.Uint64
}

func NewDomainProxyServer() *DomainProxyServer {
	return &DomainProxyServer{
		sharedParams:          common.NewSharedParams(),
		targetWhitelist:       getTargetWhitelist(),
		enableInternalProxy:   getEnableInternalProxy(),
		internalProxyHost:     getInternalProxyHost(),
		internalProxyPort:     getInternalProxyPort(),
		internalProxyUser:     getInternalProxyUser(),
		internalProxyPassword: getInternalProxyPassword(),
		internalNonProxyHosts: getInternalNonProxyHosts(),
	}
}

func (dps *DomainProxyServer) Start(ready chan<- bool) {
	sharedParams := dps.sharedParams
	logger.Println("Starting domain proxy server...")
	if _, err := os.Stat(sharedParams.DomainSocket); err == nil {
		if err := os.Remove(sharedParams.DomainSocket); err != nil {
			logger.Fatalf("Failed to delete existing domain socket: %v", err)
		}
	}
	var err error
	sharedParams.Listener, err = net.Listen(UNIX, sharedParams.DomainSocket)
	if err != nil {
		logger.Fatalf("Failed to start domain socket listener: %v", err)
	}
	go dps.startServer(ready)
}

func (dps *DomainProxyServer) startServer(ready chan<- bool) {
	sharedParams := dps.sharedParams
	logger.Printf("Domain socket server listening on %s", sharedParams.DomainSocket)
	ready <- true
	for {
		select {
		case <-sharedParams.RunningContext.Done():
			return
		default:
			if domainConnection, err := sharedParams.Listener.Accept(); err != nil {
				select {
				case <-sharedParams.RunningContext.Done():
					return
				default:
					logger.Printf("Failed to accept domain socket connection: %v", err)
				}
			} else {
				go dps.handleConnectionRequest(domainConnection)
			}
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
	if request.Method == http.MethodConnect {
		dps.handleHttpsConnection(domainConnection, writer, request)
	} else {
		dps.handleHttpConnection(domainConnection, writer, request)
	}
}

func (dps *DomainProxyServer) handleHttpConnection(sourceConnection net.Conn, writer http.ResponseWriter, request *http.Request) {
	sharedParams := dps.sharedParams
	connectionNo := sharedParams.HttpConnectionCounter.Add(1)
	targetHost, targetPort := getTargetHostAndPort(request.Host, HttpPort)
	actualTargetHost, actualTargetPort := targetHost, targetPort
	targetConnectionName := "target"
	useInternalProxy := dps.useInternalProxy(targetHost)
	// Redirect connection to internal proxy if enabled
	if useInternalProxy {
		targetHost, targetPort = dps.internalProxyHost, dps.internalProxyPort
		logger.Printf("Handling %s Connection %d with internal proxy %s:%d and target %s:%d", DomainSocketToHttp, connectionNo, targetHost, targetPort, actualTargetHost, actualTargetPort)
		targetConnectionName = "internal proxy"
	} else {
		logger.Printf("Handling %s Connection %d with target %s:%d", DomainSocketToHttp, connectionNo, actualTargetHost, actualTargetPort)
	}
	// Check if target is whitelisted
	if !dps.isTargetWhitelisted(actualTargetHost, writer) {
		if err := sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	startTime := time.Now()
	request.Header.Del("Proxy-Connection")    // Prevent keep-alive as it breaks internal proxy authentication
	request.Header.Set("Connection", "close") // Prevent keep-alive as it breaks internal proxy authentication
	// Update request with target details for internal proxy if enabled
	if useInternalProxy {
		request.Header.Set("Host", fmt.Sprintf("%s:%d", actualTargetHost, actualTargetPort))
		// Add authentication details if configured
		if dps.internalProxyUser != "" && dps.internalProxyPassword != "" {
			request.Header.Set("Proxy-Authorization", "Basic "+GetBasicAuth(dps.internalProxyUser, dps.internalProxyPassword))
		}
	}
	// Try to connect to target or internal proxy
	targetConnection, err := net.DialTimeout(TCP, fmt.Sprintf("%s:%d", targetHost, targetPort), sharedParams.ConnectionTimeout)
	if err != nil {
		dps.handleErrorResponse(writer, err, fmt.Sprintf("Failed to connect to %s", targetConnectionName))
		if err = sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	if err = targetConnection.SetDeadline(time.Now().Add(sharedParams.IdleTimeout)); err != nil {
		common.HandleSetDeadlineError(targetConnection, err)
		if err = sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	// Send HTTP request to internal proxy if enabled
	if useInternalProxy {
		err = request.WriteProxy(targetConnection)
	} else {
		err = request.Write(targetConnection)
	}
	if err != nil {
		dps.handleErrorResponse(writer, err, fmt.Sprintf("Failed to send request to %s", targetConnectionName))
		if err = targetConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		if err = sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	// Initiate transfer between source and target or internal proxy
	go func() {
		common.BiDirectionalTransfer(sharedParams.RunningContext, sourceConnection, targetConnection, sharedParams.ByteBufferSize, DomainSocketToHttp, connectionNo)
		logger.Printf("%s Connection %d ended after %d ms", DomainSocketToHttp, connectionNo, time.Since(startTime).Milliseconds())
	}()
}

func (dps *DomainProxyServer) handleHttpsConnection(sourceConnection net.Conn, writer http.ResponseWriter, request *http.Request) {
	sharedParams := dps.sharedParams
	connectionNo := dps.httpsConnectionCounter.Add(1)
	targetHost, targetPort := getTargetHostAndPort(request.Host, HttpsPort)
	actualTargetHost, actualTargetPort := targetHost, targetPort
	targetConnectionName := "target"
	useInternalProxy := dps.useInternalProxy(targetHost)
	// Redirect connection to internal proxy if enabled
	if useInternalProxy {
		targetHost, targetPort = dps.internalProxyHost, dps.internalProxyPort
		logger.Printf("Handling %s Connection %d with internal proxy %s:%d and target %s:%d", DomainSocketToHttps, connectionNo, targetHost, targetPort, actualTargetHost, actualTargetPort)
		targetConnectionName = "internal proxy"
	} else {
		logger.Printf("Handling %s Connection %d with target %s:%d", DomainSocketToHttps, connectionNo, actualTargetHost, actualTargetPort)
	}
	// Check if target is whitelisted
	if !dps.isTargetWhitelisted(actualTargetHost, writer) {
		if err := sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	startTime := time.Now()
	request.Header.Del("Proxy-Connection")    // Prevent keep-alive as it breaks internal proxy authentication
	request.Header.Set("Connection", "close") // Prevent keep-alive as it breaks internal proxy authentication
	// Try to connect to target or internal proxy
	targetConnection, err := net.DialTimeout(TCP, fmt.Sprintf("%s:%d", targetHost, targetPort), sharedParams.ConnectionTimeout)
	if err != nil {
		dps.handleErrorResponse(writer, err, fmt.Sprintf("Failed to connect to %s", targetConnectionName))
		if err = sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	if err = targetConnection.SetDeadline(time.Now().Add(sharedParams.IdleTimeout)); err != nil {
		common.HandleSetDeadlineError(targetConnection, err)
		if err = sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	// Create HTTPS connection to internal proxy if enabled
	if useInternalProxy {
		proxyConnectRequest := fmt.Sprintf("CONNECT %s:%d HTTP/1.1\r\nHost: %s:%d\r\nConnection: close\r\n", actualTargetHost, actualTargetPort, actualTargetHost, actualTargetPort) // Prevent keep-alive as it breaks internal proxy authentication
		// Add authentication details if configured
		if dps.internalProxyUser != "" && dps.internalProxyPassword != "" {
			proxyConnectRequest += fmt.Sprintf("Proxy-Authorization: Basic %s\r\n", GetBasicAuth(dps.internalProxyUser, dps.internalProxyPassword))
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
		if err != nil {
			dps.handleErrorResponse(writer, err, "Failed to establish connection with internal proxy")
			if err = targetConnection.Close(); err != nil {
				common.HandleConnectionCloseError(err)
			}
			if err = sourceConnection.Close(); err != nil {
				common.HandleConnectionCloseError(err)
			}
			return
		} else if proxyResponse.StatusCode != http.StatusOK {
			proxyResponse.Header.Set("Connection", "close") // Prevent keep-alive as it breaks internal proxy authentication
			if err := proxyResponse.Write(sourceConnection); err != nil {
				dps.handleErrorResponse(writer, err, "Failed to send internal proxy response to source")
			}
			if err = targetConnection.Close(); err != nil {
				common.HandleConnectionCloseError(err)
			}
			if err = sourceConnection.Close(); err != nil {
				common.HandleConnectionCloseError(err)
			}
			return
		}
	}
	// Notify source that HTTPS connection has been established to target or internal proxy
	if _, err = writer.Write([]byte("HTTP/1.1 200 Connection Established\r\nConnection: close\r\n\r\n")); err != nil { // Prevent keep-alive as it breaks internal proxy authentication
		dps.handleErrorResponse(writer, err, "Failed to send connect response to source")
		if err = targetConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		if err = sourceConnection.Close(); err != nil {
			common.HandleConnectionCloseError(err)
		}
		return
	}
	// Initiate transfer between source and target or internal proxy
	go func() {
		common.BiDirectionalTransfer(sharedParams.RunningContext, sourceConnection, targetConnection, sharedParams.ByteBufferSize, DomainSocketToHttps, connectionNo)
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
	if !dps.targetWhitelist[targetHost] {
		message := fmt.Sprintf("Target host %s is not whitelisted", targetHost)
		logger.Println(message)
		http.Error(writer, message, http.StatusForbidden)
		return false
	}
	return true
}

func (dps *DomainProxyServer) useInternalProxy(targetHost string) bool {
	if dps.enableInternalProxy {
		if !dps.internalNonProxyHosts[targetHost] {
			return true
		} else {
			logger.Printf("Target host %s is non-proxy host", targetHost)
		}
	}
	return false
}

func GetBasicAuth(user string, password string) string {
	return base64.StdEncoding.EncodeToString([]byte(user + ":" + password))
}

func (dps *DomainProxyServer) handleErrorResponse(writer http.ResponseWriter, err error, message string) {
	logger.Printf("%s: %v", message, err)
	writer.Header().Set("Connection", "close") // Prevent keep-alive as it breaks internal proxy authentication
	status := http.StatusInternalServerError
	http.Error(writer, message+": "+err.Error(), status)
}

func (dps *DomainProxyServer) Stop() {
	sharedParams := dps.sharedParams
	logger.Println("Shutting down domain proxy server...")
	sharedParams.InitiateShutdown()
	if sharedParams.Listener != nil {
		if err := sharedParams.Listener.Close(); err != nil {
			common.HandleListenerCloseError(err)
		}
	}
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
	headers += "Connection: close\r\n" // Prevent keep-alive as it breaks internal proxy authentication
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

func getTargetWhitelist() map[string]bool {
	return common.GetCsvEnvVariable(TargetWhitelistKey, DefaultTargetWhitelist)
}

func getEnableInternalProxy() bool {
	return common.GetBoolEnvVariable(EnableInternalProxyKey, DefaultEnableInternalProxy)
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
