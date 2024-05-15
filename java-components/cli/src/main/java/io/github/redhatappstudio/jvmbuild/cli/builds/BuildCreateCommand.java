package io.github.redhatappstudio.jvmbuild.cli.builds;

import org.apache.commons.codec.digest.DigestUtils;

import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuildSpec;
import com.redhat.hacbs.resources.model.v1alpha1.ModelConstants;
import com.redhat.hacbs.resources.model.v1alpha1.dependencybuildspec.Scm;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
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

    @CommandLine.Option(names = "-r", description = "Reuse existing SCM repository for pushing changes")
    boolean reuse;

    @Override
    public void run() {
        try (InstanceHandle<KubernetesClient> instanceHandle = Arc.container().instance(KubernetesClient.class)) {
            DependencyBuild dependencyBuild = new DependencyBuild();
            dependencyBuild.setSpec(new DependencyBuildSpec());
            dependencyBuild.getMetadata().setName(DigestUtils.md5Hex(url + tag + contextPath));
            dependencyBuild.getSpec().setVersion(version);
            Scm scm = new Scm();
            scm.setScmType("git");
            scm.setScmURL(url);
            scm.setTag(tag);
            scm.setPath(contextPath);
            scm.setCommitHash(scmHash);
            dependencyBuild.getSpec().setScm(scm);

            if (reuse) {
                dependencyBuild.getMetadata().getAnnotations().put(ModelConstants.REUSE_SCM, "true");
            }
            dependencyBuild.getMetadata().getAnnotations().put(ModelConstants.DEPENDENCY_CREATED, "true");

            instanceHandle.get().resource(dependencyBuild).create();
        }
    }
}
