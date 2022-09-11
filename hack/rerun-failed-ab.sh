#!/bin/sh
list=""
rsrcs=$(kubectl  get -o json artifactbuild |jq '.items[].metadata.name'|sed "s/\"//g")
for r in ${rsrcs};do
  state=$(kubectl  get -o yaml artifactbuild ${r} | yq '.status.state')
  if [ $state = "ArtifactBuildFailed" ]; then
      list="$list $r"
  fi
done
kubectl annotate artifactbuilds $list  jvmbuildservice.io/rebuild=true
