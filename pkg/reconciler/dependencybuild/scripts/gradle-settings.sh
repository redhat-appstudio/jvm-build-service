#!/usr/bin/env bash
set -eu

GRADLE_USER_HOME="$(workspaces.build-settings.path)/.gradle"
export GRADLE_USER_HOME
mkdir -p "${GRADLE_USER_HOME}"
cat > "${GRADLE_USER_HOME}"/gradle.properties << EOF
org.gradle.caching=false
org.gradle.console=plain
# This prevents the daemon from running (which is unnecessary in one-off builds) and increases the memory allocation
org.gradle.daemon=false
# For Spring/Nebula Release Plugins
release.useLastTag=true

# Increase timeouts
systemProp.org.gradle.internal.http.connectionTimeout=600000
systemProp.org.gradle.internal.http.socketTimeout=600000
systemProp.http.socketTimeout=600000
systemProp.http.connectionTimeout=600000

EOF
cat > "${GRADLE_USER_HOME}"/init.gradle << EOF
allprojects {
    buildscript {
        repositories {
            mavenLocal()
            maven {
                name "HACBS Maven Repository"
                url "$(params.CACHE_URL)"
                //allowInsecureProtocol = true
            }
        }
    }
    repositories {
        mavenLocal()
        maven {
            name "HACBS Maven Repository"
            url "$(params.CACHE_URL)"
            //allowInsecureProtocol = true
        }
    }
}

settingsEvaluated { settings ->
    settings.pluginManagement {
        repositories {
            mavenLocal()
            maven {
                name "HACBS Maven Repository"
                url "$(params.CACHE_URL)"
                //allowInsecureProtocol = true
            }
        }
    }
}
EOF

# fix-permissions-for-builder
chown 1001:1001 -R "$(workspaces.source.path)"
