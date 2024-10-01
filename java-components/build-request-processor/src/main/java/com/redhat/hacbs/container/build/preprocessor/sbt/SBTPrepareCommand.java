package com.redhat.hacbs.container.build.preprocessor.sbt;

import com.redhat.hacbs.container.build.preprocessor.AbstractPreprocessor;

import picocli.CommandLine;

/**
 * TODO: a noop for now
 */
@CommandLine.Command(name = "sbt-prepare")
public class SBTPrepareCommand extends AbstractPreprocessor {

    public SBTPrepareCommand() {
        type = ToolType.SBT;
    }

    @Override
    public void run() {
        super.run();
    }
}
