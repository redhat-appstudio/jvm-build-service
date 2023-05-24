package com.redhat.hacbs.analyser.artifactanalysis;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ProgressMonitor;

import com.redhat.hacbs.analyser.config.CheckoutConfig;
import com.redhat.hacbs.analyser.config.RepoConfig;
import com.redhat.hacbs.analyser.data.scm.Repository;
import com.redhat.hacbs.analyser.data.scm.ScmManager;
import com.redhat.hacbs.recipies.location.RecipeGroupManager;
import com.redhat.hacbs.recipies.location.RecipeLayoutManager;

import io.quarkus.dev.console.QuarkusConsole;
import io.quarkus.dev.console.StatusLine;
import io.quarkus.logging.Log;
import picocli.CommandLine;

@CommandLine.Command(name = "legacy-checkout-repositories")
@Singleton
public class LegacyCheckoutRepositoriesCommand implements Runnable {

    public static final String CLONE_FAILURE = "CLONE_FAILURE";
    @Inject
    CheckoutConfig checkoutConfig;

    @Inject
    RepoConfig repoConfig;

    @CommandLine.Option(names = "-t", defaultValue = "10")
    int threads;

    @Override
    public void run() {
        var overallStatus = QuarkusConsole.INSTANCE.registerStatusLine(200);
        System.out.println("START EVENT " + this.toString());
        ExecutorService executorService = Executors.newFixedThreadPool(threads);
        Map<String, String> doubleUps = new TreeMap<>();
        Set<Path> doubleUpFiles = new HashSet<>();
        try (ScmManager manager = ScmManager.create(repoConfig.path())) {
            RecipeLayoutManager recipeLayoutManager = new RecipeLayoutManager(repoConfig.path());
            RecipeGroupManager groupManager = new RecipeGroupManager(List.of(recipeLayoutManager));
            int count = manager.getAll().size();
            int currentCount = 0;
            List<Future<?>> results = multiThreadedEagerCheckout(executorService, manager.getAll(), checkoutConfig.path());
            for (var i : results) {
                try {
                    i.get();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                manager.writeData();
            }
            manager.writeData();
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
        if (repository.isFailed() || repository.isProcessed() || repository.isDisabled()) { //deprecated needs special handling
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
    private List<Future<?>> multiThreadedEagerCheckout(ExecutorService executorService, List<Repository> all,
            Path checkoutBase) {
        List<Repository> copy = new ArrayList<>(all);
        Collections.reverse(copy);
        var overallStatus = QuarkusConsole.INSTANCE.registerStatusLine(400);
        AtomicInteger oustanding = new AtomicInteger();

        List<Future<?>> ret = new ArrayList<>();
        for (var repository : copy) {
            if (shouldSkip(repository)) {
                continue;
            }
            if (repository.getUuid() == null) {
                int priority = oustanding.incrementAndGet();
                ret.add(executorService.submit(new Runnable() {
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
                }));
            }
        }
        return ret;
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

        @Override
        public void showDuration(boolean b) {

        }
    }
}
