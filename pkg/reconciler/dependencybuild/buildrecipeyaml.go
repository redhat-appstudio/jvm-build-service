package dependencybuild

var (
	// since taskspec does not implment runtime.Object we have to mimic the TaskRun created in dependencybuilds
	maven = `
apiVersion: tekton.dev/v1beta1
kind: TaskRun
metadata:
  name: run-maven-component-build
spec:
  params:
  - name: URL
    value: https://github.com/smallrye/smallrye-common.git
  - name: TAG
    value: 1.10.0
  - name: CONTEXT_DIR
    value: ""
  - name: IMAGE
    value: quay.io/sdouglas/hacbs-jdk11-builder:latest
  - name: GOALS
    value:
      - -DskipTests
      - clean
      - install
      - -Denforcer.skip
  - name: JAVA_VERSION
    value: ""
  - name: TOOL_VERSION
    value: ""
  workspaces:
  - emptyDir: {}
    name: maven-settings
  - emptyDir: {}
    name: source
  taskSpec:
    results:
      - name: contaminants
        description: Any community GAVs that have ended up in the final output.
    workspaces:
      - name: maven-settings
      - name: source
    params:
      - name: URL
        type: string
      - name: TAG
        type: string
      - name: IMAGE
        type: string
        description: Build image
      - name: GOALS
        description: maven goals to run
        type: array
        default:
          - -DskipTests
          - clean
          - install
          - -Denforcer.skip
      - name: JAVA_VERSION
        description: Java version.
        type: string
        default: ""
      - name: TOOL_VERSION
        description: Maven version.
        type: string
        default: ""
      - name: MAVEN_MIRROR_URL
        description: The Maven repository mirror url
        type: string
        default: http://localhost:2000/maven2
      - name: SERVER_USER
        description: The username for the server
        type: string
        default: ""
      - name: SERVER_PASSWORD
        description: The password for the server
        type: string
        default: ""
      - name: PROXY_USER
        description: The username for the proxy server
        type: string
        default: ""
      - name: PROXY_PASSWORD
        description: The password for the proxy server
        type: string
        default: ""
      - name: PROXY_PORT
        description: Port number for the proxy server
        type: string
        default: ""
      - name: PROXY_HOST
        description: Proxy server Host
        type: string
        default: ""
      - name: PROXY_NON_PROXY_HOSTS
        description: Non proxy server host
        type: string
        default: ""
      - name: PROXY_PROTOCOL
        description: Protocol for the proxy ie http or https
        type: string
        default: "http"
      - name: CONTEXT_DIR
        type: string
        description: >-
          The context directory within the repository for sources on
          which we want to execute maven goals.
        default: "."
      - name: ENFORCE_VERSION
        type: string
        description: >-
          Some builds are incorrectly tagged with a snapshot version, rather
          than the release version. If this is set then the version will be
          updated to the release version before the build.
        default: ""
      - name: IGNORED_ARTIFACTS
        type: string
        description: >-
          Comma seperated list of artifact names that should not be deployed or checked for contaminants.
        default: ""
    steps:
      - name: git-clone
        image: "gcr.io/tekton-releases/github.com/tektoncd/pipeline/cmd/git-init:v0.21.0"
        resources:
          requests:
            memory: "128Mi"
            cpu: "10m"
          limits:
            memory: "512Mi"
            cpu: "300m"
        args:
          - -path=$(workspaces.source.path)
          - -url=$(params.URL)
          - -revision=$(params.TAG)
      - name: settings
        image: "registry.access.redhat.com/ubi8/ubi:8.5"
        securityContext:
          runAsUser: 0
        resources:
          requests:
            memory: "128Mi"
            cpu: "10m"
          limits:
            memory: "512Mi"
            cpu: "300m"
        script: |
          #!/usr/bin/env bash

          # fix-permissions-for-builder
          chown 1001:1001 -R $(workspaces.source.path)

          # set maven
          [[ -f $(workspaces.maven-settings.path)/settings.xml ]] && \
          echo 'using existing $(workspaces.maven-settings.path)/settings.xml' && exit 0

          cat > $(workspaces.maven-settings.path)/settings.xml <<EOF
          <settings>
            <servers>
              <!-- The servers added here are generated from environment variables. Don't change. -->
              <!-- ### SERVER's USER INFO from ENV ### -->
            </servers>
            <mirrors>
              <!-- The mirrors added here are generated from environment variables. Don't change. -->
              <!-- ### mirrors from ENV ### -->
            </mirrors>
            <proxies>
              <!-- The proxies added here are generated from environment variables. Don't change. -->
              <!-- ### HTTP proxy from ENV ### -->
            </proxies>
          </settings>
          EOF

          xml=""
          if [ -n "$(params.PROXY_HOST)" -a -n "$(params.PROXY_PORT)" ]; then
            xml="<proxy>\
              <id>genproxy</id>\
              <active>true</active>\
              <protocol>$(params.PROXY_PROTOCOL)</protocol>\
              <host>$(params.PROXY_HOST)</host>\
              <port>$(params.PROXY_PORT)</port>"
            if [ -n "$(params.PROXY_USER)" -a -n "$(params.PROXY_PASSWORD)" ]; then
              xml="$xml\
                  <username>$(params.PROXY_USER)</username>\
                  <password>$(params.PROXY_PASSWORD)</password>"
            fi
            if [ -n "$(params.PROXY_NON_PROXY_HOSTS)" ]; then
              xml="$xml\
                  <nonProxyHosts>$(params.PROXY_NON_PROXY_HOSTS)</nonProxyHosts>"
            fi
            xml="$xml\
                </proxy>"
            sed -i "s|<!-- ### HTTP proxy from ENV ### -->|$xml|" $(workspaces.maven-settings.path)/settings.xml
          fi

          if [ -n "$(params.SERVER_USER)" -a -n "$(params.SERVER_PASSWORD)" ]; then
            xml="<server>\
              <id>serverid</id>"
            xml="$xml\
                  <username>$(params.SERVER_USER)</username>\
                  <password>$(params.SERVER_PASSWORD)</password>"
            xml="$xml\
                </server>"
            sed -i "s|<!-- ### SERVER's USER INFO from ENV ### -->|$xml|" $(workspaces.maven-settings.path)/settings.xml
          fi

          if [ -n "$(params.MAVEN_MIRROR_URL)" ]; then
            xml="    <mirror>\
              <id>mirror.default</id>\
              <url>$(params.MAVEN_MIRROR_URL)</url>\
              <mirrorOf>*</mirrorOf>\
            </mirror>"
            sed -i "s|<!-- ### mirrors from ENV ### -->|$xml|" $(workspaces.maven-settings.path)/settings.xml
          fi
      - name: mvn-goals
        image: $(params.IMAGE)
        securityContext:
          runAsUser: 0
        workingDir: $(workspaces.source.path)/$(params.CONTEXT_DIR)
        args: [ "$(params.GOALS[*])" ]
        script: |
          #!/usr/bin/env bash
          if [ -z "$(params.ENFORCE_VERSION)" ]
          then
            echo "Enforce version not set, skipping"
          else
            echo "Setting version to $(params.ENFORCE_VERSION)"
            mvn -s "$(workspaces.maven-settings.path)/settings.xml" versions:set -DnewVersion=$(params.ENFORCE_VERSION)
          fi

          # fix-permissions-for-builder
          chown 1001:1001 -R $(workspaces.source.path)
          #we can't use array parameters directly here
          #we pass them in as goals
          /usr/bin/mvn -e -s "$(workspaces.maven-settings.path)/settings.xml" $@ "-DaltDeploymentRepository=local::file:$(workspaces.source.path)/hacbs-jvm-deployment-repo" "org.apache.maven.plugins:maven-deploy-plugin:3.0.0-M2:deploy" || { cat $(workspaces.maven-settings.path)/sidecar.log ; false ; }
      - name: deploy-and-check-for-contaminates
        image: "registry.access.redhat.com/ubi8/ubi:8.5"
        securityContext:
          runAsUser: 0
        script: |
          tar -czf $(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz -C $(workspaces.source.path)/hacbs-jvm-deployment-repo .
          curl --data-binary @$(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz http://localhost:2000/deploy

          curl --fail http://localhost:2000/deploy/result -o $(results.contaminants.path) || { cat $(workspaces.maven-settings.path)/sidecar.log ; false ; }
          cat $(workspaces.maven-settings.path)/sidecar.log
        resources:
          requests:
            memory: "128Mi"
            cpu: "10m"
          limits:
            memory: "512Mi"
            cpu: "300m"
    sidecars:
      - image: hacbs-jvm-sidecar
        securityContext:
          runAsUser: 0
        env:
          - name: QUARKUS_LOG_FILE_ENABLE
            value: "true"
          - name: QUARKUS_LOG_FILE_PATH
            value: "$(workspaces.maven-settings.path)/sidecar.log"
          - name: IGNORED_ARTIFACTS
            value: $(params.IGNORED_ARTIFACTS)
          - name: QUARKUS_VERTX_EVENT_LOOPS_POOL_SIZE
            value: "2"
          - name: QUARKUS_THREAD_POOL_MAX_THREADS
            value: "4"
        name: proxy
        volumeMounts:
          - name: $(workspaces.maven-settings.volume)
            mountPath: $(workspaces.maven-settings.path)
        livenessProbe:
          httpGet:
            path: /q/health/live
            port: 2000
          initialDelaySeconds: 1
          periodSeconds: 3
        readinessProbe:
          httpGet:
            path: /q/health/ready
            port: 2000
          initialDelaySeconds: 1
          periodSeconds: 3
        resources:
          requests:
            memory: "128Mi"
            cpu: "10m"
          limits:
            memory: "8Gi"
            cpu: "2"
`

	// since taskspec does not implment runtime.Object we have to mimic the TaskRun created in dependencybuilds
	gradle = `
apiVersion: tekton.dev/v1beta1
kind: TaskRun
metadata:
  name: run-gradle-component-build
spec:
  params:
  - name: URL
    value: https://gitlab.ow2.org/asm/asm.git
  - name: TAG
    value: ASM_9_3
  - name: CONTEXT_DIR
    value: ""
  - name: IMAGE
    value: quay.io/dwalluck/gradle:latest
  - name: GOALS
    value:
    - build
    - publish
  - name: JAVA_VERSION
    value: ""
  - name: TOOL_VERSION
    value: ""
  workspaces:
  - emptyDir: {}
    name: maven-settings
  - emptyDir: {}
    name: source
  taskSpec:
    description: >-
      This Task can be used to run a Gradle build of a component that will be deployed to the sidecar.
    workspaces:
      - name: source
        description: The workspace consisting of the gradle project.
      - name: maven-settings
        description: >-
          The workspace consisting of the custom gradle settings
          provided by the user.
    results:
      - name: contaminants
        description: Any community GAVs that have ended up in the final output.
    params:
      - name: URL
        type: string
      - name: TAG
        type: string
      - name: IMAGE
        description: Gradle base image.
        type: string
        default: quay.io/dwalluck/gradle:1@sha256:sha256:2429dad0ceef471455f4b121521c9eb63972b4cd693b25f51383c68ffd3a13b5
      - name: GOALS
        description: 'The gradle tasks to run (default: build publish)'
        type: array
        default:
          - build
          - publish
      - name: JAVA_VERSION
        description: Java version.
        type: string
        default: ""
      - name: TOOL_VERSION
        description: Gradle version.
        type: string
        default: ""
      - name: GRADLE_MANIPULATOR_ARGS
        description: Gradle manipulator arguments.
        type: string
        default: "-DdependencySource=NONE -DignoreUnresolvableDependencies=true -DpluginRemoval=ALL -DversionModification=false"
      - name: MAVEN_MIRROR_URL
        description: The Maven repository mirror url
        type: string
        default: "http://localhost:2000/maven2"
      - name: SERVER_USER
        description: The username for the server
        type: string
        default: ""
      - name: SERVER_PASSWORD
        description: The password for the server
        type: string
        default: ""
      - name: PROXY_USER
        description: The username for the proxy server
        type: string
        default: ""
      - name: PROXY_PASSWORD
        description: The password for the proxy server
        type: string
        default: ""
      - name: PROXY_PORT
        description: Port number for the proxy server
        type: string
        default: ""
      - name: PROXY_HOST
        description: Proxy server Host
        type: string
        default: ""
      - name: PROXY_NON_PROXY_HOSTS
        description: Non proxy server host
        type: string
        default: ""
      - name: PROXY_PROTOCOL
        description: Protocol for the proxy ie http or https
        type: string
        default: "http"
      - name: CONTEXT_DIR
        description: The directory containing build.gradle
        type: string
        default: "."
      - name: ENFORCE_VERSION
        type: string
        description: >-
          Some builds are incorrectly tagged with a snapshot version, rather
          than the release version. If this is set then the version will be
          updated to the release version before the build.
        default: ""
      - name: IGNORED_ARTIFACTS
        type: string
        description: >-
          Comma-separated list of artifact names that should not be deployed or checked for contaminants.
        default: ""
    steps:
      - name: git-clone
        image: "gcr.io/tekton-releases/github.com/tektoncd/pipeline/cmd/git-init:v0.21.0"
        resources:
          requests:
            memory: "128Mi"
            cpu: "10m"
          limits:
            memory: "512Mi"
            cpu: "300m"
        args:
          - -path=$(workspaces.source.path)
          - -url=$(params.URL)
          - -revision=$(params.TAG)
      - name: maven-settings
        image: "registry.access.redhat.com/ubi8/ubi:8.5"
        securityContext:
          runAsUser: 0
        resources:
          requests:
            memory: "128Mi"
            cpu: "10m"
          limits:
            memory: "512Mi"
            cpu: "300m"
        env:
          - name: GRADLE_USER_HOME
            value: $(workspaces.maven-settings.path)/.gradle
        script: |
          #!/usr/bin/env bash

          mkdir -p ${GRADLE_USER_HOME}
          cat > ${GRADLE_USER_HOME}/gradle.properties << EOF
          org.gradle.caching=false
          # This prevents the daemon from running (which is unnecessary in one-off builds) and increases the memory allocation
          org.gradle.daemon=false
          # For Spring/Nebula Release Plugins
          release.useLastTag=true

          # Increase timeouts
          systemProp.org.gradle.internal.http.connectionTimeout=600000
          systemProp.org.gradle.internal.http.socketTimeout=600000
          systemProp.http.socketTimeout=600000
          systemProp.http.connectionTimeout=600000

          # Proxy settings <https://docs.gradle.org/current/userguide/build_environment.html#sec:accessing_the_web_via_a_proxy>
          systemProp.http.proxyHost=$(params.PROXY_HOST)
          systemProp.http.proxyPort=$(params.PROXY_PORT)
          systemProp.http.proxyUser=$(params.PROXY_USER)
          systemProp.http.proxyPassword=$(params.PROXY_PASSWORD)
          systemProp.http.nonProxyHosts=$(params.PROXY_NON_PROXY_HOSTS)
          EOF
          cat > ${GRADLE_USER_HOME}/init.gradle << EOF
          allprojects {
              buildscript {
                  repositories {
                      mavenLocal()
                      maven {
                          name "HACBS Maven Repository"
                          url "$(params.MAVEN_MIRROR_URL)"
                          credentials {
                              username "$(params.SERVER_USER)"
                              password "$(params.SERVER_PASSWORD)"
                          }
                          //allowInsecureProtocol = true
                      }
                      maven {
                          name "Gradle Central Plugin Repository"
                          url "https://plugins.gradle.org/m2/"
                      }
                  }
              }
              repositories {
                  mavenLocal()
                  maven {
                      name "HACBS Maven Repository"
                      url "$(params.MAVEN_MIRROR_URL)"
                      credentials {
                          username "$(params.SERVER_USER)"
                          password "$(params.SERVER_PASSWORD)"
                      }
                      //allowInsecureProtocol = true
                  }
                  maven {
                      name "Gradle Central Plugin Repository"
                      url "https://plugins.gradle.org/m2/"
                  }
              }
          }

          settingsEvaluated { settings ->
              settings.pluginManagement {
                  repositories {
                      mavenLocal()
                      maven {
                          name "HACBS Maven Repository"
                          url "$(params.MAVEN_MIRROR_URL)"
                          credentials {
                              username "$(params.SERVER_USER)"
                              password "$(params.SERVER_PASSWORD)"
                          }
                          //allowInsecureProtocol = true
                      }
                      maven {
                          name "Gradle Central Plugin Repository"
                          url "https://plugins.gradle.org/m2/"
                      }
                  }
              }
          }
          EOF

          # fix-permissions-for-builder
          chown 1001:1001 -R $(workspaces.source.path)
      - name: gradle-tasks
        image: $(params.IMAGE)
        securityContext:
          runAsUser: 0
        env:
          - name: GRADLE_USER_HOME
            value: $(workspaces.maven-settings.path)/.gradle
        workingDir: $(workspaces.source.path)/$(params.CONTEXT_DIR)
        args: [ "$(params.GOALS[*])" ]
        script: |
          #!/usr/bin/env bash

          echo "@=$@"

          if [ -z "$(params.JAVA_VERSION)" ]; then
              echo "JAVA_VERSION has not been set" >&2
              exit 1
          fi

          case "$(params.JAVA_VERSION)" in
              8)
                  export JAVA_HOME="/usr/lib/jvm/java-1.8.0-openjdk"
                  ;;
              *)
                  export JAVA_HOME="/usr/lib/jvm/java-$(params.JAVA_VERSION)-openjdk"
                  ;;
          esac

          echo "JAVA_HOME=${JAVA_HOME}"
          export PATH="${JAVA_HOME}/bin:${PATH}"

          if [ -z "$(params.TOOL_VERSION)" ]; then
              echo "TOOL_VERSION has not been set" >&2
              exit 1
          fi

          TOOL_VERSION="$(params.TOOL_VERSION)"
          export GRADLE_HOME="/opt/gradle-${TOOL_VERSION}"
          echo "GRADLE_HOME=${GRADLE_HOME}"
          export PATH="${GRADLE_HOME}/bin:${PATH}"
          case "${TOOL_VERSION}" in
              7.*)
                  sed -i -e 's|//allowInsecureProtocol|allowInsecureProtocol|g' ${GRADLE_USER_HOME}/init.gradle
                  ;;
          esac

          export LANG="en_US.UTF-8"
          export LC_ALL="en_US.UTF-8"

          # FIXME: additionalArgs is added to args, but we need additionalArgs only; assume that we know the original tasks so that we can remove them
          ADDITIONAL_ARGS=$(echo "$@" | sed 's/build publish \?//')
          echo ADDITIONAL_ARGS="${ADDITIONAL_ARGS}"

          if [ -n "$(params.ENFORCE_VERSION)" ]; then
              gradle-manipulator --info --stacktrace -l "${GRADLE_HOME}" $(params.GRADLE_MANIPULATOR_ARGS) -DversionOverride=$(params.ENFORCE_VERSION) "${ADDITIONAL_ARGS}" generateAlignmentMetadata || exit 1
          else
              gradle-manipulator --info --stacktrace -l "${GRADLE_HOME}" $(params.GRADLE_MANIPULATOR_ARGS) "${ADDITIONAL_ARGS}" generateAlignmentMetadata || exit 1
          fi

          gradle -DAProxDeployUrl=file:$(workspaces.source.path)/hacbs-jvm-deployment-repo --info --stacktrace "$@" || exit 1

          # fix-permissions-for-builder
          chown 1001:1001 -R $(workspaces.source.path)
      - name: deploy-and-check-for-contaminates
        image: "registry.access.redhat.com/ubi8/ubi:8.5"
        securityContext:
          runAsUser: 0
        script: |
          tar -cvvzf $(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz -C $(workspaces.source.path)/hacbs-jvm-deployment-repo .
          curl --verbose --data-binary @$(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz http://localhost:2000/deploy

          curl --verbose --fail http://localhost:2000/deploy/result -o $(results.contaminants.path) || cat $(workspaces.maven-settings.path)/sidecar.log
          cat $(workspaces.maven-settings.path)/sidecar.log
        resources:
          requests:
            memory: "128Mi"
            cpu: "10m"
          limits:
            memory: "512Mi"
            cpu: "300m"
    sidecars:
      - image: hacbs-jvm-sidecar
        securityContext:
          runAsUser: 0
        env:
          - name: QUARKUS_LOG_FILE_ENABLE
            value: "true"
          - name: QUARKUS_LOG_FILE_PATH
            value: "$(workspaces.maven-settings.path)/sidecar.log"
          - name: IGNORED_ARTIFACTS
            value: $(params.IGNORED_ARTIFACTS)
          - name: QUARKUS_VERTX_EVENT_LOOPS_POOL_SIZE
            value: "2"
          - name: QUARKUS_THREAD_POOL_MAX_THREADS
            value: "4"
        name: proxy
        volumeMounts:
          - name: $(workspaces.maven-settings.volume)
            mountPath: $(workspaces.maven-settings.path)
        livenessProbe:
          httpGet:
            path: /q/health/live
            port: 2000
          initialDelaySeconds: 1
          periodSeconds: 3
        readinessProbe:
          httpGet:
            path: /q/health/ready
            port: 2000
          initialDelaySeconds: 1
          periodSeconds: 3
        resources:
          requests:
            memory: "128Mi"
            cpu: "10m"
          limits:
            memory: "8Gi"
            cpu: "2"
`
)
