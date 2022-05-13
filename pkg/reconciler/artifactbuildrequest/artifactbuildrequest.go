package artifactbuildrequest

import (
	"context"
	"crypto/md5"
	"encoding/hex"
	pipelinev1beta1 "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1beta1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/labels"
	"k8s.io/apimachinery/pkg/runtime"
	"knative.dev/pkg/apis"
	ctrl "sigs.k8s.io/controller-runtime"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/log"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"time"

	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
)

const (
	//TODO eventually we'll need to decide if we want to make this tuneable
	contextTimeout = 300 * time.Second
	abrLabel       = "jvmbuildservice.io/artifactbuildrequest"
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
	log := log.FromContext(ctx)
	abr := v1alpha1.ArtifactBuildRequest{}
	err := r.client.Get(ctx, request.NamespacedName, &abr)
	if err != nil {
		if errors.IsNotFound(err) {
			return ctrl.Result{}, nil
		}
		return ctrl.Result{}, err
	}
	abrNameForLabel, existingLabel := abr.Labels[abrLabel]
	if !existingLabel {
		//we make sure that there is always a label assigned
		//our names don't meet the label rules, so everything matches
		//on this synthetic label
		hash := md5.Sum([]byte(abr.Name))
		abrNameForLabel = hex.EncodeToString(hash[:])
		if abr.Labels == nil {
			abr.Labels = map[string]string{}
		}
		abr.Labels[abrLabel] = abrNameForLabel
		r.client.Update(ctx, &abr)
	}

	if abr.Status.State == v1alpha1.ArtifactBuildRequestStateNew || abr.Status.State == "" {

		//log.Info("Found new", "ArtifactBuildRequest", abr)

		//this is in the new state, we launch a TaskRun to do discovery
		list := &pipelinev1beta1.TaskRunList{}
		lbls := map[string]string{abrLabel: abrNameForLabel}
		listOpts := &client.ListOptions{
			Namespace:     abr.Namespace,
			LabelSelector: labels.SelectorFromSet(lbls),
		}
		err = r.client.List(ctx, list, listOpts)
		if err != nil {
			return reconcile.Result{}, err
		}
		//if we are back in NEW that means something has gone wrong with discovery and we want to re-run it

		if len(list.Items) == 0 {
			log.Info("No taskrun found, creating new run", "ArtifactBuildRequest", abr.Name, "label", abrNameForLabel)
			// create task run
			tr := pipelinev1beta1.TaskRun{}
			tr.Spec.TaskRef = &pipelinev1beta1.TaskRef{Name: "lookup-artifact-location", Kind: pipelinev1beta1.NamespacedTaskKind}
			tr.Namespace = abr.Namespace
			tr.GenerateName = abr.Name + "-scm-discovery-"
			tr.Labels = map[string]string{abrLabel: abrNameForLabel}
			tr.Spec.Params = append(tr.Spec.Params, pipelinev1beta1.Param{Name: "GAV", Value: pipelinev1beta1.ArrayOrString{Type: pipelinev1beta1.ParamTypeString, StringVal: abr.Spec.GAV}})
			err = r.client.Create(ctx, &tr)
			if err != nil {
				return reconcile.Result{}, err
			}
			log.Info("Updating status to discovering", "ArtifactBuildRequest", abr)
			abr.Status.State = v1alpha1.ArtifactBuildRequestStateDiscovering
			err = r.client.Status().Update(ctx, &abr)
			if err != nil {
				return reconcile.Result{}, err
			}
		} else {
			pr := list.Items[0]
			if pr.Status.CompletionTime != nil {
				condition := pr.Status.GetCondition(apis.ConditionSucceeded)
				if !condition.IsTrue() {
					abr.Status.State = v1alpha1.ArtifactBuildRequestStateMissing
					results := pr.Status.TaskRunResults
					for _, result := range results {
						if result.Name == "message" {
							abr.Status.Message = result.Value
							break
						}
					}
				} else {
					abr.Status.State = v1alpha1.ArtifactBuildRequestStateBuilding
					//TODO: build pipeline
				}
				err = r.client.Status().Update(ctx, &abr)
				if err != nil {
					return reconcile.Result{}, err
				}
			}
		}
	}

	// rough approximation of what is in https://github.com/redhat-appstudio/jvm-build-service/blob/main/build-request-processor/src/main/java/com/redhat/hacbs/container/analyser/ProcessCommand.java
	// where we replace the list done there, using the watch / relist induced event we get here
	if abr.Status.State == v1alpha1.ArtifactBuildRequestStateNew {
		//if the ABR is new then we want to kick of a pipeline to
		abr.Status.State = v1alpha1.ArtifactBuildRequestStateMissing
		err = r.client.Update(ctx, &abr)
		if err != nil {
			return reconcile.Result{}, err
		}
		return reconcile.Result{}, nil
	}

	//TODO need some golang approximation of RecipeRepositoryManager and RecipeGroupManager that takes
	// the gav and abr and produces the needed result
	// Per last team meeting: may make sense to capture the RecipeRepositoryManager and RecipeGroupManager
	// as steps in a PipelineRun that we launch here.  We then analyze the results/output of the PipelineRun
	// (where we figure what those results/output are stored so this reconciler can retrieve them) and then
	// move onto the next step below.
	//
	//dbName := "someNamDerivedFromArtifactBuildRequestAndGa"
	//dbNamespace := abr.Namespace
	//key := types.NamespacedName{Namespace: dbNamespace, Name: dbName}
	//db := v1alpha1.DependencyBuild{ObjectMeta: metav1.ObjectMeta{Name: dbName}}
	//err = r.client.Get(ctx, key, &db)
	//if errors.IsNotFound(err) {
	//	db.Spec.SCMURL = "someurl"
	//	db.Spec.SCMType = "git"
	//	db.Spec.Tag = "selectedTag"
	//	db.Spec.Version = "someVersion"
	//
	//	//err = r.client.Create(ctx, &db)
	//	//if err != nil {
	//	//	return reconcile.Result{}, err
	//	//}
	//}
	//if err != nil {
	//	return reconcile.Result{}, err
	//}
	//
	//abr.Status.State = v1alpha1.ArtifactBuildRequestStateBuilding
	//err = r.client.Update(ctx, &abr)
	//if err != nil {
	//	return reconcile.Result{}, err
	//}
	//
	//// see if we already launched a PipelineRun associated with this ABR
	//list := &pipelinev1beta1.PipelineRunList{}
	//abrNameForLabel := types.NamespacedName{Namespace: abr.Namespace, Name: abr.Name}.String()
	//lbls := map[string]string{"jvmbuildservice.io/artifactbuildrequet": abrNameForLabel}
	//listOpts := &client.ListOptions{
	//	Namespace:     abr.Namespace,
	//	LabelSelector: labels.SelectorFromSet(lbls),
	//}
	//err = r.client.List(ctx, list, listOpts)
	//if err != nil {
	//	return reconcile.Result{}, err
	//}
	//if len(list.Items) == 0 {
	//	// create pipelinerun
	//	pr := &pipelinev1beta1.PipelineRun{}
	//	pr.Namespace = abr.Namespace
	//	pr.GenerateName = abr.Name + "-"
	//	//TODO fill in other needed fields of PR
	//	err = r.client.Create(ctx, pr)
	//	if err != nil {
	//		return reconcile.Result{}, err
	//	}
	//} else {
	//	pr := list.Items[0]
	//	if pr.Status.CompletionTime != nil {
	//		abr.Status.State = v1alpha1.ArtifactBuildRequestStateComplete
	//		condition := pr.Status.GetCondition(apis.ConditionSucceeded)
	//		if !condition.IsTrue() {
	//			abr.Status.State = v1alpha1.ArtifactBuildRequestStateFailed
	//		}
	//
	//	}
	//}

	return reconcile.Result{}, nil
}
