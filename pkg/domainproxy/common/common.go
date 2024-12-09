package common

import (
	"context"
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
	DefaultConnectionTimeout = 5000 * time.Millisecond
	IdleTimeoutKey           = "IDLE_TIMEOUT"
	DefaultIdleTimeout       = 10000 * time.Millisecond
	TCP                      = "tcp"
	UNIX                     = "unix"
)

type Common struct {
	logger *log.Logger
}

type SharedParams struct {
	ByteBufferSize    int
	DomainSocket      string
	ConnectionTimeout time.Duration
	IdleTimeout       time.Duration
}

func NewCommon(logger *log.Logger) *Common {
	return &Common{
		logger: logger,
	}
}

func NewLogger(appName string) *log.Logger {
	return log.New(os.Stdout, appName+" ", log.LstdFlags|log.Lshortfile)
}

func (c *Common) NewSharedParams() SharedParams {
	return SharedParams{
		ByteBufferSize:    c.getByteBufferSize(),
		DomainSocket:      c.getDomainSocket(),
		ConnectionTimeout: c.getConnectionTimeout(),
		IdleTimeout:       c.getIdleTimeout(),
	}
}

func (c *Common) BiDirectionalTransfer(runningContext context.Context, leftConnection, rightConnection net.Conn, byteBufferSize int, idleTimeout time.Duration, connectionType string, connectionNo uint64) {
	defer c.CloseConnection(leftConnection, rightConnection, connectionType, connectionNo)
	done := make(chan struct{}, 2)
	if err := leftConnection.SetDeadline(time.Now().Add(idleTimeout)); err != nil {
		c.HandleSetDeadlineError(leftConnection, err)
		return
	}
	if err := rightConnection.SetDeadline(time.Now().Add(idleTimeout)); err != nil {
		c.HandleSetDeadlineError(rightConnection, err)
		return
	}
	go c.Transfer(runningContext, leftConnection, rightConnection, done, byteBufferSize, idleTimeout, connectionType, connectionNo)
	go c.Transfer(runningContext, rightConnection, leftConnection, done, byteBufferSize, idleTimeout, connectionType, connectionNo)
	<-done
	<-done
}

func (c *Common) Transfer(runningContext context.Context, sourceConnection, targetConnection net.Conn, done chan struct{}, bufferSize int, idleTimeout time.Duration, connectionType string, connectionNo uint64) {
	defer func() {
		done <- struct{}{}
	}()
	buf := make([]byte, bufferSize)
	for {
		select {
		case <-runningContext.Done():
			return
		default:
			if n, err := io.CopyBuffer(sourceConnection, targetConnection, buf); err != nil {
				c.handleConnectionError(err, connectionType, connectionNo)
				return
			} else if n > 0 {
				if err = sourceConnection.SetReadDeadline(time.Now().Add(idleTimeout)); err != nil {
					c.HandleSetDeadlineError(sourceConnection, err)
					return
				}
				if err = targetConnection.SetWriteDeadline(time.Now().Add(idleTimeout)); err != nil {
					c.HandleSetDeadlineError(targetConnection, err)
					return
				}
				c.logger.Printf("%d bytes transferred for %s connection %d", n, connectionType, connectionNo)
			}
		}
	}
}

func (c *Common) HandleSetDeadlineError(connection net.Conn, err error) {
	c.logger.Printf("Failed to set deadline: %v", err)
	if err = connection.Close(); err != nil {
		c.HandleConnectionCloseError(err)
	}
}

func (c *Common) HandleConnectionCloseError(err error) {
	c.logger.Printf("Failed to close connection: %v", err)
}

func (c *Common) HandleListenerCloseError(err error) {
	c.logger.Printf("Failed to close listener: %v", err)
}

func (c *Common) handleConnectionError(err error, connectionType string, connectionNo uint64) {
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		c.logger.Printf("%s connection %d timed out", connectionType, connectionNo)
	} else if err != io.EOF {
		c.logger.Printf("Failed to transfer data using %s connection %d: %v", connectionType, connectionNo, err)
	}
}

func (c *Common) CloseConnection(leftConnection, rightConnection net.Conn, connectionType string, connectionNo uint64) {
	if err := leftConnection.Close(); err != nil {
		c.HandleConnectionCloseError(err)
	}
	if err := rightConnection.Close(); err != nil {
		c.HandleConnectionCloseError(err)
	}
	c.logger.Printf("%s connection %d closed", connectionType, connectionNo)
}

func (c *Common) GetEnvVariable(key, defaultValue string) string {
	value := os.Getenv(key)
	if value == "" {
		c.logger.Printf("Environment variable %s is not set, using default value: %s", key, defaultValue)
		return defaultValue
	}
	return value
}

func (c *Common) GetIntEnvVariable(key string, defaultValue int) int {
	valueStr := os.Getenv(key)
	if valueStr == "" {
		c.logger.Printf("Environment variable %s is not set, using default value: %d", key, defaultValue)
		return defaultValue
	}
	value, err := strconv.Atoi(valueStr)
	if err != nil {
		c.logger.Printf("Invalid environment variable %s: %v, using default value: %d", key, err, defaultValue)
		return defaultValue
	}
	return value
}

func (c *Common) GetCsvEnvVariable(key, defaultValue string) map[string]bool {
	valuesStr := os.Getenv(key)
	if valuesStr == "" {
		c.logger.Printf("Environment variable %s is not set, using default value: %s", key, defaultValue)
		return c.parseCsvToMap(defaultValue)
	}
	return c.parseCsvToMap(valuesStr)
}

func (c *Common) GetMillisecondsEnvVariable(key string, defaultValue time.Duration) time.Duration {
	valueStr := os.Getenv(key)
	if valueStr == "" {
		c.logger.Printf("Environment variable %s is not set, using default value: %d", key, defaultValue.Milliseconds())
		return defaultValue
	}
	value, err := strconv.Atoi(valueStr)
	if err != nil {
		c.logger.Printf("Invalid environment variable %s: %v, using default value: %d", key, err, defaultValue.Milliseconds())
		return defaultValue
	}
	return time.Duration(value) * time.Millisecond
}

func (c *Common) parseCsvToMap(csvString string) map[string]bool {
	valuesStr := strings.Split(csvString, ",")
	values := make(map[string]bool)
	for _, value := range valuesStr {
		trimmedValue := strings.TrimSpace(value)
		values[trimmedValue] = true
	}
	return values
}

func (c *Common) GetBoolEnvVariable(key string, defaultValue bool) bool {
	valueStr := os.Getenv(key)
	if valueStr == "" {
		c.logger.Printf("Environment variable %s is not set, using default value: %t", key, defaultValue)
		return defaultValue
	}
	value, err := strconv.ParseBool(valueStr)
	if err != nil {
		c.logger.Printf("Invalid environment variable %s: %v, using default value: %t", key, err, defaultValue)
		return defaultValue
	}
	return value
}

func (c *Common) getByteBufferSize() int {
	return c.GetIntEnvVariable(ByteBufferSizeKey, DefaultByteBufferSize)
}

func (c *Common) getDomainSocket() string {
	return c.GetEnvVariable(DomainSocketKey, DefaultDomainSocket)
}

func (c *Common) getConnectionTimeout() time.Duration {
	return c.GetMillisecondsEnvVariable(ConnectionTimeoutKey, DefaultConnectionTimeout)
}

func (c *Common) getIdleTimeout() time.Duration {
	return c.GetMillisecondsEnvVariable(IdleTimeoutKey, DefaultIdleTimeout)
}
