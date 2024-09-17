package jbsconfig

import (
	"context"
	errors2 "errors"
	"fmt"
	imagecontroller "github.com/konflux-ci/image-controller/api/v1alpha1"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/systemconfig"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"

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

const (
	TlsServiceName            = v1alpha1.CacheDeploymentName + "-tls"
	TestRegistry              = "jvmbuildservice.io/test-registry"
	RetryTimeAnnotations      = "jvmbuildservice.io/retry-time"
	RetryTimestampAnnotations = "jvmbuildservice.io/retry-timestamp"
)

type ReconcilerJBSConfig struct {
	client               client.Client
	scheme               *runtime.Scheme
	eventRecorder        record.EventRecorder
	configuredCacheImage string
	spiPresent           bool
}

func newReconciler(mgr ctrl.Manager, spiPresent bool) reconcile.Reconciler {
	ret := &ReconcilerJBSConfig{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("JBSConfig"),
		spiPresent:    spiPresent,
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
		if errors.IsNotFound(err) {
			return reconcile.Result{}, nil
		}
		return reconcile.Result{}, err
	}
	log.Info("reconciling JBSConfig")

	// TODO: ### Should we add some sanity checking i.e. if ContainerBuilds are enabled, we need GIT_DEPLOY_TOKEN
	//      i.e. source archiving in DeployPreBuildSourceCommand
	fmt.Printf("### JBSConfig containerBuilds %#v \n", jbsConfig.Spec.ContainerBuilds)
	log.Info(fmt.Sprintf("### JBSConfig containerBuilds %#v \n", jbsConfig.Spec.ContainerBuilds))

	//TODO do we eventually want to allow more than one JBSConfig per namespace?
	if jbsConfig.Name == v1alpha1.JBSConfigName {
		systemConfig := v1alpha1.SystemConfig{}
		err := r.client.Get(ctx, types.NamespacedName{Name: systemconfig.SystemConfigKey}, &systemConfig)
		if err != nil {
			return reconcile.Result{}, err
		}
		err = r.validations(ctx, log, request, &jbsConfig)
		if err != nil {
			log.Error(err, "validation failed for JBSConfig")
			if errors.IsConflict(err) {
				return reconcile.Result{}, err
			}
			if jbsConfig.Status.Message != err.Error() || jbsConfig.Status.RebuildsPossible {
				jbsConfig.Status.Message = err.Error()
				jbsConfig.Status.RebuildsPossible = false
				//this should trigger a requeue, which will then fall through to the retry code
				log.Error(err, fmt.Sprintf("Unable to enable rebuilds for namespace %s", jbsConfig.Namespace))
				err2 := r.client.Status().Update(ctx, &jbsConfig)
				return reconcile.Result{Requeue: true}, err2
			}
			if r.spiPresent && jbsConfig.Spec.Registry.ImageRegistry.Owner == "" {
				//this is due to https://issues.redhat.com/browse/RHTAPBUGS-937
				//we should not need the retry if this is fixed in the image controller
				if jbsConfig.Annotations == nil {
					jbsConfig.Annotations = map[string]string{}
				}

				existingRetryTimestamp := jbsConfig.Annotations[RetryTimestampAnnotations]
				if existingRetryTimestamp != "" {
					secs, err := strconv.ParseInt(existingRetryTimestamp, 10, 64)
					if err == nil {
						if time.Now().UnixMilli() < secs {
							//too early, just return
							return reconcile.Result{RequeueAfter: time.Millisecond * time.Duration(secs-time.Now().UnixMilli())}, err
						}
					}
				}
				existing := jbsConfig.Annotations[RetryTimeAnnotations]
				if existing == "" {
					existing = "1"
				}
				var existingSeconds int64
				secs, err := strconv.Atoi(existing)
				existingSeconds = int64(secs)
				if err != nil {
					log.Error(err, fmt.Sprintf("Unable to enable rebuilds for namespace %s, failed to parse retry timeout", jbsConfig.Namespace))
					return reconcile.Result{}, nil
				}
				jbsConfig.Annotations[RetryTimeAnnotations] = strconv.Itoa(secs * 2)
				jbsConfig.Annotations[RetryTimeAnnotations] = strconv.FormatInt(time.Now().UnixMilli()+(existingSeconds*1000), 10)
				err = r.client.Update(ctx, &jbsConfig)
				if err != nil {
					return reconcile.Result{RequeueAfter: time.Second * time.Duration(existingSeconds)}, err
				}
				log.Info(fmt.Sprintf("will retry secret lookup after %d seconds", existingSeconds))
				return reconcile.Result{RequeueAfter: time.Second * time.Duration(existingSeconds)}, nil
			} else {
				log.Error(err, fmt.Sprintf("Unable to enable rebuilds for namespace %s", jbsConfig.Namespace))
				return reconcile.Result{}, nil

			}
		}

		err = r.deploymentSupportObjects(ctx, request, &jbsConfig)
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

func setEnvVarValue(field, envName string, cache *appsv1.Deployment) *appsv1.Deployment {
	envVar := corev1.EnvVar{
		Name:  envName,
		Value: field,
	}
	return setEnvVar(envVar, cache)
}

func setEnvVar(envVar corev1.EnvVar, cache *appsv1.Deployment) *appsv1.Deployment {
	if len(strings.TrimSpace(envVar.Value)) > 0 || envVar.ValueFrom != nil {
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

	if jbsConfig.ImageRegistry().Owner == "" {
		if !r.spiPresent {
			return fmt.Errorf("image repository not configured")
		}
		err := r.handleNoOwnerSpecified(ctx, log, jbsConfig)
		if err != nil {
			return err
		}
	}

	registrySecret := &corev1.Secret{}
	secretName := jbsConfig.ImageRegistry().SecretName
	if secretName == "" {
		//this we be updated below in the status if the secret is present
		secretName = v1alpha1.DefaultImageSecretName
	}
	// our client is wired to not cache secrets / establish informers for secrets
	err := r.client.Get(ctx, types.NamespacedName{Namespace: request.Namespace, Name: secretName}, registrySecret)
	if err != nil {
		if errors.IsNotFound(err) {
			return fmt.Errorf("secret %s not found, and explicit repository configured or image controller not installed, rebuilds not possible", secretName)
		}
		return err
	}
	_, keyPresent1 := registrySecret.Data[v1alpha1.ImageSecretTokenKey]
	_, keyPresent2 := registrySecret.StringData[v1alpha1.ImageSecretTokenKey]
	if !keyPresent1 && !keyPresent2 {
		err := fmt.Errorf("need image registry token set at key %s in secret %s to enable rebuilds", v1alpha1.ImageSecretTokenKey, v1alpha1.DefaultImageSecretName)
		return err
	}
	message := fmt.Sprintf("found %s secret with appropriate token keys in namespace %s, rebuilds are possible", v1alpha1.ImageSecretTokenKey, request.Namespace)
	log.Info(message)
	if jbsConfig.Status.Message != message || !jbsConfig.Status.RebuildsPossible || jbsConfig.ImageRegistry().SecretName != secretName {
		jbsConfig.Status.RebuildsPossible = true
		jbsConfig.Status.Message = message
		if jbsConfig.Status.ImageRegistry == nil {
			jbsConfig.Status.ImageRegistry = &v1alpha1.ImageRegistry{}
		}
		jbsConfig.Status.ImageRegistry.SecretName = secretName
		err2 := r.client.Status().Update(ctx, jbsConfig)
		if err2 != nil {
			return err2
		}
	}
	return nil
}

func (r *ReconcilerJBSConfig) deploymentSupportObjects(ctx context.Context, request reconcile.Request, jbsConfig *v1alpha1.JBSConfig) error {
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

			if err := controllerutil.SetOwnerReference(jbsConfig, &pvc, r.scheme); err != nil {
				return err
			}
			if err := r.client.Create(ctx, &pvc); err != nil {
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
			if err := controllerutil.SetOwnerReference(jbsConfig, &service, r.scheme); err != nil {
				return err
			}
			if err := r.client.Create(ctx, &service); err != nil {
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
			if err := controllerutil.SetOwnerReference(jbsConfig, &service, r.scheme); err != nil {
				return err
			}
			if err := r.client.Create(ctx, &service); err != nil {
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
				if err := controllerutil.SetOwnerReference(jbsConfig, &service, r.scheme); err != nil {
					return err
				}
				if err := r.client.Create(ctx, &service); err != nil {
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
			if err := controllerutil.SetOwnerReference(jbsConfig, &sa, r.scheme); err != nil {
				return err
			}
			if err := r.client.Create(ctx, &sa); err != nil {
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
			if err = controllerutil.SetOwnerReference(jbsConfig, &cb, r.scheme); err != nil {
				return err
			}
			if err := r.client.Create(ctx, &cb); err != nil {
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
			message := fmt.Sprintf("Creating cache in namespace %s", request.Namespace)
			log.Info(message)
			create = true
			cache.Name = deploymentName.Name
			cache.Namespace = deploymentName.Namespace
			var replicas int32 = 1
			var zero int32 = 0
			cache.Spec.RevisionHistoryLimit = &zero
			cache.Spec.Replicas = &replicas
			cache.Spec.Strategy = appsv1.DeploymentStrategy{Type: appsv1.RecreateDeploymentStrategyType}
			cache.Spec.Selector = &metav1.LabelSelector{MatchLabels: map[string]string{"app": v1alpha1.CacheDeploymentName}}
			cache.Spec.Template.Labels = map[string]string{"app": v1alpha1.CacheDeploymentName}
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
				StartupProbe:  &corev1.Probe{FailureThreshold: 120, PeriodSeconds: 1, ProbeHandler: corev1.ProbeHandler{HTTPGet: &corev1.HTTPGetAction{Path: "/q/health/live", Port: intstr.FromInt32(8080)}}},
				LivenessProbe: &corev1.Probe{FailureThreshold: 3, PeriodSeconds: 5, ProbeHandler: corev1.ProbeHandler{HTTPGet: &corev1.HTTPGetAction{Path: "/q/health/live", Port: intstr.FromInt32(8080)}}},
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
	secretOptional := false
	if jbsConfig.Annotations != nil {
		val := jbsConfig.Annotations[TestRegistry]
		if val == "true" {
			secretOptional = true
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
		if jbsConfig.ImageRegistry().SecretName != "" {
			// Builds or tooling mostly use the .docker/config.json directly which is updated via Tekton/Kubernetes secrets. But the
			// Java code may require the token as well.
			cache = setEnvVar(corev1.EnvVar{
				Name:      "REGISTRY_TOKEN",
				ValueFrom: &corev1.EnvVarSource{SecretKeyRef: &corev1.SecretKeySelector{LocalObjectReference: corev1.LocalObjectReference{Name: jbsConfig.ImageRegistry().SecretName}, Key: v1alpha1.ImageSecretTokenKey, Optional: &secretOptional}},
			}, cache)
		}
		cache = setEnvVar(corev1.EnvVar{
			Name:      "GIT_TOKEN",
			ValueFrom: &corev1.EnvVarSource{SecretKeyRef: &corev1.SecretKeySelector{LocalObjectReference: corev1.LocalObjectReference{Name: v1alpha1.GitSecretName}, Key: v1alpha1.GitSecretTokenKey, Optional: &trueBool}},
		}, cache)

		if jbsConfig.Spec.MavenDeployment.Repository != "" && !jbsConfig.Spec.MavenDeployment.OnlyDeploy {
			cache = setEnvVarValue(jbsConfig.Spec.MavenDeployment.Repository, "MAVEN_REPOSITORY_URL", cache)
			cache = setEnvVarValue(jbsConfig.Spec.MavenDeployment.Username, "MAVEN_REPOSITORY_USERNAME", cache)
			cache = setEnvVar(corev1.EnvVar{
				Name:      "MAVEN_REPOSITORY_PASSWORD",
				ValueFrom: &corev1.EnvVarSource{SecretKeyRef: &corev1.SecretKeySelector{LocalObjectReference: corev1.LocalObjectReference{Name: v1alpha1.MavenSecretName}, Key: v1alpha1.MavenSecretKey, Optional: &trueBool}},
			}, cache)
		}

		sharedRegistryString := ImageRegistriesToString(jbsConfig.Spec.SharedRegistries)
		cache = setEnvVarValue(sharedRegistryString, "SHARED_REGISTRIES", cache)
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
		r.configuredCacheImage, err = util.GetImageName(ctx, r.client, "cache", "JVM_BUILD_SERVICE_CACHE_IMAGE")
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
		if err := controllerutil.SetOwnerReference(jbsConfig, cache, r.scheme); err != nil {
			return err
		}
		return r.client.Create(ctx, cache)
	} else {
		return r.client.Update(ctx, cache)
	}
}

func (r *ReconcilerJBSConfig) handleNoOwnerSpecified(ctx context.Context, log logr.Logger, config *v1alpha1.JBSConfig) error {

	vis := imagecontroller.ImageVisibilityPublic
	if config.Spec.Registry.Private != nil && *config.Spec.Registry.Private {
		vis = imagecontroller.ImageVisibilityPrivate
	}
	repo := imagecontroller.ImageRepository{}
	err := r.client.Get(ctx, types.NamespacedName{Namespace: config.Namespace, Name: v1alpha1.DefaultImageSecretName}, &repo)
	if err != nil {
		if errors.IsNotFound(err) {
			repo.Name = v1alpha1.DefaultImageSecretName
			repo.Namespace = config.Namespace
			repo.Spec.Image.Visibility = vis
			err := controllerutil.SetOwnerReference(config, &repo, r.scheme)
			if err != nil {
				return err
			}
			return r.client.Create(ctx, &repo)
		} else {
			return err
		}
	}
	if repo.Status.State == imagecontroller.ImageRepositoryStateFailed {
		return errors2.New(repo.Status.Message)
	}
	if repo.Status.State != imagecontroller.ImageRepositoryStateReady {
		return errors2.New("image repository not ready yet")
	}
	url := strings.Split(repo.Status.Image.URL, "/")
	host := url[0]
	owner := url[1]
	repository := strings.Join(url[2:], "/")

	config.Status.ImageRegistry = &v1alpha1.ImageRegistry{
		Host:       host,
		Owner:      owner,
		Repository: repository,
		SecretName: repo.Status.Credentials.PushSecretName,
	}
	return nil
}

func ImageRegistriesToString(sharedRegistries []v1alpha1.ImageRegistry) string {
	sharedRegistryString := ""
	for i, shared := range sharedRegistries {
		if i > 0 {
			sharedRegistryString += ";"
		}
		sharedRegistryString += ImageRegistryToString(shared)
	}
	return sharedRegistryString
}

func ImageRegistryToString(registry v1alpha1.ImageRegistry) string {
	result := registry.Host
	result += ","
	result += registry.Port
	result += ","
	result += registry.Owner
	result += ","
	result += registry.Repository
	result += ","
	result += strconv.FormatBool(registry.Insecure)
	result += ","
	result += registry.PrependTag

	// TODO: How to transfer the secret across? Do we need multiple secrets?

	return result
}
