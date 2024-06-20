package com.redhat.hacbs.recipes.scm;

import com.redhat.hacbs.resources.model.maven.GAV;

public interface ScmLocator {

    TagInfo resolveTagInfo(GAV toBuild);
}
