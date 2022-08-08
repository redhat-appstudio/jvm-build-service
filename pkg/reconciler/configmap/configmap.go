// This package contains the reconciler for the system and user level config
// System level config is a single ConfigMap, while the user level config is
// per namespace
package configmap

import (
	"context"
	"fmt"
	v1 "k8s.io/api/apps/v1"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/api/resource"
	v12 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/apimachinery/pkg/util/intstr"
	"k8s.io/client-go/tools/record"
	"regexp"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"sort"
	"strconv"
	"strings"
	"time"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout              = 300 * time.Second
	UserConfigMapName           = "jvm-build-config"
	UserConfigAdditionalRecipes = "additional-build-recipes"
	CacheDeploymentName         = "jvm-build-workspace-artifact-cache"
	LocalstackDeploymentName    = "jvm-build-workspace-localstack"
	EnableRebuilds              = "enable-rebuilds"
	SystemConfigMapName         = "jvm-build-system-config"
	SystemConfigMapNamespace    = "jvm-build-service"
	SystemCacheImage            = "image.cache"
	SystemBuilderImages         = "builder-image.names"
	SystemBuilderImageFormat    = "builder-image.%s.image"
)

var (
	RequiredKeys = map[string]bool{SystemCacheImage: true}
	log          = ctrl.Log.WithName("configmap")
)

type ReconcileConfigMap struct {
	client               client.Client
	scheme               *runtime.Scheme
	eventRecorder        record.EventRecorder
	configuredCacheImage string
}

func newReconciler(mgr ctrl.Manager, config map[string]string) reconcile.Reconciler {
	return &ReconcileConfigMap{
		client:               mgr.GetClient(),
		scheme:               mgr.GetScheme(),
		eventRecorder:        mgr.GetEventRecorderFor("ArtifactBuild"),
		configuredCacheImage: config[SystemCacheImage],
	}
}

func (r *ReconcileConfigMap) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	ctx, cancel := context.WithTimeout(ctx, contextTimeout)
	defer cancel()
	if request.Name == SystemConfigMapName && request.Namespace == SystemConfigMapNamespace {
		return r.handleSystemConfigMap(ctx, request)
	}
	if request.Name != UserConfigMapName {
		return reconcile.Result{}, nil
	}
	configMap := corev1.ConfigMap{}
	err := r.client.Get(ctx, request.NamespacedName, &configMap)
	if err != nil {
		return reconcile.Result{}, nil
	}
	enabled := configMap.Data[EnableRebuilds] == "true"

	log.Info("Setup Cache %s", "name", request.Name)
	if err = r.setupCache(ctx, request, enabled, configMap); err != nil {
		return reconcile.Result{}, err
	}
	if enabled {
		log.Info("Setup Rebuilds %s", "name", request.Name)
		err := r.setupRebuilds(ctx, request)
		if err != nil {
			return reconcile.Result{}, err
		}
	}

	return reconcile.Result{}, nil
}

