This module contains the build recipes database.

This database is one or more git repositories that contain information on how to build projects, including
information like SCM locations and build parameters.

Note that this format is extensible, additional yaml files can be added as required with additional
information if required. This module provides a way of mapping an artifact identified by a GAV to
a set of YAML files that describe the build. The contents of these YAML files are handled elsewhere.

## Directory and File Layout

This section describes the layout of the git repo. The rules used to select particular files from this
layout are covered in the next section.

Each database is a git repository with the recipes stored in a `/recipes` folder. The layout of this folder
is based on the group id of the artifact. An artifact with the group id of `com.acme` would have its 
information stored in the `/recipes/com/acme` folder.

Within this group id folder it is also possible to store more specific information that is only applied to a
specific artifact, version or artifact + version combination. This is done using the `_artifact` and `_version` 
folders. Examples of this are shown below:

Files specific to the `test-acme` artifact: `/recipes/com/acme/_artifact/test-acme`.
Files specific to the `1.0-redhat1` artifact: `/recipes/com/acme/_version/1.0-redhat1`.
Files specific to `test-acme` with version `1.0-redhat1`: `/recipes/com/acme/_artifact/test-acme_version/1.0-redhat1`.

Note that when using both `_artifact` and `_version` the artifact must come first.

### Redirects

It is possible to use a redirect to point to a different artifacts metadata. There are a few possible use cases
for this:

* A projects group id has changed however all the build metadata is still the same.
* A multi-module project may want to redirect every child module to the parent, so there is only one set of build metadata.

WARNING: At present redirects only affect the current repo, if you have multiple repos be careful of redirects when overriding
build information.

To perform a redirect add a `redirect.yaml` to one of the directories specified above. It should be the only yaml file
present, as other files will be ignored. This file can contain the following keys: `group-id`, `artifact-id`, `version`.

When this is found a new attempt to resolve metadata is performed with the relevant values updated according to the redirect.

Redirects are applied in a hierarchical manner, so if you provide a group level redirect then everything else will be resolved
from the redirected group id.

Some examples are shown below:

If a project has renamed its group id from `org.jboss.acme` to `org.acme` then in `org/jboss/acme/redirect.yaml` we can
put `group-id: org.acme`, and all requests for `or.jboss.acme` information will be redirected to the new group id.

If we have `acme-parent` and `acme-core` artifacts, and we want to redirect any requests for `acme-core` to the parent
we can create a `org/acme/_artifact/acme-core/redirect.yaml` file and add `artifact-id: acme-parent` to redirect to the
parent. Note that most of the time this is not necessary, as you would store this information at the group level, this is
only required if you have multiple different projects that share the same group id.

## Location Priority

Locations are resolved from most specific file to least specific file, and for each file this is resolved from the highest
priority repository to the lowest priority.

File priority is as follows:

1. Artifact and Version specified
2. Artifact specified
3. Version specified
4. Group id

When resolving yaml files the database will look for a match with both `_version` and `_artifact` specified in all repositories,
from the highest priority to lowest. If this is not found then it continues onto the next one on the list.

Note that this is applied for each requested file. If you request both `scm.yaml` and `build.yaml` and there is a group
specific `build.yaml` and a version specific `scm.yaml` then it will return the group level build file and the version
specific scm file.



