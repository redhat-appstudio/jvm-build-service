package com.redhat.hacbs.container.analyser.sbom;

import java.util.Map;
import java.util.Set;

import org.cyclonedx.model.Component;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import com.redhat.hacbs.classfile.tracker.TrackingData;
import com.redhat.hacbs.container.analyser.dependencies.SBomGenerator;

public class SBomMergeTestCase {

    @Test
    public void testRemoveDuplicate() {
        var sbom = SBomGenerator.generateSBom(Set.of(), getClass().getClassLoader().getResourceAsStream("syft-sbom.json"));
        for (var c : sbom.getComponents()) {
            //make sure the duplicate was removed
            Assertions.assertNotEquals("commons-digester.commons-digester", c.getName());
            System.out.println(c.getName());
        }
        //make sure that the duplicate is removed from the file
        Assertions.assertEquals(4, sbom.getComponents().size());
    }

    @Test
    public void testSbomMerge() {
        var sbom = SBomGenerator.generateSBom(
                Set.of(
                        new TrackingData("commons-digester:commons-digester:2.1", "rebuilt", Map.of()),
                        new TrackingData("com.test:test:1.0", "central", Map.of())),
                getClass().getClassLoader().getResourceAsStream("syft-sbom.json"));
        Component test = null;
        Component digester = null;
        for (var c : sbom.getComponents()) {
            if (c.getName().equals("commons-digester")) {
                digester = c;
            } else if (c.getName().equals("test")) {
                test = c;
            }
            //make sure the duplicate was removed
            Assertions.assertNotEquals("commons-digester.commons-digester", c.getName());
            System.out.println(c.getName());
        }
        Assertions.assertEquals(5, sbom.getComponents().size());
        Assertions.assertNotNull(digester);
        Assertions.assertNotNull(test);
        Assertions.assertEquals("rebuilt", digester.getPublisher());
        Assertions.assertNotNull(digester.getBomRef());
        Assertions.assertEquals("central", test.getPublisher());

    }
}
