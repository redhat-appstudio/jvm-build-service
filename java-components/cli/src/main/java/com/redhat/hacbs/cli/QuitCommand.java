package com.redhat.hacbs.cli;

import jakarta.inject.Inject;

import picocli.CommandLine;

@CommandLine.Command(name = "quit", aliases = { "q", "exit" })
public class QuitCommand implements Runnable {

    @Inject
    Main main;

    @Override
    public void run() {
        main.shutdown();
    }
}
