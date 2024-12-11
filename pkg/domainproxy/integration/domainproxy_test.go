package integration

import (
	"crypto/md5"
	"crypto/tls"
	"encoding/hex"
	"errors"
	"fmt"
	"github.com/elazarl/goproxy"
	. "github.com/redhat-appstudio/jvm-build-service/pkg/domainproxy/client"
	. "github.com/redhat-appstudio/jvm-build-service/pkg/domainproxy/common"
	. "github.com/redhat-appstudio/jvm-build-service/pkg/domainproxy/server"
	"io"
	"math/rand"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"strings"
	"testing"
)

const (
	DomainProxyPort    = "8081"
	InternalProxyPort  = "8082"
	DomainProxyUrl     = "http://" + Localhost + ":" + DomainProxyPort
	ContentType        = "text/xml"
	Md5Hash            = "ea3ca57f8f99d1d210d1b438c9841440"
	ContentLength      = "403"
	MockUrlPath        = "/com/foo/bar/1.0/bar-1.0.pom"
	NonExistentUrlPath = "/com/foo/bar/1.0/bar-2.0.pom"
	NonWhitelistedUrl  = "repo1.maven.org/maven2/org/apache/maven/plugins/maven-jar-plugin/3.4.1/maven-jar-plugin-3.4.1.jar"
	NonExistentHost    = "foo.bar"
	User               = "foo"
	Password           = "bar"
)

func createClient(t *testing.T) *http.Client {
	proxyUrl, err := url.Parse(DomainProxyUrl)
	if err != nil {
		t.Fatal(err)
	}
	transport := &http.Transport{
		Proxy:           http.ProxyURL(proxyUrl),
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
	}
	return &http.Client{
		Transport: transport,
	}
}

func getMd5Hash(bytes []byte) string {
	hash := md5.Sum(bytes)
	return hex.EncodeToString(hash[:])
}

func getRandomDomainSocket() string {
	return "/tmp/domain-socket-" + strconv.Itoa(rand.Int()) + ".sock"
}

func mockHandler(t *testing.T) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet && r.URL.Path == MockUrlPath {
			// Mock GET response
			pom, err := os.ReadFile("testdata/bar-1.0.pom")
			if err != nil {
				t.Fatal(err)
			}
			w.Header().Set("Content-Type", ContentType)
			w.WriteHeader(http.StatusOK)
			if _, err := w.Write(pom); err != nil {
				t.Fatal(err)
			}
		} else if r.Method == http.MethodHead && r.URL.Path == MockUrlPath {
			// Mock HEAD response
			w.Header().Set("Content-Type", ContentType)
			w.Header().Set("Content-Length", ContentLength)
			w.WriteHeader(http.StatusOK)
		} else {
			http.NotFound(w, r)
		}
	}
}

func startDomainProxy() (*DomainProxyServer, *DomainProxyClient) {
	domainProxyServer := NewDomainProxyServer()
	serverReady := make(chan bool)
	go domainProxyServer.Start(serverReady)
	<-serverReady
	clientReady := make(chan bool)
	domainProxyClient := NewDomainProxyClient()
	go domainProxyClient.Start(clientReady)
	<-clientReady
	return domainProxyServer, domainProxyClient
}

func stopDomainProxy(domainProxyServer *DomainProxyServer, domainProxyClient *DomainProxyClient) {
	domainProxyServer.Stop()
	domainProxyClient.Stop()
}

func startMockServers(t *testing.T) (*httptest.Server, *httptest.Server) {
	mockHandler := mockHandler(t)
	mockHttpServer := httptest.NewServer(mockHandler)
	mockHttpsServer := httptest.NewUnstartedServer(mockHandler)
	mockHttpsServer.StartTLS()
	return mockHttpServer, mockHttpsServer
}

func stopMockServers(mockHttpServer *httptest.Server, mockHttpsServer *httptest.Server) {
	mockHttpServer.Close()
	mockHttpsServer.Close()
}

func startInternalProxyServer(t *testing.T, onRequestFunction func(req *http.Request, ctx *goproxy.ProxyCtx) (*http.Request, *http.Response), onConnectFunction func(host string, ctx *goproxy.ProxyCtx) (*goproxy.ConnectAction, string)) *http.Server {
	internalProxy := goproxy.NewProxyHttpServer()
	internalProxy.Verbose = true
	if onRequestFunction != nil {
		internalProxy.OnRequest().DoFunc(onRequestFunction)
		internalProxy.OnRequest().HandleConnectFunc(onConnectFunction)
	}
	internalProxyServer := &http.Server{
		Addr:    Localhost + ":" + InternalProxyPort,
		Handler: internalProxy,
	}
	go func() {
		if err := internalProxyServer.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			t.Error(err)
		}
	}()
	return internalProxyServer
}

func stopInternalProxyServer(t *testing.T, internalProxyServer *http.Server) {
	err := internalProxyServer.Close()
	if err != nil {
		t.Fatal(err)
	}
}

