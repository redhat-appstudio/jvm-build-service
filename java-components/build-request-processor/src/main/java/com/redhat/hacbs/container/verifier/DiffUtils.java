package com.redhat.hacbs.container.verifier;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.builder.DiffResult;
import org.jboss.logging.Logger;

import com.redhat.hacbs.container.verifier.asm.AsmDiffable;

public class DiffUtils {
    private static final Logger Log = Logger.getLogger(DiffUtils.class);

    public record DiffResults(Set<String> shared, Set<String> added, Set<String> deleted, List<DiffResult<?>> diffResults,
            List<String> results) {
        public DiffResults(Set<String> shared, Set<String> added, Set<String> deleted, List<DiffResult<?>> diffResults,
                List<String> results) {
            this.shared = Set.copyOf(shared);
            this.added = Set.copyOf(added);
            this.deleted = Set.copyOf(deleted);
            this.diffResults = List.copyOf(diffResults);
            this.results = List.copyOf(results);
        }
    }

    public static <T extends AsmDiffable<T>> DiffResults diff(String oldName, String newName, String type, Map<String, T> left,
            Map<String, T> right) {
        var results = new ArrayList<String>();
        var oname = oldName.replace('/', '.');
        var nname = newName.replace('/', '.');
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
        added.forEach(i -> results.add(String.format("+:%s:%s:%s", nname, type, right.get(i).getName().replace('/', '.'))));
        deleted.forEach(i -> results.add(String.format("-:%s:%s:%s", oname, type, left.get(i).getName().replace('/', '.'))));
        var diffResults = new ArrayList<DiffResult<?>>(shared.size());
        shared.forEach(clazz -> {
            var l = left.get(clazz);
            var r = right.get(clazz);
            var diffResult = l.diff(r);
            diffResults.add(diffResult);
        });
        return new DiffResults(shared, added, deleted, diffResults, results);
    }
}
