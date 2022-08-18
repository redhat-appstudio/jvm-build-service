### How to run e2e tests

In order to run e2e tests locally you need an OpenShift cluster with bootstrapped AppStudio.

Easiest way to boostrap AppStudio on a clean OpenShift cluster is via scripts located
in [e2e-tests GitHub repository](https://github.com/redhat-appstudio/e2e-tests/blob/main/README.md).

Since the e2e tests located in this repo are quite different compared to e2e in e2e-tests repo,
you won't need to export all environment variables that are mentioned in [the guide](https://github.com/redhat-appstudio/e2e-tests/blob/main/README.md#install-appstudio-in-e2e-mode).

Required env vars are:
`GITHUB_TOKEN`, `QUAY_TOKEN`, `GITHUB_E2E_ORGANIZATION`, `QUAY_E2E_ORGANIZATION`, `DEFAULT_BUILD_BUNDLE`

`DEFAULT_BUILD_BUNDLE` should point to the HACBS pipelines bundle located in the container registry.
If you don't have a specific bundle you want to test, you can use the latest version:
```bash
export DEFAULT_BUILD_BUNDLE=quay.io/redhat-appstudio/hacbs-templates-bundle:latest
```

See more information about the rest of the env vars in [the table](https://github.com/redhat-appstudio/e2e-tests/blob/main/README.md#install-appstudio-in-e2e-mode).

Note: `GITHUB_E2E_ORGANIZATION` needs to be a name of your GitHub organization. A regular GitHub account (username) does not work. You can [easily create your own GitHub org for free](https://docs.github.com/en/organizations/collaborating-with-groups-in-organizations/creating-a-new-organization-from-scratch)

After the bootstrap is finished, you can run the e2e test with `make` (from the root of this repo):
```bash
make appstudio-installed-on-openshift-e2e
```
