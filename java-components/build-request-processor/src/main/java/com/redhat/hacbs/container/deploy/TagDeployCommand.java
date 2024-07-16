package com.redhat.hacbs.container.deploy;

import static org.apache.commons.lang3.ObjectUtils.isEmpty;
import static org.apache.commons.lang3.ObjectUtils.isNotEmpty;

import java.net.URL;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.codeartifact.AWSCodeArtifactClientBuilder;
import com.amazonaws.services.codeartifact.model.GetAuthorizationTokenRequest;
import com.amazonaws.util.AwsHostNameUtils;
import com.redhat.hacbs.container.deploy.git.Git;
import com.redhat.hacbs.container.deploy.mavenrepository.CodeArtifactRepository;
import com.redhat.hacbs.container.deploy.mavenrepository.MavenRepositoryDeployer;

import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "deploy")
public class TagDeployCommand implements Runnable {

    private static final Pattern CODE_ARTIFACT_PATTERN = Pattern.compile("https://([^.]*)-\\d+\\..*\\.amazonaws\\.com/maven/(.*)/");

    @CommandLine.Option(names = "--directory")
    String artifactDirectory;

    // Maven Repo Deployment specification
    @CommandLine.Option(names = "--mvn-username")
    String mvnUser;

    @ConfigProperty(name = "maven.password")
    Optional<String> mvnPassword;

    @ConfigProperty(name = "aws.profile")
    Optional<String> awsProfile;

    @CommandLine.Option(names = "--mvn-repo")
    String mvnRepo;

    @ConfigProperty(name = "git.deploy.token")
    Optional<String> gitToken;

    // If endpoint is null then default GitHub API endpoint is used. Otherwise:
    // for GitHub, endpoint like https://api.github.com
    // for GitLib, endpoint like https://gitlab.com
    @CommandLine.Option(names = "--git-url")
    String gitURL;

    @CommandLine.Option(names = "--git-identity")
    String gitIdentity;

    @CommandLine.Option(names = "--git-disable-ssl-verification")
    boolean gitDisableSSLVerification;

    @CommandLine.Option(names = "--git-reuse-repository")
    boolean reuseRepository;

    @CommandLine.Option(names = "--image-id")
    String imageId;

    @CommandLine.Option(required = true, names = "--scm-uri")
    String scmUri;

    @CommandLine.Option(required = true, names = "--scm-commit")
    String commit;

    @CommandLine.Option(required = true, names = "--source-path")
    Path sourcePath;

    @Inject
    BootstrapMavenContext mvnCtx;

    public void run() {
        try {

            var deploymentPath = Path.of(artifactDirectory);

            // TODO: Should we write out to a 'DependencyPipelineResults' a GitArchive?
            Git.GitStatus archivedSourceTags = new Git.GitStatus();
            // Save the source first regardless of deployment checks
            if (isNotEmpty(gitIdentity) && gitToken.isPresent()) {
                var git = Git.builder(gitURL, gitIdentity, gitToken.get(), gitDisableSSLVerification);
                if (reuseRepository) {
                    git.initialise(scmUri);
                } else {
                    Log.warnf("Not reusing repository; creating under %s", scmUri);
                    git.create(scmUri);
                }
                Log.infof("Pushing changes back to URL %s", git.getName());
                archivedSourceTags = git.add(sourcePath, commit, imageId);
            }

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
}
