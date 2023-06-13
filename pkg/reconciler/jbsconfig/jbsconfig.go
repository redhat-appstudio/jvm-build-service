package jbsconfig

import (
	"context"
	"encoding/base64"
	errors2 "errors"
	"fmt"
	"github.com/redhat-appstudio/image-controller/pkg/quay"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/systemconfig"
	"github.com/redhat-appstudio/service-provider-integration-operator/api/v1beta1"
	"regexp"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sort"
	"strconv"
	"strings"
	"time"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	rbacv1 "k8s.io/api/rbac/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/tools/record"

	"github.com/go-logr/logr"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

const TlsServiceName = v1alpha1.CacheDeploymentName + "-tls"
const TestRegistry = "jvmbuildservice.io/test-registry"
const ImageRepositoryFinalizer = "jvmbuildservice.io/quay-repository-finalizer"
const DeleteImageRepositoryAnnotationName = "image.redhat.com/delete-image-repo"

const (
	Action              = "action"
	Audit               = "audit"
	ActionView   string = "VIEW"
	ActionAdd    string = "ADD"
	ActionUpdate string = "UPDATE"
	ActionDelete string = "DELETE"
)

type ReconcilerJBSConfig struct {
	client               client.Client
	scheme               *runtime.Scheme
	eventRecorder        record.EventRecorder
	configuredCacheImage string
	spiPresent           bool
	quayClient           *quay.QuayClient
	quayOrgName          string
}

func newReconciler(mgr ctrl.Manager, spiPresent bool, quayClient *quay.QuayClient, quayOrgName string) reconcile.Reconciler {
	ret := &ReconcilerJBSConfig{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("JBSConfig"),
		spiPresent:    spiPresent,
		quayClient:    quayClient,
		quayOrgName:   quayOrgName,
	}
	return ret
}

func (r *ReconcilerJBSConfig) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {

	var cancel context.CancelFunc
	ctx, cancel = context.WithTimeout(ctx, 300*time.Second)
	defer cancel()
	log := ctrl.Log.WithName("jbsconfig").WithValues("namespace", request.NamespacedName.Namespace, "resource", request.Name, "kind", "JBSConfig")
	jbsConfig := v1alpha1.JBSConfig{}
	err := r.client.Get(ctx, request.NamespacedName, &jbsConfig)
	if err != nil {
		// Deleted JBSConfig - delete cache resources
		if errors.IsNotFound(err) && request.Name == v1alpha1.JBSConfigName {
			service := &corev1.Service{}
			service.Name = v1alpha1.CacheDeploymentName
			service.Namespace = request.Namespace
			err = r.client.Delete(ctx, service)
			if err != nil && !errors.IsNotFound(err) {
				msg := fmt.Sprintf("Unable to delete service - %s", err.Error())
				log.Error(err, msg)
				r.eventRecorder.Event(service, corev1.EventTypeWarning, msg, "")
			}
			service = &corev1.Service{}
			service.Name = TlsServiceName
			service.Namespace = request.Namespace
			err = r.client.Delete(ctx, service)
			if err != nil && !errors.IsNotFound(err) {
				msg := fmt.Sprintf("Unable to delete service - %s", err.Error())
				log.Error(err, msg)
				r.eventRecorder.Event(service, corev1.EventTypeWarning, msg, "")
			}
			tlsConfigMap := &corev1.ConfigMap{}
			tlsConfigMap.Name = v1alpha1.TlsConfigMapName
			tlsConfigMap.Namespace = request.Namespace
			err = r.client.Delete(ctx, tlsConfigMap)
			if err != nil && !errors.IsNotFound(err) {
				msg := fmt.Sprintf("Unable to delete configmap - %s", err.Error())
				log.Error(err, msg)
				r.eventRecorder.Event(service, corev1.EventTypeWarning, msg, "")
			}
			serviceAccount := &corev1.ServiceAccount{}
			serviceAccount.Name = v1alpha1.CacheDeploymentName
			serviceAccount.Namespace = request.Namespace
			err = r.client.Delete(ctx, serviceAccount)
			if err != nil && !errors.IsNotFound(err) {
				msg := fmt.Sprintf("Unable to delete serviceAccount - %s", err.Error())
				log.Error(err, msg)
				r.eventRecorder.Event(serviceAccount, corev1.EventTypeWarning, msg, "")
			}
			roleBinding := &rbacv1.RoleBinding{}
			roleBinding.Name = v1alpha1.CacheDeploymentName
			roleBinding.Namespace = request.Namespace
			err = r.client.Delete(ctx, roleBinding)
			if err != nil && !errors.IsNotFound(err) {
				msg := fmt.Sprintf("Unable to delete roleBinding - %s", err.Error())
				log.Error(err, msg)
				r.eventRecorder.Event(roleBinding, corev1.EventTypeWarning, msg, "")
			}
			deployment := &appsv1.Deployment{}
			deployment.Name = v1alpha1.CacheDeploymentName
			deployment.Namespace = request.Namespace
			err = r.client.Delete(ctx, deployment)
			if err != nil && !errors.IsNotFound(err) {
				msg := fmt.Sprintf("Unable to delete deployment - %s", err.Error())
				log.Error(err, msg)
				r.eventRecorder.Event(deployment, corev1.EventTypeWarning, msg, "")
			}
			pvc := &corev1.PersistentVolumeClaim{}
			pvc.Name = v1alpha1.CacheDeploymentName
			pvc.Namespace = request.Namespace
			err = r.client.Delete(ctx, pvc)
			if err != nil && !errors.IsNotFound(err) {
				msg := fmt.Sprintf("Unable to delete PersistentVolumeClaim - %s", err.Error())
				log.Error(err, msg)
				r.eventRecorder.Event(deployment, corev1.EventTypeWarning, msg, "")
			}
			binding := &v1beta1.SPIAccessTokenBinding{}
			binding.Name = v1alpha1.ImageSecretName
			binding.Namespace = request.Namespace
			err = r.client.Delete(ctx, binding)
			if err != nil && !errors.IsNotFound(err) {
				msg := fmt.Sprintf("Unable to delete SPIAccessTokenBinding - %s", err.Error())
				log.Error(err, msg)
				r.eventRecorder.Event(deployment, corev1.EventTypeWarning, msg, "")
			}
			return reconcile.Result{}, nil
		}
		return reconcile.Result{}, err
	}
	if !jbsConfig.DeletionTimestamp.IsZero() {
		if controllerutil.ContainsFinalizer(&jbsConfig, ImageRepositoryFinalizer) {
			robotAccountName := generateRobotAccountName(&jbsConfig)
			isDeleted, err := r.quayClient.DeleteRobotAccount(r.quayOrgName, robotAccountName)
			if err != nil {
				log.Error(err, "failed to delete robot account")
				// Do not block Component deletion if failed to delete robot account
			}
			if isDeleted {
				log.Info(fmt.Sprintf("Deleted robot account %s", robotAccountName))
			}

			if val, exists := jbsConfig.Annotations[DeleteImageRepositoryAnnotationName]; exists && val == "true" {
				imageRepo := generateRepositoryName(&jbsConfig)
				isRepoDeleted, err := r.quayClient.DeleteRepository(r.quayOrgName, imageRepo)
				if err != nil {
					log.Error(err, "failed to delete image repository")
					// Do not block Component deletion if failed to delete image repository
				}
				if isRepoDeleted {
					log.Info(fmt.Sprintf("Deleted image repository %s", imageRepo))
				}
			}

			controllerutil.RemoveFinalizer(&jbsConfig, ImageRepositoryFinalizer)
			if err := r.client.Update(ctx, &jbsConfig); err != nil {
				log.Error(err, "failed to remove image repository finalizer")
				return ctrl.Result{}, err
			}
			log.Info("Image repository finalizer removed from the JBSConfig")
		}
	}

	//TODO do we eventually want to allow more than one JBSConfig per namespace?
	if jbsConfig.Name == v1alpha1.JBSConfigName {

		systemConfig := v1alpha1.SystemConfig{}
		err := r.client.Get(ctx, types.NamespacedName{Name: systemconfig.SystemConfigKey}, &systemConfig)
		if err != nil {
			return reconcile.Result{}, err
		}
		err = r.validations(ctx, log, request, &jbsConfig)
		if err != nil {
			return reconcile.Result{}, err
		}

		err = r.deploymentSupportObjects(ctx, log, request, &jbsConfig)
		if err != nil {
			return reconcile.Result{}, err
		}

		err = r.cacheDeployment(ctx, log, request, &jbsConfig, &systemConfig)
		if err != nil {
			return reconcile.Result{}, err
		}
	}
	return reconcile.Result{}, nil
}

func settingOrDefault(setting, def string) string {
	if len(strings.TrimSpace(setting)) == 0 {
		return def
	}
	return setting
}
func generateRobotAccountName(component *v1alpha1.JBSConfig) string {
	robotAccountName := component.Namespace + component.Name
	robotAccountName = strings.Replace(robotAccountName, "-", "_", -1)
	return robotAccountName
}

func generateRepositoryName(component *v1alpha1.JBSConfig) string {
	return component.Namespace + "/jvm-build-service-artifacts"
}
func setEnvVarValue(field, envName string, cache *appsv1.Deployment) *appsv1.Deployment {
	envVar := corev1.EnvVar{
		Name:  envName,
		Value: field,
	}
	return setEnvVar(envVar, cache)
}

func setEnvVar(envVar corev1.EnvVar, cache *appsv1.Deployment) *appsv1.Deployment {
	if len(strings.TrimSpace(envVar.Value)) > 0 {
		//insert them in alphabetical order
		for i, e := range cache.Spec.Template.Spec.Containers[0].Env {

			compare := strings.Compare(envVar.Name, e.Name)
			if compare < 0 {
				val := []corev1.EnvVar{}
				val = append(val, cache.Spec.Template.Spec.Containers[0].Env[0:i]...)
				val = append(val, envVar)
				val = append(val, cache.Spec.Template.Spec.Containers[0].Env[i:]...)
				cache.Spec.Template.Spec.Containers[0].Env = val
				return cache
			} else if compare == 0 {
				//already present, overwrite
				cache.Spec.Template.Spec.Containers[0].Env[i] = envVar
				return cache
			}
		}
		//needs to go at the end
		cache.Spec.Template.Spec.Containers[0].Env = append(cache.Spec.Template.Spec.Containers[0].Env, envVar)
	} else {
		//we might need to remove the setting
		for i, e := range cache.Spec.Template.Spec.Containers[0].Env {
			if envVar.Name == e.Name {
				//remove the entry
				val := cache.Spec.Template.Spec.Containers[0].Env[0:i]
				val = append(val, cache.Spec.Template.Spec.Containers[0].Env[i+1:]...)
				cache.Spec.Template.Spec.Containers[0].Env = val
				return cache
			}
		}
	}
	return cache
}

func (r *ReconcilerJBSConfig) validations(ctx context.Context, log logr.Logger, request reconcile.Request, jbsConfig *v1alpha1.JBSConfig) error {
	if jbsConfig.Annotations != nil {
		val := jbsConfig.Annotations[TestRegistry]
		if val == "true" {
			return nil
		}
	}
	if !jbsConfig.Spec.EnableRebuilds {
		return nil
	}

	if jbsConfig.Spec.Owner == "" {
		return r.handleNoOwnerSpecified(ctx, log, jbsConfig)
	}

	registrySecret := &corev1.Secret{}
	// our client is wired to not cache secrets / establish informers for secrets
	err := r.client.Get(ctx, types.NamespacedName{Namespace: request.Namespace, Name: v1alpha1.ImageSecretName}, registrySecret)
	if err != nil {
		if errors.IsNotFound(err) {
			return r.handleNoImageSecretFound(ctx, jbsConfig)
		}
		return err
	}
	_, keyPresent1 := registrySecret.Data[v1alpha1.ImageSecretTokenKey]
	_, keyPresent2 := registrySecret.StringData[v1alpha1.ImageSecretTokenKey]
	if !keyPresent1 && !keyPresent2 {
		err := fmt.Errorf("need image registry token set at key %s in secret %s to enable rebuilds", v1alpha1.ImageSecretTokenKey, v1alpha1.ImageSecretName)
		errorMessage := err.Error()
		if jbsConfig.Status.Message != errorMessage {
			jbsConfig.Status.Message = errorMessage
			err2 := r.client.Status().Update(ctx, jbsConfig)
			if err2 != nil {
				return err2
			}
		}
		if r.spiPresent {
			return nil
		}
		return err
	}
	message := fmt.Sprintf("found %s secret with appropriate token keys in namespace %s, rebuilds are possible", v1alpha1.ImageSecretTokenKey, request.Namespace)
	log.Info(message)
	if jbsConfig.Status.Message != message {
		jbsConfig.Status.Message = message
		err2 := r.client.Status().Update(ctx, jbsConfig)
		if err2 != nil {
			return err2
		}
	}
	return nil
}

func (r *ReconcilerJBSConfig) deploymentSupportObjects(ctx context.Context, log logr.Logger, request reconcile.Request, jbsConfig *v1alpha1.JBSConfig) error {
	//TODO may have to switch to ephemeral storage for KCP until storage story there is sorted out
	pvc := corev1.PersistentVolumeClaim{}
	deploymentName := types.NamespacedName{Namespace: request.Namespace, Name: v1alpha1.CacheDeploymentName}
	err := r.client.Get(ctx, deploymentName, &pvc)
	if err != nil {
		if errors.IsNotFound(err) {
			pvc = corev1.PersistentVolumeClaim{}
			pvc.Name = v1alpha1.CacheDeploymentName
			pvc.Namespace = request.Namespace
			pvc.Spec.AccessModes = []corev1.PersistentVolumeAccessMode{corev1.ReadWriteOnce}
			qty, err := resource.ParseQuantity(settingOrDefault(jbsConfig.Spec.CacheSettings.Storage, v1alpha1.ConfigArtifactCacheStorageDefault))
			if err != nil {
				return err
			}
			pvc.Spec.Resources.Requests = map[corev1.ResourceName]resource.Quantity{"storage": qty}
			err = r.client.Create(ctx, &pvc)
			if err != nil {
				return err
			}
		}
	}
	//and setup the service
	err = r.client.Get(ctx, types.NamespacedName{Name: v1alpha1.CacheDeploymentName, Namespace: request.Namespace}, &corev1.Service{})
	if err != nil {
		if errors.IsNotFound(err) {
			service := corev1.Service{
				ObjectMeta: ctrl.ObjectMeta{
					Name:      v1alpha1.CacheDeploymentName,
					Namespace: request.Namespace,
				},
				Spec: corev1.ServiceSpec{
					Ports: []corev1.ServicePort{
						{
							Name:       "http",
							Port:       80,
							TargetPort: intstr.IntOrString{IntVal: 8080},
						},
					},
					Type:     corev1.ServiceTypeClusterIP,
					Selector: map[string]string{"app": v1alpha1.CacheDeploymentName},
				},
			}
			err := r.client.Create(ctx, &service)
			if err != nil {
				return err
			}
		}
	}
	//and setup the TLS service
	err = r.client.Get(ctx, types.NamespacedName{Name: TlsServiceName, Namespace: request.Namespace}, &corev1.Service{})
	if err != nil {
		if errors.IsNotFound(err) {
			service := corev1.Service{
				ObjectMeta: ctrl.ObjectMeta{
					Name:        TlsServiceName,
					Namespace:   request.Namespace,
					Annotations: map[string]string{"service.beta.openshift.io/serving-cert-secret-name": v1alpha1.TlsSecretName},
				},
				Spec: corev1.ServiceSpec{
					Ports: []corev1.ServicePort{
						{
							Name:       "https",
							Port:       443,
							TargetPort: intstr.IntOrString{IntVal: 8443},
						},
					},
					Type:     corev1.ServiceTypeClusterIP,
					Selector: map[string]string{"app": v1alpha1.CacheDeploymentName},
				},
			}
			err := r.client.Create(ctx, &service)
			if err != nil {
				return err
			}
		}
	}
	if !jbsConfig.Spec.CacheSettings.DisableTLS {
		//and setup the CA for the secured service
		err = r.client.Get(ctx, types.NamespacedName{Name: v1alpha1.TlsConfigMapName, Namespace: request.Namespace}, &corev1.ConfigMap{})
		if err != nil {
			if errors.IsNotFound(err) {
				service := corev1.ConfigMap{
					ObjectMeta: ctrl.ObjectMeta{
						Name:        v1alpha1.TlsConfigMapName,
						Namespace:   request.Namespace,
						Annotations: map[string]string{"service.beta.openshift.io/inject-cabundle": "true"},
					},
				}
				err := r.client.Create(ctx, &service)
				if err != nil {
					return err
				}
			}
		}
	}
	//setup the service account
	sa := corev1.ServiceAccount{}
	saName := types.NamespacedName{Namespace: request.Namespace, Name: v1alpha1.CacheDeploymentName}
	err = r.client.Get(ctx, saName, &sa)
	if err != nil {
		if errors.IsNotFound(err) {
			sa := corev1.ServiceAccount{}
			sa.Name = v1alpha1.CacheDeploymentName
			sa.Namespace = request.Namespace
			err := r.client.Create(ctx, &sa)
			if err != nil {
				return err
			}
		}
	}
	cb := rbacv1.RoleBinding{}
	cbName := types.NamespacedName{Namespace: request.Namespace, Name: v1alpha1.CacheDeploymentName}
	err = r.client.Get(ctx, cbName, &cb)
	if err != nil {
		if errors.IsNotFound(err) {
			cb := rbacv1.RoleBinding{}
			cb.Name = v1alpha1.CacheDeploymentName
			cb.Namespace = request.Namespace
			cb.RoleRef = rbacv1.RoleRef{Kind: "ClusterRole", Name: "hacbs-jvm-cache", APIGroup: "rbac.authorization.k8s.io"}
			cb.Subjects = []rbacv1.Subject{{Kind: "ServiceAccount", Name: v1alpha1.CacheDeploymentName, Namespace: request.Namespace}}
			err := r.client.Create(ctx, &cb)
			if err != nil {
				return err
			}
		}
	}
	return nil
}

func (r *ReconcilerJBSConfig) cacheDeployment(ctx context.Context, log logr.Logger, request reconcile.Request, jbsConfig *v1alpha1.JBSConfig, sysConfig *v1alpha1.SystemConfig) error {
	cache := &appsv1.Deployment{}
	trueBool := true
	deploymentName := types.NamespacedName{Namespace: request.Namespace, Name: v1alpha1.CacheDeploymentName}
	err := r.client.Get(ctx, deploymentName, cache)
	create := false
	if err != nil {
		if errors.IsNotFound(err) {
			create = true
			cache.Name = deploymentName.Name
			cache.Namespace = deploymentName.Namespace
			var replicas int32 = 1
			var zero int32 = 0
			cache.Spec.RevisionHistoryLimit = &zero
			cache.Spec.Replicas = &replicas
			cache.Spec.Strategy = appsv1.DeploymentStrategy{Type: appsv1.RecreateDeploymentStrategyType}
			cache.Spec.Selector = &metav1.LabelSelector{MatchLabels: map[string]string{"app": v1alpha1.CacheDeploymentName}}
			cache.Spec.Template.ObjectMeta.Labels = map[string]string{"app": v1alpha1.CacheDeploymentName}
			cache.Spec.Template.Spec.Containers = []corev1.Container{{
				Name:            v1alpha1.CacheDeploymentName,
				ImagePullPolicy: corev1.PullIfNotPresent,
				Ports: []corev1.ContainerPort{
					{
						Name:          "http",
						ContainerPort: 8080,
						Protocol:      "TCP",
					},
					{
						Name:          "https",
						ContainerPort: 8443,
						Protocol:      "TCP",
					}},
				VolumeMounts: []corev1.VolumeMount{{Name: v1alpha1.CacheDeploymentName, MountPath: "/cache"}, {Name: "tls", MountPath: "/tls"}},

				Resources: corev1.ResourceRequirements{
					Requests: map[corev1.ResourceName]resource.Quantity{
						"memory": resource.MustParse(settingOrDefault(jbsConfig.Spec.CacheSettings.RequestMemory, v1alpha1.ConfigArtifactCacheRequestMemoryDefault)),
						"cpu":    resource.MustParse(settingOrDefault(jbsConfig.Spec.CacheSettings.RequestCPU, v1alpha1.ConfigArtifactCacheRequestCPUDefault))},
					Limits: map[corev1.ResourceName]resource.Quantity{
						"memory": resource.MustParse(settingOrDefault(jbsConfig.Spec.CacheSettings.LimitMemory, v1alpha1.ConfigArtifactCacheLimitMemoryDefault)),
						"cpu":    resource.MustParse(settingOrDefault(jbsConfig.Spec.CacheSettings.LimitCPU, v1alpha1.ConfigArtifactCacheLimitCPUDefault))},
				},
				LivenessProbe:  &corev1.Probe{TimeoutSeconds: 15, ProbeHandler: corev1.ProbeHandler{HTTPGet: &corev1.HTTPGetAction{Path: "/q/health/live", Port: intstr.FromInt(8080)}}},
				ReadinessProbe: &corev1.Probe{ProbeHandler: corev1.ProbeHandler{HTTPGet: &corev1.HTTPGetAction{Path: "/q/health/ready", Port: intstr.FromInt(8080)}}},
			}}
			cache.Spec.Template.Spec.Volumes = []corev1.Volume{
				{Name: v1alpha1.CacheDeploymentName, VolumeSource: corev1.VolumeSource{PersistentVolumeClaim: &corev1.PersistentVolumeClaimVolumeSource{ClaimName: v1alpha1.CacheDeploymentName}}},
			}
			if !jbsConfig.Spec.CacheSettings.DisableTLS {
				cache.Spec.Template.Spec.Volumes = append(cache.Spec.Template.Spec.Volumes, corev1.Volume{Name: "tls", VolumeSource: corev1.VolumeSource{Secret: &corev1.SecretVolumeSource{SecretName: v1alpha1.TlsSecretName, Optional: &trueBool}}})
			} else {
				cache.Spec.Template.Spec.Volumes = append(cache.Spec.Template.Spec.Volumes, corev1.Volume{Name: "tls", VolumeSource: corev1.VolumeSource{EmptyDir: &corev1.EmptyDirVolumeSource{}}})
			}

		} else {
			return err
		}
	}
	cache.Spec.Template.Spec.ServiceAccountName = v1alpha1.CacheDeploymentName
	cache.Spec.Template.Spec.Containers[0].Env = []corev1.EnvVar{}
	setEnvVarValue("/cache", "CACHE_PATH", cache)
	setEnvVarValue(settingOrDefault(jbsConfig.Spec.CacheSettings.IOThreads, v1alpha1.ConfigArtifactCacheIOThreadsDefault), "QUARKUS_VERTX_EVENT_LOOPS_POOL_SIZE", cache)
	setEnvVarValue(settingOrDefault(jbsConfig.Spec.CacheSettings.WorkerThreads, v1alpha1.ConfigArtifactCacheWorkerThreadsDefault), "QUARKUS_THREAD_POOL_MAX_THREADS", cache)

	if !jbsConfig.Spec.CacheSettings.DisableTLS {
		setEnvVarValue("/tls/tls.crt", "QUARKUS_HTTP_SSL_CERTIFICATE_FILES", cache)
		setEnvVarValue("/tls/tls.key", "QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES", cache)
	}
	if jbsConfig.Annotations != nil {
		val := jbsConfig.Annotations[TestRegistry]
		if val == "true" {
			setEnvVarValue("true", "INSECURE_TEST_REGISTRY", cache)
		}
	}
	type Repo struct {
		name     string
		position int
	}

	recipeData := ""
	if sysConfig.Spec.RecipeDatabase == "" {
		recipeData = v1alpha1.DefaultRecipeDatabase
	} else {
		recipeData = sysConfig.Spec.RecipeDatabase
	}
	for _, i := range jbsConfig.Spec.AdditionalRecipes {
		recipeData = recipeData + "," + i
	}
	cache = setEnvVarValue(recipeData, "BUILD_INFO_REPOSITORIES", cache)

	//central is at the hard coded 200 position
	//redhat is configured at 250
	repos := []Repo{{name: "central", position: 200}, {name: "redhat", position: 250}}
	if jbsConfig.Spec.EnableRebuilds {
		repos = append(repos, Repo{name: "rebuilt", position: 100})

		imageRegistry := jbsConfig.ImageRegistry()
		cache = setEnvVarValue(imageRegistry.Owner, "REGISTRY_OWNER", cache)
		cache = setEnvVarValue(imageRegistry.Host, "REGISTRY_HOST", cache)
		cache = setEnvVarValue(imageRegistry.Port, "REGISTRY_PORT", cache)
		cache = setEnvVarValue(imageRegistry.Repository, "REGISTRY_REPOSITORY", cache)
		cache = setEnvVarValue(strconv.FormatBool(imageRegistry.Insecure), "REGISTRY_INSECURE", cache)
		cache = setEnvVarValue(imageRegistry.PrependTag, "REGISTRY_PREPEND_TAG", cache)
		cache = setEnvVar(corev1.EnvVar{
			Name:      "REGISTRY_TOKEN",
			ValueFrom: &corev1.EnvVarSource{SecretKeyRef: &corev1.SecretKeySelector{LocalObjectReference: corev1.LocalObjectReference{Name: v1alpha1.ImageSecretName}, Key: v1alpha1.ImageSecretTokenKey, Optional: &trueBool}},
		}, cache)
		cache = setEnvVar(corev1.EnvVar{
			Name:      "GIT_TOKEN",
			ValueFrom: &corev1.EnvVarSource{SecretKeyRef: &corev1.SecretKeySelector{LocalObjectReference: corev1.LocalObjectReference{Name: v1alpha1.GitSecretName}, Key: v1alpha1.GitSecretTokenKey, Optional: &trueBool}},
		}, cache)
		for _, relocationPatternElement := range jbsConfig.Spec.RelocationPatterns {
			buildPolicy := relocationPatternElement.RelocationPattern.BuildPolicy
			if buildPolicy == "" {
				buildPolicy = "default"
			}
			envName := "BUILD_POLICY_" + strings.ToUpper(buildPolicy) + "_RELOCATION_PATTERN"

			var envValues []string
			for _, patternElement := range relocationPatternElement.RelocationPattern.Patterns {
				envValues = append(envValues, patternElement.Pattern.From+"="+patternElement.Pattern.To)
			}
			envValue := strings.Join(envValues, ",")
			cache = setEnvVarValue(envValue, envName, cache)
		}
	}

	regex, err := regexp.Compile(`maven-repository-(\d+)-([\w-]+)`)
	if err != nil {
		return err
	}
	for k, v := range jbsConfig.Spec.MavenBaseLocations {
		if regex.MatchString(k) {
			results := regex.FindStringSubmatch(k)
			atoi, err := strconv.Atoi(results[1])
			name := results[2]
			if err != nil {
				return err
			}
			existing := false
			for _, i := range repos {
				if i.name == name {
					existing = true
					break
				}
			}
			if existing {
				jbsConfig.Status.Message = jbsConfig.Status.Message + " Repository " + name + " defined twice, ignoring " + v
				continue
			}
			cache = setEnvVarValue(v, "STORE_"+strings.ToUpper(strings.Replace(name, "-", "_", -1))+"_URL", cache)
			cache = setEnvVarValue("maven2", "STORE_"+strings.ToUpper(strings.Replace(name, "-", "_", -1))+"_TYPE", cache)
			repos = append(repos, Repo{position: atoi, name: name})
		}
	}
	var sb strings.Builder
	sort.Slice(repos, func(i, j int) bool {
		return repos[i].position < repos[j].position
	})
	for _, i := range repos {
		if sb.Len() > 0 {
			sb.WriteString(",")
		}
		sb.WriteString(i.name)
	}
	cache = setEnvVarValue(sb.String(), "BUILD_POLICY_DEFAULT_STORE_LIST", cache)

	if len(r.configuredCacheImage) == 0 {
		r.configuredCacheImage, err = util.GetImageName(ctx, r.client, log, "cache", "JVM_BUILD_SERVICE_CACHE_IMAGE")
		if err != nil {
			return err
		}
	}
	cache.Spec.Template.Spec.Containers[0].Image = r.configuredCacheImage
	if strings.HasPrefix(r.configuredCacheImage, "quay.io/minikube") {
		cache.Spec.Template.Spec.Containers[0].ImagePullPolicy = corev1.PullNever
	} else if !strings.HasPrefix(r.configuredCacheImage, "quay.io/redhat-appstudio") {
		// work around for developer mode while we are hard coding the spec in the controller
		cache.Spec.Template.Spec.Containers[0].ImagePullPolicy = corev1.PullAlways
	}

	if create {
		return r.client.Create(ctx, cache)
	} else {
		return r.client.Update(ctx, cache)
	}
}

func (r *ReconcilerJBSConfig) handleNoImageSecretFound(ctx context.Context, config *v1alpha1.JBSConfig) error {
	binding := v1beta1.SPIAccessTokenBinding{}
	err := r.client.Get(ctx, types.NamespacedName{Name: v1alpha1.ImageSecretName, Namespace: config.Namespace}, &binding)
	if err != nil {
		if errors.IsNotFound(err) {
			binding.Name = v1alpha1.ImageSecretName
			binding.Namespace = config.Namespace
			imageRegistry := config.ImageRegistry()
			url := "https://"
			if imageRegistry.Host == "" {
				url += "quay.io"
			} else {
				url += imageRegistry.Host
			}
			url += "/" + imageRegistry.Owner + "/"
			if imageRegistry.Repository == "" {
				url += "artifact-deployments"
			} else {
				url += imageRegistry.Repository
			}

			binding.Spec.RepoUrl = url
			binding.Spec.Lifetime = "-1"
			binding.Spec.Permissions = v1beta1.Permissions{Required: []v1beta1.Permission{{Type: v1beta1.PermissionTypeReadWrite, Area: v1beta1.PermissionAreaRegistry}}}
			binding.Spec.Secret = v1beta1.SecretSpec{
				Name: v1alpha1.ImageSecretName,
				Type: corev1.SecretTypeDockerConfigJson,
			}
			err = controllerutil.SetOwnerReference(config, &binding, r.scheme)
			if err != nil {
				return err
			}
			//we just return
			err := r.client.Create(ctx, &binding)
			if err != nil {
				return err
			}
			return errors2.New("created SPIAccessTokenBinding, waiting for secret to be injected")
		} else {
			return err
		}
	}
	switch binding.Status.Phase {
	case v1beta1.SPIAccessTokenBindingPhaseError:
		return errors2.New(binding.Status.ErrorMessage)
	case v1beta1.SPIAccessTokenBindingPhaseInjected:
		return errors2.New("unexpected error, SPIAccessTokenBinding claims to be injected but secret was not found")
	}
	return errors2.New("created SPIAccessTokenBinding, waiting for secret to be injected")
}

func (r *ReconcilerJBSConfig) handleNoOwnerSpecified(ctx context.Context, log logr.Logger, config *v1alpha1.JBSConfig) error {
	if r.quayClient == nil || r.quayOrgName == "" {
		return errors2.New("no owner specified and automatic repo creation is disabled")
	}

	repo, robotAccount, err := r.generateImageRepository(log, config)
	if err != nil {
		return err
	}
	if repo == nil || robotAccount == nil {
		return errors2.New("unknown error in the repository generation process")
	}
	controllerutil.AddFinalizer(config, ImageRepositoryFinalizer)
	config.Status.ImageRegistry = &v1alpha1.ImageRegistry{
		Owner:      r.quayOrgName,
		Host:       "quay.io",
		Repository: repo.Name,
	}
	err = r.client.Status().Update(ctx, config)
	if err != nil {
		return err
	}

	// Create secret with the reposuitory credentials
	robotAccountSecret := generateSecret(config, *robotAccount)

	robotAccountSecretKey := types.NamespacedName{Namespace: config.Namespace, Name: v1alpha1.ImageSecretName}
	existingRobotAccountSecret := &corev1.Secret{}
	if err := r.client.Get(ctx, robotAccountSecretKey, existingRobotAccountSecret); err == nil {
		if err := r.client.Delete(ctx, existingRobotAccountSecret); err != nil {
			log.Error(err, fmt.Sprintf("failed to delete robot account secret %v", robotAccountSecretKey), Action, ActionDelete)
			return err
		} else {
			log.Info(fmt.Sprintf("Deleted old robot account secret %v", robotAccountSecretKey), Action, ActionDelete)
		}
	} else if !errors.IsNotFound(err) {
		log.Error(err, fmt.Sprintf("failed to read robot account secret %v", robotAccountSecretKey), Action, ActionView)
		return err
	}

	if err := r.client.Create(ctx, &robotAccountSecret); err != nil {
		log.Error(err, fmt.Sprintf("error writing robot account token into Secret: %v", robotAccountSecretKey), Action, ActionAdd)
		return err
	}
	log.Info(fmt.Sprintf("Created image registry secret %s for Component", robotAccountSecretKey.Name), Action, ActionAdd)

	return nil
}

func (r *ReconcilerJBSConfig) generateImageRepository(log logr.Logger, component *v1alpha1.JBSConfig) (*quay.Repository, *quay.RobotAccount, error) {

	imageRepositoryName := generateRepositoryName(component)
	repo, err := r.quayClient.CreateRepository(quay.RepositoryRequest{
		Namespace:   r.quayOrgName,
		Visibility:  "public",
		Description: "JVM Build Service repository for the user",
		Repository:  imageRepositoryName,
	})
	if err != nil {
		log.Error(err, fmt.Sprintf("failed to create image repository %s", imageRepositoryName))
		return nil, nil, err
	}

	robotAccountName := generateRobotAccountName(component)
	_, _ = r.quayClient.DeleteRobotAccount(r.quayOrgName, robotAccountName)
	robotAccount, err := r.quayClient.CreateRobotAccount(r.quayOrgName, robotAccountName)
	if err != nil {
		log.Error(err, fmt.Sprintf("failed to create robot account %s", robotAccountName))
		return nil, nil, err
	}

	err = r.quayClient.AddWritePermissionsToRobotAccount(r.quayOrgName, repo.Name, robotAccountName)
	if err != nil {
		log.Error(err, fmt.Sprintf("failed to add permissions to robot account %s", robotAccountName))
		return nil, nil, err
	}

	return repo, robotAccount, nil
}

// generateSecret dumps the robot account token into a Secret for future consumption.
func generateSecret(c *v1alpha1.JBSConfig, r quay.RobotAccount) corev1.Secret {
	secret := corev1.Secret{
		ObjectMeta: metav1.ObjectMeta{
			Name:      v1alpha1.ImageSecretName,
			Namespace: c.Namespace,
			OwnerReferences: []metav1.OwnerReference{
				{
					Name:       c.Name,
					APIVersion: c.APIVersion,
					Kind:       c.Kind,
					UID:        c.UID,
				},
			},
		},
		Type: corev1.SecretTypeDockerConfigJson,
	}

	secretData := map[string]string{}
	authString := fmt.Sprintf("%s:%s", r.Name, r.Token)
	secretData[corev1.DockerConfigJsonKey] = fmt.Sprintf(`{"auths":{"%s":{"auth":"%s"}}}`,
		"quay.io",
		base64.StdEncoding.EncodeToString([]byte(authString)),
	)

	secret.StringData = secretData
	return secret
}
