package jvmbuildstatus

import (
	"context"
	"crypto/sha1" //#nosec G505
	"encoding/hex"
	"fmt"
	"github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/client-go/tools/record"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"strings"
	"time"
	"unicode"

	"github.com/go-logr/logr"
	jvmbs "github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/artifactbuild"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout = 300 * time.Second
)

type ReconcileJvmBuildStatus struct {
	client        client.Client
	scheme        *runtime.Scheme
	eventRecorder record.EventRecorder
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcileJvmBuildStatus{
		client:        mgr.GetClient(),
		scheme:        mgr.GetScheme(),
		eventRecorder: mgr.GetEventRecorderFor("JvmBuildStatus"),
	}
}

func (r *ReconcileJvmBuildStatus) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	var cancel context.CancelFunc
	ctx, cancel = context.WithTimeout(ctx, contextTimeout)
	defer cancel()
	log := ctrl.Log.WithName("artifactbuild").WithValues("request", request.NamespacedName)

	abr := jvmbs.ArtifactBuild{}
	abrerr := r.client.Get(ctx, request.NamespacedName, &abr)
	if abrerr != nil {
		if !errors.IsNotFound(abrerr) {
			log.Error(abrerr, "Reconcile key %s as artifactbuild unexpected error", request.NamespacedName.String())
			return ctrl.Result{}, abrerr
		}
	}

	cb := jvmbs.JvmBuildStatus{}
	cberr := r.client.Get(ctx, request.NamespacedName, &cb)
	if cberr != nil {
		if !errors.IsNotFound(cberr) {
			log.Error(cberr, "Reconcile key %s as componentbuild unexpected error", request.NamespacedName.String())
			return ctrl.Result{}, cberr
		}
	}

	pr := v1beta1.PipelineRun{}
	prerr := r.client.Get(ctx, request.NamespacedName, &pr)
	if prerr != nil {
		if !errors.IsNotFound(prerr) {
			log.Error(prerr, "Reconcile key %s as pipelinerun unexpected error", request.NamespacedName.String())
			return ctrl.Result{}, prerr
		}
	}
	if cberr != nil && abrerr != nil && prerr != nil {
		msg := "Reconcile key received not found errors for jvmbuildstatus, artifactbuilds, pipelineruns (probably deleted): " + request.NamespacedName.String()
		log.Info(msg)
		return ctrl.Result{}, nil
	}

	switch {
	case cberr == nil:
		return r.handleBuildStatusReceived(ctx, log, &cb)
	case abrerr == nil:
		return r.handleArtifactBuildReceived(ctx, log, &abr)
	case prerr == nil:
		return r.handlePipelineRunReceived(ctx, log, &pr)
	}

	return reconcile.Result{}, nil
}

func (r *ReconcileJvmBuildStatus) handleBuildStatusReceived(ctx context.Context, log logr.Logger, cb *jvmbs.JvmBuildStatus) (reconcile.Result, error) {
	log.Info("Handling JvmBuildStatus", "name", cb.Name, "outstanding", cb.Status.Outstanding, "state", cb.Status.State)

	//iterate over the spec, and calculate the corresponding status
	cb.Status.Outstanding = 0
	cb.Status.ArtifactState = map[string]jvmbs.ArtifactState{}
	//TODO: Handle contaminates
	for _, i := range cb.Spec.Artifacts {
		if i.Source == "rebuilt" || i.Source == "redhat" {
			//TODO: this is a hack, this should be configurablen
			continue
		}
		existing := jvmbs.ArtifactBuild{}
		key := types.NamespacedName{Namespace: cb.Namespace, Name: artifactbuild.CreateABRName(i.GAV)}
		aberr := r.client.Get(ctx, key, &existing)
		if aberr == nil || !errors.IsNotFound(aberr) {
			cb.Status.ArtifactState[i.GAV] = r.artifactState(ctx, log, &existing)
			state := cb.Status.ArtifactState[i.GAV]
			if !state.Built && !state.Failed {
				cb.Status.Outstanding++
			}
		} else {
			abr := jvmbs.ArtifactBuild{}
			abr.Spec = jvmbs.ArtifactBuildSpec{GAV: i.GAV}
			abr.Name = artifactbuild.CreateABRName(i.GAV)
			abr.Namespace = cb.Namespace
			err := r.client.Create(ctx, &abr)
			if err != nil {
				return reconcile.Result{}, err
			}
			cb.Status.ArtifactState[i.GAV] = r.artifactState(ctx, log, &abr)
			cb.Status.Outstanding++
		}
	}
	if cb.Status.Outstanding == 0 {
		//completed, change the state
		failed := false
		for _, v := range cb.Status.ArtifactState {
			if v.Failed {
				failed = true
				break
			}
		}
		if failed {
			cb.Status.State = jvmbs.JvmBuildStateFailed
		} else {
			cb.Status.State = jvmbs.JvmBuildStateComplete
		}
	} else {
		//if there are still some outstanding we reset the notification state
		cb.Status.State = jvmbs.JvmBuildStateInProgress
	}
	err := r.client.Status().Update(ctx, cb)
	return reconcile.Result{}, err
}

