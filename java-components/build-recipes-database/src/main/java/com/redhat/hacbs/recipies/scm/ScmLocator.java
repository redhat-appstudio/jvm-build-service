package com.redhat.hacbs.recipies.scm;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.text.StringSubstitutor;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.maven.model.Model;
import org.apache.maven.model.Parent;
import org.apache.maven.model.Scm;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.eclipse.jgit.api.Git;
import org.jboss.logging.Logger;

import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.location.ArtifactInfoRequest;
import com.redhat.hacbs.recipies.location.RecipeDirectory;
import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeRepositoryManager;
import com.redhat.hacbs.recipies.util.GitCredentials;

public class ScmLocator {

    private static final Logger log = Logger.getLogger(ScmLocator.class);

    private static final String HTTPS_GITHUB_COM = "https://github.com/";
    private static final Pattern NUMERIC_PART = Pattern.compile("(\\d+\\.)(\\d+\\.?)+");

    public static ScmLocator getInstance() {
        return getInstance(ScmLocatorConfig.builder().build());
    }

    public static ScmLocator getInstance(ScmLocatorConfig config) {
        return new ScmLocator(config);
    }

    private final ScmLocatorConfig config;
    private RecipeGroupManager recipeGroupManager;

    private ScmLocator(ScmLocatorConfig config) {
        this.config = config;
    }

    private RecipeGroupManager getRecipeGroupManager() {
        if (recipeGroupManager == null) {
            //checkout the git recipe database and load the recipes
            final List<RecipeDirectory> managers = new ArrayList<>(config.getRecipeRepos().size());
            for (var i : config.getRecipeRepos()) {
                log.infof("Checking out recipe repo %s", i);
                try {
                    final Path tempDir = Files.createTempDirectory("recipe");
                    managers.add(RecipeRepositoryManager.create(i, "main", Optional.empty(), tempDir));
                } catch (Exception e) {
                    throw new RuntimeException("Failed to checkout " + i, e);
                }
            }
            recipeGroupManager = new RecipeGroupManager(managers);
        }
        return recipeGroupManager;
    }

