#!/bin/sh -x

# From https://github.com/tektoncd/operator/blob/main/docs/TektonResult.md

export NAMESPACE="openshift-pipelines"

# Cleanup
kubectl -n ${NAMESPACE} delete --ignore-not-found services tekton-results-api-service tekton-results-postgres-service tekton-results-watcher
kubectl -n ${NAMESPACE} delete --ignore-not-found deployments.apps tekton-results-api tekton-results-watcher
kubectl -n ${NAMESPACE} delete --ignore-not-found secret tekton-results-postgres
kubectl -n ${NAMESPACE} delete --ignore-not-found tektonresults.operator.tekton.dev result
kubectl -n ${NAMESPACE} delete --ignore-not-found persistentvolumeclaims postgredb-tekton-results-postgres-0

# Generate new self-signed cert.
openssl req -x509 \
-newkey rsa:4096 \
-keyout /tmp/key.pem \
-out /tmp/cert.pem \
-days 365 \
-nodes \
-subj "/CN=tekton-results-api-service.${NAMESPACE}.svc.cluster.local" \
-addext "subjectAltName = DNS:tekton-results-api-service.${NAMESPACE}.svc.cluster.local"

# Secret
kubectl create secret generic tekton-results-postgres --namespace=${NAMESPACE} --from-literal=POSTGRES_USER=result --from-literal=POSTGRES_PASSWORD=$(openssl rand -base64 20)
# Create new TLS Secret from cert.
kubectl -n ${NAMESPACE} delete --ignore-not-found secret tekton-results-tls
kubectl create secret tls -n ${NAMESPACE} tekton-results-tls \
--cert=/tmp/cert.pem \
--key=/tmp/key.pem

kubectl apply -f pvc.yaml
# From https://github.com/tektoncd/operator/blob/main/config/crs/kubernetes/result/operator_v1alpha1_result_cr.yaml
kubectl apply -f tekton_operator_v1alpha1_result_cr.yaml
kubectl apply -f tekton_route.yaml