func (r *ReconcileJvmBuildStatus) handleArtifactBuildReceived(ctx context.Context, log logr.Logger, abr *jvmbs.ArtifactBuild) (reconcile.Result, error) {
	log.Info("Handling ArtifactBuild", "name", abr.Name, "state", abr.Status.State)
	cbList := jvmbs.JvmBuildStatusList{}
	err := r.client.List(ctx, &cbList, client.InNamespace(abr.Namespace))
	if err != nil {
		return reconcile.Result{}, err
	}
	for _, i := range cbList.Items {
		_, exists := i.Status.ArtifactState[abr.Spec.GAV]
		if exists {
			cbItem := i
			_, cberr := r.handleBuildStatusReceived(ctx, log, &cbItem)
			if cberr != nil {
				log.Error(cberr, fmt.Sprintf("Error handling componentbuild %s", i.Name))
			}
		}
	}
	return reconcile.Result{}, nil
}

func (r *ReconcileJvmBuildStatus) handlePipelineRunReceived(ctx context.Context, log logr.Logger, pr *v1beta1.PipelineRun) (reconcile.Result, error) {
	if pr.Status.PipelineResults != nil {
		for _, prRes := range pr.Status.PipelineResults {
			if prRes.Name == artifactbuild.JavaDependencies {
				return reconcile.Result{}, r.handleCommunityDependencies(ctx, strings.Split(prRes.Value.StringVal, ","), pr, log)
			}
		}
	}
	return reconcile.Result{}, nil
}

func (r *ReconcileJvmBuildStatus) artifactState(ctx context.Context, log logr.Logger, abr *jvmbs.ArtifactBuild) jvmbs.ArtifactState {
	failed := abr.Status.State == jvmbs.ArtifactBuildStateFailed || abr.Status.State == jvmbs.ArtifactBuildStateMissing
	built := abr.Status.State == jvmbs.ArtifactBuildStateComplete
	return jvmbs.ArtifactState{ArtifactBuild: abr.Name, Failed: failed, Built: built}
}

func (r *ReconcileJvmBuildStatus) handleCommunityDependencies(ctx context.Context, artifactList []string, pr *v1beta1.PipelineRun, log logr.Logger) error {

	log.Info("Found pipeline run with Java dependencies")
	artifacts := map[string]jvmbs.JvmBuildStatusArtifact{}
	for _, i := range artifactList {
		if len(i) == 0 {
			continue
		}
		split := strings.Split(i, "|")
		if len(split) == 3 {
			artifacts[split[0]] = jvmbs.JvmBuildStatusArtifact{GAV: split[0], Source: split[1], BuildId: split[2]}
		} else {
			log.Info("Invalid artifact|source|uid", "value", i)
		}
	}
	//look for tag + scm info
	url := ""
	revision := ""
	for _, tr := range pr.Status.PipelineSpec.Tasks {
		if tr.TaskRef.Name == "git-clone" {
			for _, p := range tr.Params {
				if p.Name == "url" {
					url = p.Value.StringVal
				} else if p.Name == "revision" {
					revision = p.Value.StringVal
				}
			}
		}
	}
	name := ""
	if len(url) == 0 {
		name = pr.Name //just use the pipeline name if we can't determine the revision
	} else {
		name = ComponentBuildName(url + revision)
	}

	status := jvmbs.JvmBuildStatus{}
	err := r.client.Get(ctx, types.NamespacedName{Namespace: pr.Namespace, Name: name}, &status)
	create := false
	if err != nil {
		if errors.IsNotFound(err) {
			//we need to create the status
			status.Name = name
			status.Namespace = pr.Namespace
			create = true
		} else {
			return err
		}
	}
	existing := status.Spec.Artifacts
	for gav, res := range artifacts {
		found := false
		for _, i := range existing {
			if i.GAV == gav {
				found = true
				i.Source = res.Source
				i.BuildId = res.BuildId
				break
			}
		}
		//now add the artifacts
		if !found {
			artifact := res
			status.Spec.Artifacts = append(status.Spec.Artifacts, &artifact)
		}
	}
	if create {
		err = r.client.Create(ctx, &status)
	} else {
		err = r.client.Update(ctx, &status)
	}
	if err != nil {
		return err
	}
	return nil
}

func ComponentBuildName(input string) string {
	hashedBytes := sha1.Sum([]byte(input)) //#nosec
	hash := hex.EncodeToString(hashedBytes[:])[0:8]
	namePart := input[strings.Index(input, ":")+1:]

	var newName = strings.Builder{}
	lastDot := false
	first := true
	for _, i := range namePart {
		if unicode.IsLetter(i) || unicode.IsDigit(i) {
			first = false
			newName.WriteRune(i)
			lastDot = false
		} else {
			if !lastDot && !first {
				newName.WriteString(".")
			}
			lastDot = true
		}
	}
	newName.WriteString("-")
	newName.WriteString(hash)
	return strings.ToLower(newName.String())
}