    public TagInfo resolveTagInfo(GAV toBuild) throws Exception {

        log.debugf("Looking up %s", toBuild);

        var recipeGroupManager = getRecipeGroupManager();

        //look for SCM info
        var recipes = recipeGroupManager
                .requestArtifactInformation(
                        new ArtifactInfoRequest(Set.of(toBuild), Set.of(BuildRecipe.SCM, BuildRecipe.BUILD)))
                .getRecipes()
                .get(toBuild);
        var deserialized = recipes == null ? null : recipes.get(BuildRecipe.SCM);
        List<RepositoryInfo> repos = List.of();
        List<TagMapping> allMappings = List.of();
        if (recipes != null && deserialized != null) {
            log.debugf("Found %s %s", recipes, deserialized);
            ScmInfo main = BuildRecipe.SCM.getHandler().parse(deserialized);
            if (main.getLegacyRepos() != null) {
                repos = new ArrayList<>(main.getLegacyRepos().size() + 1);
                allMappings = new ArrayList<>();
                repos.add(main);
                allMappings.addAll(main.getTagMapping());
                if (main.getLegacyRepos() != null) {
                    for (var j : main.getLegacyRepos()) {
                        repos.add(j);
                        allMappings.addAll(j.getTagMapping());
                    }
                }
            } else {
                repos = List.of(main);
                allMappings = main.getTagMapping();
            }
        }

        if (repos.isEmpty()) {
            log.debugf("No information found, attempting to use the pom to determine the location");
            //TODO: do we want to rely on pom discovery long term? Should we just use this to update the database instead?
            ScmInfo discovered = attemptPomDiscovery(toBuild);
            if (discovered != null) {
                repos = List.of(discovered);
            }
            if (repos.isEmpty()) {
                throw new RuntimeException("Unable to determine SCM repo");
            }
        }

        Exception firstFailure = null;
        for (var parsedInfo : repos) {
            log.debugf("Looking for a tag in %s", parsedInfo.getUri());

            //now look for a tag
            try {
                String version = toBuild.getVersion();
                String selectedTag = null;
                Set<String> versionExactContains = new HashSet<>();
                Set<String> tagExactContains = new HashSet<>();
                Map<String, String> tagsToHash = new HashMap<>();
                var tags = Git.lsRemoteRepository()
                        .setCredentialsProvider(
                                new GitCredentials())
                        .setRemote(parsedInfo.getUri()).setTags(true).setHeads(false).call();
                Set<String> tagNames = new HashSet<>();
                for (var tag : tags) {
                    var name = tag.getName().replace("refs/tags/", "");
                    tagNames.add(name);
                    tagsToHash.put(name, tag.getObjectId().name());
                }

                //first try tag mappings
                for (var mapping : allMappings) {
                    log.debugf("Trying tag pattern %s on version %s", mapping.getPattern(), version);
                    Matcher m = Pattern.compile(mapping.getPattern()).matcher(version);
                    if (m.matches()) {
                        log.debugf("Tag pattern %s matches", mapping.getPattern());
                        String match = mapping.getTag();
                        for (int i = 0; i <= m.groupCount(); ++i) {
                            match = match.replaceAll("\\$" + i, m.group(i));
                        }
                        log.debugf("Trying to find tag %s", match);
                        //if the tag was a constant we don't require it to be in the tag set
                        //this allows for explicit refs to be used
                        if (tagNames.contains(match) || match.equals(mapping.getTag())) {
                            selectedTag = match;
                            break;
                        }
                    }
                }

                if (selectedTag == null) {
                    for (var name : tagNames) {
                        if (name.equals(version)) {
                            //exact match is always good
                            selectedTag = version;
                            break;
                        } else if (name.contains(version)) {
                            versionExactContains.add(name);
                        } else if (version.contains(name)) {
                            tagExactContains.add(name);
                        }
                    }
                }
                if (selectedTag == null) {
                    //no exact match
                    if (versionExactContains.size() == 1) {
                        //only one contained the full version
                        selectedTag = versionExactContains.iterator().next();
                    } else {
                        for (var i : versionExactContains) {
                            //look for a tag that ends with the version (i.e. no -rc1 or similar)
                            if (i.endsWith(version)) {
                                if (selectedTag == null) {
                                    selectedTag = i;
                                } else {
                                    throw new RuntimeException(
                                            "Could not determine tag for " + version
                                                    + " multiple possible tags were found: "
                                                    + versionExactContains);
                                }
                            }
                        }
                        if (selectedTag == null && tagExactContains.size() == 1) {
                            //this is for cases where the tag is something like 1.2.3 and the version is 1.2.3.Final
                            //we need to be careful though, as e.g. this could also make '1.2' match '1.2.3'
                            //we make sure the numeric part is an exact match
                            var tempTag = tagExactContains.iterator().next();
                            Matcher tm = NUMERIC_PART.matcher(tempTag);
                            Matcher vm = NUMERIC_PART.matcher(version);
                            if (tm.find() && vm.find()) {
                                if (Objects.equals(tm.group(0), vm.group(0))) {
                                    selectedTag = tempTag;
                                }
                            }
                        }
                        if (selectedTag == null) {
                            RuntimeException runtimeException = new RuntimeException(
                                    "Could not determine tag for " + version);
                            runtimeException.setStackTrace(new StackTraceElement[0]);
                            throw runtimeException;
                        }
                        firstFailure = null;
                    }
                }

                if (selectedTag != null) {
                    return new TagInfo(parsedInfo, selectedTag, tagsToHash.get(selectedTag));
                }
            } catch (Exception ex) {
                log.error("Failure to determine tag", ex);
                if (firstFailure == null) {
                    firstFailure = ex;
                } else {
                    firstFailure.addSuppressed(ex);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }

        return null;
    }

    private ScmInfo attemptPomDiscovery(GAV toBuild) {
        try (CloseableHttpClient client = HttpClientBuilder.create().build()) {
            Optional<Model> currentPom = getPom(toBuild.getGroupId(), toBuild.getArtifactId(), toBuild.getVersion(), client);
            while (currentPom.isPresent()) {
                var origin = getScmOrigin(currentPom.get(), toBuild.getVersion());
                if (origin != null) {
                    return new ScmInfo("git", origin);
                }
                Parent p = currentPom.get().getParent();
                if (p == null) {
                    return null;
                }
                if (!Objects.equals(p.getGroupId(), toBuild.getGroupId())) {
                    return null;
                }
                currentPom = getPom(p.getGroupId(), p.getArtifactId(), p.getVersion(), client);
            }
        } catch (IOException e) {
            log.errorf(e, "Failed to get pom for %s:%s:%s", toBuild.getGroupId(), toBuild.getArtifactId(),
                    toBuild.getVersion());
            return null;
        }
        return null;
    }

    Optional<Model> getPom(String group, String artifact, String version, CloseableHttpClient client) {
        HttpGet get = new HttpGet(
                config.getCacheUrl() + "/" + group.replace(".", "/") + "/" + artifact + "/" + version + "/" + artifact
                        + "-" + version + ".pom");
        try (var response = client.execute(get)) {
            if (response.getStatusLine().getStatusCode() != 200) {
                log.errorf("Unexpected status %s for %s:%s:%s", response.getStatusLine().getStatusCode(), group, artifact,
                        version);
                return Optional.empty();
            }
            try (Reader pomReader = new InputStreamReader(response.getEntity().getContent())) {
                MavenXpp3Reader reader = new MavenXpp3Reader();
                Model model = reader.read(pomReader);
                return Optional.of(model);
            }

        } catch (Exception e) {
            log.errorf(e, "Failed to get pom for %s:%s:%s", group, artifact, version);
            return Optional.empty();
        }
    }

    public static String getScmOrigin(Model model, String version) {
        final Scm scm = model.getScm();
        if (scm == null) {
            return null;
        }
        if (scm.getConnection() != null && !scm.getConnection().isEmpty()) {
            String s = resolveModelValue(model, scm.getConnection(), version);
            s = scmToHttps(s);
            return s;
        }
        String url = resolveModelValue(model, model.getUrl(), version);
        if (url != null && url.startsWith("https://github.com/")) {
            return scmToHttps(url);
        }
        url = resolveModelValue(model, scm.getUrl(), version);
        if (url != null && url.startsWith("https://github.com/")) {
            return scmToHttps(url);
        }
        return null;
    }

    private static String scmToHttps(String s) {
        s = s.replace("scm:", "");
        s = s.replace("git:", "");
        s = s.replace("git@", "");
        s = s.replace("ssh:", "");
        s = s.replace("svn:", "");
        s = s.replace(".git", "");
        if (s.startsWith("http://")) {
            s = s.replace("http://", "https://");
        } else if (!s.startsWith("https://")) {
            s = s.replace(':', '/');
            if (s.startsWith("github.com:")) {
                s = s.replace(':', '/');
            }
            if (s.startsWith("//")) {
                s = "https:" + s;
            } else {
                s = "https://" + s;
            }
        }
        if (s.startsWith(HTTPS_GITHUB_COM)) {
            var tmp = s.substring(HTTPS_GITHUB_COM.length());
            final String[] parts = tmp.split("/");
            if (parts.length > 2) {
                s = HTTPS_GITHUB_COM + parts[0] + "/" + parts[1];
            }
        }

        return s;
    }

    private static String resolveModelValue(Model model, String value, String version) {
        return value == null ? null : value.contains("${") ? substituteProperties(value, model, version) : value;
    }

    private static String substituteProperties(String str, Model model, String version) {
        final Properties props = model.getProperties();
        Map<String, String> map = new HashMap<>(props.size());
        for (Map.Entry<?, ?> prop : props.entrySet()) {
            map.put(prop.getKey().toString(), prop.getValue().toString());
        }
        map.put("project.version", version);
        return new StringSubstitutor(map).replace(str);
    }
}
