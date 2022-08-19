### How to run e2e tests

In order to run e2e tests locally you need an OpenShift cluster with bootstrapped AppStudio.

Easiest way to boostrap AppStudio on a clean OpenShift cluster is via scripts located
in [e2e-tests GitHub repository](https://github.com/redhat-appstudio/e2e-tests/blob/main/README.md).

After the bootstrap is finished, you can run the e2e test with `make` (from the root of this repo):
```bash
make appstudio-installed-on-openshift-e2e
```
