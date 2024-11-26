package main

import (
	"errors"
	"io"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"
)

const (
	ByteBufferSizeKey        = "BYTE_BUFFER_SIZE"
	DefaultByteBufferSize    = 1024
	DomainSocketKey          = "DOMAIN_SOCKET"
	DefaultDomainSocket      = "/tmp/domain-socket.sock"
	ConnectionTimeoutKey     = "CONNECTION_TIMEOUT"
	DefaultConnectionTimeout = 1000 * time.Millisecond
	IdleTimeoutKey           = "IDLE_TIMEOUT"
	DefaultIdleTimeout       = 1000 * time.Millisecond
)

var Logger *log.Logger

func InitLogger(appName string) {
	Logger = log.New(os.Stdout, appName+" ", log.LstdFlags|log.Lshortfile)
}

func BiDirectionalTransfer(leftConn, rightConn net.Conn, byteBufferSize int, idleTimeout time.Duration, executor *sync.WaitGroup) {
	defer executor.Done()
	defer CloseConnections(leftConn, rightConn)
	done := make(chan struct{}, 2)
	leftConn.SetDeadline(time.Now().Add(idleTimeout))
	rightConn.SetDeadline(time.Now().Add(idleTimeout))
	go Transfer(leftConn, rightConn, done, byteBufferSize, idleTimeout)
	go Transfer(rightConn, leftConn, done, byteBufferSize, idleTimeout)
	<-done
	<-done
}

func Transfer(targetConn, sourceConn net.Conn, done chan struct{}, bufferSize int, idleTimeout time.Duration) {
	defer func() {
		done <- struct{}{}
	}()
	buf := make([]byte, bufferSize)
	for {
		n, err := sourceConn.Read(buf)
		if err != nil {
			handleConnectionError(err)
			return
		} else if n > 0 {
			Logger.Printf("%d bytes read", n)
			sourceConn.SetReadDeadline(time.Now().Add(idleTimeout))
			n, err = targetConn.Write(buf[:n])
			if err != nil {
				handleConnectionError(err)
				return
			} else if n > 0 {
				Logger.Printf("%d bytes written", n)
				targetConn.SetWriteDeadline(time.Now().Add(idleTimeout))
			}
		}
	}
}

func handleConnectionError(err error) {
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		Logger.Printf("Connection timed out")
	} else if err != io.EOF {
		Logger.Printf("Error using connection: %v", err)
	}
}

func CloseConnections(leftConn, rightConn net.Conn) {
	if leftConn != nil {
		leftConn.Close()
	}
	if rightConn != nil {
		rightConn.Close()
	}
	Logger.Println("Connections closed")
}

func GetEnvVariable(key, defaultValue string) string {
	value := os.Getenv(key)
	if value == "" {
		Logger.Printf("Environment variable %s is not set, using default value: %s", key, defaultValue)
		return defaultValue
	}
	return value
}

func GetIntEnvVariable(key string, defaultValue int) int {
	valueStr := os.Getenv(key)
	if valueStr == "" {
		Logger.Printf("Environment variable %s is not set, using default value: %d", key, defaultValue)
		return defaultValue
	}
	value, err := strconv.Atoi(valueStr)
	if err != nil {
		Logger.Printf("Invalid environment variable %s: %v, using default value: %d", key, err, defaultValue)
		return defaultValue
	}
	return value
}

func GetCsvEnvVariable(key, defaultValue string) map[string]bool {
	valuesStr := os.Getenv(key)
	if valuesStr == "" {
		Logger.Printf("Environment variable %s is not set, using default value: %s", key, defaultValue)
		return parseCsvToMap(defaultValue)
	}
	return parseCsvToMap(valuesStr)
}

func GetMillisecondsEnvVariable(key string, defaultValue time.Duration) time.Duration {
	valueStr := os.Getenv(key)
	if valueStr == "" {
		Logger.Printf("Environment variable %s is not set, using default value: %d", key, defaultValue.Milliseconds())
		return defaultValue
	}
	value, err := strconv.Atoi(valueStr)
	if err != nil {
		Logger.Printf("Invalid environment variable %s: %v, using default value: %d", key, err, defaultValue.Milliseconds())
		return defaultValue
	}
	return time.Duration(value) * time.Millisecond
}

func parseCsvToMap(csvString string) map[string]bool {
	valuesStr := strings.Split(csvString, ",")
	values := make(map[string]bool)
	for _, value := range valuesStr {
		trimmedValue := strings.TrimSpace(value)
		values[trimmedValue] = true
	}
	return values
}

func GetByteBufferSize() int {
	return GetIntEnvVariable(ByteBufferSizeKey, DefaultByteBufferSize)
}

func GetDomainSocket() string {
	return GetEnvVariable(DomainSocketKey, DefaultDomainSocket)
}

func GetConnectionTimeout() time.Duration {
	return GetMillisecondsEnvVariable(ConnectionTimeoutKey, DefaultConnectionTimeout)
}

func GetIdleTimeout() time.Duration {
	return GetMillisecondsEnvVariable(IdleTimeoutKey, DefaultIdleTimeout)
}
