package com.redhat.hacbs.container.analyser;

import com.redhat.hacbs.container.analyser.location.LookupScmLocationCommand;

import io.quarkus.picocli.runtime.annotations.TopCommand;
import picocli.CommandLine;

@TopCommand
@CommandLine.Command(mixinStandardHelpOptions = true, subcommands = { LookupScmLocationCommand.class
})
public class EntryPoint {
}
