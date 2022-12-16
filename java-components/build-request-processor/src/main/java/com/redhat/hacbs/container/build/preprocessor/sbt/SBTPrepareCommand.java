package com.redhat.hacbs.container.build.preprocessor.sbt;

import com.redhat.hacbs.container.build.preprocessor.AbstractPreprocessor;

import picocli.CommandLine;

/**
 * TODO: a noop for now
 */
@CommandLine.Command(name = "sbt-prepare")
public class SBTPrepareCommand extends AbstractPreprocessor {

    @Override
    public void run() {
    }
}
