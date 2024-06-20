package dependencybuild

import (
	. "github.com/onsi/gomega"
	tektonpipeline "github.com/tektoncd/pipeline/pkg/apis/pipeline/v1"
	"testing"
)

func TestImageRegistryArrayToString(t *testing.T) {
	g := NewGomegaWithT(t)
	prependTag := "123456"
	imageId := "quay.io:993/foo/artifact-deployments:975ea3800099190263d38f051c1a188a-pre-build-image"
	imageId = prependTagToImage(imageId, prependTag)
	g.Expect(imageId).To(Equal("quay.io:993/foo/artifact-deployments:123456_975ea3800099190263d38f051c1a188a-pre-build-image"))
	imageId = "quay.io/foo/artifact-deployments:975ea3800099190263d38f051c1a188a-pre-build-image"
	imageId = prependTagToImage(imageId, prependTag)
	g.Expect(imageId).To(Equal("quay.io/foo/artifact-deployments:123456_975ea3800099190263d38f051c1a188a-pre-build-image"))
	imageId = "quay.io/foobar-repository/jvm-build-mxlq-tenant/jvm-build-service-artifacts/artifact-deployments:975ea3800099190263d38f051c1a188a-pre-build-image"
	imageId = prependTagToImage(imageId, prependTag)
	g.Expect(imageId).To(Equal("quay.io/foobar-repository/jvm-build-mxlq-tenant/jvm-build-service-artifacts/artifact-deployments:123456_975ea3800099190263d38f051c1a188a-pre-build-image"))
	imageId = "quay.io/foo/artifact-deployments:975ea3800099190263d38f051c1a188a975ea3800099190263d38f051c1a188a975ea3800099190263d38f051c1a188a975ea3800099190263d38f051c1a188a"
	imageId = prependTagToImage(imageId, prependTag)
	g.Expect(imageId).To(Equal("quay.io/foo/artifact-deployments:123456_975ea3800099190263d38f051c1a188a975ea3800099190263d38f051c1a188a975ea3800099190263d38f051c1a188a975ea3800099190263d38f051"))
	imageId = prependTagToImage("65ad275dfafd4c607186d4e99e793cdf-pre-build-image", prependTag)
	g.Expect(imageId).To(Equal("123456_65ad275dfafd4c607186d4e99e793cdf-pre-build-image"))
}

func TestExtractArrayParam(t *testing.T) {
	g := NewGomegaWithT(t)
	goals := []string{"Foo", "-Pversion=$(PROJECT_VERSION)"}
	paramValues := []tektonpipeline.Param{
		{
			Name:  PipelineParamGoals,
			Value: tektonpipeline.ParamValue{Type: tektonpipeline.ParamTypeArray, ArrayVal: goals},
		},
	}
	result := extractArrayParam(PipelineParamGoals, paramValues)
	g.Expect(result).Should(Equal("Foo -Pversion=$PROJECT_VERSION "))
}
