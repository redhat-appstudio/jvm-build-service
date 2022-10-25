package e2e

const (
	testNamespace          = "jvm-build-service-test-namespace-"
	maxNameLength          = 63
	randomLength           = 5
	maxGeneratedNameLength = maxNameLength - randomLength
	gitCloneTaskUrl        = "https://raw.githubusercontent.com/redhat-appstudio/build-definitions/main/task/git-clone/0.1/git-clone.yaml"
)
