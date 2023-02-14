//go:build !ignore_autogenerated
// +build !ignore_autogenerated

/*
Copyright 2021-2022 Red Hat, Inc.

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
// Code generated by deepcopy-gen. DO NOT EDIT.

package v1alpha1

import (
	v1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	runtime "k8s.io/apimachinery/pkg/runtime"
)

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *AdditionalDownload) DeepCopyInto(out *AdditionalDownload) {
	*out = *in
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new AdditionalDownload.
func (in *AdditionalDownload) DeepCopy() *AdditionalDownload {
	if in == nil {
		return nil
	}
	out := new(AdditionalDownload)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *ArtifactBuild) DeepCopyInto(out *ArtifactBuild) {
	*out = *in
	out.TypeMeta = in.TypeMeta
	in.ObjectMeta.DeepCopyInto(&out.ObjectMeta)
	out.Spec = in.Spec
	out.Status = in.Status
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new ArtifactBuild.
func (in *ArtifactBuild) DeepCopy() *ArtifactBuild {
	if in == nil {
		return nil
	}
	out := new(ArtifactBuild)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyObject is an autogenerated deepcopy function, copying the receiver, creating a new runtime.Object.
func (in *ArtifactBuild) DeepCopyObject() runtime.Object {
	if c := in.DeepCopy(); c != nil {
		return c
	}
	return nil
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *ArtifactBuildList) DeepCopyInto(out *ArtifactBuildList) {
	*out = *in
	out.TypeMeta = in.TypeMeta
	in.ListMeta.DeepCopyInto(&out.ListMeta)
	if in.Items != nil {
		in, out := &in.Items, &out.Items
		*out = make([]ArtifactBuild, len(*in))
		for i := range *in {
			(*in)[i].DeepCopyInto(&(*out)[i])
		}
	}
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new ArtifactBuildList.
func (in *ArtifactBuildList) DeepCopy() *ArtifactBuildList {
	if in == nil {
		return nil
	}
	out := new(ArtifactBuildList)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyObject is an autogenerated deepcopy function, copying the receiver, creating a new runtime.Object.
func (in *ArtifactBuildList) DeepCopyObject() runtime.Object {
	if c := in.DeepCopy(); c != nil {
		return c
	}
	return nil
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *ArtifactBuildSpec) DeepCopyInto(out *ArtifactBuildSpec) {
	*out = *in
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new ArtifactBuildSpec.
func (in *ArtifactBuildSpec) DeepCopy() *ArtifactBuildSpec {
	if in == nil {
		return nil
	}
	out := new(ArtifactBuildSpec)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *ArtifactBuildStatus) DeepCopyInto(out *ArtifactBuildStatus) {
	*out = *in
	out.SCMInfo = in.SCMInfo
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new ArtifactBuildStatus.
func (in *ArtifactBuildStatus) DeepCopy() *ArtifactBuildStatus {
	if in == nil {
		return nil
	}
	out := new(ArtifactBuildStatus)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *BuildRecipe) DeepCopyInto(out *BuildRecipe) {
	*out = *in
	if in.CommandLine != nil {
		in, out := &in.CommandLine, &out.CommandLine
		*out = make([]string, len(*in))
		copy(*out, *in)
	}
	if in.AdditionalDownloads != nil {
		in, out := &in.AdditionalDownloads, &out.AdditionalDownloads
		*out = make([]AdditionalDownload, len(*in))
		copy(*out, *in)
	}
	if in.Repositories != nil {
		in, out := &in.Repositories, &out.Repositories
		*out = make([]string, len(*in))
		copy(*out, *in)
	}
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new BuildRecipe.
func (in *BuildRecipe) DeepCopy() *BuildRecipe {
	if in == nil {
		return nil
	}
	out := new(BuildRecipe)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *BuildSettings) DeepCopyInto(out *BuildSettings) {
	*out = *in
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new BuildSettings.
func (in *BuildSettings) DeepCopy() *BuildSettings {
	if in == nil {
		return nil
	}
	out := new(BuildSettings)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *CacheSettings) DeepCopyInto(out *CacheSettings) {
	*out = *in
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new CacheSettings.
func (in *CacheSettings) DeepCopy() *CacheSettings {
	if in == nil {
		return nil
	}
	out := new(CacheSettings)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *Contaminant) DeepCopyInto(out *Contaminant) {
	*out = *in
	if in.ContaminatedArtifacts != nil {
		in, out := &in.ContaminatedArtifacts, &out.ContaminatedArtifacts
		*out = make([]string, len(*in))
		copy(*out, *in)
	}
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new Contaminant.
func (in *Contaminant) DeepCopy() *Contaminant {
	if in == nil {
		return nil
	}
	out := new(Contaminant)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *DependencyBuild) DeepCopyInto(out *DependencyBuild) {
	*out = *in
	out.TypeMeta = in.TypeMeta
	in.ObjectMeta.DeepCopyInto(&out.ObjectMeta)
	out.Spec = in.Spec
	in.Status.DeepCopyInto(&out.Status)
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new DependencyBuild.
func (in *DependencyBuild) DeepCopy() *DependencyBuild {
	if in == nil {
		return nil
	}
	out := new(DependencyBuild)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyObject is an autogenerated deepcopy function, copying the receiver, creating a new runtime.Object.
func (in *DependencyBuild) DeepCopyObject() runtime.Object {
	if c := in.DeepCopy(); c != nil {
		return c
	}
	return nil
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *DependencyBuildList) DeepCopyInto(out *DependencyBuildList) {
	*out = *in
	out.TypeMeta = in.TypeMeta
	in.ListMeta.DeepCopyInto(&out.ListMeta)
	if in.Items != nil {
		in, out := &in.Items, &out.Items
		*out = make([]DependencyBuild, len(*in))
		for i := range *in {
			(*in)[i].DeepCopyInto(&(*out)[i])
		}
	}
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new DependencyBuildList.
func (in *DependencyBuildList) DeepCopy() *DependencyBuildList {
	if in == nil {
		return nil
	}
	out := new(DependencyBuildList)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyObject is an autogenerated deepcopy function, copying the receiver, creating a new runtime.Object.
func (in *DependencyBuildList) DeepCopyObject() runtime.Object {
	if c := in.DeepCopy(); c != nil {
		return c
	}
	return nil
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *DependencyBuildSpec) DeepCopyInto(out *DependencyBuildSpec) {
	*out = *in
	out.ScmInfo = in.ScmInfo
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new DependencyBuildSpec.
func (in *DependencyBuildSpec) DeepCopy() *DependencyBuildSpec {
	if in == nil {
		return nil
	}
	out := new(DependencyBuildSpec)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *DependencyBuildStatus) DeepCopyInto(out *DependencyBuildStatus) {
	*out = *in
	if in.Conditions != nil {
		in, out := &in.Conditions, &out.Conditions
		*out = make([]v1.Condition, len(*in))
		for i := range *in {
			(*in)[i].DeepCopyInto(&(*out)[i])
		}
	}
	if in.Contaminants != nil {
		in, out := &in.Contaminants, &out.Contaminants
		*out = make([]Contaminant, len(*in))
		for i := range *in {
			(*in)[i].DeepCopyInto(&(*out)[i])
		}
	}
	if in.CurrentBuildRecipe != nil {
		in, out := &in.CurrentBuildRecipe, &out.CurrentBuildRecipe
		*out = new(BuildRecipe)
		(*in).DeepCopyInto(*out)
	}
	if in.PotentialBuildRecipes != nil {
		in, out := &in.PotentialBuildRecipes, &out.PotentialBuildRecipes
		*out = make([]*BuildRecipe, len(*in))
		for i := range *in {
			if (*in)[i] != nil {
				in, out := &(*in)[i], &(*out)[i]
				*out = new(BuildRecipe)
				(*in).DeepCopyInto(*out)
			}
		}
	}
	if in.FailedBuildRecipes != nil {
		in, out := &in.FailedBuildRecipes, &out.FailedBuildRecipes
		*out = make([]*BuildRecipe, len(*in))
		for i := range *in {
			if (*in)[i] != nil {
				in, out := &(*in)[i], &(*out)[i]
				*out = new(BuildRecipe)
				(*in).DeepCopyInto(*out)
			}
		}
	}
	if in.DeployedArtifacts != nil {
		in, out := &in.DeployedArtifacts, &out.DeployedArtifacts
		*out = make([]string, len(*in))
		copy(*out, *in)
	}
	if in.DiagnosticDockerFiles != nil {
		in, out := &in.DiagnosticDockerFiles, &out.DiagnosticDockerFiles
		*out = make([]string, len(*in))
		copy(*out, *in)
	}
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new DependencyBuildStatus.
func (in *DependencyBuildStatus) DeepCopy() *DependencyBuildStatus {
	if in == nil {
		return nil
	}
	out := new(DependencyBuildStatus)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *ImageRegistry) DeepCopyInto(out *ImageRegistry) {
	*out = *in
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new ImageRegistry.
func (in *ImageRegistry) DeepCopy() *ImageRegistry {
	if in == nil {
		return nil
	}
	out := new(ImageRegistry)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *JBSConfig) DeepCopyInto(out *JBSConfig) {
	*out = *in
	out.TypeMeta = in.TypeMeta
	in.ObjectMeta.DeepCopyInto(&out.ObjectMeta)
	in.Spec.DeepCopyInto(&out.Spec)
	out.Status = in.Status
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new JBSConfig.
func (in *JBSConfig) DeepCopy() *JBSConfig {
	if in == nil {
		return nil
	}
	out := new(JBSConfig)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyObject is an autogenerated deepcopy function, copying the receiver, creating a new runtime.Object.
func (in *JBSConfig) DeepCopyObject() runtime.Object {
	if c := in.DeepCopy(); c != nil {
		return c
	}
	return nil
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *JBSConfigList) DeepCopyInto(out *JBSConfigList) {
	*out = *in
	out.TypeMeta = in.TypeMeta
	in.ListMeta.DeepCopyInto(&out.ListMeta)
	if in.Items != nil {
		in, out := &in.Items, &out.Items
		*out = make([]JBSConfig, len(*in))
		for i := range *in {
			(*in)[i].DeepCopyInto(&(*out)[i])
		}
	}
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new JBSConfigList.
func (in *JBSConfigList) DeepCopy() *JBSConfigList {
	if in == nil {
		return nil
	}
	out := new(JBSConfigList)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyObject is an autogenerated deepcopy function, copying the receiver, creating a new runtime.Object.
func (in *JBSConfigList) DeepCopyObject() runtime.Object {
	if c := in.DeepCopy(); c != nil {
		return c
	}
	return nil
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *JBSConfigSpec) DeepCopyInto(out *JBSConfigSpec) {
	*out = *in
	if in.AdditionalRecipes != nil {
		in, out := &in.AdditionalRecipes, &out.AdditionalRecipes
		*out = make([]string, len(*in))
		copy(*out, *in)
	}
	if in.MavenBaseLocations != nil {
		in, out := &in.MavenBaseLocations, &out.MavenBaseLocations
		*out = make(map[string]string, len(*in))
		for key, val := range *in {
			(*out)[key] = val
		}
	}
	out.ImageRegistry = in.ImageRegistry
	out.CacheSettings = in.CacheSettings
	out.BuildSettings = in.BuildSettings
	if in.RelocationPatterns != nil {
		in, out := &in.RelocationPatterns, &out.RelocationPatterns
		*out = make([]RelocationPatternElement, len(*in))
		for i := range *in {
			(*in)[i].DeepCopyInto(&(*out)[i])
		}
	}
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new JBSConfigSpec.
func (in *JBSConfigSpec) DeepCopy() *JBSConfigSpec {
	if in == nil {
		return nil
	}
	out := new(JBSConfigSpec)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *JBSConfigStatus) DeepCopyInto(out *JBSConfigStatus) {
	*out = *in
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new JBSConfigStatus.
func (in *JBSConfigStatus) DeepCopy() *JBSConfigStatus {
	if in == nil {
		return nil
	}
	out := new(JBSConfigStatus)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *JavaVersionInfo) DeepCopyInto(out *JavaVersionInfo) {
	*out = *in
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new JavaVersionInfo.
func (in *JavaVersionInfo) DeepCopy() *JavaVersionInfo {
	if in == nil {
		return nil
	}
	out := new(JavaVersionInfo)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *Pattern) DeepCopyInto(out *Pattern) {
	*out = *in
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new Pattern.
func (in *Pattern) DeepCopy() *Pattern {
	if in == nil {
		return nil
	}
	out := new(Pattern)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *PatternElement) DeepCopyInto(out *PatternElement) {
	*out = *in
	out.Pattern = in.Pattern
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new PatternElement.
func (in *PatternElement) DeepCopy() *PatternElement {
	if in == nil {
		return nil
	}
	out := new(PatternElement)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *RebuiltArtifact) DeepCopyInto(out *RebuiltArtifact) {
	*out = *in
	out.TypeMeta = in.TypeMeta
	in.ObjectMeta.DeepCopyInto(&out.ObjectMeta)
	out.Spec = in.Spec
	out.Status = in.Status
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new RebuiltArtifact.
func (in *RebuiltArtifact) DeepCopy() *RebuiltArtifact {
	if in == nil {
		return nil
	}
	out := new(RebuiltArtifact)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyObject is an autogenerated deepcopy function, copying the receiver, creating a new runtime.Object.
func (in *RebuiltArtifact) DeepCopyObject() runtime.Object {
	if c := in.DeepCopy(); c != nil {
		return c
	}
	return nil
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *RebuiltArtifactList) DeepCopyInto(out *RebuiltArtifactList) {
	*out = *in
	out.TypeMeta = in.TypeMeta
	in.ListMeta.DeepCopyInto(&out.ListMeta)
	if in.Items != nil {
		in, out := &in.Items, &out.Items
		*out = make([]RebuiltArtifact, len(*in))
		for i := range *in {
			(*in)[i].DeepCopyInto(&(*out)[i])
		}
	}
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new RebuiltArtifactList.
func (in *RebuiltArtifactList) DeepCopy() *RebuiltArtifactList {
	if in == nil {
		return nil
	}
	out := new(RebuiltArtifactList)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyObject is an autogenerated deepcopy function, copying the receiver, creating a new runtime.Object.
func (in *RebuiltArtifactList) DeepCopyObject() runtime.Object {
	if c := in.DeepCopy(); c != nil {
		return c
	}
	return nil
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *RebuiltArtifactSpec) DeepCopyInto(out *RebuiltArtifactSpec) {
	*out = *in
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new RebuiltArtifactSpec.
func (in *RebuiltArtifactSpec) DeepCopy() *RebuiltArtifactSpec {
	if in == nil {
		return nil
	}
	out := new(RebuiltArtifactSpec)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *RebuiltArtifactStatus) DeepCopyInto(out *RebuiltArtifactStatus) {
	*out = *in
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new RebuiltArtifactStatus.
func (in *RebuiltArtifactStatus) DeepCopy() *RebuiltArtifactStatus {
	if in == nil {
		return nil
	}
	out := new(RebuiltArtifactStatus)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *RelocationPattern) DeepCopyInto(out *RelocationPattern) {
	*out = *in
	if in.Patterns != nil {
		in, out := &in.Patterns, &out.Patterns
		*out = make([]PatternElement, len(*in))
		copy(*out, *in)
	}
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new RelocationPattern.
func (in *RelocationPattern) DeepCopy() *RelocationPattern {
	if in == nil {
		return nil
	}
	out := new(RelocationPattern)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *RelocationPatternElement) DeepCopyInto(out *RelocationPatternElement) {
	*out = *in
	in.RelocationPattern.DeepCopyInto(&out.RelocationPattern)
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new RelocationPatternElement.
func (in *RelocationPatternElement) DeepCopy() *RelocationPatternElement {
	if in == nil {
		return nil
	}
	out := new(RelocationPatternElement)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *SCMInfo) DeepCopyInto(out *SCMInfo) {
	*out = *in
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new SCMInfo.
func (in *SCMInfo) DeepCopy() *SCMInfo {
	if in == nil {
		return nil
	}
	out := new(SCMInfo)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *SystemConfig) DeepCopyInto(out *SystemConfig) {
	*out = *in
	out.TypeMeta = in.TypeMeta
	in.ObjectMeta.DeepCopyInto(&out.ObjectMeta)
	in.Spec.DeepCopyInto(&out.Spec)
	out.Status = in.Status
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new SystemConfig.
func (in *SystemConfig) DeepCopy() *SystemConfig {
	if in == nil {
		return nil
	}
	out := new(SystemConfig)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyObject is an autogenerated deepcopy function, copying the receiver, creating a new runtime.Object.
func (in *SystemConfig) DeepCopyObject() runtime.Object {
	if c := in.DeepCopy(); c != nil {
		return c
	}
	return nil
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *SystemConfigList) DeepCopyInto(out *SystemConfigList) {
	*out = *in
	out.TypeMeta = in.TypeMeta
	in.ListMeta.DeepCopyInto(&out.ListMeta)
	if in.Items != nil {
		in, out := &in.Items, &out.Items
		*out = make([]SystemConfig, len(*in))
		for i := range *in {
			(*in)[i].DeepCopyInto(&(*out)[i])
		}
	}
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new SystemConfigList.
func (in *SystemConfigList) DeepCopy() *SystemConfigList {
	if in == nil {
		return nil
	}
	out := new(SystemConfigList)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyObject is an autogenerated deepcopy function, copying the receiver, creating a new runtime.Object.
func (in *SystemConfigList) DeepCopyObject() runtime.Object {
	if c := in.DeepCopy(); c != nil {
		return c
	}
	return nil
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *SystemConfigSpec) DeepCopyInto(out *SystemConfigSpec) {
	*out = *in
	if in.Builders != nil {
		in, out := &in.Builders, &out.Builders
		*out = make(map[string]JavaVersionInfo, len(*in))
		for key, val := range *in {
			(*out)[key] = val
		}
	}
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new SystemConfigSpec.
func (in *SystemConfigSpec) DeepCopy() *SystemConfigSpec {
	if in == nil {
		return nil
	}
	out := new(SystemConfigSpec)
	in.DeepCopyInto(out)
	return out
}

// DeepCopyInto is an autogenerated deepcopy function, copying the receiver, writing into out. in must be non-nil.
func (in *SystemConfigStatus) DeepCopyInto(out *SystemConfigStatus) {
	*out = *in
	return
}

// DeepCopy is an autogenerated deepcopy function, copying the receiver, creating a new SystemConfigStatus.
func (in *SystemConfigStatus) DeepCopy() *SystemConfigStatus {
	if in == nil {
		return nil
	}
	out := new(SystemConfigStatus)
	in.DeepCopyInto(out)
	return out
}
