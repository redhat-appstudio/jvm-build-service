package io.github.redhatappstudio.jvmbuild.cli.builds;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;
import com.redhat.hacbs.resources.model.v1alpha1.dependencybuildspec.Scm;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.redhatappstudio.jvmbuild.cli.RequestScopedCompleter;
import io.quarkus.arc.Arc;

public class BuildCompleter extends RequestScopedCompleter {

    @Override
    protected Iterable<String> completionCandidates(KubernetesClient client) {
        return createNames().keySet();
    }

    public static Map<String, DependencyBuild> createNames() {
        KubernetesClient client = Arc.container().instance(KubernetesClient.class).get();
        List<DependencyBuild> builds = client.resources(DependencyBuild.class).list().getItems();
        Set<String> doubleUps = new HashSet<>();
        Map<String, DependencyBuild> names = new HashMap<>();
        for (var i : builds) {
            var scm = i.getSpec().getScm();
            String full = createFullName(scm);
            try {
                URI uri = new URI(scm.getScmURL());
                String name = uri.getPath().substring(1) + (uri.getFragment() == null ? "" : "#" + uri.getFragment()) + "@"
                        + scm.getTag();
                if (scm.getPath() != null && scm.getPath().length() > 1) {
                    name = name + ":" + scm.getPath();
                }
                if (names.containsKey(name)) {
                    doubleUps.add(name);
                    //put the existing one under the full name
                    names.put(createFullName(names.get(name).getSpec().getScm()), names.get(name));
                    names.put(full, i);
                } else {
                    names.put(name, i);
                }
            } catch (URISyntaxException e) {
                //can't parse, use full name
                names.put(full, i);
            }
        }
        for (var i : doubleUps) {
            names.remove(i);
        }
        return names;
    }

    private static String createFullName(Scm scm) {
        String full = scm.getScmURL() + "@" + scm.getTag();
        if (scm.getPath() != null && scm.getPath().length() > 1) {
            full = full + ":" + scm.getPath();
        }
        return full;
    }

}
