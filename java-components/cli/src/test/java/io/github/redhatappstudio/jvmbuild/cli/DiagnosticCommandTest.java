package io.github.redhatappstudio.jvmbuild.cli;

import static com.github.stefanbirkner.systemlambda.SystemLambda.tapSystemOut;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.Test;

public class DiagnosticCommandTest {
    @Test
    public void testTrackingData() throws Exception {
        Path thisClass = Paths.get(getClass().getResource(getClass().getSimpleName() + ".class").toURI());
        Path setHelperClass = Paths.get(getClass().getClassLoader().getResource("Icon.class").toURI());

        DiagnosticCommand dc = new DiagnosticCommand();
        String out = tapSystemOut(() -> dc.classdump(thisClass));
        assertTrue(out.contains("No tracking data found"));
        out = tapSystemOut(() -> dc.classdump(setHelperClass));
        assertTrue(out.contains(
                "TrackingData{gav='org.jboss.metadata:jboss-metadata-common:9.0.0.Final', source='central', attributes={}}"));
    }
}
