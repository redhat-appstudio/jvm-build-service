package com.redhat.hacbs.container.analyser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

import javax.inject.Inject;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.google.cloud.tools.jib.api.DescriptorDigest;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.InvalidImageReferenceException;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory;
import com.google.cloud.tools.jib.http.FailoverHttpClient;
import com.google.cloud.tools.jib.image.json.BuildableManifestTemplate;
import com.google.cloud.tools.jib.image.json.ManifestTemplate;
import com.google.cloud.tools.jib.image.json.V21ManifestTemplate;
import com.google.cloud.tools.jib.registry.ManifestAndDigest;
import com.google.cloud.tools.jib.registry.RegistryClient;
import com.google.cloud.tools.jib.registry.credentials.CredentialRetrievalException;
import com.redhat.hacbs.classfile.tracker.ClassFileTracker;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class Main implements QuarkusApplication {

    @ConfigProperty(name = "image")
    String image;

    @ConfigProperty(name = "base-image")
    Optional<String> baseImage;

    @ConfigProperty(name = "sources-to-rebuild", defaultValue = "central")
    Set<String> sourcesToRebuild;

    @Inject
    KubernetesClient kubernetesClient;

    public static void main(String... args) {
        Quarkus.run(Main.class, args);
    }

    @Override
    public int run(String... args) throws Exception {

        Set<DescriptorDigest> layersToProcess = new HashSet<>();
        RegistryClient client = extractLayers(image, layersToProcess::add);
        Log.infof("Processing image %s", image);
        if (baseImage.isPresent()) {
            Log.infof("Processing base image %s", image);
            extractLayers(baseImage.get(), layersToProcess::remove);
        }
        Log.infof("Processing layers to extract: %s", layersToProcess);
        Set<String> gavs = new HashSet<>();
        for (var layer : layersToProcess) {
            var blob = client.pullBlob(layer, s -> {
            }, s -> {
            });
            Path targetFile = Files.createTempFile("layer", "layer");

            try (OutputStream out = Files.newOutputStream(targetFile)) {
                blob.writeTo(out);
            }
            try (InputStream in = Files.newInputStream(targetFile)) {
                GZIPInputStream inputStream = new GZIPInputStream(in);
                TarArchiveInputStream tarArchiveInputStream = new TarArchiveInputStream(inputStream);
                for (TarArchiveEntry entry = tarArchiveInputStream
                        .getNextTarEntry(); entry != null; entry = tarArchiveInputStream.getNextTarEntry()) {
                    if (entry.getName().endsWith(".class")) {
                        Log.debugf("Processing %s from layer %s", entry.getName(), layer.getHash());
                        var data = ClassFileTracker.readTrackingInformationFromClass(tarArchiveInputStream.readAllBytes());
                        if (data != null) {
                            if (sourcesToRebuild.contains(data.source)) {
                                Log.debugf("Found GAV %s in %s from layer %s", data.gav, entry.getName(), layer.getHash());
                                gavs.add(data.gav);
                            }
                        }
                    } else if (entry.getName().endsWith(".jar")) {
                        Log.debugf("Processing %s from layer %s", entry.getName(), layer.getHash());
                        var jarData = ClassFileTracker.readTrackingDataFromJar(tarArchiveInputStream.readAllBytes());
                        for (var data : jarData) {
                            if (data != null) {
                                if (sourcesToRebuild.contains(data.source)) {
                                    Log.debugf("Found GAV %s in %s from layer %s", data.gav, entry.getName(), layer.getHash());
                                    gavs.add(data.gav);
                                }
                            }
                        }
                    }
                }
            }
        }

        Log.infof("Identified Community Dependencies: %s", gavs);
        //know we know which community dependencies went into the build

        //now use the kube client to stick it into a CR to signify that these dependencies should be built
        //        MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> resources = kubernetesClient
        //                .genericKubernetesResources(ResourceDefinitionContext.fromResourceType(ArtifactBuildRequest.class));
        //        for (var gav : gavs) {
        //            GenericKubernetesResource item = new GenericKubernetesResource();
        //            item.setAdditionalProperty("name", gav);
        //            resources.create(item);
        //        }
        return 0;
    }

    RegistryClient extractLayers(String image, Consumer<DescriptorDigest> layerConsumer)
            throws InvalidImageReferenceException, IOException, RegistryException, CredentialRetrievalException {
        ImageReference imageReference = ImageReference.parse(image);
        CredentialRetrieverFactory credentialRetrieverFactory = CredentialRetrieverFactory.forImage(imageReference,
                (s) -> System.err.println(s.getMessage()));
        RegistryClient.Factory factory = RegistryClient.factory(new EventHandlers.Builder().build(),
                imageReference.getRegistry(),
                imageReference.getRepository(), new FailoverHttpClient(false, false, s -> System.out.println(s.getMessage())));
        factory.setCredential(credentialRetrieverFactory.dockerConfig().retrieve().get());
        RegistryClient registryClient = factory.newRegistryClient();

        ManifestAndDigest<ManifestTemplate> result = registryClient.pullManifest(imageReference.getQualifier());
        if (result.getManifest() instanceof V21ManifestTemplate) {
            V21ManifestTemplate template = (V21ManifestTemplate) result.getManifest();
            for (var layer : template.getLayerDigests()) {
                layerConsumer.accept(layer);
            }
        } else if (result.getManifest() instanceof BuildableManifestTemplate) {
            BuildableManifestTemplate template = (BuildableManifestTemplate) result.getManifest();
            for (var layer : template.getLayers()) {
                layerConsumer.accept(layer.getDigest());
            }
        }
        return registryClient;
    }
}
