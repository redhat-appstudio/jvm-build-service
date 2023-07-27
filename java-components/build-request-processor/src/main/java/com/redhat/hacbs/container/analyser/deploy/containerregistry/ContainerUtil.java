package com.redhat.hacbs.container.analyser.deploy.containerregistry;

import java.util.Optional;

import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory;
import com.google.cloud.tools.jib.http.FailoverHttpClient;
import com.google.cloud.tools.jib.registry.RegistryClient;
import com.google.cloud.tools.jib.registry.credentials.CredentialRetrievalException;

import io.quarkus.logging.Log;

public class ContainerUtil {
    public static RegistryClient getRegistryClient(ImageReference imageReference)
            throws CredentialRetrievalException {
        CredentialRetrieverFactory credentialRetrieverFactory = CredentialRetrieverFactory.forImage(imageReference,
                s -> Log.info(
                        s.getMessage()));
        RegistryClient.Factory factory = RegistryClient.factory(new EventHandlers.Builder().build(),
                imageReference.getRegistry(),
                imageReference.getRepository(), new FailoverHttpClient(false, false, s -> Log.info(
                        s.getMessage())));

        Optional<Credential> optionalCredential = credentialRetrieverFactory.dockerConfig().retrieve();
        optionalCredential.ifPresent(factory::setCredential);
        return factory.newRegistryClient();
    }

}
