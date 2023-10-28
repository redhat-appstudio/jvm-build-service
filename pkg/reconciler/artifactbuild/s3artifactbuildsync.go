package artifactbuild

import (
	"bytes"
	"context"
	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/service/s3/s3manager"
	"github.com/go-logr/logr"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"github.com/redhat-appstudio/jvm-build-service/pkg/reconciler/util"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/runtime"
	"k8s.io/apimachinery/pkg/types"
	"k8s.io/cli-runtime/pkg/printers"
	"strings"
)

const (
	S3BucketNameAnnotation = "jvmbuildservice.io/s3-bucket-name"
	S3SyncStateAnnotation  = "jvmbuildservice.io/s3-sync-state"
	S3Finalizer            = "jvmbuildservice.io/s3-finalizer"

	S3StateSyncRequired = "required"
	S3StateSyncDisable  = "disabled"
	S3StateSyncComplete = "complete"
)

func (r *ReconcileArtifactBuild) handleS3SyncArtifactBuild(ctx context.Context, ab *v1alpha1.ArtifactBuild, log logr.Logger) (bool, error) {
	if !util.S3Enabled {
		return false, nil
	}
	if ab.Annotations == nil {
		ab.Annotations = map[string]string{}
	}
	if ab.Status.State != v1alpha1.ArtifactBuildStateComplete &&
		ab.Status.State != v1alpha1.ArtifactBuildStateFailed &&
		ab.Status.State != v1alpha1.ArtifactBuildStateMissing {
		if ab.Annotations == nil {
			ab.Annotations = map[string]string{}
		}
		//add a marker to indicate if sync is required of not
		//if it is already synced we remove this marker as its state has changed
		if ab.Annotations[S3SyncStateAnnotation] == "" || ab.Annotations[S3SyncStateAnnotation] == S3StateSyncComplete {
			jbsConfig := &v1alpha1.JBSConfig{}
			err := r.client.Get(ctx, types.NamespacedName{Namespace: ab.Namespace, Name: v1alpha1.JBSConfigName}, jbsConfig)
			if err != nil && !errors.IsNotFound(err) {
				return false, err
			} else if err != nil {
				return false, nil
			}
			if jbsConfig.Annotations != nil && jbsConfig.Annotations[S3BucketNameAnnotation] != "" {
				log.Info("marking ArtifactBuild as requiring S3 sync")
				ab.Annotations[S3SyncStateAnnotation] = S3StateSyncRequired
			} else {
				log.Info("marking ArtifactBuild as S3 sync disabled")
				ab.Annotations[S3SyncStateAnnotation] = S3StateSyncDisable
			}
			return true, r.client.Update(ctx, ab)
		}
		return false, nil
	}
	if ab.Annotations[S3SyncStateAnnotation] != "" && ab.Annotations[S3SyncStateAnnotation] != S3StateSyncRequired {
		//no sync required
		return false, nil
	}
	bucketName, err := util.BucketName(r.client, ctx, ab.Namespace)
	if err != nil && !errors.IsNotFound(err) {
		return false, err
	} else if err != nil {
		return false, nil
	}
	if bucketName == "" {
		ab.Annotations[util.S3SyncStateAnnotation] = S3StateSyncDisable
		return true, r.client.Update(ctx, ab)
	}
	log.Info("attempting to sync ArtifactBuild to S3")

	//lets grab the credentials

	//now lets do the sync
	sess := util.CreateS3Session(r.client, ctx, log, ab.Namespace)

	uploader := s3manager.NewUploader(sess)
	encodedDb := encodeToYaml(ab)
	_, err = uploader.Upload(&s3manager.UploadInput{
		Bucket:      aws.String(bucketName),
		Key:         aws.String("artifacts/" + ab.Name + "/" + string(ab.UID) + "/ArtifactBuild.yaml"),
		Body:        strings.NewReader(encodedDb),
		ContentType: aws.String("text/yaml"),
		Metadata: map[string]*string{
			"artifact-build":     aws.String(ab.Name),
			"artifact-build-uid": aws.String(string(ab.UID)),
			"type":               aws.String("artifact-build-yaml"),
			"gav":                aws.String(ab.Spec.GAV),
		},
	})
	if err != nil {
		log.Error(err, "failed to upload to s3, make sure credentials are correct")
		return false, nil
	}
	ab.Annotations[S3SyncStateAnnotation] = S3StateSyncComplete
	return true, r.client.Update(ctx, ab)
}

func encodeToYaml(obj runtime.Object) string {

	y := printers.YAMLPrinter{}
	b := bytes.Buffer{}
	_ = y.PrintObj(obj, &b)
	return b.String()
}
