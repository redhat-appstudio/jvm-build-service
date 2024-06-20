package com.redhat.hacbs.cli.builds;

import java.util.TreeMap;

import com.redhat.hacbs.common.tools.completer.BuildCompleter;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import picocli.CommandLine;

@CommandLine.Command(name = "list", mixinStandardHelpOptions = true, description = "Lists the builds")
public class BuildListCommand implements Runnable {

    @CommandLine.Option(names = "--failed", description = "List only failed")
    boolean failed;
    @CommandLine.Option(names = "--building", description = "List only building")
    boolean building;

    @Override
    public void run() {
        if (building && failed) {
            throw new RuntimeException("Cannot specify both --building and --failed");
        }
        var builds = new TreeMap<>(BuildCompleter.createNames());
        var it = builds.entrySet().iterator();
        int nameLongest = 0;
        int stateLongest = 0;
        while (it.hasNext()) {
            var e = it.next();
            String state = e.getValue().getStatus().getState();
            boolean buildFailed = state.equals(ModelConstants.DEPENDENCY_BUILD_FAILED)
                    || state.equals(ModelConstants.DEPENDENCY_BUILD_CONTAMINATED);
            if (failed && !buildFailed) {
                it.remove();
            } else if (building && (buildFailed || state.equals(ModelConstants.DEPENDENCY_BUILD_COMPLETE))) {
                it.remove();
            } else {
                nameLongest = Math.max(nameLongest, e.getKey().length());
                stateLongest = Math.max(stateLongest, state.length());
            }
        }
        for (var i : builds.entrySet()) {
            System.out.print(i.getKey());
            for (var c = 0; c < (nameLongest - i.getKey().length()); ++c) {
                System.out.print(" ");
            }
            System.out.print("   ");
            System.out.print(i.getValue().getStatus().getState());
            for (var c = 0; c < (stateLongest - i.getValue().getStatus().getState().length()); ++c) {
                System.out.print(" ");
            }
            System.out.print("   ");
            System.out.println(i.getValue().getMetadata().getName());

        }

    }

}
