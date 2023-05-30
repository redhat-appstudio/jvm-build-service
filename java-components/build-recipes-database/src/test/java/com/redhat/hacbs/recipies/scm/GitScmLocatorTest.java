package com.redhat.hacbs.recipies.scm;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class GitScmLocatorTest {

    //test tag mapping heuristics
    @Test
    void runTagHeuristic() {
        runPassingTest("1.0", "1.0", "1.0", "1.0.Alpha1", "1.0.1");
        runPassingTest("1.0", "v1.0", "v1.0", "1.0.Alpha1", "1.0.1");
        runPassingTest("1.0", "v1.0", "v1.0", "1.0.0", "1.0.1");
        runPassingTest("1.0", "v1.0", "v1.0", "1.0.0", "1.0.1");
        runPassingTest("4.9.3", "4.9.3", "antlr4-master-4.9.3", "4.9.3-rc1", "4.9.3");
        runPassingTest("1.0.Final", "1.0", "1.0", "1.1");
        runPassingTest("1.0.Final", "1.0", "1.0", "1.0.a1");
        runFailingTest("1.0", "1.0.Beta1", "1.0.Alpha1");
        runFailingTest("1.0", "1.0.Final", "1.0.Alpha1");
    }

    void runPassingTest(String version, String expected, String... tags) {
        Map<String, String> tagMap = new HashMap<>();
        Arrays.stream(tags).forEach(a -> tagMap.put(a, ""));
        Assertions.assertEquals(expected, GitScmLocator.runTagHeuristic(version, tagMap));
    }

    void runFailingTest(String version, String... tags) {
        Map<String, String> tagMap = new HashMap<>();
        Arrays.stream(tags).forEach(a -> tagMap.put(a, ""));
        Assertions.assertThrows(RuntimeException.class, () -> {
            GitScmLocator.runTagHeuristic(version, tagMap);
        });
    }
}
