package userconfig

import (
	"context"
	"fmt"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"time"

	v13 "k8s.io/api/rbac/v1"

	appsv1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/tools/record"

	"github.com/go-logr/logr"
	"github.com/kcp-dev/logicalcluster/v2"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"

	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
)

type ReconcilerUserConfig struct {
	client               client.Client
	scheme               *runtime.Scheme
	eventRecorder        record.EventRecorder
	configuredCacheImage string
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	ret := &ReconcilerUserConfig{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("UserConfig"),
	}
	return ret
}

func (r *ReconcilerUserConfig) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	var cancel context.CancelFunc
	if request.ClusterName != "" {
		// use logicalcluster.ClusterFromContxt(ctx) to retrieve this value later on
		ctx = logicalcluster.WithCluster(ctx, logicalcluster.New(request.ClusterName))
	}
	ctx, cancel = context.WithTimeout(ctx, 300*time.Second)
	defer cancel()
	log := ctrl.Log.WithName("userconfig").WithValues("request", request.NamespacedName).WithValues("cluster", request.ClusterName)
	userConfig := v1alpha1.UserConfig{}
	err := r.client.Get(ctx, request.NamespacedName, &userConfig)
	if err != nil {
		return reconcile.Result{}, err
	}
	//TODO do we eventually want to allow more than one UserConfig per namespace?
	if userConfig.Name == v1alpha1.UserConfigName {
		err = r.validations(ctx, log, request, &userConfig)
		if err != nil {
			return reconcile.Result{}, err
		}

		err = r.deploymentSupportObjects(ctx, log, request, &userConfig)
		if err != nil {
			return reconcile.Result{}, err
		}

		err = r.cacheDeployment(ctx, log, request, &userConfig)
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

func (r *ReconcilerUserConfig) validations(ctx context.Context, log logr.Logger, request reconcile.Request, userConfig *v1alpha1.UserConfig) error {
	if !userConfig.Spec.EnableRebuilds {
		return nil
	}
	registrySecret := &corev1.Secret{}
	// our client is wired to not cache secrets / establish informers for secrets
	err := r.client.Get(ctx, types.NamespacedName{Namespace: request.Namespace, Name: v1alpha1.UserSecretName}, registrySecret)
	if err != nil {
		return err
	}
	_, keyPresent1 := registrySecret.Data[v1alpha1.UserSecretTokenKey]
	_, keyPresent2 := registrySecret.StringData[v1alpha1.UserSecretTokenKey]
	if !keyPresent1 && !keyPresent2 {
		return fmt.Errorf("need image registry token set at key %s in secret %s", v1alpha1.UserSecretTokenKey, v1alpha1.UserSecretName)
	}
	log.Info(fmt.Sprintf("found %s secret with appropriate token keys in namespace %s, rebuilds are possible", v1alpha1.UserSecretTokenKey, request.Namespace))
	return nil
}

func (r *ReconcilerUserConfig) deploymentSupportObjects(ctx context.Context, log logr.Logger, request reconcile.Request, userConfig *v1alpha1.UserConfig) error {
	//TODO may have to switch to ephemeral storage for KCP until storage story there is sorted out
	//pvc := corev1.PersistentVolumeClaim{}
	//deploymentName := types.NamespacedName{Namespace: request.Namespace, Name: v1alpha1.CacheDeploymentName}
	//err = r.client.Get(ctx, deploymentName, &pvc)
	//if err != nil {
	//	if errors.IsNotFound(err) {
	//		pvc = corev1.PersistentVolumeClaim{}
	//		pvc.Name = v1alpha1.CacheDeploymentName
	//		pvc.Namespace = request.Namespace
	//		pvc.Spec.AccessModes = []corev1.PersistentVolumeAccessMode{corev1.ReadWriteOnce}
	//		qty, err := resource.ParseQuantity(settingOrDefault(userConfig.Spec.Storage, v1alpha1.ConfigArtifactCacheStorageDefault))
	//		if err != nil {
	//			return err
	//		}
	//		//TODO: make configurable
	//		pvc.Spec.Resources.Requests = map[corev1.ResourceName]resource.Quantity{"storage": qty}
	//		err = r.client.Create(ctx, &pvc)
	//		if err != nil {
	//			return err
	//		}
	//	}
	//}
	//and setup the service
	err := r.client.Get(ctx, types.NamespacedName{Name: v1alpha1.CacheDeploymentName, Namespace: request.Namespace}, &corev1.Service{})
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
	return nil
}

func (r *ReconcilerUserConfig) cacheDeployment(ctx context.Context, log logr.Logger, request reconcile.Request, userConfig *v1alpha1.UserConfig) error {
	cache := &appsv1.Deployment{}
	deploymentName := types.NamespacedName{Namespace: request.Namespace, Name: v1alpha1.CacheDeploymentName}
	err := r.client.Get(ctx, deploymentName, cache)
	create := false
	if err != nil {
		if errors.IsNotFound(err) {
			qty, err := resource.ParseQuantity(settingOrDefault(userConfig.Spec.Storage, v1alpha1.ConfigArtifactCacheStorageDefault))
			if err != nil {
				return err
			}
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
				Ports: []corev1.ContainerPort{{
					Name:          "http",
					ContainerPort: 8080,
					Protocol:      "TCP",
				}},
				VolumeMounts: []corev1.VolumeMount{{Name: v1alpha1.CacheDeploymentName, MountPath: "/cache"}},

				Resources: corev1.ResourceRequirements{
					Requests: map[corev1.ResourceName]resource.Quantity{
						"memory": resource.MustParse(settingOrDefault(userConfig.Spec.RequestMemory, v1alpha1.ConfigArtifactCacheRequestMemoryDefault)),
						"cpu":    resource.MustParse(settingOrDefault(userConfig.Spec.RequestCPU, v1alpha1.ConfigArtifactCacheRequestCPUDefault))},
					Limits: map[corev1.ResourceName]resource.Quantity{
						"memory": resource.MustParse(settingOrDefault(userConfig.Spec.LimitMemory, v1alpha1.ConfigArtifactCacheLimitMemoryDefault)),
						"cpu":    resource.MustParse(settingOrDefault(userConfig.Spec.LimitCPU, v1alpha1.ConfigArtifactCacheLimitCPUDefault))},
				},
			}}
			cache.Spec.Template.Spec.Volumes = []corev1.Volume{
				{
					//Name: v1alpha1.CacheDeploymentName,
					//VolumeSource: corev1.VolumeSource{
					//	PersistentVolumeClaim: &corev1.PersistentVolumeClaimVolumeSource{
					//		ClaimName: v1alpha1.CacheDeploymentName,
					//	},
					//},
					Name: v1alpha1.CacheDeploymentName,
					VolumeSource: corev1.VolumeSource{
						EmptyDir: &corev1.EmptyDirVolumeSource{
							SizeLimit: &qty,
						},
					},
				},
			}

		} else {
			return err
		}
	}
	cache.Spec.Template.Spec.ServiceAccountName = "pipeline"
	cache.Spec.Template.Spec.Containers[0].Env = []corev1.EnvVar{
		{Name: "CACHE_PATH", Value: "/cache"},
		{Name: "QUARKUS_VERTX_EVENT_LOOPS_POOL_SIZE", Value: settingOrDefault(userConfig.Spec.IOThreads, v1alpha1.ConfigArtifactCacheIOThreadsDefault)},
		{Name: "QUARKUS_THREAD_POOL_MAX_THREADS", Value: settingOrDefault(userConfig.Spec.WorkerThreads, v1alpha1.ConfigArtifactCacheWorkerThreadsDefault)},
	}
	type Repo struct {
		name     string
		position int
	}
	//central is at the hard coded 200 position
	repos := []Repo{{name: "central", position: 200}}
	trueBool := true
	if userConfig.Spec.EnableRebuilds {
		repos = append(repos, Repo{name: "rebuilt", position: 100})

		cache = settingIfSet(userConfig.Spec.Owner, "REGISTRY_OWNER", cache)
		cache = settingIfSet(userConfig.Spec.Host, "REGISTRY_HOST", cache)
		cache = settingIfSet(userConfig.Spec.Port, "REGISTRY_PORT", cache)
		cache = settingIfSet(userConfig.Spec.Repository, "REGISTRY_REPOSITORY", cache)
		cache = settingIfSet(strconv.FormatBool(userConfig.Spec.Insecure), "REGISTRY_INSECURE", cache)
		cache = settingIfSet(userConfig.Spec.PrependTag, "REGISTRY_PREPEND_TAG", cache)
		cache.Spec.Template.Spec.Containers[0].Env = append(cache.Spec.Template.Spec.Containers[0].Env, corev1.EnvVar{
			Name:      "REGISTRY_TOKEN",
			ValueFrom: &corev1.EnvVarSource{SecretKeyRef: &corev1.SecretKeySelector{LocalObjectReference: corev1.LocalObjectReference{Name: v1alpha1.UserSecretName}, Key: v1alpha1.UserSecretTokenKey, Optional: &trueBool}},
		})
		for _, relocationPatternElement := range userConfig.Spec.RelocationPatterns {
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
	for k, v := range userConfig.Spec.MavenBaseLocations {
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