func commonTestBehaviour(t *testing.T, qualifier string) {
	// Set env variables
	t.Setenv(DomainSocketKey, getRandomDomainSocket())
	t.Setenv(HttpPortKey, DomainProxyPort)
	t.Setenv(TargetWhitelistKey, "127.0.0.1,foo.bar")
	// Start services
	domainProxyServer, domainProxyClient := startDomainProxy()
	defer stopDomainProxy(domainProxyServer, domainProxyClient)
	// Start mock HTTP and HTTPS servers
	mockHttpServer, mockHttpsServer := startMockServers(t)
	defer stopMockServers(mockHttpServer, mockHttpsServer)
	mockHttpUrl := mockHttpServer.URL
	mockHttpsUrl := mockHttpsServer.URL
	// Create HTTP client
	httpClient := createClient(t)

	t.Run(fmt.Sprintf("Test HTTP GET dependency%s", qualifier), func(t *testing.T) {
		response, err := httpClient.Get(mockHttpUrl + MockUrlPath)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusOK {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusOK)
		}
		pom, err := io.ReadAll(response.Body)
		if err != nil {
			t.Fatal(err)
		}
		hash := getMd5Hash(pom)
		if hash != Md5Hash {
			t.Fatalf("Actual MD5 hash %s did not match expected MD5 hash %s", hash, Md5Hash)
		}
	})

	t.Run(fmt.Sprintf("Test HTTPS GET dependency%s", qualifier), func(t *testing.T) {
		response, err := httpClient.Get(mockHttpsUrl + MockUrlPath)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusOK {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusOK)
		}
		pom, err := io.ReadAll(response.Body)
		if err != nil {
			t.Fatal(err)
		}
		hash := getMd5Hash(pom)
		if hash != Md5Hash {
			t.Fatalf("Actual MD5 hash %s did not match expected MD5 hash %s", hash, Md5Hash)
		}
	})

	t.Run(fmt.Sprintf("Test HTTP GET non-existent dependency%s", qualifier), func(t *testing.T) {
		response, err := httpClient.Get(mockHttpUrl + NonExistentUrlPath)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusNotFound {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusNotFound)
		}
	})

	t.Run(fmt.Sprintf("Test HTTPS GET non-existent dependency%s", qualifier), func(t *testing.T) {
		response, err := httpClient.Get(mockHttpsUrl + NonExistentUrlPath)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusNotFound {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusNotFound)
		}
	})

	t.Run(fmt.Sprintf("Test HTTP non-whitelisted host%s", qualifier), func(t *testing.T) {
		response, err := httpClient.Get("http://" + NonWhitelistedUrl)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusForbidden {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusForbidden)
		}
	})

	t.Run(fmt.Sprintf("Test HTTPS non-whitelisted host%s", qualifier), func(t *testing.T) {
		_, err := httpClient.Get("https://" + NonWhitelistedUrl)
		statusText := http.StatusText(http.StatusForbidden)
		if !strings.Contains(err.Error(), statusText) {
			t.Fatalf("Actual error %s did not contain expected HTTP status text %s", err.Error(), statusText)
		}
	})

	t.Run(fmt.Sprintf("Test HTTP non-existent host%s", qualifier), func(t *testing.T) {
		response, err := httpClient.Get("http://" + NonExistentHost)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusInternalServerError {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusInternalServerError)
		}
	})

	t.Run(fmt.Sprintf("Test HTTPS non-existent host%s", qualifier), func(t *testing.T) {
		_, err := httpClient.Get("https://" + NonExistentHost)
		internalServerStatusText := http.StatusText(http.StatusInternalServerError)
		badGatewayStatusText := http.StatusText(http.StatusBadGateway)
		if !strings.Contains(err.Error(), internalServerStatusText) && !strings.Contains(err.Error(), badGatewayStatusText) { // Internal proxy may return 502 Bad Gateway
			t.Fatalf("Actual error %s did not contain expected HTTP status text %s or %s", err.Error(), internalServerStatusText, badGatewayStatusText)
		}
	})

	t.Run(fmt.Sprintf("Test HTTP HEAD dependency%s", qualifier), func(t *testing.T) {
		response, err := httpClient.Head(mockHttpUrl + MockUrlPath)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		actualContentLength := response.Header.Get("Content-Length")
		if actualContentLength != ContentLength {
			t.Fatalf("Actual content length %s did not match expected content length %s", actualContentLength, ContentLength)
		}
	})

	t.Run(fmt.Sprintf("Test HTTPS HEAD dependency%s", qualifier), func(t *testing.T) {
		response, err := httpClient.Head(mockHttpsUrl + MockUrlPath)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		actualContentLength := response.Header.Get("Content-Length")
		if actualContentLength != ContentLength {
			t.Fatalf("Actual content length %s did not match expected content length %s", actualContentLength, ContentLength)
		}
	})

	t.Run(fmt.Sprintf("Test HTTP HEAD non-existent dependency%s", qualifier), func(t *testing.T) {
		response, err := httpClient.Head(mockHttpUrl + NonExistentUrlPath)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusNotFound {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusNotFound)
		}
	})

	t.Run(fmt.Sprintf("Test HTTPS HEAD non-existent dependency%s", qualifier), func(t *testing.T) {
		response, err := httpClient.Head(mockHttpsUrl + NonExistentUrlPath)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusNotFound {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusNotFound)
		}
	})
}

