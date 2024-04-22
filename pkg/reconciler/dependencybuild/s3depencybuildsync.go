package dependencybuild

import (
	"bytes"
	"context"
	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/service/s3/s3manager"
	"github.com/go-logr/logr"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"
	"github.com/tektoncd/cli/pkg/cli"
	tknlogs "github.com/tektoncd/cli/pkg/log"
	"github.com/tektoncd/cli/pkg/options"
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
	"io"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/cli-runtime/pkg/printers"
	"os"
	"sigs.k8s.io/controller-runtime/pkg/client"
	"sigs.k8s.io/controller-runtime/pkg/controller/controllerutil"
	"sigs.k8s.io/controller-runtime/pkg/reconcile"
	"strings"
)

const (
	S3StateSyncRequired = "required"
	S3StateSyncDisable  = "disabled"
	S3StateSyncComplete = "complete"

	Tasks     = "tasks"
	Pipelines = "pipelines"
	Logs      = "logs"
)

func (r *ReconcileDependencyBuild) handleS3SyncPipelineRun(ctx context.Context, log logr.Logger, pr *tektonpipeline.PipelineRun) (*reconcile.Result, error) {
	if !util.S3Enabled {
		return nil, nil
	}
	if pr.GetDeletionTimestamp() != nil {
		//we use finalizers to handle pipeline runs that have been cleaned
		if controllerutil.ContainsFinalizer(pr, util.S3Finalizer) {
			controllerutil.RemoveFinalizer(pr, util.S3Finalizer)
			ann := pr.Annotations[util.S3SyncStateAnnotation]
			defer func(client client.Client, ctx context.Context, obj client.Object) {
				//if we did not update the object then make sure we remove the finalizer
				//we always change this annotation on update
				if ann == pr.Annotations[util.S3SyncStateAnnotation] {
					_ = client.Update(ctx, obj)
				}
			}(r.client, ctx, pr)
		}
		// If this is not done but is deleted then we just let it happen
		if !pr.IsDone() {
			return nil, nil
		}
	}
	if pr.Annotations == nil {
		pr.Annotations = map[string]string{}
	}
	dep, err := r.dependencyBuildForPipelineRun(ctx, log, pr)
	if err != nil || dep == nil {
		return nil, err
	}
	namespace := pr.Namespace
	if !pr.IsDone() {
		if pr.Annotations == nil {
			pr.Annotations = map[string]string{}
		}
		//add a marker to indicate if sync is required of not
		//if it is already synced we remove this marker as its state has changed
		if pr.Annotations[util.S3SyncStateAnnotation] == "" || pr.Annotations[util.S3SyncStateAnnotation] == S3StateSyncComplete {
			jbsConfig := &v1alpha1.JBSConfig{}
			err := r.client.Get(ctx, types.NamespacedName{Namespace: namespace, Name: v1alpha1.JBSConfigName}, jbsConfig)
			if err != nil && !errors.IsNotFound(err) {
				return nil, err
			} else if err != nil {
				return nil, nil
			}
			if jbsConfig.Annotations != nil && jbsConfig.Annotations[util.S3BucketNameAnnotation] != "" {
				log.Info("marking PipelineRun as requiring S3 sync")
				pr.Annotations[util.S3SyncStateAnnotation] = S3StateSyncRequired
				controllerutil.AddFinalizer(pr, util.S3Finalizer)
			} else {
				log.Info("marking PipelineRun as S3 sync disabled")
				pr.Annotations[util.S3SyncStateAnnotation] = S3StateSyncDisable
			}
			return &reconcile.Result{}, r.client.Update(ctx, pr)
		}
		return nil, nil
	}
	if pr.Annotations[util.S3SyncStateAnnotation] != "" && pr.Annotations[util.S3SyncStateAnnotation] != S3StateSyncRequired {
		//no sync required
		return nil, nil
	}
	bucketName, err := util.BucketName(r.client, ctx, namespace)
	if err != nil {
		return nil, err
	}
	if bucketName == "" {
		pr.Annotations[util.S3SyncStateAnnotation] = S3StateSyncDisable
		return &reconcile.Result{}, r.client.Update(ctx, pr)
	}
	log.Info("attempting to sync PipelineRun to S3")

	if err != nil {
		return nil, err
	}
	//lets grab the credentials
	sess := util.CreateS3Session(r.client, ctx, log, namespace)
	if sess == nil {
		return nil, nil
	}
	name := pr.Name
	if pr.Labels[PipelineTypeLabel] == PipelineTypeBuildInfo {
		name = "build-discovery"
	} else if pr.Labels[PipelineTypeLabel] == PipelineTypeDeploy {
		name = "deploy"
	}

	uploader := s3manager.NewUploader(sess)
	encodedPipeline := encodeToYaml(pr)
	_, err = uploader.Upload(&s3manager.UploadInput{
		Bucket:      aws.String(bucketName),
		Key:         aws.String("build-pipelines/" + dep.Name + "/" + string(dep.UID) + "/" + name + ".yaml"),
		Body:        strings.NewReader(encodedPipeline),
		ContentType: aws.String("text/yaml"),
		Metadata: map[string]*string{
			"dependency-build":     aws.String(dep.Name),
			"dependency-build-uid": aws.String(string(dep.UID)),
			"type":                 aws.String("pipeline-run-yaml"),
			"scm-uri":              aws.String(dep.Spec.ScmInfo.SCMURL),
			"scm-tag":              aws.String(dep.Spec.ScmInfo.Tag),
			"scm-commit":           aws.String(dep.Spec.ScmInfo.CommitHash),
			"scm-path":             aws.String(dep.Spec.ScmInfo.Path),
		},
	})
	if err != nil {
		log.Error(err, "failed to upload to s3, make sure credentials are correct")
		return nil, nil
	}

	taskRuns := tektonpipeline.TaskRunList{}
	err = r.client.List(ctx, &taskRuns, client.InNamespace(pr.Namespace))
	if err != nil {
		return nil, err
	}
	pods := corev1.PodList{}
	err = r.client.List(ctx, &pods, client.InNamespace(pr.Namespace))
	if err != nil {
		return nil, err
	}

	pipereader, pipewriter := io.Pipe()
	defer func(pipereader *io.PipeReader) {
		_ = pipereader.Close()
	}(pipereader)
	defer func(pipewriter *io.PipeWriter) {
		_ = pipewriter.Close()
	}(pipewriter)
	stream := cli.Stream{
		Out: pipewriter,
		Err: pipewriter,
		In:  os.Stdin,
	}

	tektonParams := *r.logReaderParams
	tektonParams.SetNamespace(pr.Namespace)
	tektonParams.SetNoColour(true)
	reader, err := tknlogs.NewReader(tknlogs.LogTypePipeline, &options.LogOptions{PipelineRunName: pr.Name, Params: &tektonParams, Stream: &stream, Follow: true, AllSteps: true})
	if err != nil {
		log.Error(err, "failed to create log reader")
	} else {
		writer := tknlogs.NewWriter(tknlogs.LogTypePipeline, true)
		logs, errors, err := reader.Read()
		if err != nil {
			log.Error(err, "failed to create log reader")
		}
		go func() {
			writer.Write(&stream, logs, errors)
			err := pipewriter.Close()
			if err != nil {
				log.Error(err, "error closing pipe")
			}
		}()

		logsPath := "build-logs/" + dep.Name + "/" + string(dep.UID) + "/" + name + ".log"
		log.Info("attempting to upload logs to S3", "path", logsPath)
		_, err = uploader.Upload(&s3manager.UploadInput{

			Bucket:      aws.String(bucketName),
			Key:         aws.String(logsPath),
			Body:        pipereader,
			ContentType: aws.String("text/plain"),
			Metadata: map[string]*string{
				"dependency-build":     aws.String(dep.Name),
				"dependency-build-uid": aws.String(string(dep.UID)),
				"type":                 aws.String("pipeline-logs"),
				"scm-uri":              aws.String(dep.Spec.ScmInfo.SCMURL),
				"scm-tag":              aws.String(dep.Spec.ScmInfo.Tag),
				"scm-commit":           aws.String(dep.Spec.ScmInfo.CommitHash),
				"scm-path":             aws.String(dep.Spec.ScmInfo.Path),
			},
		})
		if err != nil {
			log.Error(err, "failed to upload task logs to s3")
		}

	}
	for _, tr := range taskRuns.Items {
		found := false
		for _, owner := range tr.OwnerReferences {
			if owner.UID == pr.UID {
				found = true
			}
		}
		if !found {
			continue
		}
		taskPath := "tasks/" + dep.Name + "/" + string(dep.UID) + "/" + pr.Name + "/" + tr.Name + ".yaml"
		log.Info("attempting to upload TaskRun to s3", "path", taskPath)
		encodeableTr := tr
		_, err = uploader.Upload(&s3manager.UploadInput{
			Bucket:      aws.String(bucketName),
			Key:         aws.String(taskPath),
			Body:        strings.NewReader(encodeToYaml(&encodeableTr)),
			ContentType: aws.String("text/yaml"),
			Metadata: map[string]*string{
				"dependency-build":     aws.String(dep.Name),
				"dependency-build-uid": aws.String(string(dep.UID)),
				"type":                 aws.String("task-run-yaml"),
				"scm-uri":              aws.String(dep.Spec.ScmInfo.SCMURL),
				"scm-tag":              aws.String(dep.Spec.ScmInfo.Tag),
				"scm-commit":           aws.String(dep.Spec.ScmInfo.CommitHash),
				"scm-path":             aws.String(dep.Spec.ScmInfo.Path),
			},
		})
		if err != nil {
			log.Error(err, "failed to upload task to s3")
		}
	}

	controllerutil.RemoveFinalizer(pr, util.S3Finalizer)
	pr.Annotations[util.S3SyncStateAnnotation] = S3StateSyncComplete
	return &reconcile.Result{}, r.client.Update(ctx, pr)
}

