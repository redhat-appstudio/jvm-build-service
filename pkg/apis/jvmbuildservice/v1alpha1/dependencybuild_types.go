package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

const (
	DependencyBuildStateNew          = "DependencyBuildStateNew"
	DependencyBuildStateBuilding     = "DependencyBuildStateBuilding"
	DependencyBuildStateComplete     = "DependencyBuildStateComplete"
	DependencyBuildStateFailed       = "DependencyBuildStateFailed"
	DependencyBuildStateContaminated = "DependencyBuildStateContaminated"
)

type DependencyBuildSpec struct {
	ScmInfo SCMInfo `json:"scmInfo,omitempty,inline"`
}

type DependencyBuildStatus struct {
	// Conditions for capturing generic status
	// NOTE: inspecting the fabric8 Status class, it looked analogous to k8s Condition,
	// and then I took the liberty of making it an array, given best practices in the k8s/ocp ecosystems
	Conditions   []metav1.Condition `json:"conditions,omitempty"`
	State        string             `json:"state,omitempty"`
	Contaminants []string           `json:"contaminates,omitempty"`
}

// +genclient
// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object
// +kubebuilder:subresource:status
// +kubebuilder:resource:path=dependencybuilds,scope=Namespaced
// +kubebuilder:printcolumn:name="URL",type=string,JSONPath=`.spec.scmURL`
// +kubebuilder:printcolumn:name="State",type=string,JSONPath=`.status.state`

// DependencyBuild TODO provide godoc description
type DependencyBuild struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   DependencyBuildSpec   `json:"spec"`
	Status DependencyBuildStatus `json:"status,omitempty"`
}

// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object

// DependencyBuildList contains a list of DependencyBuild
type DependencyBuildList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []DependencyBuild `json:"items"`
}
