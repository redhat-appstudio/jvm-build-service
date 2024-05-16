package util

import (
	"context"
	"fmt"
	"strings"
	"testing"

	appsv1 "k8s.io/api/apps/v1"
	v1core "k8s.io/api/core/v1"
	v1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"sigs.k8s.io/controller-runtime/pkg/client/fake"
)

const (
	imageRepoTestValue            = "image-repo"
	imageTagTestValue             = "image-tag"
	controllerDeploymentImageName = "deployment-test-controller-image-name"
	reqprocessorImageEnvVarName   = "JVM_BUILD_SERVICE_REQPROCESSOR_IMAGE"
	reqprocessorImageEnvVarValue  = "reqprocessor_image"
	substr                        = "build-request-processor"
)

func setupControllerDeployment(noContainers bool) *appsv1.Deployment {
	var containers []v1core.Container

	if !noContainers {
		containers = append(containers, v1core.Container{Image: controllerDeploymentImageName})
	}

	return &appsv1.Deployment{
		ObjectMeta: v1.ObjectMeta{
			Name:      ControllerDeploymentName,
			Namespace: ControllerNamespace,
		},
		Spec: appsv1.DeploymentSpec{
			Template: v1core.PodTemplateSpec{
				Spec: v1core.PodSpec{
					Containers: containers,
				},
			},
		},
	}
}

func TestGetImageName(t *testing.T) {
	scheme := runtime.NewScheme()
	_ = appsv1.AddToScheme(scheme)

	tests := []struct {
		name             string
		envKey           string
		envValue         string
		imageRepo        string
		imageTag         string
		noContainerImage bool
		want             string
		wantErr          bool
	}{
		{
			name:     "should get the reqprocesor image from the env var",
			envKey:   reqprocessorImageEnvVarName,
			envValue: reqprocessorImageEnvVarValue,
			want:     reqprocessorImageEnvVarValue,
			wantErr:  false,
		},
		{
			name:      "should get the reqprocessor image name based on the values in ImageTag and ImageRepo variables",
			imageRepo: imageRepoTestValue,
			imageTag:  imageTagTestValue,
			want:      fmt.Sprintf("quay.io/%s/hacbs-jvm-%s:%s", imageRepoTestValue, substr, imageTagTestValue),
			wantErr:   false,
		},
		{
			name:    "should get the reqprocessor image name based on the image name specified in the controller's deployment",
			want:    strings.Replace(controllerDeploymentImageName, "controller", substr, 1),
			wantErr: false,
		},
		{
			name:             "should fail to get the reqprocessor image if the controller deployment does not contain containers in template spec",
			want:             "",
			noContainerImage: true,
			wantErr:          true,
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			if len(tt.envKey) > 0 {
				t.Setenv(tt.envKey, tt.envValue)
			}
			ImageTag = tt.imageTag
			ImageRepo = tt.imageRepo

			d := setupControllerDeployment(tt.noContainerImage)
			fakeClient := fake.NewClientBuilder().WithScheme(scheme).WithObjects(d).Build()

			got, err := GetImageName(context.Background(), fakeClient, substr, reqprocessorImageEnvVarName)
			if (err != nil) != tt.wantErr {
				t.Errorf("GetImageName() error = %v, wantErr %v", err, tt.wantErr)
				return
			}
			if got != tt.want {
				t.Errorf("GetImageName() = %v, want %v", got, tt.want)
			}
		})
	}
}
