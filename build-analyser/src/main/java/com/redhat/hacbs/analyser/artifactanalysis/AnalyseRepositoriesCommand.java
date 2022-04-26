package com.redhat.hacbs.analyser.artifactanalysis;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ProgressMonitor;

import com.redhat.hacbs.analyser.config.CheckoutConfig;
import com.redhat.hacbs.analyser.config.RepoConfig;
import com.redhat.hacbs.analyser.data.scm.Repository;
import com.redhat.hacbs.analyser.data.scm.ScmManager;
import com.redhat.hacbs.analyser.maven.GradleAnalyser;
import com.redhat.hacbs.analyser.maven.MavenAnalyser;
import com.redhat.hacbs.analyser.maven.MavenProject;
import com.redhat.hacbs.recipies.BuildRecipe;
import com.redhat.hacbs.recipies.GAV;
import com.redhat.hacbs.recipies.location.*;
import com.redhat.hacbs.recipies.scm.ScmInfo;

import io.quarkus.dev.console.QuarkusConsole;
import io.quarkus.dev.console.StatusLine;
import io.quarkus.logging.Log;
import io.quarkus.runtime.ApplicationLifecycleManager;
import picocli.CommandLine;

@CommandLine.Command(name = "analyse-repositories")
@Singleton
public class AnalyseRepositoriesCommand implements Runnable {

    public static final String CLONE_FAILURE = "CLONE_FAILURE";
    @Inject
    CheckoutConfig checkoutConfig;

    @Inject
    RepoConfig repoConfig;

    @CommandLine.Option(names = "-t", defaultValue = "10")
    int threads;

