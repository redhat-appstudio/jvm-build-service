package com.redhat.hacbs.container.verifier.asm;

import static com.redhat.hacbs.container.verifier.asm.AsmUtils.isPublic;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

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
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return classes;
    }

    public boolean diffJar(JarInfo jar) {
        var failed = false;
        var results = DiffUtils.diff(jar.name(), this.classes(), jar.classes());

        for (var name : results.shared()) {
            var left = this.classes().get(name);
            var right = jar.classes().get(name);

            if (!left.version().equals(right.version())) {
                failed = true;
            }

            if (!left.access().equals(right.access())) {
                failed = true;
                Log.infof("%s: Class access %s changed to %s", left.name(), left.access(), right.access());
            }

            if (!left.fields().equals(right.fields())) {
                failed = true;
                DiffUtils.diff(left.name(), left.fields(), right.fields());
            }

            if (!left.methods().equals(right.methods())) {
                failed = true;
                DiffUtils.diff(left.name(), left.methods(), right.methods());
            }

            if (!Objects.equals(left.visibleAnnotations(), right.visibleAnnotations())) {
                failed = true;
                DiffUtils.diff(left.name(), left.visibleAnnotations(), right.visibleAnnotations());
            }
        }

        return failed || !results.added().isEmpty() || !results.deleted().isEmpty();
    }
}
