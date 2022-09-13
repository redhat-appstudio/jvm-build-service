package com.redhat.hacbs.artifactcache.deploy;

import java.nio.file.Path;

public interface Deployer {

    public void deployArchive(Path tarGzFile) throws Exception;

}
