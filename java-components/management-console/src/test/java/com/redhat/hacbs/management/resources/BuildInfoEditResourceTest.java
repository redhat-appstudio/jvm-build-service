package com.redhat.hacbs.management.resources;

import static org.junit.jupiter.api.Assertions.*;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BuildInfoEditResourceTest {

    public static final String FAILURE = "-:narayana-jts-idlj-7.0.1.Final.jar:class:com/arjuna/ats/arjuna/tools/stats/TxPerfGraph";

    @Test
    void failureToPattern() {
        String pattern = BuildInfoEditResource.failureToPattern(FAILURE);
        Assertions.assertEquals("-:narayana-jts-idlj-.*?.jar:class:com/arjuna/ats/arjuna/tools/stats/TxPerfGraph",
                pattern);
        Assertions.assertTrue(Pattern.compile(pattern).matcher(FAILURE).matches());
    }
}
