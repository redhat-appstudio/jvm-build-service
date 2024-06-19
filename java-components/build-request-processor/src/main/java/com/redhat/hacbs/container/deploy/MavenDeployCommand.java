package com.redhat.hacbs.container.deploy;

import static org.apache.commons.lang3.ObjectUtils.isEmpty;
import static org.apache.commons.lang3.ObjectUtils.isNotEmpty;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.codeartifact.AWSCodeArtifactClientBuilder;
import com.amazonaws.services.codeartifact.model.GetAuthorizationTokenRequest;
import com.amazonaws.util.AwsHostNameUtils;
import com.redhat.hacbs.common.images.ociclient.OCIRegistryClient;
import com.redhat.hacbs.common.sbom.GAV;
import com.redhat.hacbs.container.deploy.mavenrepository.CodeArtifactRepository;
import com.redhat.hacbs.container.deploy.mavenrepository.MavenRepositoryDeployer;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.logging.Log;
import picocli.CommandLine;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
@CommandLine.Command(name = "maven-repository-deploy")
public class MavenDeployCommand implements Runnable {

    static final Pattern CODE_ARTIFACT_PATTERN = Pattern.compile("https://([^.]*)-\\d+\\..*\\.amazonaws\\.com/maven/(.*)/");
    private static final String DOT_JAR = ".jar";
    private static final String DOT_POM = ".pom";
    private static final String DOT = ".";
    public static final String ARTIFACTS = "artifacts";

    @CommandLine.Option(names = "--registry-host", defaultValue = "quay.io")
    String host;
    @CommandLine.Option(names = "--registry-port", defaultValue = "443")
    int port;
    @CommandLine.Option(names = "--registry-owner", defaultValue = "hacbs")
    String owner;
    @ConfigProperty(name = "registry.token")
    Optional<String> token;
    @CommandLine.Option(names = "--registry-repository", defaultValue = "artifact-deployments")
    String repository;
    @CommandLine.Option(names = "--registry-insecure", defaultValue = "false")
    boolean insecure;

    @CommandLine.Option(names = "--image-digest")
    String imageDigest;


    // Maven Repo Deployment specification
    @CommandLine.Option(names = "--mvn-username")
    String mvnUser;

    @ConfigProperty(name = "maven.password")
    Optional<String> mvnPassword;

    @ConfigProperty(name = "aws.profile")
    Optional<String> awsProfile;

    @CommandLine.Option(names = "--mvn-repo")
    String mvnRepo;

    @Inject
    BootstrapMavenContext mvnCtx;

    public void run() {
        try {
            OCIRegistryClient client = getRegistryClient();
            var image = client.pullImage(imageDigest);

            if (!image.isPresent()) {
                throw new RuntimeException("Could not find image");
            }
            Path directory = Files.createTempDirectory("pull");
            image.get().pullLayer(image.get().getLayerCount() - 1, directory);

            var deploymentPath = directory.resolve(ARTIFACTS);
            if (!deploymentPath.toFile().exists()) {
                Log.warnf("No deployed artifacts found. Has the build been correctly configured to deploy?");
                throw new RuntimeException("Deploy failed");
            }
            CodeArtifactRepository codeArtifactRepository = null;
            if (isNotEmpty(mvnRepo) && mvnPassword.isEmpty()) {
                Log.infof("Maven repository specified as %s and no password specified", mvnRepo);
                URL url = new URL(mvnRepo);
                String repo = url.getHost();
                // This is special handling for AWS CodeArtifact. It will automatically retrieve a token
                // (which normally only last up to 12 hours). Token information will be retrieved from
                // the AWS configuration which will utilise the configuration file and/or scan environment
                // variables such as AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY and AWS_PROFILE
                if (repo.endsWith(".amazonaws.com")) {
                    if (isEmpty(mvnUser)) {
                        Log.warnf("Username for deployment is empty");
                    }
                    Matcher matcher = CODE_ARTIFACT_PATTERN.matcher(mvnRepo);
                    if (matcher.matches()) {
                        var mr = matcher.toMatchResult();
                        int firstDash = repo.indexOf("-");
                        String parsedRegion = AwsHostNameUtils.parseRegion(repo, null);
                        String domain = repo.substring(0, firstDash);
                        String domainOwner = repo.substring(firstDash + 1, repo.indexOf("."));
                        Log.infof("Generating AWS token for domain %s, owner %s, region %s", domain, domainOwner, parsedRegion);

                        Regions region = Regions.fromName(parsedRegion);
                        var awsClient = AWSCodeArtifactClientBuilder.standard()
                                .withCredentials(awsProfile.isEmpty() ? DefaultAWSCredentialsProviderChain.getInstance()
                                        : new ProfileCredentialsProvider(awsProfile.get()))
                                .withRegion(region).build();
                        mvnPassword = Optional.of(awsClient.getAuthorizationToken(
                                new GetAuthorizationTokenRequest().withDomain(domain).withDomainOwner(domainOwner))
                                .getAuthorizationToken());
                        codeArtifactRepository = new CodeArtifactRepository(awsClient, mr.group(1), mr.group(2));
                    } else {
                        Log.errorf("Unable to parse AWS CodeArtifact URL: %s", mvnRepo);
                    }
                }
            }

            if (isNotEmpty(mvnRepo)) {
                // Maven Repo Deployment
                MavenRepositoryDeployer deployer = new MavenRepositoryDeployer(mvnCtx, mvnUser, mvnPassword.orElse(""), mvnRepo,
                    deploymentPath, codeArtifactRepository);
                deployer.deploy();
            }

        } catch (Exception e) {
            Log.error("Deployment failed", e);
            throw new RuntimeException(e);
        }
    }

     OCIRegistryClient getRegistryClient() {
         String fullName = host + (port == 443 ? "" : ":" + port);
        return new OCIRegistryClient(fullName, owner, repository, token,
                insecure);
    }

    private Optional<GAV> getGav(String entryName) {
        if (entryName.startsWith("." + File.separator)) {
            entryName = entryName.substring(2);
        }
        if (entryName.endsWith(DOT_JAR) || entryName.endsWith(DOT_POM)) {
            List<String> pathParts = List.of(StringUtils.split(entryName, File.separatorChar));
            int numberOfParts = pathParts.size();

            String version = pathParts.get(numberOfParts - 2);
            String artifactId = pathParts.get(numberOfParts - 3);
            List<String> groupIdList = pathParts.subList(0, numberOfParts - 3);
            String groupId = String.join(DOT, groupIdList);

            return Optional.of(GAV.create(groupId, artifactId, version));
        }
        return Optional.empty();
    }

}
