package io.github.redhatappstudio.jvmbuild.cli.artifacts;

import java.util.TreeMap;

import com.redhat.hacbs.resources.model.v1alpha1.ArtifactBuild;

import picocli.CommandLine;

@CommandLine.Command(name = "list", mixinStandardHelpOptions = true, description = "Lists ArtifactBuild objects")
public class ArtifactListCommand implements Runnable {

    @CommandLine.Option(names = "--failed")
    boolean failed;
    @CommandLine.Option(names = "--building")
    boolean building;

    @Override
    public void run() {
        var builds = new TreeMap<>(GavCompleter.createNames());
        var it = builds.entrySet().iterator();
        int nameLongest = 0;
        int stateLongest = 0;
        while (it.hasNext()) {
            if (building && failed) {
                throw new RuntimeException("Cannot specify both --building and --failed");
            }
            var e = it.next();
            String state = e.getValue().getStatus().getState();
            boolean buildFailed = state.equals(ArtifactBuild.FAILED) || state.equals(ArtifactBuild.MISSING);
            if (failed && !buildFailed) {
                it.remove();
            } else if (building && (buildFailed || state.equals(ArtifactBuild.COMPLETE))) {
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
