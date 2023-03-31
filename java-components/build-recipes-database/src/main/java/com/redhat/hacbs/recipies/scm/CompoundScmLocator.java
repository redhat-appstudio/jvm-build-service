package com.redhat.hacbs.recipies.scm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.jboss.logging.Logger;

import com.redhat.hacbs.recipies.GAV;

/**
 * Locator that delegates to others in order
 */
public class CompoundScmLocator implements ScmLocator {
    public static final Logger LOGGER = Logger.getLogger(CompoundScmLocator.class);
    final List<ScmLocator> scmLocators;

    public CompoundScmLocator(List<ScmLocator> scmLocators) {
        this.scmLocators = new ArrayList<>(scmLocators);
    }

    public CompoundScmLocator(ScmLocator... scmLocators) {
        this.scmLocators = Arrays.asList(scmLocators);
    }

    @Override
    public TagInfo resolveTagInfo(GAV toBuild) {
        for (var i : scmLocators) {
            try {
                var ret = i.resolveTagInfo(toBuild);
                if (ret != null) {
                    return ret;
                }
            } catch (Exception e) {
                LOGGER.warnf("Failed to resolve SCM information from %s", i);
            }
        }
        throw new RuntimeException("Unable to determine SCM repo");
    }
}
