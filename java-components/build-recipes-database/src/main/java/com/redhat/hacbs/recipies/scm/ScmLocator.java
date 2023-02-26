package com.redhat.hacbs.recipies.scm;

import com.redhat.hacbs.recipies.GAV;

public interface ScmLocator {

    TagInfo resolveTagInfo(GAV toBuild);
}
