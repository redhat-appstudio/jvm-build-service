package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

const (
	JvmBuildStateFailed     = "JvmBuildFailed"
	JvmBuildStateComplete   = "JvmBuildComplete"
	JvmBuildStateInProgress = "JvmBuildInProgress"
)

type JvmBuildStatusSpec struct {
	SCMURL    string                    `json:"scmURL,omitempty"`
	PRURL     string                    `json:"prURL,omitempty"`
	Tag       string                    `json:"tag,omitempty"`
	Artifacts []*JvmBuildStatusArtifact `json:"artifacts,omitempty"`
}

type JvmBuildStatusStatus struct {
	State         string                   `json:"state,omitempty"`
	Outstanding   int                      `json:"outstanding,omitempty"`
	ArtifactState map[string]ArtifactState `json:"artifactState,omitempty"`
	Message       string                   `json:"message,omitempty"`
}

type JvmBuildStatusArtifact struct {
	GAV     string `json:"gav,omitempty"`
	Source  string `json:"source,omitempty"`
	BuildId string `json:"buildId,omitempty"`
}

//type ArtifactBuildState string

// +genclient
// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object
// +kubebuilder:subresource:status
// +kubebuilder:resource:path=jvmbuildstatus,scope=Namespaced
// +kubebuilder:printcolumn:name="URL",type=string,JSONPath=`.spec.scmURL`
// +kubebuilder:printcolumn:name="Tag",type=string,JSONPath=`.spec.tag`
// +kubebuilder:printcolumn:name="Outstanding",type=integer,JSONPath=`.status.outstanding`
// +kubebuilder:printcolumn:name="State",type=string,JSONPath=`.status.state`
// +kubebuilder:printcolumn:name="Message",type=string,JSONPath=`.status.message`
// JvmBuildStatus A build of an upstream component
type JvmBuildStatus struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   JvmBuildStatusSpec   `json:"spec"`
	Status JvmBuildStatusStatus `json:"status,omitempty"`
}

// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object

// JvmBuildStatusList contains a list of JvmBuildStatus
type JvmBuildStatusList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []JvmBuildStatus `json:"items"`
}

type ArtifactState struct {
	ArtifactBuild string            `json:"artifactBuild,omitempty"`
	Built         bool              `json:"built,omitempty"`
	Failed        bool              `json:"failed,omitempty"`
	Annotations   map[string]string `json:"annotations,omitempty"`
}
