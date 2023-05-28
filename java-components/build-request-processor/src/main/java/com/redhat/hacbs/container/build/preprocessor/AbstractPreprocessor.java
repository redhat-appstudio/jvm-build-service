package com.redhat.hacbs.container.build.preprocessor;

import java.nio.file.Path;

import picocli.CommandLine;

/**
 * We keep all the options the same between maven and gradle for now,
 * to keep the pipeline setup simpler.
 *
 * Some of these may be ignored by different processors
 */
public abstract class AbstractPreprocessor implements Runnable {

    @CommandLine.Parameters(description = "The directory to process")
    protected Path buildRoot;

    @CommandLine.Option(names = "--run-Tests", required = false, description = "If the tests should be run")
    protected boolean runTests;
}
