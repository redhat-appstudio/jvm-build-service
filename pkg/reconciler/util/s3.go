package util

import (
	"context"
	"github.com/aws/aws-sdk-go/aws"
	"github.com/aws/aws-sdk-go/aws/credentials"
	"github.com/aws/aws-sdk-go/aws/session"
	"github.com/go-logr/logr"
	"github.com/redhat-appstudio/jvm-build-service/pkg/apis/jvmbuildservice/v1alpha1"
	"k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/errors"
	"k8s.io/apimachinery/pkg/types"
	"sigs.k8s.io/controller-runtime/pkg/client"
)

const (
	SecretName             = "jvm-build-s3-secrets" //#nosec
	S3BucketNameAnnotation = "jvmbuildservice.io/s3-bucket-name"
	S3SyncStateAnnotation  = "jvmbuildservice.io/s3-sync-state"
	S3Finalizer            = "jvmbuildservice.io/s3-finalizer"
)

func CreateS3Session(client client.Client, ctx context.Context, log logr.Logger, namespace string) *session.Session {

	awsSecret := &v1.Secret{}
	// our client is wired to not cache secrets / establish informers for secrets
	err := client.Get(ctx, types.NamespacedName{Namespace: namespace, Name: SecretName}, awsSecret)
	if err != nil {
		log.Info("S3 Failed to sync due to missing secret")
		//no secret we just return
		return nil
	}
	//now lets do the sync
	sess, err := session.NewSession(&aws.Config{
		Credentials: credentials.NewStaticCredentials(string(awsSecret.Data[v1alpha1.AWSAccessID]), string(awsSecret.Data[v1alpha1.AWSSecretKey]), ""),
		Region:      aws.String(string(awsSecret.Data[v1alpha1.AWSRegion]))},
	)
	if err != nil {
		log.Error(err, "failed to create S3 session, make sure credentials are correct")
		//no secret we just return
		return nil
	}
	return sess
}

func BucketName(client client.Client, ctx context.Context, namespace string) (string, error) {
	jbsConfig := &v1alpha1.JBSConfig{}
	err := client.Get(ctx, types.NamespacedName{Namespace: namespace, Name: v1alpha1.JBSConfigName}, jbsConfig)
	if err != nil && !errors.IsNotFound(err) {
		return "", err
	} else if err != nil {
		return "", nil
	}
	bucketName := ""
	if jbsConfig.Annotations != nil {
		bucketName = jbsConfig.Annotations[S3BucketNameAnnotation]
	}
	return bucketName, nil
}
