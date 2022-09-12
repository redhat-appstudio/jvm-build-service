#!/bin/sh

dir="$(dirname $0)/service-registry-artifacts"
echo $dir
rm -r "${dir}"
mkdir -p "${dir}"
rsrcs=$(kubectl  get -o json artifactbuild |jq '.items[].metadata.name'|sed "s/\"//g")
for r in ${rsrcs};do
  target=$dir/$(kubectl  get -o yaml artifactbuild ${r} | yq '.status.state')
  mkdir -p "${target}"
  kubectl get -o yaml artifactbuild  ${r} |  yq 'del(.status, .metadata.creationTimestamp, .metadata.generation, .metadata.resourceVersion, .metadata.uid)' > "${target}/${r}.yaml"
done
