package io.github.redhatappstudio.jvmbuild.cli.builds;

import org.apache.commons.codec.digest.DigestUtils;

import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuildSpec;
import com.redhat.hacbs.resources.model.v1alpha1.dependencybuildspec.Scm;

import io.fabric8.openshift.client.OpenShiftClient;
import io.quarkus.arc.Arc;
import picocli.CommandLine;

@CommandLine.Command(name = "create", mixinStandardHelpOptions = true, description = "Creates a dependency build")
public class BuildCreateCommand implements Runnable {

    @CommandLine.Option(names = "-u", description = "SCM URL", required = true)
    String url;

    @CommandLine.Option(names = "-t", description = "SCM Tag", required = true)
    String tag;

    @CommandLine.Option(names = "-p", description = "SCM Context Path")
    String contextPath = "";

    @CommandLine.Option(names = "-h", description = "SCM Hash", required = true)
    String scmHash;

    @CommandLine.Option(names = "-v", description = "Version", required = true)
    String version;

    @Override
    public void run() {
        var client = Arc.container().instance(OpenShiftClient.class).get();
        DependencyBuild dependencyBuild = new DependencyBuild();
        dependencyBuild.setSpec(new DependencyBuildSpec());
        dependencyBuild.getMetadata().setName(DigestUtils.md5Hex(url + tag + contextPath));
        dependencyBuild.getSpec().setVersion(version);
        Scm scm = new Scm();
        scm.setScmType("git");
        scm.setScmURL(url);
        scm.setTag(tag);
        scm.setPath(contextPath);
        // TODO: Do we need to set the hash?
        scm.setCommitHash(scmHash);
        dependencyBuild.getSpec().setScm(scm);

        client.resource(dependencyBuild).create();
    }
}
