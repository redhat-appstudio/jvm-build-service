package jbsconfig

import (
	"context"
	"fmt"
	"regexp"
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

type ReconcilerJBSConfig struct {
	client               client.Client
	scheme               *runtime.Scheme
	eventRecorder        record.EventRecorder
	configuredCacheImage string
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	ret := &ReconcilerJBSConfig{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("JBSConfig"),
	}
	return ret
}

func (r *ReconcilerJBSConfig) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	var cancel context.CancelFunc
	ctx, cancel = context.WithTimeout(ctx, 300*time.Second)
	defer cancel()
	log := ctrl.Log.WithName("jbsconfig").WithValues("request", request.NamespacedName)
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
			return reconcile.Result{}, nil
		}
		return reconcile.Result{}, err
	}
	//TODO do we eventually want to allow more than one JBSConfig per namespace?
	if jbsConfig.Name == v1alpha1.JBSConfigName {
		err = r.validations(ctx, log, request, &jbsConfig)
		if err != nil {
			return reconcile.Result{}, err
		}

		err = r.deploymentSupportObjects(ctx, log, request, &jbsConfig)
		if err != nil {
			return reconcile.Result{}, err
		}

		err = r.cacheDeployment(ctx, log, request, &jbsConfig)
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

func settingIfSet(field, envName string, cache *appsv1.Deployment) *appsv1.Deployment {
	if len(strings.TrimSpace(field)) > 0 {
		cache.Spec.Template.Spec.Containers[0].Env = append(cache.Spec.Template.Spec.Containers[0].Env, corev1.EnvVar{
			Name:  envName,
			Value: field,
		})
	}
	return cache
}

func (r *ReconcilerJBSConfig) validations(ctx context.Context, log logr.Logger, request reconcile.Request, jbsConfig *v1alpha1.JBSConfig) error {
	if !jbsConfig.Spec.EnableRebuilds {
		return nil
	}
	registrySecret := &corev1.Secret{}
	// our client is wired to not cache secrets / establish informers for secrets
	err := r.client.Get(ctx, types.NamespacedName{Namespace: request.Namespace, Name: v1alpha1.ImageSecretName}, registrySecret)
	if err != nil {
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

func (r *ReconcilerJBSConfig) cacheDeployment(ctx context.Context, log logr.Logger, request reconcile.Request, jbsConfig *v1alpha1.JBSConfig) error {
	cache := &appsv1.Deployment{}
	deploymentName := types.NamespacedName{Namespace: request.Namespace, Name: v1alpha1.CacheDeploymentName}
	err := r.client.Get(ctx, deploymentName, cache)
	create := false
	if err != nil {
		if errors.IsNotFound(err) {
			create = true
			cache.Name = deploymentName.Name
			cache.Namespace = deploymentName.Namespace
			var replicas int32 = 1
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
				{Name: "tls", VolumeSource: corev1.VolumeSource{Secret: &corev1.SecretVolumeSource{SecretName: v1alpha1.TlsSecretName}}},
			}

		} else {
			return err
		}
	}
	cache.Spec.Template.Spec.ServiceAccountName = v1alpha1.CacheDeploymentName
	cache.Spec.Template.Spec.Containers[0].Env = []corev1.EnvVar{
		{Name: "CACHE_PATH", Value: "/cache"},
		{Name: "QUARKUS_VERTX_EVENT_LOOPS_POOL_SIZE", Value: settingOrDefault(jbsConfig.Spec.CacheSettings.IOThreads, v1alpha1.ConfigArtifactCacheIOThreadsDefault)},
		{Name: "QUARKUS_THREAD_POOL_MAX_THREADS", Value: settingOrDefault(jbsConfig.Spec.CacheSettings.WorkerThreads, v1alpha1.ConfigArtifactCacheWorkerThreadsDefault)},
		{Name: "QUARKUS_HTTP_SSL_CERTIFICATE_FILES", Value: "/tls/tls.crt"},
		{Name: "QUARKUS_HTTP_SSL_CERTIFICATE_KEY_FILES", Value: "/tls/tls.key"},
	}
	type Repo struct {
		name     string
		position int
	}
	//central is at the hard coded 200 position
	repos := []Repo{{name: "central", position: 200}}
	trueBool := true
	if jbsConfig.Spec.EnableRebuilds {
		repos = append(repos, Repo{name: "rebuilt", position: 100})

		cache = settingIfSet(jbsConfig.Spec.Owner, "REGISTRY_OWNER", cache)
		cache = settingIfSet(jbsConfig.Spec.Host, "REGISTRY_HOST", cache)
		cache = settingIfSet(jbsConfig.Spec.Port, "REGISTRY_PORT", cache)
		cache = settingIfSet(jbsConfig.Spec.Repository, "REGISTRY_REPOSITORY", cache)
		cache = settingIfSet(strconv.FormatBool(jbsConfig.Spec.Insecure), "REGISTRY_INSECURE", cache)
		cache = settingIfSet(jbsConfig.Spec.PrependTag, "REGISTRY_PREPEND_TAG", cache)
		cache.Spec.Template.Spec.Containers[0].Env = append(cache.Spec.Template.Spec.Containers[0].Env, corev1.EnvVar{
			Name:      "REGISTRY_TOKEN",
			ValueFrom: &corev1.EnvVarSource{SecretKeyRef: &corev1.SecretKeySelector{LocalObjectReference: corev1.LocalObjectReference{Name: v1alpha1.ImageSecretName}, Key: v1alpha1.ImageSecretTokenKey, Optional: &trueBool}},
		})
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
			cache = settingIfSet(envValue, envName, cache)
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
			cache = settingIfSet(v, "STORE_"+strings.ToUpper(strings.Replace(name, "-", "_", -1))+"_URL", cache)
			cache = settingIfSet("maven2", "STORE_"+strings.ToUpper(strings.Replace(name, "-", "_", -1))+"_TYPE", cache)
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
	cache = settingIfSet(sb.String(), "BUILD_POLICY_DEFAULT_STORE_LIST", cache)

	if len(r.configuredCacheImage) == 0 {
		r.configuredCacheImage, err = util.GetImageName(ctx, r.client, log, "cache", "JVM_BUILD_SERVICE_CACHE_IMAGE")
		if err != nil {
			return err
		}
	}
	cache.Spec.Template.Spec.Containers[0].Image = r.configuredCacheImage
	if !strings.HasPrefix(r.configuredCacheImage, "quay.io/redhat-appstudio") {
		// work around for developer mode while we are hard coding the spec in the controller
		cache.Spec.Template.Spec.Containers[0].ImagePullPolicy = corev1.PullAlways
	}

	if create {
		return r.client.Create(ctx, cache)
	} else {
		return r.client.Update(ctx, cache)
	}
}
