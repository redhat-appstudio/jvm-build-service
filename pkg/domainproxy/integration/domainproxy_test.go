package integration

import (
	"context"
	"crypto/md5"
	"crypto/tls"
	"encoding/hex"
	. "github.com/redhat-appstudio/jvm-build-service/pkg/domainproxy/client"
	. "github.com/redhat-appstudio/jvm-build-service/pkg/domainproxy/common"
	. "github.com/redhat-appstudio/jvm-build-service/pkg/domainproxy/server"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"
	"github.com/wiremock/go-wiremock"
	. "github.com/wiremock/wiremock-testcontainers-go"
	"io"
	"math/rand"
	"net/http"
	"net/url"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

const (
	WireMockHttpPort  = "8080"
	WireMockHttpsPort = "8443"
	WireMockProtocol  = "/tcp"
	ProxyUrl          = "http://" + Localhost + ":8081"
	ContentType       = "text/xml"
	Md5Hash           = "ea3ca57f8f99d1d210d1b438c9841440"
	ContentLength     = "403"
)

type containerCustomizer struct{}

func (c containerCustomizer) Customize(req *testcontainers.GenericContainerRequest) error {
	req.ExposedPorts = []string{WireMockHttpPort + WireMockProtocol, WireMockHttpsPort + WireMockProtocol}
	req.WaitingFor = wait.ForListeningPort(WireMockHttpPort)
	req.Cmd = []string{"--port", WireMockHttpPort, "--https-port", WireMockHttpsPort}
	return nil
}

func createClient(t *testing.T) *http.Client {
	proxy, err := url.Parse(ProxyUrl)
	if err != nil {
		t.Fatal(err)
	}
	transport := &http.Transport{
		Proxy:           http.ProxyURL(proxy),
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

func head(urlMatchingPair wiremock.URLMatcher) *wiremock.StubRule {
	return wiremock.NewStubRule(http.MethodHead, urlMatchingPair)
}

func TestDomainProxy(t *testing.T) {
	// Start Wiremock container
	ctx := context.Background()
	container, err := RunContainerAndStopOnCleanup(ctx, t, containerCustomizer{})
	if err != nil {
		t.Fatal(err)
	}
	// HTTP Get stub
	pom, err := os.ReadFile("testdata/bar-1.0.pom")
	if err != nil {
		t.Fatal(err)
	}
	err = container.Client.StubFor(
		wiremock.Get(wiremock.URLEqualTo("/com/foo/bar/1.0/bar-1.0.pom")).
			WillReturnResponse(
				wiremock.NewResponse().
					WithHeader("Content-Type", ContentType).
					WithBody(string(pom)).
					WithStatus(http.StatusOK),
			),
	)
	if err != nil {
		t.Fatal(err)
	}
	// HTTP Head stub
	err = container.Client.StubFor(
		head(wiremock.URLEqualTo("/com/foo/bar/1.0/bar-1.0.pom")).
			WillReturnResponse(
				wiremock.NewResponse().
					WithHeader("Content-Type", ContentType).
					WithHeader("Content-Length", ContentLength).
					WithStatus(http.StatusOK),
			),
	)
	if err != nil {
		t.Fatal(err)
	}
	// Set env variables
	os.Setenv(DomainSocketKey, "/tmp/domain-socket-"+strconv.Itoa(rand.Int())+".sock")
	os.Setenv(ServerHttpPortKey, "8081")
	os.Setenv(ProxyTargetWhitelistKey, "localhost,foo.bar")
	// Start services
	domainProxyServer := NewDomainProxyServer()
	go domainProxyServer.Start()
	domainProxyClient := NewDomainProxyClient()
	go domainProxyClient.Start()
	time.Sleep(1 * time.Second)
	defer domainProxyServer.Stop()
	defer domainProxyClient.Stop()
	// Get Wiremock container details
	mappedHttpPort, err := container.MappedPort(ctx, WireMockHttpPort)
	if err != nil {
		t.Fatal(err)
	}
	mappedHttpsPort, err := container.MappedPort(ctx, WireMockHttpsPort)
	if err != nil {
		t.Fatal(err)
	}
	wireMockHttpUrl := "http://" + Localhost + ":" + mappedHttpPort.Port()
	wireMockHttpsUrl := "https://" + Localhost + ":" + mappedHttpsPort.Port()
	// Create HTTP client
	httpClient := createClient(t)

	t.Run("Test HTTP GET dependency", func(t *testing.T) {
		response, err := httpClient.Get(wireMockHttpUrl + "/com/foo/bar/1.0/bar-1.0.pom")
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != http.StatusOK {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusOK)
		}
		defer response.Body.Close()
		pom, err := io.ReadAll(response.Body)
		if err != nil {
			t.Fatal(err)
		}
		hash := getMd5Hash(pom)
		if hash != Md5Hash {
			t.Fatalf("Actual MD5 hash %s did not match expected MD5 hash %s", hash, Md5Hash)
		}
	})

	t.Run("Test HTTPS GET dependency", func(t *testing.T) {
		response, err := httpClient.Get(wireMockHttpsUrl + "/com/foo/bar/1.0/bar-1.0.pom")
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != http.StatusOK {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusOK)
		}
		defer response.Body.Close()
		pom, err := io.ReadAll(response.Body)
		if err != nil {
			t.Fatal(err)
		}
		hash := getMd5Hash(pom)
		if hash != Md5Hash {
			t.Fatalf("Actual MD5 hash %s did not match expected MD5 hash %s", hash, Md5Hash)
		}
	})

	t.Run("Test HTTP GET non-existent dependency", func(t *testing.T) {
		response, err := httpClient.Get(wireMockHttpUrl + "/com/foo/bar/1.0/bar-2.0.pom")
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != http.StatusNotFound {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusNotFound)
		}
	})

	t.Run("Test HTTPS GET non-existent dependency", func(t *testing.T) {
		response, err := httpClient.Get(wireMockHttpsUrl + "/com/foo/bar/1.0/bar-2.0.pom")
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != http.StatusNotFound {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusNotFound)
		}
	})

	t.Run("Test HTTP non-whitelisted host", func(t *testing.T) {
		response, err := httpClient.Get("http://repo1.maven.org/maven2/org/apache/maven/plugins/maven-jar-plugin/3.4.1/maven-jar-plugin-3.4.1.jar")
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != http.StatusForbidden {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusForbidden)
		}
	})

	t.Run("Test HTTPS non-whitelisted host", func(t *testing.T) {
		_, err := httpClient.Get("https://repo1.maven.org/maven2/org/apache/maven/plugins/maven-jar-plugin/3.4.1/maven-jar-plugin-3.4.1.jar")
		statusText := http.StatusText(http.StatusForbidden)
		if !strings.Contains(err.Error(), statusText) {
			t.Fatalf("Actual error %s did not contain expected HTTP status text %s", err.Error(), statusText)
		}
	})

	t.Run("Test HTTP non-existent host", func(t *testing.T) {
		response, err := httpClient.Get("http://foo.bar")
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != http.StatusBadGateway {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusBadGateway)
		}
	})

	t.Run("Test HTTPS non-existent host", func(t *testing.T) {
		_, err := httpClient.Get("https://foo.bar")
		statusText := http.StatusText(http.StatusBadGateway)
		if !strings.Contains(err.Error(), statusText) {
			t.Fatalf("Actual error %s did not contain expected HTTP status text %s", err.Error(), statusText)
		}
	})

	t.Run("Test HTTP HEAD dependency", func(t *testing.T) {
		response, err := httpClient.Head(wireMockHttpUrl + "/com/foo/bar/1.0/bar-1.0.pom")
		if err != nil {
			t.Fatal(err)
		}
		actualContentLength := response.Header.Get("Content-Length")
		if actualContentLength != ContentLength {
			t.Fatalf("Actual content length %s did not match expected content length %s", actualContentLength, ContentLength)
		}
	})

	t.Run("Test HTTPS HEAD dependency", func(t *testing.T) {
		response, err := httpClient.Head(wireMockHttpsUrl + "/com/foo/bar/1.0/bar-1.0.pom")
		if err != nil {
			t.Fatal(err)
		}
		actualContentLength := response.Header.Get("Content-Length")
		if actualContentLength != ContentLength {
			t.Fatalf("Actual content length %s did not match expected content length %s", actualContentLength, ContentLength)
		}
	})

	t.Run("Test HTTP HEAD non-existent dependency", func(t *testing.T) {
		response, err := httpClient.Head(wireMockHttpUrl + "/com/foo/bar/1.0/bar-2.0.pom")
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != http.StatusNotFound {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusNotFound)
		}
	})

	t.Run("Test HTTPS HEAD non-existent dependency", func(t *testing.T) {
		response, err := httpClient.Head(wireMockHttpsUrl + "/com/foo/bar/1.0/bar-2.0.pom")
		if err != nil {
			t.Fatal(err)
		}
		if response.StatusCode != http.StatusNotFound {
			t.Fatalf("Actual HTTP status %d did not match expected HTTP status %d", response.StatusCode, http.StatusNotFound)
		}
	})
}
