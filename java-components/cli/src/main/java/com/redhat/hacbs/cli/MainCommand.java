package com.redhat.hacbs.cli;

import java.io.PrintWriter;

import jakarta.enterprise.inject.Vetoed;

import com.redhat.hacbs.cli.artifacts.ArtifactCommand;
import com.redhat.hacbs.cli.builds.BuildCommand;
import com.redhat.hacbs.cli.driver.DriverCommand;
import com.redhat.hacbs.cli.rebuilt.RebuiltCommand;
import com.redhat.hacbs.cli.settings.SetupCommand;

import jline.console.ConsoleReader;
import picocli.CommandLine;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@CommandLine.Command(name = "jbs", mixinStandardHelpOptions = true, subcommands = {
        QuitCommand.class,
        BuildCommand.class,
        ArtifactCommand.class,
        RebuiltCommand.class,
        SetupCommand.class,
        DiagnosticCommand.class,
        DriverCommand.class
})
@Vetoed
public class MainCommand {
    final ConsoleReader reader;
    final PrintWriter out;

    @Spec
    private CommandSpec spec;

    MainCommand(ConsoleReader reader) {
        this.reader = reader;
        out = new PrintWriter(reader.getOutput());
    }

    public void run() {
        out.println(spec.commandLine().getUsageMessage());
    }

}
