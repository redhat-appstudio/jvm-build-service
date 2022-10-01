package v1alpha1

import metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

const (
	UserConfigName                          = "jvm-build-config"
	UserSecretName                          = "jvm-build-secrets"
	UserSecretTokenKey                      = "registry.token"
	CacheDeploymentName                     = "jvm-build-workspace-artifact-cache"
	ConfigArtifactCacheRequestMemoryDefault = "3Gi"
	ConfigArtifactCacheRequestCPUDefault    = "1"
	ConfigArtifactCacheLimitMemoryDefault   = "3Gi"
	ConfigArtifactCacheLimitCPUDefault      = "4"
	ConfigArtifactCacheIOThreadsDefault     = "4"
	ConfigArtifactCacheWorkerThreadsDefault = "50"
	ConfigArtifactCacheStorageDefault       = "10Gi"
)

type UserConfigSpec struct {
	EnableRebuilds bool `json:"enableRebuilds,omitempty"`

	AdditionalRecipes []string `json:"AdditionalRecipes,omitempty"`

	MavenBaseLocations map[string]string `json:"mavenBaseLocations,omitempty"`

	CacheSettings      `json:",inline"`
	ImageRegistry      `json:",inline"`
	RelocationPatterns []RelocationPatternElement `json:"relocationPatterns,omitempty"`
}

type UserConfigStatus struct {
}

type CacheSettings struct {
	RequestMemory string `json:"requestMemory,omitempty"`
	RequestCPU    string `json:"requestCPU,omitempty"`
	LimitMemory   string `json:"limitMemory,omitempty"`
	LimitCPU      string `json:"limitCPU,omitempty"`
	IOThreads     string `json:"ioThreads,omitempty"`
	WorkerThreads string `json:"workerThreads,omitempty"`
	Storage       string `json:"storage,omitempty"`
}

type ImageRegistry struct {
	Host       string `json:"host,omitempty"`
	Port       string `json:"port,omitempty"`
	Owner      string `json:"owner,omitempty"`
	Repository string `json:"repository,omitempty"`
	Insecure   bool   `json:"insecure,omitempty"`
	PrependTag string `json:"prependTag,omitempty"`
}

type RelocationPatternElement struct {
	RelocationPattern RelocationPattern `json:"relocationPattern"`
}

type RelocationPattern struct {
	BuildPolicy string           `json:"buildPolicy,omitempty" default:"default"`
	Patterns    []PatternElement `json:"patterns,omitempty"`
}

type PatternElement struct {
	Pattern Pattern `json:"pattern"`
}

type Pattern struct {
	From string `json:"from"`
	To   string `json:"to"`
}

// +genclient
// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object
// +kubebuilder:subresource:status
// +kubebuilder:resource:path=userconfigs,scope=Namespaced
// UserConfig TODO provide godoc description
type UserConfig struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   UserConfigSpec   `json:"spec"`
	Status UserConfigStatus `json:"status,omitempty"`
}

// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object

// UserConfigList contains a list of SystemConfig
type UserConfigList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []UserConfig `json:"items"`
}
