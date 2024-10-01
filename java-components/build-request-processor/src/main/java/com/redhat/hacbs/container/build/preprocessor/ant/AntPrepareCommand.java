package com.redhat.hacbs.container.build.preprocessor.ant;

import com.redhat.hacbs.container.build.preprocessor.AbstractPreprocessor;

import picocli.CommandLine;

/**
 * A simple preprocessor that attempts to fix problematic Ant build files.
 * <p>
 * At present this is a no-op.
 */
@CommandLine.Command(name = "ant-prepare")
public class AntPrepareCommand extends AbstractPreprocessor {

    public AntPrepareCommand() {
        type = ToolType.ANT;
    }

    @Override
    public void run() {
        super.run();
    }
}
