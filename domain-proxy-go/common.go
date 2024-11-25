package main

import (
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
	DefaultIdleTimeout       = 10000 * time.Millisecond
)

func ChannelToChannelBiDirectionalHandler(leftConn, rightConn net.Conn, byteBufferSize int, idleTimeout time.Duration, executor *sync.WaitGroup) {
	defer executor.Done()
	done := make(chan struct{})
	var mutex sync.Mutex
	go Transfer(leftConn, rightConn, done, byteBufferSize, idleTimeout, &mutex)
	go Transfer(rightConn, leftConn, done, byteBufferSize, idleTimeout, &mutex)
	<-done
	<-done
	CloseConnections(leftConn, rightConn)
}

func Transfer(targetConn, sourceConn net.Conn, done chan struct{}, bufferSize int, idleTimeout time.Duration, mutex *sync.Mutex) {
	defer func() {
		done <- struct{}{}
	}()
	buf := make([]byte, bufferSize)
	mutex.Lock()
	sourceConn.SetDeadline(time.Now().Add(idleTimeout))
	targetConn.SetDeadline(time.Now().Add(idleTimeout))
	mutex.Unlock()
	for {
		n, err := sourceConn.Read(buf)
		if err != nil {
			if err != io.EOF {
				log.Printf("Error reading from source: %v", err)
			}
			return
		}
		if n > 0 {
			mutex.Lock()
			sourceConn.SetDeadline(time.Now().Add(idleTimeout))
			targetConn.SetDeadline(time.Now().Add(idleTimeout))
			mutex.Unlock()
			_, err = targetConn.Write(buf[:n])
			if err != nil {
				log.Printf("Error writing to target: %v", err)
				return
			}
		}
	}
}

func CloseConnections(leftConn, rightConn net.Conn) {
	if leftConn != nil {
		leftConn.Close()
	}
	if rightConn != nil {
		rightConn.Close()
	}
	log.Println("Connections closed")
}

func GetEnvVariable(key, defaultValue string) string {
	value := os.Getenv(key)
	if value == "" {
		log.Printf("Environment variable %s is not set, using default value: %s", key, defaultValue)
		return defaultValue
	}
	return value
}

func GetIntEnvVariable(key string, defaultValue int) int {
	valueStr := os.Getenv(key)
	if valueStr == "" {
		log.Printf("Environment variable %s is not set, using default value: %d", key, defaultValue)
		return defaultValue
	}
	value, err := strconv.Atoi(valueStr)
	if err != nil {
		log.Printf("Invalid environment variable %s: %v, using default value: %d", key, err, defaultValue)
		return defaultValue
	}
	return value
}

func GetCsvEnvVariable(key, defaultValue string) map[string]bool {
	valuesStr := os.Getenv(key)
	if valuesStr == "" {
		log.Printf("Environment variable %s is not set, using default value: %s", key, defaultValue)
		return parseCsvToMap(defaultValue)
	}
	return parseCsvToMap(valuesStr)
}

func GetMillisecondsEnvVariable(key string, defaultValue time.Duration) time.Duration {
	valueStr := os.Getenv(key)
	if valueStr == "" {
		log.Printf("Environment variable %s is not set, using default value: %d", key, defaultValue.Milliseconds())
		return defaultValue
	}
	value, err := strconv.Atoi(valueStr)
	if err != nil {
		log.Printf("Invalid environment variable %s: %v, using default value: %d", key, err, defaultValue.Milliseconds())
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
