package com.redhat.hacbs.analyser.maven;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

import com.redhat.hacbs.resources.model.maven.GAV;

public class MavenAnalyser {

    public static MavenProject doProjectDiscovery(Path projectRoot) {
        Map<GAV, MavenModule> modules = new HashMap<>();
        doProjectDiscovery(projectRoot, modules, List.of());
        return new MavenProject(modules);
    }

    private static void doProjectDiscovery(Path projectRoot, Map<GAV, MavenModule> modules, List<String> requiredProfiles) {
        Path rootPom = projectRoot.resolve("pom.xml");
        if (!Files.isRegularFile(rootPom)) {
            return;
        }
        try (BufferedReader pomReader = Files.newBufferedReader(rootPom)) {
            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(pomReader);
            GAV gav = new GAV(model.getGroupId() == null ? model.getParent().getGroupId() : model.getGroupId(),
                    model.getArtifactId(), model.getVersion() == null ? model.getParent().getVersion() : model.getVersion());

            GAV parentGav = null;
            if (model.getParent() != null) {
                parentGav = new GAV(model.getParent().getGroupId(), model.getParent().getArtifactId(),
                        model.getParent().getVersion());
            }
            MavenModule mavenModule = new MavenModule(gav,
                    parentGav,
                    rootPom);
            modules.put(mavenModule.getGav(), mavenModule);
            List<String> submodules = model.getModules();
            for (var module : submodules) {
                doProjectDiscovery(projectRoot.resolve(module), modules, Collections.emptyList());
            }
            for (var profile : model.getProfiles()) {
                for (var module : profile.getModules()) {
                    List<String> copy = new ArrayList<>(requiredProfiles);
                    copy.add(profile.getId());
                    doProjectDiscovery(projectRoot.resolve(module), modules, copy);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
