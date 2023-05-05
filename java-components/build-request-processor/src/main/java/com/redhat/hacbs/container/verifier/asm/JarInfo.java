package com.redhat.hacbs.container.verifier.asm;

import static com.redhat.hacbs.container.verifier.asm.AsmUtils.isPublic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import com.redhat.hacbs.container.verifier.DiffUtils;

public record JarInfo(String name, Map<String, ClassInfo> classes) implements AsmDiffable<JarInfo> {

    private static final Logger Log = Logger.getLogger(JarInfo.class);

    // diffClass excluding name

    public JarInfo(Path file) {
        this(file.getFileName().toString(), readClasses(file));
    }

    private static Map<String, ClassInfo> readClasses(Path file) {
        var classes = new LinkedHashMap<String, ClassInfo>();

        try (var in = new JarInputStream(Files.newInputStream(file))) {
            var entry = (JarEntry) null;

            while ((entry = in.getNextJarEntry()) != null) {
                try {
                    var name = entry.getName();

                    // XXX: Also handle other files?
                    if (!name.endsWith(".class")) {
                        continue;
                    }

                    // XXX: Skipping lambda for now
                    if (name.contains("$$Lambda$")) {
                        Log.debugf("Skipping file %s", name);
                        continue;
                    }

                    var reader = new ClassReader(in);
                    var node = new ClassNode();
                    reader.accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                    if (isPublic(node.access)) {
                        var classInfo = new ClassInfo(node);
                        classes.put(classInfo.name(), classInfo);
                    }
                } catch (Exception e) {
                    Log.errorf(e, "Failed to verify %s", entry.getName());
                }

            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return classes;
    }

    private static void addChange(List<String> diffResults, String jarName, String type, String className,
            String fieldName, String oldValue, String newValue) {
        var s = String.format("^:%s:%s:%s|%s:%s>%s", jarName, type, className.replace('/', '.'), fieldName, oldValue, newValue);
        diffResults.add(s);
    }

    private static <T extends AsmDiffable<T>> void addChanges(String oldName, String newName, Map<String, T> left,
            Map<String, T> right, List<String> diffResults, String jarName, String type) {
        var results = DiffUtils.diff(jarName + ":" + oldName, jarName + ":" + newName, type, left, right);
        diffResults.addAll(results.results());

        for (var r : results.diffResults()) {
            for (var s : r.getDiffs()) {
                addChange(diffResults, jarName, type, oldName, s.getFieldName(), s.getLeft().toString(),
                        s.getRight().toString());
            }
        }
    }

    public int diffJar(JarInfo jar, List<String> excludes) {
        var classResults = DiffUtils.diff(this.name(), jar.name(), "class", this.classes(), jar.classes());
        var diffResults = new ArrayList<>(classResults.results());

        for (var name : classResults.shared()) {
            var left = this.classes().get(name);
            var right = jar.classes().get(name);

            for (var r : classResults.diffResults()) {
                for (var s : r.getDiffs()) {
                    var f = s.getFieldName();

                    if (f.equals("version") || f.equals("access") || f.equals("fields") || f.equals("methods")
                            || f.equals("visibleAnnotations")) {
                        continue;
                    }

                    addChange(diffResults, this.name(), "class", left.name(), s.getFieldName(), s.getLeft().toString(),
                            s.getRight().toString());
                }
            }

            if (!left.version().equals(right.version())) {
                addChange(diffResults, this.name(), "class", left.name(), "version",
                        left.version().majorVersion() + "." + left.version().minorVersion(),
                        right.version().majorVersion() + "." + right.version().minorVersion());
            }

            if (!left.access().equals(right.access())) {
                addChange(diffResults, this.name(), "class", left.name(), "access", left.access().toString(),
                        right.access().toString());
            }

            if (!left.fields().equals(right.fields())) {
                addChanges(left.name(), right.name(), left.fields(), right.fields(), diffResults, this.name(), "field");
            }

            if (!left.methods().equals(right.methods())) {
                addChanges(left.name(), right.name(), left.methods(), right.methods(), diffResults, this.name(), "method");
            }

            if (!Objects.equals(left.visibleAnnotations(), right.visibleAnnotations())) {
                addChanges(left.name(), right.name(), left.visibleAnnotations(), right.visibleAnnotations(), diffResults,
                        this.name(), "annotation");
            }
        }

        var errors = new ArrayList<>(diffResults);
        excludes.stream().map(Pattern::compile).map(Pattern::asPredicate).forEachOrdered(errors::removeIf);
        Log.infof("Got %d results: %s", diffResults.size(), diffResults);
        Log.infof("Got %d errors: %s", errors.size(), errors);
        return errors.size();
    }

    @Override
    public String getName() {
        return name;
    }
}
