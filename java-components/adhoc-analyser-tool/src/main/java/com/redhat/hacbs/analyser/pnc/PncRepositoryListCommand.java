package com.redhat.hacbs.analyser.pnc;

import java.net.URI;
import java.util.Set;
import java.util.TreeSet;

import jakarta.inject.Inject;

import org.eclipse.microprofile.rest.client.RestClientBuilder;

import com.redhat.hacbs.analyser.config.RepoConfig;
import com.redhat.hacbs.analyser.data.scm.Repository;
import com.redhat.hacbs.analyser.data.scm.ScmManager;
import com.redhat.hacbs.analyser.pnc.rest.PageParameters;
import com.redhat.hacbs.analyser.pnc.rest.SCMRepositoryEndpoint;
import com.redhat.hacbs.analyser.pnc.rest.SwaggerConstants;

import picocli.CommandLine;

@CommandLine.Command(name = "repository-list", description = "Retrieve list of repositories from PNC")
public class PncRepositoryListCommand implements Runnable {

    @CommandLine.Option(names = "-u", description = "PNC URI", required = true)
    String uri;

    @Inject
    RepoConfig config;

    @Override
    public void run() {
        try (ScmManager manager = ScmManager.create(config.path())) {
            SCMRepositoryEndpoint client = RestClientBuilder.newBuilder().baseUri(new URI(uri))
                    .build(SCMRepositoryEndpoint.class);

            int pageNo = 0;
            PageParameters pageParameters = new PageParameters();
            pageParameters.setPageIndex(0);
            pageParameters.setPageSize(SwaggerConstants.MAX_PAGE_SIZE);

            Set<String> urls = new TreeSet<>();
            var result = client.getAll(pageParameters, null, null);
            int max = result.getTotalPages();
            do {
                for (var page : result.getContent()) {
                    if (page.getExternalUrl() != null && !page.getExternalUrl().contains("redhat.com")) {
                        urls.add(page.getExternalUrl()
                                .replaceAll("git@github.com:", "https://github.com/")
                                .replaceAll("(git\\+)?ssh://git@github.com/", "https://github.com/")
                                .replaceAll("(git\\+)?ssh://git@github.com:", "https://github.com/")
                                .replaceAll("git+https://github.com/", "https://github.com/"));
                    }
                }
                pageNo++;
                pageParameters.setPageIndex(pageNo);
                result = client.getAll(pageParameters, null, null);
            } while (pageNo < max);

            for (var i : urls) {
                if (manager.get(i) == null) {
                    Repository repository = new Repository();
                    repository.setUri(i);
                    if (i.contains("svn")) {
                        repository.setType("svn");
                    }
                    manager.add(repository);
                    System.out.println(i);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