    @Override
    public void run() {
        var status = QuarkusConsole.INSTANCE.registerStatusLine(100);
        var overallStatus = QuarkusConsole.INSTANCE.registerStatusLine(200);
        System.out.println("START EVENT " + this.toString());
        ExecutorService executorService = Executors.newFixedThreadPool(threads);
        Map<String, String> doubleUps = new TreeMap<>();
        Set<Path> doubleUpFiles = new HashSet<>();
        try (ScmManager manager = ScmManager.create(repoConfig.path())) {
            RecipeLayoutManager recipeLayoutManager = new RecipeLayoutManager(
                    repoConfig.path().resolve(RecipeRepositoryManager.RECIPES));
            RecipeGroupManager groupManager = new RecipeGroupManager(List.of(recipeLayoutManager));
            int count = manager.getAll().size();
            int currentCount = 0;
            //multiThreadedEagerCheckout(executorService, manager.getAll(), checkoutConfig.path());
            for (var repository : new ArrayList<>(manager.getAll())) {

                overallStatus.setMessage("Processing repo " + (currentCount++) + " out of " + count);
                if (!isRunning()) {
                    return;
                }
                try {
                    if (shouldSkip(repository)) {
                        continue;
                    }
                    if (repository.getUuid() == null) {
                        repository.setUuid(UUID.randomUUID().toString());
                    }
                    Path checkoutPath = checkoutConfig.path().resolve(repository.getUuid());
                    Git checkout;
                    if (Files.exists(checkoutPath)) {
                        checkout = Git.open(checkoutPath.toFile());
                        System.out.println("Using existing " + repository.getUri() + " at " + checkoutPath);
                    } else {
                        try {
                            System.out.println("Checking out " + repository.getUri() + " into " + checkoutPath);
                            checkout = Git.cloneRepository().setDirectory(checkoutPath.toFile())
                                    .setProgressMonitor(new QuarkusProgressMonitor(status))
                                    .setURI(repository.getUri()).call();
                        } catch (Throwable t) {
                            Log.errorf(t, "Failed to clone %s", repository.getUri());
                            repository.setUuid(null);
                            repository.setFailed(true);
                            repository.setFailedReason(CLONE_FAILURE);
                            manager.writeData();
                            continue;
                        }
                    }
                    try {

                        MavenProject result;
                        if (Files.exists(checkoutPath.resolve("pom.xml"))) {
                            result = MavenAnalyser.doProjectDiscovery(checkoutPath);
                        } else if (Files.exists(checkoutPath.resolve("build.gradle"))) {
                            result = GradleAnalyser.doProjectDiscovery(checkoutPath);
                        } else {
                            continue;
                        }
                        Set<GAV> locationRequests = new HashSet<>();
                        for (var module : result.getProjects().values()) {
                            locationRequests.add(new GAV(module.getGav().getGroupId(), module.getGav().getArtifactId(),
                                    module.getGav().getVersion()));
                        }
                        var existing = groupManager
                                .requestBuildInformation(new ProjectBuildRequest(locationRequests, Set.of(BuildRecipe.SCM)));
                        for (var module : result.getProjects().values()) {
                            var existingModule = existing.getRecipes().get(module.getGav());
                            if (existingModule != null && existingModule.containsKey(BuildRecipe.SCM)) {
                                ScmInfo existingInfo = BuildRecipe.SCM.getHandler().parse(existingModule.get(BuildRecipe.SCM));
                                if (existingInfo.getUri().equals(repository.getUri())) {
                                    continue;
                                }
                                if (existingModule.get(BuildRecipe.SCM).toString().contains("_artifact")) {
                                    doubleUps.put(existingInfo.getUri(), repository.getUri() + "  " + module.getGav());
                                    doubleUpFiles.add(existingModule.get(BuildRecipe.SCM));
                                }
                            }
                            ScmInfo info = new ScmInfo("git", repository.getUri());
                            recipeLayoutManager.writeArtifactData(new AddRecipeRequest<>(BuildRecipe.SCM, info,
                                    module.getGav().getGroupId(), module.getGav().getArtifactId(), null));
                        }
                    } catch (Throwable t) {
                        Log.errorf(t, "Failed to analyse %s", repository.getUri());
                    }

                } catch (Exception e) {
                    Log.errorf(e, "Failed to handle %s", repository.getUri());
                    repository.setFailed(true);
                }
                manager.writeData();
            }
            status.close();
            overallStatus.close();

            for (var e : doubleUps.entrySet()) {
                System.out.println("DOUBLE UP: " + e.getKey() + " " + e.getValue());
            }
            for (var path : doubleUpFiles) {
                Files.delete(path);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            executorService.shutdownNow();
        }
    }

    private boolean shouldSkip(Repository repository) {
        if (repository.isFailed() || repository.isProcessed() || repository.isDisabled() || repository.isDeprecated()) { //deprecated needs special handling
            return true;
        }
        if (repository.getType() != null && !"git".equals(repository.getType())) {
            return true;
        }
        return false;
    }

    /**
     * Works backwards through the list doing the checkouts in a multi threaded manner
     * <p>
     * slight possibility for a race in that the main thread and this could checkout the same repo,
     * however in practice this is not actually an issue.
     * <p>
     * This approach allows us to checkout repos multi threadedly without dealing with thread safety concerns elsewhere.
     */
    private void multiThreadedEagerCheckout(ExecutorService executorService, List<Repository> all, Path checkoutBase) {
        List<Repository> copy = new ArrayList<>(all);
        Collections.reverse(copy);
        var overallStatus = QuarkusConsole.INSTANCE.registerStatusLine(400);
        AtomicInteger oustanding = new AtomicInteger();

        for (var repository : copy) {
            if (shouldSkip(repository)) {
                continue;
            }
            if (repository.getUuid() == null) {
                int priority = oustanding.incrementAndGet();
                executorService.submit(new Runnable() {
                    @Override
                    public void run() {
                        var status = QuarkusConsole.INSTANCE.registerStatusLine(priority + 500);
                        try {
                            String newUuid = UUID.randomUUID().toString();
                            Path checkoutPath = checkoutBase.resolve(newUuid);
                            try {
                                System.out.println("Checking out " + repository.getUri() + " into " + checkoutPath);
                                Git.cloneRepository().setDirectory(checkoutPath.toFile())
                                        .setProgressMonitor(new QuarkusProgressMonitor(status))
                                        .setURI(repository.getUri()).call();
                            } catch (Throwable t) {
                                Log.errorf(t, "Failed to clone %s", repository.getUri());
                                repository.setUuid(null);
                                repository.setFailed(true);
                                repository.setFailedReason(CLONE_FAILURE);
                            }
                            if (repository.getUuid() == null) {
                                repository.setUuid(newUuid);
                            }
                        } catch (Exception e) {
                            Log.errorf(e, "Failed to clone %s", repository.getUri());
                        } finally {
                            int outstanding = oustanding.decrementAndGet();
                            status.close();
                            overallStatus.setMessage("Backround repositories to checkout remaining: " + outstanding);
                        }
                    }
                });
            }
        }
    }

    boolean isRunning() {
        try {
            Field field = ApplicationLifecycleManager.class.getDeclaredField("exitCode");
            field.setAccessible(true);
            return (Integer) field.get(null) == -1;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static class QuarkusProgressMonitor implements ProgressMonitor {
        private final StatusLine status;
        int totalTasks;
        int taskCount;
        String currentTask;
        int currentTotal;
        int current;
        long lastUpdate = System.currentTimeMillis();

        public QuarkusProgressMonitor(StatusLine status) {
            this.status = status;
        }

        @Override
        public void start(int i) {
            totalTasks = i;
            taskCount = 0;
            current = 0;
        }

        @Override
        public void beginTask(String s, int i) {
            taskCount++;
            currentTask = s;
            currentTotal = i;
            current = 0;
            updateText();

        }

        @Override
        public void update(int i) {
            if (currentTask == null) {
                return;
            }
            current += i;
            if (System.currentTimeMillis() > lastUpdate + 1000) {
                updateText();
            }
        }

        @Override
        public void endTask() {
            status.setMessage("");
        }

        private void updateText() {
            lastUpdate = System.currentTimeMillis();
            status.setMessage("[" + taskCount + "/" + totalTasks + "] " + currentTask + " ("
                    + current + "/" + currentTotal + ")");
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }
}
