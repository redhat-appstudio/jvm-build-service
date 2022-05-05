package com.redhat.hacbs.sidecar.resources;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Singleton;
import javax.ws.rs.POST;
import javax.ws.rs.Path;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import com.redhat.hacbs.classfile.tracker.ClassFileTracker;

import io.quarkus.logging.Log;
import io.smallrye.common.annotation.Blocking;

@Path("/deploy")
@Blocking
@Singleton
public class DeployResource {

    @POST
    public void deployArchive(InputStream data) throws Exception {
        Set<String> contaminants = new HashSet<>();
        try (TarArchiveInputStream in = new TarArchiveInputStream(new GzipCompressorInputStream(data))) {
            TarArchiveEntry e;
            while ((e = in.getNextTarEntry()) != null) {
                Log.infof("Received %s", e.getName());
                if (e.getName().endsWith(".class")) {
                    var info = ClassFileTracker.readTrackingInformationFromClass(in.readAllBytes());
                    if (info != null) {
                        contaminants.add(info.gav);
                    }
                }
            }
        }
        Log.infof("Contaminants: %s", contaminants);
    }
}
