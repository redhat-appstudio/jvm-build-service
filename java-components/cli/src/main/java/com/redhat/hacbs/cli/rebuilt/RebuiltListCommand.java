package com.redhat.hacbs.cli.rebuilt;

import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.redhat.hacbs.resources.model.v1alpha1.RebuiltArtifact;

import picocli.CommandLine;

@CommandLine.Command(name = "list", mixinStandardHelpOptions = true, description = "Lists the rebuilds")
public class RebuiltListCommand
        implements Runnable {

    @Override
    public void run() {
        Map<String, RebuiltArtifact> builds = RebuildCompleter.createNames();

        if (builds.size() > 0) {
            int longest = builds.values()
                    .stream()
                    .max(Comparator.comparing(x -> x.getSpec().getGav().length()))
                    .get()
                    .getSpec()
                    .getGav()
                    .length() + 5;

            System.out.println(
                    "Found " + builds.size() + " builds.\n");

            TreeMap<String, String> gavsToID = builds.entrySet().stream()
                    .collect(Collectors.toMap(x -> x.getValue().getSpec().getGav(),
                            Map.Entry::getKey,
                            (k1, k2) -> k1, TreeMap::new));

            for (var b : gavsToID.keySet()) {
                System.out.printf("%-" + longest + "s %s\n", b, gavsToID.get(b));
            }
        }
    }
}
