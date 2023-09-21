package com.redhat.hacbs.analyser;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.analyser.maven.MavenAnalyser;
import com.redhat.hacbs.analyser.maven.MavenModule;

public class AnalysisTest {


    @Test
    public void testLayoutAnalysis() {
        Path currentPom = Paths.get("pom.xml").toAbsolutePath();
        Path projectRoot = currentPom.getParent().getParent();
        var result = MavenAnalyser.doProjectDiscovery(projectRoot);
        MavenModule thisProject = null;
        for (var entry : result.getProjects().entrySet()) {
            if (entry.getKey().getArtifactId().equals("hacbs-adhoc-analyser-tool")) {
                thisProject = entry.getValue();
            }
        }
        Assertions.assertNotNull(thisProject);
        Assertions.assertEquals(currentPom, thisProject.getPomFile());
    }
}
