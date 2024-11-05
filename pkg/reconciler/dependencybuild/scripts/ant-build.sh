echo "Running $(which ant) with arguments: $@"
eval "ant -debug -autoproxy $@" | tee ${JBS_WORKDIR}/logs/ant.log
