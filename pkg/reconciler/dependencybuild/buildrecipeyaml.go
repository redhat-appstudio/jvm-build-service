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
    - build
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
            mvn versions:set -DnewVersion=$(params.ENFORCE_VERSION)
          fi

          # fix-permissions-for-builder
          chown 1001:1001 -R $(workspaces.source.path)
          #we can't use array parameters directly here
          #we pass them in as goals
          /usr/bin/mvn -s "$(workspaces.maven-settings.path)/settings.xml" $@ "-DaltDeploymentRepository=local::file:$(workspaces.source.path)/hacbs-jvm-deployment-repo" "org.apache.maven.plugins:maven-deploy-plugin:3.0.0-M2:deploy" || { cat $(workspaces.maven-settings.path)/sidecar.log ; false ; }
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
        imagePullPolicy: Always
        env:
          - name: QUARKUS_REST_CLIENT_CACHE_SERVICE_URL
            value: "http://hacbs-jvm-cache.jvm-build-service.svc.cluster.local"
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
    value: https://github.com/smallrye/smallrye-common.git
  - name: TAG
    value: 1.10.0
  - name: CONTEXT_DIR
    value: ""
  - name: IMAGE
    value: quay.io/sdouglas/hacbs-jdk11-builder:latest
  - name: GOALS
    value:
    - clean
    - install
    - -DskipTests
    - -Denforcer.skip
    - -Dcheckstyle.skip
    - -Drat.skip=true
  workspaces:
  - emptyDir: {}
    name: maven-settings
  - emptyDir: {}
    name: source
  taskSpec:
    workspaces:
      - name: source
        description: The workspace consisting of gradle project.
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
        type: string
        description: Build image
      - name: GOALS
        description: gradle goals to run
        type: array
        default:
          - build
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
          which we want to execute gradle goals.
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
      - name: gradle-goals
        image: $(params.IMAGE)
        workingDir: $(workspaces.source.path)/$(params.CONTEXT_DIR)
        args: [ "$(params.GOALS[*])" ]
        script: |
          #we can't use array parameters directly here
          #we pass them in as goals
          gradle $@
      - name: deploy-and-check-for-contaminates
        image: "registry.access.redhat.com/ubi8/ubi:8.5"
        script: |
          #fail here for now
          exit 1
          tar -czf $(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz -C $(workspaces.source.path)/hacbs-jvm-deployment-repo .
          curl --data-binary @$(workspaces.source.path)/hacbs-jvm-deployment-repo.tar.gz http://localhost:2000/deploy

          curl --fail http://localhost:2000/deploy/result -o $(results.contaminants.path)
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
        imagePullPolicy: Always
        env:
          - name: QUARKUS_REST_CLIENT_CACHE_SERVICE_URL
            value: "http://hacbs-jvm-cache.jvm-build-service.svc.cluster.local"
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
