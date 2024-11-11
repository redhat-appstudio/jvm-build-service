if [ -n "${ENFORCE_VERSION}" ]; then
  echo "Setting version to ${PROJECT_VERSION} to match enforced version"
  mvn -B -e org.codehaus.mojo:versions-maven-plugin:2.8.1:set -DnewVersion="${PROJECT_VERSION}" | tee ${JBS_WORKDIR}/logs/enforce-version.log
fi

echo "Running Maven command with arguments: $@"
mvn -X -V -B -e -Dhttp.keepAlive=false -Dmaven.wagon.http.pool=false -Dmaven.wagon.httpconnectionManager.ttlSeconds=25 -Dmaven.wagon.http.retryHandler.count=3 -Daether.connector.http.connectionMaxTtl=25 "$@" | tee ${JBS_WORKDIR}/logs/maven.log
