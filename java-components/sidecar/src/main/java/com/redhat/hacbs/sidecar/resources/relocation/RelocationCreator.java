package com.redhat.hacbs.sidecar.resources.relocation;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class RelocationCreator {

    private static final String TEMPLATE = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>${fromGroupId}</groupId>
                <artifactId>${fromArtifactId}</artifactId>
                <version>${fromVersion}</version>
                <distributionManagement>
                    <relocation>
                        <groupId>${toGroupId}</groupId>
                        <artifactId>${toArtifactId}</artifactId>
                        <version>${toVersion}</version>
                        <message>Relocated by the HACBS System</message>
                    </relocation>
                </distributionManagement>
            </project>""";

    public byte[] create(Gav from, Gav to) {
        String pom = TEMPLATE.replace("${fromGroupId}", from.getGroupId())
                .replace("${fromArtifactId}", from.getArtifactId())
                .replace("${fromVersion}", from.getVersion())
                .replace("${toGroupId}", to.getGroupId())
                .replace("${toArtifactId}", to.getArtifactId())
                .replace("${toVersion}", to.getVersion());
        return pom.getBytes();
    }
}