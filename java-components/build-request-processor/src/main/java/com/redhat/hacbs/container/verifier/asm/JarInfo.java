package com.redhat.hacbs.container.verifier.asm;

import static com.redhat.hacbs.container.verifier.asm.AsmUtils.isPublic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import com.redhat.hacbs.container.verifier.DiffUtils;

public record JarInfo(String name, Map<String, ClassInfo> classes) implements AsmDiffable<JarInfo> {

    private static final Logger Log = Logger.getLogger(JarInfo.class);

    // diffClass excluding name

    public JarInfo(Path file) {
        this(Objects.toString(file.getFileName()), readClasses(file));
    }

    private static Map<String, ClassInfo> readClasses(Path file) {
        var classes = new LinkedHashMap<String, ClassInfo>();

        try (var in = new JarInputStream(Files.newInputStream(file))) {
            var entry = (JarEntry) null;

            while ((entry = in.getNextJarEntry()) != null) {
                try {
                    var name = entry.getRealName();

                    if (!name.endsWith(".class")) {
                        classes.put(name, null);
                        continue;
                    }

                    // XXX: Skipping lambda for now
                    if (name.contains("$$Lambda$")) {
                        Log.debugf("Skipping file %s", name);
                        continue;
                    }

                    var reader = new ClassReader(in);
                    var node = new ClassNode();
                    reader.accept(node, 0);

                    if (isPublic(node.access)) {
                        var classInfo = new ClassInfo(node);
                        classes.put(name, classInfo);
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

    private static <T extends AsmDiffable<T>> void addChanges(List<String> diffResults, String jarName, String type,
            String oldName, String newName, Map<String, T> left,
            Map<String, T> right) {
        var results = DiffUtils.diff(jarName + ":" + oldName, jarName + ":" + newName, type, left, right);
        diffResults.addAll(results.results());

        for (var r : results.diffResults().entrySet()) {
            for (var s : r.getValue().getDiffs()) {
                addChange(diffResults, jarName, type, oldName, r.getKey(), Objects.toString(s.getLeft()),
                        Objects.toString(s.getRight()));
            }
        }
    }

    public int diffJar(JarInfo jar, List<String> excludes) {
        var classResults = DiffUtils.diff(this.name(), jar.name(), "class", this.classes(), jar.classes());
        var diffResults = new ArrayList<>(classResults.results());

        for (var name : classResults.shared()) {
            var left = this.classes().get(name);

            // null means file other than .class file
            if (left == null) {
                continue;
            }

            var right = jar.classes().get(name);

            if (!Objects.equals(left.version(), right.version())) {
                addChange(diffResults, this.name(), "class", left.name(), "version",
                        left.version().majorVersion() + "." + left.version().minorVersion(),
                        right.version().majorVersion() + "." + right.version().minorVersion());
            }

            if (!Objects.equals(left.access(), right.access())) {
                addChange(diffResults, this.name(), "class", left.name(), "access", Objects.toString(left.access()),
                        Objects.toString(right.access()));
            }

            if (!Objects.equals(left.name(), right.name())) {
                addChange(diffResults, this.name(), "class", left.name(), "name", left.name(), right.name());
            }

            if (!Objects.equals(left.signature(), right.signature())) {
                addChange(diffResults, this.name(), "class", left.signature(), "signature", left.signature(),
                        right.signature());
            }

            if (!Objects.equals(left.superName(), right.superName())) {
                addChange(diffResults, this.name(), "class", left.superName(), "superName", left.superName(),
                        right.superName());
            }

            if (!Objects.equals(left.interfaces(), right.interfaces())) {
                addChange(diffResults, this.name(), "class", Objects.toString(left.interfaces()), "interfaces",
                        Objects.toString(left.interfaces()), Objects.toString(right.interfaces()));
            }

            if (!Objects.equals(left.sourceFile(), right.sourceFile())) {
                addChange(diffResults, this.name(), "class", left.sourceFile(), "sourceFile", left.sourceFile(),
                        right.sourceFile());
            }

            if (!Objects.equals(left.sourceDebug(), right.sourceDebug())) {
                addChange(diffResults, this.name(), "class", left.sourceDebug(), "sourceDebug", left.sourceDebug(),
                        right.sourceDebug());
            }

            if (!Objects.equals(left.module(), right.module())) {
                addChanges(diffResults, this.name, "module", left.name(), right.name(), left.module(), right.module());
            }

            if (!Objects.equals(left.outerClass(), right.outerClass())) {
                addChange(diffResults, this.name(), "class", left.outerClass(), "outerClass", left.outerClass(),
                        right.outerClass());
            }

            if (!Objects.equals(left.outerMethod(), right.outerMethod())) {
                addChange(diffResults, this.name(), "class", left.outerMethod(), "outerMethod", left.outerMethod(),
                        right.outerMethod());
            }

            if (!Objects.equals(left.outerMethodDesc(), right.outerMethodDesc())) {
                addChange(diffResults, this.name(), "class", left.outerMethodDesc(), "outerMethodDesc", left.outerMethodDesc(),
                        right.outerMethodDesc());
            }

            if (!Objects.equals(left.visibleAnnotations(), right.visibleAnnotations())) {
                addChanges(diffResults, this.name(), "annotation", left.name(), right.name(), left.visibleAnnotations(),
                        right.visibleAnnotations());
            }

            if (!Objects.equals(left.permittedSubclasses(), right.permittedSubclasses())) {
                addChange(diffResults, this.name(), "class", Objects.toString(left.permittedSubclasses()),
                        "permittedSubclasses", Objects.toString(left.permittedSubclasses()),
                        Objects.toString(right.permittedSubclasses()));
            }

            if (!Objects.equals(left.recordComponents(), right.recordComponents())) {
                addChanges(diffResults, this.name(), "recordComponent", left.name(), right.name(), left.recordComponents(),
                        right.recordComponents());
            }

            if (!Objects.equals(left.fields(), right.fields())) {
                addChanges(diffResults, this.name(), "field", left.name(), right.name(), left.fields(), right.fields());
            }

            if (!Objects.equals(left.methods(), right.methods())) {
                addChanges(diffResults, this.name(), "method", left.name(), right.name(), left.methods(), right.methods());
            }
        }

        var errors = new ArrayList<>(diffResults);
        excludes.stream().map(Pattern::compile).map(Pattern::asPredicate).forEach(errors::removeIf);

        if (Log.isInfoEnabled() && !errors.isEmpty()) {
            Log.infof("Jar verification got %d errors:\n%s", errors.size(), StringUtils.join(errors, '\n'));
        }

        return errors.size();
    }

    @Override
    public String getName() {
        return name;
    }
}