func commonInternalProxyTestBehaviour(t *testing.T, qualifier string, onRequestFunction func(req *http.Request, ctx *goproxy.ProxyCtx) (*http.Request, *http.Response), onConnectFunction func(host string, ctx *goproxy.ProxyCtx) (*goproxy.ConnectAction, string)) {
	// Start internal proxy
	internalProxyServer := startInternalProxyServer(t, onRequestFunction, onConnectFunction)
	// Set env variables
	t.Setenv(EnableInternalProxyKey, "true")
	t.Setenv(InternalProxyHostKey, Localhost)
	t.Setenv(InternalProxyPortKey, InternalProxyPort)
	t.Setenv(InternalNonProxyHostsKey, "example.com")
	// Run tests with internal proxy
	commonTestBehaviour(t, qualifier)
	// Stop internal proxy
	stopInternalProxyServer(t, internalProxyServer)
	// Set non-proxy hosts env variable
	t.Setenv(InternalNonProxyHostsKey, "127.0.0.1,foo.bar")
	// Run tests without internal proxy
	commonTestBehaviour(t, qualifier+" and non-proxy host")
}

func TestDomainProxy(t *testing.T) {
	commonTestBehaviour(t, "")
}

func TestDomainProxyWithInternalProxy(t *testing.T) {
	commonInternalProxyTestBehaviour(t, " with internal proxy", nil, nil)
}

func TestDomainProxyWithInternalProxyAndAuthentication(t *testing.T) {
	// Set env variables
	t.Setenv(InternalProxyUserKey, User)
	t.Setenv(InternalProxyPasswordKey, Password)
	basicAuth := "Basic " + GetBasicAuth(User, Password)
	// Create internal proxy HTTP authentication handler
	onRequestFunction := func(req *http.Request, ctx *goproxy.ProxyCtx) (*http.Request, *http.Response) {
		if req.Header.Get("Proxy-Authorization") != basicAuth {
			return nil, goproxy.NewResponse(req, goproxy.ContentTypeText, http.StatusProxyAuthRequired, http.StatusText(http.StatusProxyAuthRequired))
		}
		return req, nil
	}
	// Create internal proxy HTTPS authentication handler
	onConnectionFunction := func(host string, ctx *goproxy.ProxyCtx) (*goproxy.ConnectAction, string) {
		req := ctx.Req
		authHeader := req.Header.Get("Proxy-Authorization")
		if authHeader != basicAuth {
			ctx.Resp = goproxy.NewResponse(req, goproxy.ContentTypeText, http.StatusProxyAuthRequired, http.StatusText(http.StatusProxyAuthRequired))
			return goproxy.RejectConnect, host
		}
		return goproxy.OkConnect, host
	}
	// Run tests with internal proxy and authentication
	commonInternalProxyTestBehaviour(t, " with internal proxy and authentication", onRequestFunction, onConnectionFunction)

	// Set invalid authentication env variables
	t.Setenv(DomainSocketKey, getRandomDomainSocket())
	t.Setenv(InternalProxyUserKey, "123")
	t.Setenv(InternalProxyPasswordKey, "456")
	t.Setenv(InternalNonProxyHostsKey, "example.com")
	// Start internal proxy
	internalProxyServer := startInternalProxyServer(t, onRequestFunction, onConnectionFunction)
	defer stopInternalProxyServer(t, internalProxyServer)
	// Start services
	domainProxyServer, domainProxyClient := startDomainProxy()
	defer stopDomainProxy(domainProxyServer, domainProxyClient)
	// Start mock HTTP and HTTPS servers
	mockHttpServer, mockHttpsServer := startMockServers(t)
	defer stopMockServers(mockHttpServer, mockHttpsServer)
	mockHttpUrl := mockHttpServer.URL
	mockHttpsUrl := mockHttpsServer.URL
	// Create HTTP client
	httpClient := createClient(t)

	t.Run("Test HTTP GET dependency with internal proxy and invalid authentication", func(t *testing.T) {
		response, err := httpClient.Get(mockHttpUrl + MockUrlPath)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		if response.StatusCode != http.StatusProxyAuthRequired {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusProxyAuthRequired)
		}
	})

	t.Run("Test HTTPS GET dependency with internal proxy and invalid authentication", func(t *testing.T) {
		_, err := httpClient.Get(mockHttpsUrl + MockUrlPath)
		statusText := http.StatusText(http.StatusProxyAuthRequired)
		if !strings.Contains(err.Error(), statusText) {
			t.Fatalf("Actual error %s did not contain expected HTTP status text %s", err.Error(), statusText)
		}
	})
}
