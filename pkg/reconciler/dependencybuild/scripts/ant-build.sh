echo "Running $(which ant) with arguments: $@"
eval "ant -autoproxy $@" | tee ${JBS_WORKDIR}/logs/ant.log
