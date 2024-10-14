# Only add the Ivy Typesafe repo for SBT versions less than 1.0 which aren't found in Central. This
# is only for SBT build infrastructure.
if [ -f project/build.properties ]; then
    function ver { printf "%03d%03d%03d%03d" $(echo "$1" | tr '.' ' '); }
    if [ -n "$(cat project/build.properties | grep sbt.version)" ] && [ $(ver `cat project/build.properties | grep sbt.version | sed -e 's/.*=//'`) -lt $(ver 1.0) ]; then
        cat >> "$HOME/.sbt/repositories" <<EOF
  ivy:  https://repo.typesafe.com/typesafe/ivy-releases/, [organization]/[module]/(scala_[scalaVersion]/)(sbt_[sbtVersion]/)[revision]/[type]s/[artifact](-[classifier]).[ext]
EOF
        mkdir "$HOME/.sbt/0.13/"
        cat >"$HOME/.sbt/0.13/global.sbt" <<EOF
publishTo := Some(Resolver.file("file", new File("$(workspaces.source.path)/artifacts")))
EOF
    fi
fi

echo "Running SBT command with arguments: $@"

eval "sbt $@" | tee ${JBS_WORKDIR}/logs/sbt.log
