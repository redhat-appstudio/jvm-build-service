package com.redhat.hacbs.container.verifier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.lang3.builder.DiffResult;

import com.redhat.hacbs.container.verifier.asm.AsmDiffable;

public class DiffUtils {

    public record DiffResults(Set<String> shared, Set<String> added, Set<String> deleted,
            Map<String, DiffResult<?>> diffResults,
            List<String> results) {
        public DiffResults(Set<String> shared, Set<String> added, Set<String> deleted, Map<String, DiffResult<?>> diffResults,
                List<String> results) {
            this.shared = Set.copyOf(shared);
            this.added = Set.copyOf(added);
            this.deleted = Set.copyOf(deleted);
            this.diffResults = Map.copyOf(diffResults);
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
        added.forEach(key -> results.add(String.format("+:%s:%s:%s", nname, type, Objects.toString(right.get(key), key))));
        deleted.forEach(key -> results.add(String.format("-:%s:%s:%s", oname, type, Objects.toString(left.get(key), key))));
        var diffResults = new LinkedHashMap<String, DiffResult<?>>();
        shared.forEach(clazz -> {
            var l = left.get(clazz);

            if (l != null) {
                var r = right.get(clazz);
                var diffResult = l.diff(r);
                diffResults.put(clazz, diffResult);
            }
        });
        return new DiffResults(shared, added, deleted, diffResults, results);
    }
}