func (r *ReconcileConfigMap) setupCache(ctx context.Context, request reconcile.Request, rebuildsEnabled bool, configMap corev1.ConfigMap) error {
	//first setup the storage
	deploymentName := types.NamespacedName{Namespace: request.Namespace, Name: CacheDeploymentName}

	pvc := corev1.PersistentVolumeClaim{}
	err := r.client.Get(ctx, deploymentName, &pvc)
	if err != nil {
		if errors.IsNotFound(err) {
			pvc = corev1.PersistentVolumeClaim{}
			pvc.Name = CacheDeploymentName
			pvc.Namespace = request.Namespace
			pvc.Spec.AccessModes = []corev1.PersistentVolumeAccessMode{corev1.ReadWriteOnce}
			qty, err := resource.ParseQuantity("10Gi")
			if err != nil {
				return err
			}
			//TODO: make configurable
			pvc.Spec.Resources.Requests = map[corev1.ResourceName]resource.Quantity{"storage": qty}
			err = r.client.Create(ctx, &pvc)
			if err != nil {
				return err
			}
		}
	}

	cache := v1.Deployment{}
	err = r.client.Get(ctx, deploymentName, &cache)
	create := false
	if err != nil {
		if errors.IsNotFound(err) {
			create = true
			cache.Name = deploymentName.Name
			cache.Namespace = deploymentName.Namespace
			var replicas int32
			replicas = 1
			cache.Spec.Replicas = &replicas
			cache.Spec.Selector = &v12.LabelSelector{MatchLabels: map[string]string{"app": CacheDeploymentName}}
			cache.Spec.Template.ObjectMeta.Labels = map[string]string{"app": CacheDeploymentName}
			cache.Spec.Template.Spec.Containers = []corev1.Container{{
				Name:            CacheDeploymentName,
				ImagePullPolicy: corev1.PullIfNotPresent,
				Ports: []corev1.ContainerPort{{
					Name:          "http",
					ContainerPort: 8080,
					Protocol:      "TCP",
				}},
				VolumeMounts: []corev1.VolumeMount{{Name: CacheDeploymentName, MountPath: "/cache"}},
				Resources: corev1.ResourceRequirements{
					//TODO: make configurable
					Requests: map[corev1.ResourceName]resource.Quantity{"memory": resource.MustParse("128Mi"), "cpu": resource.MustParse("250m")},
					Limits:   map[corev1.ResourceName]resource.Quantity{"memory": resource.MustParse("700Mi"), "cpu": resource.MustParse("500m")},
				},
			}}
			cache.Spec.Template.Spec.Volumes = []corev1.Volume{{Name: CacheDeploymentName, VolumeSource: corev1.VolumeSource{PersistentVolumeClaim: &corev1.PersistentVolumeClaimVolumeSource{ClaimName: CacheDeploymentName}}}}

		} else {
			return err
		}
	}

	//and setup the service
	err = r.client.Get(ctx, types.NamespacedName{Name: CacheDeploymentName, Namespace: configMap.Namespace}, &corev1.Service{})
	if err != nil {
		if errors.IsNotFound(err) {
			service := corev1.Service{
				ObjectMeta: ctrl.ObjectMeta{
					Name:      CacheDeploymentName,
					Namespace: configMap.Namespace,
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
					Selector: map[string]string{"app": CacheDeploymentName},
				},
			}
			err := r.client.Create(ctx, &service)
			if err != nil {
				return err
			}
		}
	}
	type Repo struct {
		name     string
		position int
	}
	//TODO: make configurable
	cache.Spec.Template.Spec.Containers[0].Env = []corev1.EnvVar{
		{Name: "CACHE_PATH", Value: "/cache"},
		{Name: "QUARKUS_VERTX_EVENT_LOOPS_POOL_SIZE", Value: "4"},
		{Name: "QUARKUS_THREAD_POOL_MAX_THREADS", Value: "50"},
	}
	//central is at the hard coded 200 position
	repos := []Repo{{name: "central", position: 200}}
	if rebuildsEnabled {
		repos = append(repos, Repo{name: "rebuilt", position: 100})
		cache.Spec.Template.Spec.Containers[0].Env = append(cache.Spec.Template.Spec.Containers[0].Env, corev1.EnvVar{
			Name:  "QUARKUS_S3_ENDPOINT_OVERRIDE",
			Value: "http://" + LocalstackDeploymentName + "." + request.Namespace + ".svc.cluster.local:4572",
		})
		cache.Spec.Template.Spec.Containers[0].Env = append(cache.Spec.Template.Spec.Containers[0].Env, corev1.EnvVar{
			Name:  "QUARKUS_S3_AWS_REGION",
			Value: "us-east-1",
		})
		cache.Spec.Template.Spec.Containers[0].Env = append(cache.Spec.Template.Spec.Containers[0].Env, corev1.EnvVar{
			Name:  "QUARKUS_S3_AWS_CREDENTIALS_TYPE",
			Value: "static",
		})
		cache.Spec.Template.Spec.Containers[0].Env = append(cache.Spec.Template.Spec.Containers[0].Env, corev1.EnvVar{
			Name:  "QUARKUS_S3_AWS_CREDENTIALS_STATIC_PROVIDER_ACCESS_KEY_ID",
			Value: "accesskey",
		})
		cache.Spec.Template.Spec.Containers[0].Env = append(cache.Spec.Template.Spec.Containers[0].Env, corev1.EnvVar{
			Name:  "QUARKUS_S3_AWS_CREDENTIALS_STATIC_PROVIDER_SECRET_ACCESS_KEY",
			Value: "secretkey",
		})
	}
	regex, err := regexp.Compile("maven-repository-(\\d+)-(\\w+)")
	for k, v := range configMap.Data {
		if regex.MatchString(k) {
			results := regex.FindStringSubmatch(k)
			atoi, err := strconv.Atoi(results[1])
			name := results[2]
			if err != nil {
				return err
			}
			cache.Spec.Template.Spec.Containers[0].Env = append(cache.Spec.Template.Spec.Containers[0].Env, corev1.EnvVar{
				Name:  "STORE_" + strings.ToUpper(name) + "_URL",
				Value: v,
			})
			cache.Spec.Template.Spec.Containers[0].Env = append(cache.Spec.Template.Spec.Containers[0].Env, corev1.EnvVar{
				Name:  "STORE_" + strings.ToUpper(name) + "_TYPE",
				Value: "maven2",
			})
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
	cache.Spec.Template.Spec.Containers[0].Env = append(cache.Spec.Template.Spec.Containers[0].Env, corev1.EnvVar{
		Name:  "BUILD_POLICY_DEFAULT_STORE_LIST",
		Value: sb.String(),
	})
	cache.Spec.Template.Spec.Containers[0].Image = r.configuredCacheImage
	if !strings.HasPrefix(r.configuredCacheImage, "quay.io/redhat-appstudio") {
		// work around for developer mode while we are hard coding the spec in the controller
		cache.Spec.Template.Spec.Containers[0].ImagePullPolicy = corev1.PullAlways
	}
	if create {
		return r.client.Create(ctx, &cache)
	} else {
		return r.client.Update(ctx, &cache)
	}

}

func (r *ReconcileConfigMap) setupRebuilds(ctx context.Context, request reconcile.Request) error {
	//setup localstack
	//this is 100% temporary and needs to go away ASAP
	localstack := v1.Deployment{}
	deploymentName := types.NamespacedName{Namespace: request.Namespace, Name: LocalstackDeploymentName}
	err := r.client.Get(ctx, deploymentName, &localstack)
	if err != nil {
		if errors.IsNotFound(err) {
			log.Info("Creating localstack deployment", "name", LocalstackDeploymentName, "Namespace", request.Namespace)
			localstack.Name = deploymentName.Name
			localstack.Namespace = deploymentName.Namespace
			var replicas int32
			replicas = 1
			localstack.Spec.Replicas = &replicas
			localstack.Spec.Selector = &v12.LabelSelector{MatchLabels: map[string]string{"app": LocalstackDeploymentName}}
			localstack.Spec.Template.ObjectMeta.Labels = map[string]string{"app": LocalstackDeploymentName}
			localstack.Spec.Template.Spec.Containers = []corev1.Container{{
				Name:            LocalstackDeploymentName,
				ImagePullPolicy: "Always",
				Ports: []corev1.ContainerPort{{
					ContainerPort: 4572,
				}},
				Env: []corev1.EnvVar{{Name: "SERVICES", Value: "s3:4572"}},
			}}
			localstack.Spec.Template.Spec.Containers[0].Image = "localstack/localstack:0.11.5"
			err = r.client.Create(ctx, &localstack)
			if err != nil {
				return err
			}
		} else {
			return err
		}
	}
	//and setup the service
	err = r.client.Get(ctx, types.NamespacedName{Name: LocalstackDeploymentName, Namespace: request.Namespace}, &corev1.Service{})
	if err != nil {
		if errors.IsNotFound(err) {
			log.Info("Creating localstack service", "name", LocalstackDeploymentName, "Namespace", request.Namespace)
			service := corev1.Service{
				ObjectMeta: ctrl.ObjectMeta{
					Name:      LocalstackDeploymentName,
					Namespace: request.Namespace,
				},
				Spec: corev1.ServiceSpec{
					Ports: []corev1.ServicePort{
						{
							Name:     "s3",
							Port:     4572,
							Protocol: corev1.ProtocolTCP,
						},
					},
					Type:     corev1.ServiceTypeLoadBalancer,
					Selector: map[string]string{"app": LocalstackDeploymentName},
				},
			}
			err := r.client.Create(ctx, &service)
			if err != nil {
				return err
			}
		} else {
			return err
		}
	}
	return nil
}

func ReadUserConfigMap(client client.Client, ctx context.Context, namespace string) (map[string]string, error) {
	configMap := corev1.ConfigMap{}
	err := client.Get(ctx, types.NamespacedName{Namespace: namespace, Name: UserConfigMapName}, &configMap)
	if err != nil {
		return nil, nil
	}
	return configMap.Data, nil
}

func (r *ReconcileConfigMap) handleSystemConfigMap(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	configMap := corev1.ConfigMap{}
	err := r.client.Get(ctx, request.NamespacedName, &configMap)
	if err != nil {
		return reconcile.Result{}, err
	}
	if configMap.Data[SystemCacheImage] == "" {
		log.Info(fmt.Sprintf("System config map missing required key %s, operator will fail to restart if this is not corrected", SystemCacheImage))
	} else {
		r.configuredCacheImage = configMap.Data[SystemCacheImage]
	}
	return reconcile.Result{}, nil
}

func i64a(v int64) *int64 {
	return &v
}
