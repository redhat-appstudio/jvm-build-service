package v1alpha1

import metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

type SystemConfigSpec struct {
	JDK8Image  string `json:"jdk8image,omitempty"`
	JDK8Tags   string `json:"jdk8tags,omitempty"`
	JDK11Image string `json:"jdk11image,omitempty"`
	JDK11Tags  string `json:"jdk11tags,omitempty"`
	JDK17Image string `json:"jdk17image,omitempty"`
	JDK17Tags  string `json:"jdk17tags,omitempty"`
}

type SystemConfigStatus struct {
}

// +genclient
// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object
// +kubebuilder:subresource:status
// +kubebuilder:resource:path=systemconfigs,scope=Cluster
// SystemConfig TODO provide godoc description
type SystemConfig struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   SystemConfigSpec   `json:"spec"`
	Status SystemConfigStatus `json:"status,omitempty"`
}

// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object

// ArtifactBuildList contains a list of SystemConfig
type SystemConfigList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []SystemConfig `json:"items"`
}
