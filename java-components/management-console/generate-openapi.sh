#!/usr/bin/env bash
cd src/main/webui
npx openapi -i http://127.0.0.1:8080/q/openapi\?format=json -o src/services/openapi
