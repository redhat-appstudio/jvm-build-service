package com.redhat.hacbs.container.analyser.deploy;

import java.nio.file.Path;

public interface Deployer {

    public void deployArchive(Path tarGzFile, Path sourcePath, Path logsPath) throws Exception;

}
