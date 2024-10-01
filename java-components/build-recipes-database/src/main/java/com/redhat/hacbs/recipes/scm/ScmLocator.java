package com.redhat.hacbs.recipes.scm;

import com.redhat.hacbs.common.maven.GAV;

public interface ScmLocator {

    TagInfo resolveTagInfo(GAV toBuild);
}
