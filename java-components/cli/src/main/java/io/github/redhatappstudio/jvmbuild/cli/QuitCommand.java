package io.github.redhatappstudio.jvmbuild.cli;

import javax.inject.Inject;

import picocli.CommandLine;

@CommandLine.Command(name = "quit", aliases = "q")
public class QuitCommand implements Runnable {

    @Inject
    Main main;

    @Override
    public void run() {
        main.shutdown();
    }
}
