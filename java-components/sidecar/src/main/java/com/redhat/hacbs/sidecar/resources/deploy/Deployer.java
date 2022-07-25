package com.redhat.hacbs.sidecar.resources.deploy;

import java.nio.file.Path;

public interface Deployer {

    public void deployArchive(Path tarGzFile) throws Exception;

}
