/*
Copyright 2021.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package v1beta1

import (
	"errors"
	"fmt"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
)

var multipleTargetsForSingleNamespaceNotSupportedError = errors.New("multiple targets referencing the same namespace is not allowed")

// RemoteSecretSpec defines the desired state of RemoteSecret
type RemoteSecretSpec struct {
	// Secret defines the properties of the secret and the linked service accounts that should be
	// created in the target namespaces.
	Secret LinkableSecretSpec `json:"secret"`
	// Targets is the list of the target namespaces that the secret and service accounts should be deployed to.
	// +optional
	Targets []RemoteSecretTarget `json:"targets,omitempty"`
}

type RemoteSecretTarget struct {
	// Namespace is the name of the target namespace to which to deploy.
	Namespace string `json:"namespace,omitempty"`
	// XXX: not sure how this will look like, so let's keep it out for the time being
	// Override LinkableSecretSpec `json:"override,omitempty"`
}

// RemoteSecretStatus defines the observed state of RemoteSecret
type RemoteSecretStatus struct {
	// Conditions is the list of conditions describing the state of the deployment
	// to the targets.
	// +optional
	Conditions []metav1.Condition `json:"conditions,omitempty"`
	// Targets is the list of the deployment statuses for individual targets in the spec.
	// +optional
	Targets []TargetStatus `json:"targets,omitempty"`
}

type TargetStatus struct {
	Namespace NamespaceTargetStatus `json:"namespace,omitempty"`
}

type NamespaceTargetStatus struct {
	Namespace  string `json:"namespace"`
	SecretName string `json:"secretName"`
	// +optional
	ServiceAccountNames []string `json:"serviceAccountNames,omitempty"`
	// +optional
	Error string `json:"error,omitempty"`
}

// RemoteSecretReason is the reconciliation status of the RemoteSecret object
type RemoteSecretReason string

// RemoteSecretConditionType lists the types of conditions we track in the remote secret status
type RemoteSecretConditionType string

const (
	RemoteSecretConditionTypeDeployed     RemoteSecretConditionType = "Deployed"
	RemoteSecretConditionTypeDataObtained RemoteSecretConditionType = "DataObtained"
	RemoteSecretConditionTypeSpecValid    RemoteSecretConditionType = "SpecValid"

	RemoteSecretReasonAwaitingTokenData RemoteSecretReason = "AwaitingData"
	RemoteSecretReasonDataFound         RemoteSecretReason = "DataFound"
	RemoteSecretReasonInjected          RemoteSecretReason = "Injected"
	RemoteSecretReasonPartiallyInjected RemoteSecretReason = "PartiallyInjected"
	RemoteSecretReasonError             RemoteSecretReason = "Error"
	RemoteSecretReasonValid             RemoteSecretReason = "Valid"
)

//+kubebuilder:object:root=true
//+kubebuilder:subresource:status

// RemoteSecret is the Schema for the RemoteSecret API
type RemoteSecret struct {
	metav1.TypeMeta   `json:",inline"`
	metav1.ObjectMeta `json:"metadata,omitempty"`

	Spec   RemoteSecretSpec   `json:"spec,omitempty"`
	Status RemoteSecretStatus `json:"status,omitempty"`
}

//+kubebuilder:object:root=true

// RemoteSecretList contains a list of RemoteSecret
type RemoteSecretList struct {
	metav1.TypeMeta `json:",inline"`
	metav1.ListMeta `json:"metadata,omitempty"`
	Items           []RemoteSecret `json:"items"`
}

func init() {
	SchemeBuilder.Register(&RemoteSecret{}, &RemoteSecretList{})
}

// Validate makes sure that no two targets specify the same namespace.
// This is because the namespace is the only simple thing that can distinguish
// between two secrets in an order independent way.
// Also, having two secrets with the identical contents in the same namespace is considered
// a little bit of a corner case.
// If we were to support it we would have to come up with some more fine-grained rules, possibly
// by just disallowing two secrets with the same namespace and name or with the same namespace
// and generate name. But for now, let's keep the things simple and merely disallow them.
func (rs *RemoteSecret) Validate() error {
	nss := map[string]int{}

	for i, t := range rs.Spec.Targets {
		previous, present := nss[t.Namespace]
		if present {
			return fmt.Errorf("%w: targets on indices %d and %d point to the same namespace %s", multipleTargetsForSingleNamespaceNotSupportedError, previous, i, t.Namespace)
		}
		nss[t.Namespace] = i
	}

	return nil
}
