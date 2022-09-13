package util

import (
	"context"
	"fmt"
	"os"
	"strings"

	"github.com/go-logr/logr"

	appsv1 "k8s.io/api/apps/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

const (
	ControllerDeploymentName = "hacbs-jvm-operator"
	ControllerNamespace      = "jvm-build-service"
)

var (
	controllerName = types.NamespacedName{
		Namespace: ControllerNamespace,
		Name:      ControllerDeploymentName,
	}
)

func GetImageName(ctx context.Context, client client.Client, log logr.Logger, substr, envvar string) (string, error) {
	controllerDeployment := &appsv1.Deployment{}
	err := client.Get(ctx, controllerName, controllerDeployment)
	if err != nil && !errors.IsNotFound(err) {
		return "", err
	}
	// ignore not found errors, fake/unit test path
	retImg := ""
	if err == nil {
		if len(controllerDeployment.Spec.Template.Spec.Containers) == 0 {
			return "", fmt.Errorf("no containers in controller deployment !!!")
		}
		imageName := controllerDeployment.Spec.Template.Spec.Containers[0].Image
		log.Info(fmt.Sprintf("GetImageName controller image %s", imageName))
		switch {
		case strings.Contains(imageName, "controller"):
			retImg = strings.Replace(imageName, "controller", substr, 1)
		default:
			ciImageName := os.Getenv(envvar)
			if len(ciImageName) == 0 {
				return "", fmt.Errorf("none of our image name patterns exist; controller image %s, %s not set", imageName, envvar)
			}
			retImg = ciImageName
		}
		log.Info(fmt.Sprintf("GetImageName using %s for hacbs-jvm-%s", retImg, substr))
	}
	return retImg, nil
}
