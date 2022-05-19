package v1alpha1

import (
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

type ArtifactBuildRequestSpec struct {
	// GAV is the groupID:artifacdtID:version tuple seen in maven pom.xml files
	GAV     string `json:"gav,omitempty"`
	Message string `json:"message,omitempty"`
	SCMURL  string `json:"scmURL,omitempty"`
	SCMType string `json:"scmType,omitempty"`
	Tag     string `json:"tag,omitempty"`
	Path    string `json:"path,omitempty"`
	DBState string `json:"dbState,omitempty"`
}

type ArtifactBuildRequestStatus struct {
	State string `json:"state,omitempty"`
	//TODO need to see how this will get written to, may need to move to spec
	RecipeGitHash string `json:"recipeGitHash,omitempty"`
	//TODO need to see how this will get written to, may need to move to spec
	BuildPipelineName string `json:"buildPipelineName,omitempty"`
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
