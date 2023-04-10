package com.redhat.hacbs.container.verifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.builder.DiffResult;
import org.apache.commons.lang3.builder.Diffable;
import org.jboss.logging.Logger;

public class DiffUtils {
    private static final Logger Log = Logger.getLogger(DiffUtils.class);

    public record DiffResults(Set<String> shared, Set<String> added, Set<String> deleted, List<DiffResult<?>> diffResults) {
        public DiffResults(Set<String> shared, Set<String> added, Set<String> deleted, List<DiffResult<?>> diffResults) {
            this.shared = Set.copyOf(shared);
            this.added = Set.copyOf(added);
            this.deleted = Set.copyOf(deleted);
            this.diffResults = List.copyOf(diffResults);
        }
    }

    public static <T extends Diffable<T>> DiffResults diff(String name, Map<String, T> left, Map<String, T> right) {
        var shared = (Set<String>) new LinkedHashSet<String>();
        var deleted = (Set<String>) new LinkedHashSet<String>();
        left.keySet().forEach(key -> {
            if (right.containsKey(key)) {
                shared.add(key);
            } else {
                deleted.add(key);
            }
        });
        var added = right.keySet().stream().filter(key -> !left.containsKey(key))
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (!added.isEmpty()) {
            Log.warnf("%s: %d items were added: %s", name, added.size(), added);
        }

        if (!deleted.isEmpty()) {
            Log.warnf("%s: %d items were removed: %s", name, deleted.size(), deleted);
        }

        var results = (List<DiffResult<?>>) new ArrayList<DiffResult<?>>(shared.size());
        shared.forEach(s -> {
            var l = left.get(s);
            var r = right.get(s);
            var result = l.diff(r);
            results.add(result);

            if (result.getNumberOfDiffs() > 0) {
                Log.warnf("%s %s has %d differences", name, s, result.getNumberOfDiffs());
                var diffs = result.getDiffs();

                for (var diff : diffs) {
                    Log.warnf("  %s", diff);
                }
            }
        });
        return new DiffResults(shared, added, deleted, results);
    }
}
