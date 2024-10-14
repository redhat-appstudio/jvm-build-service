if [ ! -z ${ENFORCE_VERSION+x} ]; then
  echo "Setting version to ${PROJECT_VERSION} to match enforced version"
  mvn -B -e org.codehaus.mojo:versions-maven-plugin:2.8.1:set -DnewVersion="${PROJECT_VERSION}" | tee ${JBS_WORKDIR}/logs/enforce-version.log
fi

echo "Running Maven command with arguments: $@"

mvn -V -B -e "$@" | tee ${JBS_WORKDIR}/logs/maven.log
