package com.redhat.hacbs.common.images.ociclient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

import com.google.cloud.tools.jib.api.DescriptorDigest;
import com.google.cloud.tools.jib.image.json.OciManifestTemplate;

public interface LocalImage {
    int getLayerCount();

    OciManifestTemplate getManifest();

    DescriptorDigest getDescriptorDigest();

    String getDigestHash();

    void pullLayer(int layer, Path target) throws IOException;

    void pullLayer(int layer,
            Path outputPath,
            Consumer<Long> blobSizeListener,
            Consumer<Long> writtenByteCountListener) throws IOException;
}
