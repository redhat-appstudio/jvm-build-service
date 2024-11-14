#!/bin/bash

DIR="$( cd "$( dirname "$0" )" && pwd )"

export PROXY_TARGET_WHITELIST=localhost

server/target/domain-proxy-server-999-SNAPSHOT-runner &
server_pid=$!

rm build-script.sh

  # Without expansion
  cat >> build-script.sh << 'EOF'
#!/bin/sh
ip link set lo up
client/target/domain-proxy-client-999-SNAPSHOT-runner &
client_pid=$!
for ((i=1; i<=10000; i++)); do
    echo "request #$i"
    curl -v -x http://localhost:8080 http://localhost:2121/v2/cache/rebuild-central/1658389751000/org/sonatype/oss/oss-parent/7/oss-parent-7.pom
    sleep 0.1
done
EOF

  # Without expansion
  cat >> build-script.sh << 'EOF'
set +e
kill $client_pid
wait $client_pid
set -e
EOF

cat build-script.sh
chmod +x build-script.sh

unshare -Uf --net --keep-caps -r -- $DIR/build-script.sh
#$DIR/build-script.sh

set +e
kill $server_pid
wait $server_pid
set -e

rm build-script.sh
