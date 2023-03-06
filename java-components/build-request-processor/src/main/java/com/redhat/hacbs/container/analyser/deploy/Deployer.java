package com.redhat.hacbs.container.analyser.deploy;

import java.nio.file.Path;
import java.util.Set;

public interface Deployer {

    public void deployArchive(Path tarGzFile, Path sourcePath, Path logsPath, Set<String> gavs, String deploymentUid)
            throws Exception;

}
