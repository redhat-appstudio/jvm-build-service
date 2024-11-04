echo "Running $(which ant) with arguments: $@"
eval "ant -Dhttp.proxyHost=localhost -Dhttp.proxyPort=8080 $@" | tee ${JBS_WORKDIR}/logs/ant.log
