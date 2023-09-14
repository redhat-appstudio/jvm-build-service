package com.redhat.hacbs.container.analyser.deploy.containerregistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.tools.jib.api.Credential;
import com.google.cloud.tools.jib.api.ImageReference;
import com.google.cloud.tools.jib.api.RegistryException;
import com.google.cloud.tools.jib.event.EventHandlers;
import com.google.cloud.tools.jib.frontend.CredentialRetrieverFactory;
import com.google.cloud.tools.jib.http.FailoverHttpClient;
import com.google.cloud.tools.jib.registry.RegistryClient;
import com.google.cloud.tools.jib.registry.credentials.CredentialRetrievalException;
import com.google.cloud.tools.jib.registry.credentials.json.DockerConfigTemplate;

import io.quarkus.logging.Log;

public class ContainerUtil {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static RegistryClient getRegistryClient(ImageReference imageReference, Credential credential, boolean insecure)
            throws CredentialRetrievalException, RegistryException {
        if (insecure) {
            System.setProperty("sendCredentialsOverHttp", "true");
        }

        RegistryClient.Factory factory = RegistryClient.factory(new EventHandlers.Builder().build(),
                imageReference.getRegistry(),
                imageReference.getRepository(), new FailoverHttpClient(insecure, insecure, s -> Log.info(
                        s.getMessage())));

        if (credential != null) {
            factory.setCredential(credential);
        } else {
            CredentialRetrieverFactory credentialRetrieverFactory = CredentialRetrieverFactory.forImage(imageReference,
                    s -> Log.info(
                            s.getMessage()));
            Optional<Credential> optionalCredential = credentialRetrieverFactory.dockerConfig().retrieve();
            optionalCredential.ifPresent(factory::setCredential);
        }
        RegistryClient registryClient = factory.newRegistryClient();
        //this is quay specific possibly?
        //unfortunately we can't get the actual header
        if (imageReference.getRegistry().equals("quay.io") && credential != null) {
            String wwwAuthenticate = "Bearer realm=\"https://" + imageReference.getRegistry() + "/v2/auth\",service=\""
                    + imageReference.getRegistry()
                    + "\",scope=\"repository:" + imageReference.getRepository() + ":pull\"";
            registryClient.authPullByWwwAuthenticate(wwwAuthenticate);
        }
        return registryClient;
    }

    public static Credential processToken(String fullName, String token) {
        Credential credential;

        if (!token.isBlank()) {
            if (token.trim().startsWith("{")) {
                try {
                    //we assume this is a .dockerconfig file
                    DockerConfigTemplate config = MAPPER.readValue(token, DockerConfigTemplate.class);

                    boolean found = false;
                    String tmpUser = null;
                    String tmpPw = null;
                    String host = null;
                    for (var i : config.getAuths().entrySet()) {
                        if (fullName.startsWith(i.getKey())) {
                            found = true;
                            var decodedAuth = new String(Base64.getDecoder().decode(i.getValue().getAuth()),
                                    StandardCharsets.UTF_8);
                            int pos = decodedAuth.indexOf(":");
                            tmpUser = decodedAuth.substring(0, pos);
                            tmpPw = decodedAuth.substring(pos + 1);
                            host = i.getKey();
                            break;
                        }
                    }
                    if (!found) {
                        throw new RuntimeException("Unable to find a host matching " + fullName
                                + " in provided dockerconfig, hosts provided: " + config.getAuths().keySet());
                    }
                    credential = Credential.from(tmpUser, tmpPw);
                    Log.infof("Credential provided as .dockerconfig, selected host %s for registry %s", host, fullName);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                var decoded = new String(Base64.getDecoder().decode(token), StandardCharsets.UTF_8);
                int pos = decoded.indexOf(":");
                credential = Credential.from(decoded.substring(0, pos), decoded.substring(pos + 1));
            }
        } else {
            credential = null;
            Log.infof("No credential provided");
        }

        return credential;
    }
}
