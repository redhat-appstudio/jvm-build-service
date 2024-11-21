package com.redhat.hacbs.container.build.preprocessor.maven;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.project.MavenProject;
import org.apache.maven.shared.release.config.ReleaseDescriptorBuilder;
import org.apache.maven.shared.release.config.ReleaseUtils;
import org.apache.maven.shared.release.transform.ModelETL;
import org.apache.maven.shared.release.transform.ModelETLRequest;
import org.apache.maven.shared.release.transform.jdom2.JDomModelETL;
import org.apache.maven.shared.release.transform.jdom2.JDomModelETLFactory;
import org.apache.maven.shared.release.util.ReleaseUtil;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.commonjava.maven.ext.common.jdom.JDOMModelConverter;
import org.jdom2.Document;

import com.redhat.hacbs.container.build.preprocessor.AbstractPreprocessor;

import io.quarkus.logging.Log;
import picocli.CommandLine;

import static org.apache.commons.lang3.StringUtils.isNotEmpty;

/**
 * A simple preprocessor that attempts to fix problematic maven build files.
 * <p>
 * At present this is a no-op
 */
@CommandLine.Command(name = "maven-prepare")
public class MavenPrepareCommand extends AbstractPreprocessor {

    public MavenPrepareCommand() {
        type = ToolType.MAVEN;
    }

    @Override
    public void run() {
        super.run();
        try {
            Files.walkFileTree(buildRoot, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String fileName = file.getFileName().toString();
                    if (fileName.equals("pom.xml")) {
                        try {
                            handleBuild(file, buildRoot.resolve("pom.xml").equals(file));
                        } catch (Exception e) {
                            Log.errorf(e, "Failed to pre-process file %s", file);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

            });
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private void handleBuild(Path file, boolean topLevel) {
        try (BufferedReader pomReader = Files.newBufferedReader(file)) {
            ModelETLRequest request = new ModelETLRequest();

            MavenXpp3Reader reader = new MavenXpp3Reader();
            Model model = reader.read(pomReader);

            boolean modified = false;
            if (model.getBuild() != null && model.getBuild().getPlugins() != null) {
                modified |= handlePlugins(model.getBuild().getPlugins(), topLevel);
            }
            if (model.getBuild() != null && model.getBuild().getPluginManagement() != null
                    && model.getBuild().getPluginManagement().getPlugins() != null) {
                modified |= handlePlugins(model.getBuild().getPluginManagement().getPlugins(), topLevel);
            }
            if (model.getProfiles() != null) {
                for (var i : model.getProfiles()) {
                    if (i.getBuild() != null && i.getBuild().getPlugins() != null) {
                        modified |= handlePlugins(i.getBuild().getPlugins(), topLevel);
                    }
                    if (i.getBuild() != null && i.getBuild().getPluginManagement() != null
                            && i.getBuild().getPluginManagement().getPlugins() != null) {
                        modified |= handlePlugins(i.getBuild().getPluginManagement().getPlugins(), topLevel);
                    }
                }
            }

            if (modified) {
                // Adapted from https://github.com/release-engineering/pom-manipulation-ext/blob/main/io/src/main/java/org/commonjava/maven/ext/io/PomIO.java
                JDomModelETLFactory modelETLFactories = new JDomModelETLFactory();
                ReleaseDescriptorBuilder releaseDescriptorBuilder = new ReleaseDescriptorBuilder();
                MavenProject mp = new MavenProject(model);
                releaseDescriptorBuilder.setLineSeparator(ReleaseUtil.LS);
                request.setReleaseDescriptor( ReleaseUtils.buildReleaseDescriptor( releaseDescriptorBuilder ) );
                request.setProject( mp );
                ModelETL etl = modelETLFactories.newInstance( request );
                // Reread in order to fill in JdomModelETL
                etl.extract(file.toFile());
                // Currently the field we want to access is private; https://issues.apache.org/jira/browse/MRELEASE-1044 requests
                // them to be protected to avoid this reflection.
                Document doc = (Document) FieldUtils.getDeclaredField( JDomModelETL.class, "document", true ).get( etl );
                JDOMModelConverter jdomModelConverter = new JDOMModelConverter( );
                jdomModelConverter.convertModelToJDOM( model, doc );
                etl.load( file.toFile() );
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    private boolean handlePlugins(List<Plugin> plugins, boolean topLevel)
            throws IOException {
        boolean modified = false;
        List<PluginInfo> toRemove = new ArrayList<>();

        if (disabledPlugins != null) {
            for (String s : disabledPlugins) {
                if (isNotEmpty(s)) {
                    String[] ga = s.split(":");

                    if (ga.length != 2) {
                        throw new IOException("Error parsing groupId/artifactId:  " + s);
                    }
                    toRemove.add(new PluginInfo(ga[0], ga[1]));
                }
            }
        }

        for (Iterator<Plugin> iterator = plugins.iterator(); iterator.hasNext();) {
            Plugin i = iterator.next();
            PluginInfo p = new PluginInfo(i.getGroupId(), i.getArtifactId());
            if (toRemove.contains(p)) {
                iterator.remove();
                modified = true;
            } else if (i.getArtifactId().equals("maven-deploy-plugin")) {
                //we don't want top level deployment config
                //per artifact level is fine as some things like unit tests
                //should not be deployed
                if (topLevel && i.getConfiguration() != null) {
                    i.setConfiguration(null);
                    modified = true;
                }
            } else if (i.getArtifactId().equals("maven-javadoc-plugin")) {
                modified |= stripFailures(i.getConfiguration());
                for (var e : i.getExecutions()) {
                    modified |= stripFailures(e.getConfiguration());
                }
            }
        }
        return modified;
    }

    private boolean stripFailures(Object configuration) {
        if (configuration == null) {
            return false;
        }
        boolean modified = false;
        Xpp3Dom dom = (Xpp3Dom) configuration;
        for (int i = 0; i < dom.getChildCount(); ++i) {
            var child = dom.getChild(i);
            if (child.getName().equals("failOnWarnings") ||
                    child.getName().equals("failOnError")) {
                dom.removeChild(i);
                --i;
                modified = true;
            }
        }
        return modified;

    }

    record PluginInfo(String group, String artifact) {

    }
}
