package com.redhat.hacbs.cli.artifacts;

import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;

import picocli.CommandLine;

@CommandLine.Command(name = "list", mixinStandardHelpOptions = true, description = "Lists ArtifactBuild objects")
public class ArtifactListCommand implements Runnable {

    @CommandLine.Option(names = "--failed", description = "List only failed")
    boolean failed;
    @CommandLine.Option(names = "--building", description = "List only building")
    boolean building;

    @Override
    public void run() {
        var builds = GavCompleter.createNames();
        var it = builds.entrySet().iterator();
        int nameLongest = 0;
        int stateLongest = 0;
        while (it.hasNext()) {
            if (building && failed) {
                throw new RuntimeException("Cannot specify both --building and --failed");
            }
            var e = it.next();
            String state = e.getValue().getStatus().getState();
            boolean buildFailed = state.equals(ModelConstants.ARTIFACT_BUILD_FAILED)
                    || state.equals(ModelConstants.ARTIFACT_BUILD_MISSING);
            if (failed && !buildFailed) {
                it.remove();
            } else if (building && (buildFailed || state.equals(ModelConstants.ARTIFACT_BUILD_COMPLETE))) {
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
            System.out.print(i.getValue().getMetadata().getName());
            if (i.getValue().getStatus().getMessage() != null) {
                System.out.print(" ");
                System.out.print(i.getValue().getStatus().getMessage());
            }
            System.out.println();

        }

    }

}