func (r *ReconcileDependencyBuild) handleS3SyncDependencyBuild(ctx context.Context, db *v1alpha1.DependencyBuild, log logr.Logger) (bool, error) {
	if !util.S3Enabled {
		return false, nil
	}
	if db.Annotations == nil {
		db.Annotations = map[string]string{}
	}
	if db.Status.State != v1alpha1.DependencyBuildStateComplete &&
		db.Status.State != v1alpha1.DependencyBuildStateFailed &&
		db.Status.State != v1alpha1.DependencyBuildStateContaminated {
		if db.Annotations == nil {
			db.Annotations = map[string]string{}
		}
		//add a marker to indicate if sync is required of not
		//if it is already synced we remove this marker as its state has changed
		if db.Annotations[util.S3SyncStateAnnotation] == "" || db.Annotations[util.S3SyncStateAnnotation] == S3StateSyncComplete {
			jbsConfig := &v1alpha1.JBSConfig{}
			err := r.client.Get(ctx, types.NamespacedName{Namespace: db.Namespace, Name: v1alpha1.JBSConfigName}, jbsConfig)
			if err != nil && !errors.IsNotFound(err) {
				return false, err
			} else if err != nil {
				return false, nil
			}
			if jbsConfig.Annotations != nil && jbsConfig.Annotations[util.S3BucketNameAnnotation] != "" {
				log.Info("marking DependencyBuild as requiring S3 sync")
				db.Annotations[util.S3SyncStateAnnotation] = S3StateSyncRequired
			} else {
				log.Info("marking DependencyBuild as S3 sync disabled")
				db.Annotations[util.S3SyncStateAnnotation] = S3StateSyncDisable
			}
			return true, r.client.Update(ctx, db)
		}
		return false, nil
	}
	if db.Annotations[util.S3SyncStateAnnotation] != "" && db.Annotations[util.S3SyncStateAnnotation] != S3StateSyncRequired {
		//no sync required
		return false, nil
	}
	bucketName, err := util.BucketName(r.client, ctx, db.Namespace)
	if err != nil && !errors.IsNotFound(err) {
		return false, err
	} else if err != nil {
		return false, nil
	}
	if bucketName == "" {
		db.Annotations[util.S3SyncStateAnnotation] = S3StateSyncDisable
		return true, r.client.Update(ctx, db)
	}
	log.Info("attempting to sync DependencyBuild to S3")

	//lets grab the credentials

	//now lets do the sync
	sess := util.CreateS3Session(r.client, ctx, log, db.Namespace)

	uploader := s3manager.NewUploader(sess)
	encodedDb := encodeToYaml(db)
	_, err = uploader.Upload(&s3manager.UploadInput{
		Bucket:      aws.String(bucketName),
		Key:         aws.String("builds/" + db.Name + "/" + string(db.UID) + ".yaml"),
		Body:        strings.NewReader(encodedDb),
		ContentType: aws.String("text/yaml"),
		Metadata: map[string]*string{
			"dependency-build":     aws.String(db.Name),
			"dependency-build-uid": aws.String(string(db.UID)),
			"type":                 aws.String("dependency-build-yaml"),
			"scm-uri":              aws.String(db.Spec.ScmInfo.SCMURL),
			"scm-tag":              aws.String(db.Spec.ScmInfo.Tag),
			"scm-commit":           aws.String(db.Spec.ScmInfo.CommitHash),
			"scm-path":             aws.String(db.Spec.ScmInfo.Path),
		},
	})
	if err != nil {
		log.Error(err, "failed to upload to s3, make sure credentials are correct")
		return false, nil
	}
	db.Annotations[util.S3SyncStateAnnotation] = S3StateSyncComplete
	return true, r.client.Update(ctx, db)
}

func encodeToYaml(obj runtime.Object) string {

	y := printers.YAMLPrinter{}
	b := bytes.Buffer{}
	_ = y.PrintObj(obj, &b)
	return b.String()
}
