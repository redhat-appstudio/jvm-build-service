echo "Running $(which ant) with arguments: $@"
eval "ant $@" | tee ${JBS_WORKDIR}/logs/ant.log
