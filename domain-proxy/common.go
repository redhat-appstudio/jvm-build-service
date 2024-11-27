package main

import (
	"errors"
	"io"
	"log"
	"net"
	"os"
	"strconv"
	"strings"
	"time"
)

const (
	ByteBufferSizeKey        = "BYTE_BUFFER_SIZE"
	DefaultByteBufferSize    = 32768
	DomainSocketKey          = "DOMAIN_SOCKET"
	DefaultDomainSocket      = "/tmp/domain-socket.sock"
	ConnectionTimeoutKey     = "CONNECTION_TIMEOUT"
	DefaultConnectionTimeout = 10000 * time.Millisecond
	IdleTimeoutKey           = "IDLE_TIMEOUT"
	DefaultIdleTimeout       = 60000 * time.Millisecond
)

var Logger *log.Logger

func InitLogger(appName string) {
	Logger = log.New(os.Stdout, appName+" ", log.LstdFlags|log.Lshortfile)
}

func BiDirectionalTransfer(leftConnection, rightConnection net.Conn, byteBufferSize int, idleTimeout time.Duration, connectionType string, connectionNo uint64) {
	defer CloseConnection(leftConnection, rightConnection, connectionType, connectionNo)
	done := make(chan struct{}, 2)
	if err := leftConnection.SetDeadline(time.Now().Add(idleTimeout)); err != nil {
		handleSetDeadlineError(leftConnection, err)
		return
	}
	if err := rightConnection.SetDeadline(time.Now().Add(idleTimeout)); err != nil {
		handleSetDeadlineError(rightConnection, err)
		return
	}
	go Transfer(leftConnection, rightConnection, done, byteBufferSize, idleTimeout, connectionType, connectionNo)
	go Transfer(rightConnection, leftConnection, done, byteBufferSize, idleTimeout, connectionType, connectionNo)
	<-done
	<-done
}

func Transfer(sourceConnection, targetConnection net.Conn, done chan struct{}, bufferSize int, idleTimeout time.Duration, connectionType string, connectionNo uint64) {
	defer func() {
		done <- struct{}{}
	}()
	buf := make([]byte, bufferSize)
	for {
		if n, err := io.CopyBuffer(sourceConnection, targetConnection, buf); err != nil {
			handleConnectionError(err, connectionType, connectionNo)
			return
		} else if n > 0 {
			if err = sourceConnection.SetReadDeadline(time.Now().Add(idleTimeout)); err != nil {
				handleSetDeadlineError(sourceConnection, err)
				return
			}
			if err = targetConnection.SetWriteDeadline(time.Now().Add(idleTimeout)); err != nil {
				handleSetDeadlineError(targetConnection, err)
				return
			}
			Logger.Printf("%d bytes transferred for %s connection %d", n, connectionType, connectionNo)
		}
	}
}

func handleSetDeadlineError(connection net.Conn, err error) {
	Logger.Printf("Failed to set deadline: %v", err)
	if err = connection.Close(); err != nil {
		HandleConnectionCloseError(err)
	}
}

func HandleConnectionCloseError(err error) {
	Logger.Printf("Failed to close connection: %v", err)
}

func HandleListenerCloseError(err error) {
	Logger.Printf("Failed to close listener: %v", err)
}

func handleConnectionError(err error, connectionType string, connectionNo uint64) {
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		Logger.Printf("%s connection %d timed out", connectionType, connectionNo)
	} else if err != io.EOF {
		Logger.Printf("Failed to transfer data using %s connection %d: %v", connectionType, connectionNo, err)
	}
}

func CloseConnection(leftConnection, rightConnection net.Conn, connectionType string, connectionNo uint64) {
	if err := leftConnection.Close(); err != nil {
		HandleConnectionCloseError(err)
	}
	if err := rightConnection.Close(); err != nil {
		HandleConnectionCloseError(err)
	}
	Logger.Printf("%s connection %d closed", connectionType, connectionNo)
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
