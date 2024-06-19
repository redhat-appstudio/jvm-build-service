package com.redhat.hacbs.domainproxy;

import static com.redhat.hacbs.domainproxy.ExternalProxyEndpoint.dependencies;

import java.io.IOException;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;

import org.cyclonedx.CycloneDxSchema;
import org.cyclonedx.generators.BomGeneratorFactory;
import org.cyclonedx.generators.json.BomJsonGenerator;
import org.cyclonedx.model.Bom;
import org.cyclonedx.model.Component;
import org.cyclonedx.model.Property;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.redhat.hacbs.common.sbom.GAV;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.Startup;

@Startup
public class DomainProxyHack {

    @Inject
    @ConfigProperty(name = "server-domain-socket")
    String domainSocket;

    @ConfigProperty(name = "sbom-output-directory")
    Path sbomOutputDir;

    @PostConstruct
    public void start() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Files.delete(Path.of(domainSocket));
                            createBom();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }));
                try {
                    UnixDomainSocketAddress address = UnixDomainSocketAddress.of(domainSocket);
                    var socket = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
                    socket.bind(address);
                    while (true) {
                        SocketChannel channel = socket.accept();
                        Socket s = new Socket("localhost", 2000);
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                int r;
                                byte[] buf = new byte[1024];
                                try {
                                    while ((r = s.getInputStream().read(buf)) > 0) {
                                        channel.write(ByteBuffer.wrap(buf, 0, r));
                                    }
                                } catch (IOException e) {
                                    e.printStackTrace();
                                } finally {
                                    try {
                                        channel.close();
                                    } catch (Exception ex) {
                                        ex.printStackTrace();
                                    }
                                    try {
                                        s.close();
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                    }
                                }
                            }
                        }).start();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                ByteBuffer buf = ByteBuffer.allocate(1024);
                                buf.clear();
                                try {
                                    while (channel.read(buf) > 0) {
                                        buf.flip();
                                        s.getOutputStream().write(buf.array(), buf.arrayOffset(), buf.remaining());
                                        buf.clear();
                                    }
                                } catch (Exception e) {
                                    e.printStackTrace();
                                } finally {

                                    try {
                                        channel.close();
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                    }
                                    try {
                                        s.close();
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                    }
                                }
                            }
                        }).start();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
                Quarkus.asyncExit();
            }
        }).start();
    }

    private void createBom() throws IOException {
        Bom bom = new Bom();
        for (Dependency dependency : dependencies) {
            GAV gav = dependency.GAV();
            Component component = new Component();
            component.setType(Component.Type.LIBRARY);
            String groupId = gav.getGroupId();
            String artifactId = gav.getArtifactId();
            String version = gav.getVersion();
            component.setGroup(groupId);
            component.setName(artifactId);
            component.setVersion(version);
            String purl = String.format("pkg:maven/%s/%s@%s", groupId, artifactId, version);
            if (dependency.classifier() != null) {
                purl += String.format("?classifier=%s", dependency.classifier());
            }
            component.setPurl(purl);
            bom.addComponent(component);

            Property typeProperty = new Property();
            typeProperty.setName("package:type");
            typeProperty.setValue("maven");
            component.addProperty(typeProperty);

            Property languageProperty = new Property();
            languageProperty.setName("package:language");
            languageProperty.setValue("java");
            component.addProperty(languageProperty);
        }

        if (!dependencies.isEmpty()) {
            Files.createDirectories(sbomOutputDir);
            Path sbom = sbomOutputDir.resolve("sbom.json");
            Log.infof("Writing SBOM to %s", sbom.toAbsolutePath());
            BomJsonGenerator bomJsonGenerator = BomGeneratorFactory.createJson(CycloneDxSchema.VERSION_LATEST, bom);
            Files.writeString(sbom, bomJsonGenerator.toJsonString(), StandardCharsets.UTF_8);
        }
    }
}
