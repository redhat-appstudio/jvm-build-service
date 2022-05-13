package artifactbuildrequest

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/dependencybuild"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/runtime"
	"knative.dev/pkg/apis"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout     = 300 * time.Second
	IdLabel            = "jvmbuildservice.io/artifactbuildrequest-id"
	taskRunStatusLabel = "jvmbuildservice.io/artifactbuildrequest-status"
)

type ReconcileArtifactBuildRequest struct {
	client client.Client
	scheme *runtime.Scheme
}

func newReconciler(mgr ctrl.Manager) reconcile.Reconciler {
	return &ReconcileArtifactBuildRequest{
		client: mgr.GetClient(),
		scheme: mgr.GetScheme(),
	}
}

func (r *ReconcileArtifactBuildRequest) Reconcile(ctx context.Context, request reconcile.Request) (reconcile.Result, error) {
	// Set the ctx to be Background, as the top-level context for incoming requests.
	ctx, cancel := context.WithTimeout(ctx, contextTimeout)
	defer cancel()
	//log := log.FromContext(ctx)
	abr := v1alpha1.ArtifactBuildRequest{}
	err := r.client.Get(ctx, request.NamespacedName, &abr)
	if err != nil {
		if errors.IsNotFound(err) {
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}
	abrNameForLabel, existingLabel := abr.Labels[IdLabel]
	if !existingLabel {
		//we make sure that there is always a label assigned
		//our names don't meet the label rules, so everything matches
		//on this synthetic label
		hash := md5.Sum([]byte(abr.Name))
		abrNameForLabel = hex.EncodeToString(hash[:])
		if abr.Labels == nil {
			abr.Labels = map[string]string{}
		}
		abr.Labels[IdLabel] = abrNameForLabel
		err = r.client.Update(ctx, &abr)
		if err != nil {
			return reconcile.Result{}, err
		}
	}

	if abr.Status.State == v1alpha1.ArtifactBuildRequestStateNew || abr.Status.State == "" {
		list := &pipelinev1beta1.TaskRunList{}
		lbls := map[string]string{IdLabel: abrNameForLabel}
		listOpts := &client.ListOptions{
			Namespace:     abr.Namespace,
			LabelSelector: labels.SelectorFromSet(lbls),
		}
		err = r.client.List(ctx, list, listOpts)
		if err != nil {
			return reconcile.Result{}, err
		}
		//if we are back in NEW that means something has gone wrong with discovery, and we want to re-run it
		for _, existing := range list.Items {
			//we don't want this existing TR confusing things
			//we also don't want to just delete them as they may have info
			//so we just stick a label on them to say that we have a new one
			//this is an edge case, it would be triggered by something going wrong
			existing.Labels[taskRunStatusLabel] = "outdated"
			err := r.client.Update(ctx, &existing)
			if err != nil {
				return reconcile.Result{}, err
			}
		}

		// create task run
		tr := pipelinev1beta1.TaskRun{}
		tr.Spec.TaskRef = &pipelinev1beta1.TaskRef{Name: "lookup-artifact-location", Kind: pipelinev1beta1.NamespacedTaskKind}
		tr.Namespace = abr.Namespace
		tr.GenerateName = abr.Name + "-scm-discovery-"
		tr.Labels = map[string]string{IdLabel: abrNameForLabel, taskRunStatusLabel: "current"}
		tr.Spec.Params = append(tr.Spec.Params, pipelinev1beta1.Param{Name: "GAV", Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: abr.Spec.GAV}})
		err = r.client.Create(ctx, &tr)
		if err != nil {
			return reconcile.Result{}, err
		}
		abr.Status.State = v1alpha1.ArtifactBuildRequestStateDiscovering
		err = r.client.Status().Update(ctx, &abr)
		if err != nil {
			return reconcile.Result{}, err
		}
	} else if abr.Status.State == v1alpha1.ArtifactBuildRequestStateDiscovered {
		//we have a notification and we are in discovering
		//lets see if our tr is done
		list := &pipelinev1beta1.TaskRunList{}
		lbls := map[string]string{IdLabel: abrNameForLabel, taskRunStatusLabel: "current"}
		listOpts := &client.ListOptions{
			Namespace:     abr.Namespace,
			LabelSelector: labels.SelectorFromSet(lbls),
		}
		err = r.client.List(ctx, list, listOpts)
		if err != nil {
			return reconcile.Result{}, err
		}
		if len(list.Items) == 0 {
			//no TR found, this is odd
			//I guess just go back to new
			abr.Status.State = v1alpha1.ArtifactBuildRequestStateNew
			err := r.client.Update(ctx, &abr)
			return reconcile.Result{Requeue: true}, err
		}
		tr := list.Items[0]
		if tr.Status.CompletionTime != nil {
			//make sure the tr is done
			condition := tr.Status.GetCondition(apis.ConditionSucceeded)
			if condition.IsTrue() {
				abr.Status.State = v1alpha1.ArtifactBuildRequestStateBuilding
				//now lets create the dependency build object
				//once this object has been created it's resolver takes over
				var scmUrl string
				var scmTag string
				var scmType string
				var message string
				var path string

				for _, res := range tr.Status.TaskRunResults {
					if res.Name == "scm-url" {
						scmUrl = res.Value
					} else if res.Name == "scm-tag" {
						scmTag = res.Value
					} else if res.Name == "scm-type" {
						scmType = res.Value
					} else if res.Name == "message" {
						message = res.Value
					} else if res.Name == "context" {
						path = res.Value
					}
				}
				if scmTag == "" {
					//this is a failure
					abr.Status.State = v1alpha1.ArtifactBuildRequestStateMissing
					abr.Status.Message = message
					err = r.client.Status().Update(ctx, &abr)
					return reconcile.Result{}, err
				}
				//we generate a hash of the url, tag and path for
				//our unique identifier
				hash := md5.Sum([]byte(scmUrl + scmTag + path))
				depId := hex.EncodeToString(hash[:])
				//now lets look for an existing build object
				list := &v1alpha1.DependencyBuildList{}
				lbls := map[string]string{
					dependencybuild.IdLabel: depId,
				}
				listOpts := &client.ListOptions{
					Namespace:     abr.Namespace,
					LabelSelector: labels.SelectorFromSet(lbls),
				}
				err = r.client.List(ctx, list, listOpts)
				if err != nil {
					return reconcile.Result{}, err
				}
				if len(list.Items) == 0 {
					//no existing build object found, lets create one
					db := &v1alpha1.DependencyBuild{}
					db.Namespace = abr.Namespace
					db.Labels = lbls
					//TODO: name should be based on the git repo, not the abr, but needs
					//a sanitization algorithm
					db.GenerateName = abr.Name + "-"
					db.Spec = v1alpha1.DependencyBuildSpec{
						SCMURL:  scmUrl,
						SCMType: scmType,
						Tag:     scmTag,
						Path:    path,
					}
					err = r.client.Create(ctx, db)
					if err != nil {
						return reconcile.Result{}, err
					}
				}
				//add the dependency build label to the abr as well
				//so you can easily look them up
				abr.Labels[dependencybuild.IdLabel] = depId
				err := r.client.Update(ctx, &abr)
				if err != nil {
					return reconcile.Result{}, err
				}
				//move the state to building
				abr.Status.State = v1alpha1.ArtifactBuildRequestStateBuilding
				err = r.client.Update(ctx, &abr)
				return reconcile.Result{}, err
			} else {
				abr.Status.State = v1alpha1.ArtifactBuildRequestStateMissing
				results := tr.Status.TaskRunResults
				for _, result := range results {
					if result.Name == "message" {
						abr.Status.Message = result.Value
						break
					}
				}
				err = r.client.Status().Update(ctx, &abr)
				return reconcile.Result{}, err
			}
		} else {
			return reconcile.Result{}, nil
		}
	}
	return reconcile.Result{}, nil
}
