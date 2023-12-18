#!/usr/bin/env bash
echo "Username: $(kubectl get secret jbs-user-secret -o json | jq -r .data.username | base64 -d)"
echo "Password: $(kubectl get secret jbs-user-secret -o json | jq -r .data.password | base64 -d)"
echo ""
