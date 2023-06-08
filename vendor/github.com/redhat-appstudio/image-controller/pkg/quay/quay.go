/*
Copyright 2023 Red Hat, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package quay

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	neturl "net/url"
	"regexp"
	"strings"
)

type QuayService interface {
	CreateRepository(r RepositoryRequest) (*Repository, error)
	DeleteRepository(organization, imageRepository string) (bool, error)
	GetRobotAccount(organization string, robotName string) (*RobotAccount, error)
	CreateRobotAccount(organization string, robotName string) (*RobotAccount, error)
	DeleteRobotAccount(organization string, robotName string) (bool, error)
	AddWritePermissionsToRobotAccount(organization, imageRepository, robotAccountName string) error
	GetAllRepositories(organization string) ([]Repository, error)
	GetAllRobotAccounts(organization string) ([]RobotAccount, error)
	GetTagsFromPage(organization, repository string, page int) ([]Tag, bool, error)
	DeleteTag(organization, repository, tag string) (bool, error)
}

var _ QuayService = (*QuayClient)(nil)

type QuayClient struct {
	url        string
	httpClient *http.Client
	AuthToken  string
}

func NewQuayClient(c *http.Client, authToken, url string) QuayClient {
	return QuayClient{
		httpClient: c,
		AuthToken:  authToken,
		url:        url,
	}
}

// CreateRepository creates a new Quay.io image repository.
func (c *QuayClient) CreateRepository(r RepositoryRequest) (*Repository, error) {
	url := fmt.Sprintf("%s/%s", c.url, "repository")

	b, err := json.Marshal(r)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal repository request data: %w", err)
	}
	req, err := http.NewRequest(http.MethodPost, url, bytes.NewReader(b))
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Add("Authorization", fmt.Sprintf("%s %s", "Bearer", c.AuthToken))
	req.Header.Add("Content-Type", "application/json")

	res, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to Do request: %w", err)
	}
	defer res.Body.Close()

	body, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response body: %w", err)
	}

	data := &Repository{}
	err = json.Unmarshal(body, data)
	if err != nil {
		return nil, fmt.Errorf("failed to unmarshal response body: %w", err)
	}

	if res.StatusCode == 400 && data.ErrorMessage == "Repository already exists" {
		data.Name = r.Repository
	} else if data.ErrorMessage != "" {
		return data, errors.New(data.ErrorMessage)
	}
	return data, nil
}

// DoesRepositoryExist checks if the specified image repository exists in quay.
func (c *QuayClient) DoesRepositoryExist(organization, imageRepository string) (bool, error) {
	url := fmt.Sprintf("%s/repository/%s/%s", c.url, organization, imageRepository)

	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return false, fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Add("Authorization", fmt.Sprintf("%s %s", "Bearer", c.AuthToken))
	req.Header.Add("Content-Type", "application/json")

	res, err := c.httpClient.Do(req)
	if err != nil {
		return false, fmt.Errorf("failed to Do request: %w", err)
	}
	defer res.Body.Close()

	if res.StatusCode == 404 {
		return false, fmt.Errorf("repository %s does not exist in %s organization", imageRepository, organization)
	} else if res.StatusCode == 200 {
		return true, nil
	}

	body, err := io.ReadAll(res.Body)
	if err != nil {
		return false, fmt.Errorf("failed to read response body: %w", err)
	}
	data := &QuayError{}
	err = json.Unmarshal(body, data)
	if err != nil {
		return false, fmt.Errorf("failed to unmarshal response body: %w", err)
	}
	if data.Error != "" {
		return false, errors.New(data.Error)
	}
	return false, errors.New(data.ErrorMessage)
}

// DeleteRepository deletes specified image repository.
func (c *QuayClient) DeleteRepository(organization, imageRepository string) (bool, error) {
	url := fmt.Sprintf("%s/repository/%s/%s", c.url, organization, imageRepository)

	req, err := http.NewRequest(http.MethodDelete, url, nil)
	if err != nil {
		return false, fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Add("Authorization", fmt.Sprintf("%s %s", "Bearer", c.AuthToken))
	req.Header.Add("Content-Type", "application/json")

	res, err := c.httpClient.Do(req)
	if err != nil {
		return false, fmt.Errorf("failed to Do request: %w", err)
	}
	defer res.Body.Close()

	if res.StatusCode == 204 {
		return true, nil
	}
	if res.StatusCode == 404 {
		return false, nil
	}

	body, err := io.ReadAll(res.Body)
	if err != nil {
		return false, fmt.Errorf("failed to read response body: %w", err)
	}
	data := &QuayError{}
	err = json.Unmarshal(body, data)
	if err != nil {
		return false, fmt.Errorf("failed to unmarshal response body: %w", err)
	}
	if data.Error != "" {
		return false, errors.New(data.Error)
	}
	return false, errors.New(data.ErrorMessage)
}

func (c *QuayClient) GetRobotAccount(organization string, robotName string) (*RobotAccount, error) {
	url := fmt.Sprintf("%s/%s/%s/%s/%s", c.url, "organization", organization, "robots", robotName)

	req, err := http.NewRequest(http.MethodGet, url, &bytes.Buffer{})
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Add("Authorization", fmt.Sprintf("%s %s", "Bearer", c.AuthToken))
	req.Header.Add("Content-Type", "application/json")

	res, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to Do request: %w", err)
	}
	defer res.Body.Close()

	body, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response body: %w", err)
	}

	data := &RobotAccount{}
	err = json.Unmarshal(body, data)
	if err != nil {
		return nil, fmt.Errorf("failed to unmarshal response body: %w", err)
	}

	if data.Message != "" {
		return data, errors.New(data.Message)
	}
	return data, nil
}

// CreateRobotAccount creates a new Quay.io robot account in the organization.
func (c *QuayClient) CreateRobotAccount(organization string, robotName string) (*RobotAccount, error) {
	robotName, err := handleRobotName(robotName)
	if err != nil {
		return nil, err
	}
	url := fmt.Sprintf("%s/%s/%s/%s/%s", c.url, "organization", organization, "robots", robotName)

	payload := strings.NewReader(`{"description": "Robot account for AppStudio Component"}`)

	req, err := http.NewRequest(http.MethodPut, url, payload)
	if err != nil {
		return nil, fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Add("Authorization", fmt.Sprintf("%s %s", "Bearer", c.AuthToken))
	req.Header.Add("Content-Type", "application/json")

	res, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to Do request: %w", err)
	}
	defer res.Body.Close()

	body, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response body: %w", err)
	}

	data := &RobotAccount{}
	err = json.Unmarshal(body, data)
	if err != nil {
		return nil, fmt.Errorf("failed to unmarshal response body: %w", err)
	}

	if res.StatusCode == 400 && strings.Contains(data.Message, "Existing robot with name") {
		data, err = c.GetRobotAccount(organization, robotName)
		if err != nil {
			return nil, err
		}
	}
	return data, nil
}

// DeleteRobotAccount deletes given Quay.io robot account in the organization.
func (c *QuayClient) DeleteRobotAccount(organization string, robotName string) (bool, error) {
	robotName, err := handleRobotName(robotName)
	if err != nil {
		return false, err
	}
	url := fmt.Sprintf("%s/organization/%s/robots/%s", c.url, organization, robotName)

	req, err := http.NewRequest(http.MethodDelete, url, nil)
	if err != nil {
		return false, fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Add("Authorization", fmt.Sprintf("%s %s", "Bearer", c.AuthToken))
	req.Header.Add("Content-Type", "application/json")

	res, err := c.httpClient.Do(req)
	if err != nil {
		return false, fmt.Errorf("failed to Do request: %w", err)
	}
	defer res.Body.Close()

	if res.StatusCode == 204 {
		return true, nil
	}
	if res.StatusCode == 404 {
		return false, nil
	}

	body, err := io.ReadAll(res.Body)
	if err != nil {
		return false, fmt.Errorf("failed to read response body: %w", err)
	}
	data := &QuayError{}
	err = json.Unmarshal(body, data)
	if err != nil {
		return false, fmt.Errorf("failed to unmarshal response body: %w", err)
	}
	if data.Error != "" {
		return false, errors.New(data.Error)
	}
	return false, errors.New(data.ErrorMessage)
}

// AddWritePermissionsToRobotAccount enables "robotAccountName" to "write" to "repository"
func (c *QuayClient) AddWritePermissionsToRobotAccount(organization, imageRepository, robotAccountName string) error {
	var robotAccountFullName string
	if robotName, err := handleRobotName(robotAccountName); err == nil {
		robotAccountFullName = organization + "+" + robotName
	} else {
		return err
	}

	// url := "https://quay.io/api/v1/repository/redhat-appstudio/test-repo-using-api/permissions/user/redhat-appstudio+createdbysbose"
	url := fmt.Sprintf("%s/repository/%s/%s/permissions/user/%s", c.url, organization, imageRepository, robotAccountFullName)

	payload := strings.NewReader(`{"role": "write"}`)

	req, err := http.NewRequest(http.MethodPut, url, payload)
	if err != nil {
		return fmt.Errorf("failed to create request: %w", err)
	}
	req.Header.Add("Authorization", fmt.Sprintf("%s %s", "Bearer", c.AuthToken))
	req.Header.Add("Content-Type", "application/json")

	res, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("failed to Do request: %w", err)
	}
	if res.StatusCode != 200 {
		var message string
		defer res.Body.Close()
		// Ignore read errors while getting returned by quay API error
		if body, err := io.ReadAll(res.Body); err == nil {
			data := &QuayError{}
			if err := json.Unmarshal(body, data); err == nil {
				if data.ErrorMessage != "" {
					message = data.ErrorMessage
				} else {
					message = data.Error
				}
			}
		}
		return fmt.Errorf("failed to add write permissions to the robot account. Status code: %d, message: %s", res.StatusCode, message)
	}
	return nil
}

// Returns all repositories of the DEFAULT_QUAY_ORG organization (used in e2e-tests)
func (c *QuayClient) GetAllRepositories(organization string) ([]Repository, error) {
	url := fmt.Sprintf("%s/repository", c.url)

	values := neturl.Values{}
	values.Add("last_modified", "true")
	values.Add("namespace", organization)

	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create new request, error: %s", err)
	}

	req.URL.RawQuery = values.Encode()
	req.Header.Add("Authorization", fmt.Sprintf("%s %s", "Bearer", c.AuthToken))
	req.Header.Add("Content-Type", "application/json")

	type Response struct {
		Repositories []Repository `json:"repositories"`
		NextPage     string       `json:"next_page"`
	}
	var response Response
	var repositories []Repository

	for {
		res, err := c.httpClient.Do(req)
		if err != nil {
			return nil, fmt.Errorf("failed to Do request, error: %s", err)
		}
		if res.StatusCode != 200 {
			return nil, fmt.Errorf("error getting repositories, got status code %d", res.StatusCode)
		}

		defer res.Body.Close()
		body, err := io.ReadAll(res.Body)
		if err != nil {
			return nil, fmt.Errorf("failed to read response body, error: %s", err)
		}

		err = json.Unmarshal(body, &response)
		if err != nil {
			return nil, fmt.Errorf("failed to unmarshal body, error: %s", err)
		}

		repositories = append(repositories, response.Repositories...)

		if response.NextPage == "" || values.Get("next_page") == response.NextPage {
			break
		}

		values.Set("next_page", response.NextPage)
		req.URL.RawQuery = values.Encode()
	}
	return repositories, nil
}

// Returns all robot accounts of the DEFAULT_QUAY_ORG organization (used in e2e-tests)
func (c *QuayClient) GetAllRobotAccounts(organization string) ([]RobotAccount, error) {
	url := fmt.Sprintf("%s/organization/%s/robots", c.url, organization)

	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, fmt.Errorf("failed to create new request: %w", err)
	}

	req.Header.Add("Authorization", fmt.Sprintf("Bearer %s", c.AuthToken))
	req.Header.Add("Content-Type", "application/json")

	res, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("failed to Do request: %w", err)
	}
	if res.StatusCode != 200 {
		return nil, fmt.Errorf("failed to get robot accounts. Status code: %d", res.StatusCode)
	}

	defer res.Body.Close()
	body, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, fmt.Errorf("failed to read response body: %w", err)
	}

	type Response struct {
		Robots []RobotAccount
	}
	var response Response
	err = json.Unmarshal(body, &response)
	if err != nil {
		return nil, fmt.Errorf("failed to unmarshal response body: %w", err)
	}
	return response.Robots, nil
}

// If robotName is in longform, return shortname
// e.g. `org+robot` will be changed to `robot`, `robot` will stay `robot`
func handleRobotName(robotName string) (string, error) {
	// Regexp from quay api `^([a-z0-9]+(?:[._-][a-z0-9]+)*)$` with one plus sign in the middle allowed (representing longname)
	r, err := regexp.Compile(`^[a-z0-9]+(?:[._-][a-z0-9]+)*(?:\+[a-z0-9]+(?:[._-][a-z0-9]+)*)?$`)
	robotName = strings.TrimSpace(robotName)
	if err != nil {
		return "", fmt.Errorf("failed to compile regex, error: %s", err)
	}
	if !r.MatchString(robotName) {
		return "", fmt.Errorf("robot name is invalid, must match `^([a-z0-9]+(?:[._-][a-z0-9]+)*)$` (one plus sign in the middle is also allowed)")
	}
	if strings.Contains(robotName, "+") {
		parts := strings.Split(robotName, "+")
		if len(parts) != 2 {
			return "", fmt.Errorf("robotName could not be split into two parts, expected len 2, got len %d", len(parts))
		}
		robotName = parts[1]
	}
	return robotName, nil
}

func (c *QuayClient) GetTagsFromPage(organization, repository string, page int) ([]Tag, bool, error) {
	url := fmt.Sprintf("%s/repository/%s/%s/tag/?page=%d", c.url, organization, repository, page)

	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, false, fmt.Errorf("failed to create new request: %w", err)
	}

	req.Header.Add("Authorization", fmt.Sprintf("Bearer %s", c.AuthToken))
	req.Header.Add("Content-Type", "application/json")

	res, err := c.httpClient.Do(req)
	if err != nil {
		return nil, false, fmt.Errorf("failed to Do request: %w", err)
	}
	if res.StatusCode != 200 {
		return nil, false, fmt.Errorf("failed to get repository tags. Status code: %d", res.StatusCode)
	}

	defer res.Body.Close()
	body, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, false, fmt.Errorf("failed to read response body: %w", err)
	}

	var response struct {
		Tags          []Tag `json:"tags"`
		Page          int   `json:"page"`
		HasAdditional bool  `json:"has_additional"`
	}
	err = json.Unmarshal(body, &response)
	if err != nil {
		return nil, false, fmt.Errorf("failed to unmarshal response body: %w", err)
	}
	return response.Tags, response.HasAdditional, nil
}

func (c *QuayClient) DeleteTag(organization, repository, tag string) (bool, error) {
	url := fmt.Sprintf("%s/repository/%s/%s/tag/%s", c.url, organization, repository, tag)

	req, err := http.NewRequest(http.MethodDelete, url, nil)
	if err != nil {
		return false, err
	}

	req.Header.Add("Authorization", fmt.Sprintf("%s %s", "Bearer", c.AuthToken))
	req.Header.Add("Content-Type", "application/json")

	res, err := c.httpClient.Do(req)
	if err != nil {
		return false, fmt.Errorf("failed to Do request, error: %s", err)
	}
	defer res.Body.Close()

	if res.StatusCode == 204 {
		return true, nil
	}
	if res.StatusCode == 404 {
		return false, nil
	}

	body, err := io.ReadAll(res.Body)
	if err != nil {
		return false, fmt.Errorf("failed to read response body: %w", err)
	}
	data := &QuayError{}
	err = json.Unmarshal(body, data)
	if err != nil {
		return false, fmt.Errorf("failed to unmarshal response body: %s", err)
	}
	if data.Error != "" {
		return false, errors.New(data.Error)
	}
	return false, errors.New(data.ErrorMessage)
}
