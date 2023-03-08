package io.github.redhatappstudio.jvmbuild.cli;

import io.quarkus.arc.Arc;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import jline.TerminalFactory;
import jline.console.ConsoleReader;
import jline.console.completer.ArgumentCompleter;
import jline.internal.Configuration;
import picocli.CommandLine;
import picocli.shell.jline2.PicocliJLineCompleter;

@QuarkusMain
public class Main implements QuarkusApplication {

    private volatile boolean shutdown;

    public void shutdown() {
        shutdown = true;
    }

    @Override
    public int run(String... args) throws Exception {
        ConsoleReader reader = new ConsoleReader();
        CommandLine.IFactory factory = new CustomFactory(new InteractiveParameterConsumer(reader));
        MainCommand commands = new MainCommand(reader);
        CommandLine cmd = new CommandLine(commands, factory);
        if (args.length > 0) {
            return new CommandLine(commands, factory)
                    .execute(args);
        }
        // JLine 2 does not detect some terminal as not ANSI compatible (e.g  Eclipse Console)
        // See : https://github.com/jline/jline2/issues/185
        // This is an optional workaround which allow to use picocli heuristic instead :
        if (!CommandLine.Help.Ansi.AUTO.enabled() && //
                Configuration.getString(TerminalFactory.JLINE_TERMINAL, TerminalFactory.AUTO).toLowerCase()
                        .equals(TerminalFactory.AUTO)) {
            TerminalFactory.configure(TerminalFactory.Type.NONE);
        }

        var requestContext = Arc.container().requestContext();
        try {

            requestContext.activate();
            // set up the completion
            reader.addCompleter(new PicocliJLineCompleter(cmd.getCommandSpec()));

            // start the shell and process input until the user quits with Ctrl-D
            String line;
            while (!shutdown && (line = reader.readLine("jbs> ")) != null) {
                ArgumentCompleter.ArgumentList list = new ArgumentCompleter.WhitespaceArgumentDelimiter()
                        .delimit(line, line.length());
                new CommandLine(commands, factory)
                        .execute(list.getArguments());
                requestContext.terminate();
                requestContext.activate();
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            requestContext.terminate();
        }
        return 0;
    }
}
