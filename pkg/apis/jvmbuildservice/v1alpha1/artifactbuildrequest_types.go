package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

type ArtifactBuildRequestSpec struct {
	// GAV is the groupID:artifactID:version tuple seen in maven pom.xml files
	GAV string `json:"gav,omitempty"`
}

type ArtifactBuildRequestStatus struct {
	//TODO: conditions?
	State   string  `json:"state,omitempty"`
	Message string  `json:"message,omitempty"`
	ScmInfo SCMInfo `json:"scm,omitempty"`
}

//type ArtifactBuildRequestState string

const (
	// ArtifactBuildRequestStateNew A new resource that has not been acted on by the operator
	ArtifactBuildRequestStateNew = "ArtifactBuildRequestNew"
	// ArtifactBuildRequestStateDiscovering The discovery pipeline is running to try and figure out how to build this artifact
	ArtifactBuildRequestStateDiscovering = "ArtifactBuildRequestDiscovering"
	// ArtifactBuildRequestStateMissing The discovery pipeline failed to find a way to build this
	ArtifactBuildRequestStateMissing = "ArtifactBuildRequestMissing"
	// ArtifactBuildRequestStateBuilding The build is running
	ArtifactBuildRequestStateBuilding = "ArtifactBuildRequestBuilding"
	// ArtifactBuildRequestStateFailed The build failed
	ArtifactBuildRequestStateFailed = "ArtifactBuildRequestFailed"
	// ArtifactBuildRequestStateComplete The build completed successfully, the resource can be removed
	ArtifactBuildRequestStateComplete = "ArtifactBuildRequestComplete"
)

// +genclient
// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object
// +kubebuilder:subresource:status
// +kubebuilder:resource:path=artifactbuildrequests,scope=Namespaced
// +kubebuilder:printcolumn:name="GAV",type=string,JSONPath=`.spec.gav`
// +kubebuilder:printcolumn:name="State",type=string,JSONPath=`.status.state`
// ArtifactBuildRequest TODO provide godoc description
type ArtifactBuildRequest struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   ArtifactBuildRequestSpec   `json:"spec"`
	Status ArtifactBuildRequestStatus `json:"status,omitempty"`
}

// +k8s:deepcopy-gen:interfaces=k8s.io/apimachinery/pkg/runtime.Object

// ArtifactBuildRequestList contains a list of ArtifactBuildRequest
type ArtifactBuildRequestList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []ArtifactBuildRequest `json:"items"`
}
