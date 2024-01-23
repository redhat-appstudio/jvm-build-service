package com.redhat.hacbs.common.tools.logging;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.hacbs.resources.model.v1alpha1.DependencyBuild;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.tekton.pipeline.v1beta1.PipelineRun;
import io.fabric8.tekton.pipeline.v1beta1.TaskRun;
import io.github.redhatappstudio.jvmbuild.resources.api.LogsApi;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;

public class LogExtractor {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final JsonFactory JSON_FACTORY = new JsonFactory();

    public static String discoveryLogRetrieval(OpenShiftClient client, String host, int port, String restPath,
            DependencyBuild theBuild) {
        StringBuilder result = new StringBuilder();
        LogsApi logsApi = QuarkusRestClientBuilder.newBuilder()
                .register(((ClientRequestFilter) context -> context.getHeaders().add(HttpHeaders.AUTHORIZATION,
                        String.format("Bearer %s", client.getConfiguration().getAutoOAuthToken()))))
                .baseUri(URI.create("https://" + host + ":" + port + restPath))
                .build(LogsApi.class);

        String[] discoverySplit = theBuild.getStatus().getDiscoveryPipelineResults().getLogs().split("/");
        result.append("Discovery Log information: ").append(Arrays.toString(discoverySplit)).append("\n");
        // Equivalent to using this Quarkus API would be to call the client raw method.
        // client.raw("https://" + host + ":" + defaultPort + restPath + "/v1alpha2/parents/" + split[0]
        //          + "/results/" + split[2] + "/logs/" + split[4]);
        String log = logsApi.getLogByUid(discoverySplit[0], UUID.fromString(discoverySplit[2]),
                UUID.fromString(discoverySplit[4]));
        parseLog(result, log);
        return result.toString();
    }

    public static String buildLogRetrieval(OpenShiftClient client, String host, int port, String restPath,
            Set<Integer> buildNumbers,
            DependencyBuild theBuild) {
        StringBuilder result = new StringBuilder();

        LogsApi logsApi = QuarkusRestClientBuilder.newBuilder()
                .register(((ClientRequestFilter) context -> context.getHeaders().add(HttpHeaders.AUTHORIZATION,
                        String.format("Bearer %s", client.getConfiguration().getAutoOAuthToken()))))
                .baseUri(URI.create("https://" + host + ":" + port + restPath))
                .build(LogsApi.class);

        for (Integer buildCount : buildNumbers) {
            String[] split = theBuild.getStatus()
                    .getBuildAttempts()
                    .get(buildCount)
                    .getBuild()
                    .getResults()
                    .getPipelineResults()
                    .getLogs()
                    .split("/");
            result.append("Build Log information: ").append(Arrays.toString(split)).append("\n");

            // Equivalent to using this Quarkus API would be to call the client raw method.
            // client.raw("https://" + host + ":" + defaultPort + restPath + "/v1alpha2/parents/" + split[0]
            //          + "/results/" + split[2] + "/logs/" + split[4]);
            String log = logsApi.getLogByUid(split[0], UUID.fromString(split[2]), UUID.fromString(split[4]));
            parseLog(result, log);
        }
        return result.toString();
    }

    /**
     * When the log is too big it returns a sequence of JSON documents. While a string
     * split "((?<=[}][}]))" around the separator would work a JsonParser can parse and
     * tokenize the string itself.
     */
    private static void parseLog(StringBuilder allLog, String log) {
        try (JsonParser jp = JSON_FACTORY.createParser(log)) {
            Iterator<Result> value = MAPPER.readValues(jp, Result.class);
            value.forEachRemaining((r) ->
            // According to the spec its meant to be a Base64 encoded chunk. However, it appears
            // to be implicitly decoded
            allLog.append(new String(r.result.getData(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static String legacyDiscoveryLogRetrieval(OpenShiftClient client, DependencyBuild theBuild) {
        StringBuilder result = new StringBuilder();
        var pr = client.resources(PipelineRun.class)
                .withName(theBuild.getMetadata().getName() + "-build-discovery");
        if (pr == null || pr.get() == null) {
            result.append("PipelineRun not found so unable to extract logs.");
            return result.toString();
        }
        PipelineRun pipelineRun = pr.get();
        if (pipelineRun.getStatus().getCompletionTime() == null) {
            result.append("PipelineRun not finished.");
            return result.toString();
        }
        boolean success = false;
        for (var i : pipelineRun.getStatus().getConditions()) {
            if (Objects.equals("Succeeded", i.getType())) {
                if (i.getStatus().toLowerCase(Locale.ROOT).equals("true")) {
                    success = true;
                }
            }
        }

        result.append("---------   Logs for Discovery PipelineRun ")
                .append(pipelineRun.getMetadata().getName())
                .append(" (")
                .append(success ? "SUCCEEDED" : "FAILED")
                .append(") ---------");
        result.append(appendTaskRunLogs(client, pipelineRun));

        return result.toString();
    }

    public static String legacyBuildLogRetrieval(OpenShiftClient client, Set<Integer> buildNumbers,
            DependencyBuild theBuild) {
        StringBuilder result = new StringBuilder();
        for (var buildNo : buildNumbers) {
            var pr = client.resources(PipelineRun.class)
                    .withName(theBuild.getMetadata().getName() + "-build-" + buildNo);
            if (pr == null || pr.get() == null) {
                result.append("PipelineRun not found so unable to extract logs.");
                continue;
            }
            PipelineRun pipelineRun = pr.get();
            if (pipelineRun.getStatus().getCompletionTime() == null) {
                result.append("PipelineRun not finished.");
                continue;
            }
            boolean success = false;
            for (var i : pipelineRun.getStatus().getConditions()) {
                if (Objects.equals("Succeeded", i.getType())) {
                    if (i.getStatus().toLowerCase(Locale.ROOT).equals("true")) {
                        success = true;
                    }
                }
            }

            result.append("---------   Logs for PipelineRun ")
                    .append(pipelineRun.getMetadata().getName())
                    .append(" (")
                    .append(success ? "SUCCEEDED" : "FAILED")
                    .append(") ---------");
            result.append(appendTaskRunLogs(client, pipelineRun));
        }
        return result.toString();
    }

    private static String appendTaskRunLogs(OpenShiftClient client, PipelineRun pipelineRun) {
        StringBuilder result = new StringBuilder();
        var references = pipelineRun.getStatus().getChildReferences();
        List<TaskRun> taskRuns = new ArrayList<>();
        for (var ref : references) {
            var tr = client.resources(TaskRun.class).withName(ref.getName());
            if (tr == null || tr.get() == null) {
                result.append("TaskRun ")
                        .append(ref.getName())
                        .append(" not found so unable to extract logs.");
            } else {
                taskRuns.add(tr.get());
            }
        }
        if (taskRuns.isEmpty()) {
            result.append("No TaskRuns found");
        } else {
            DateTimeFormatter formatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

            taskRuns.sort(
                    Comparator.comparing(t -> OffsetDateTime.parse(t.getStatus().getStartTime(), formatter)));

            OffsetDateTime startTime = OffsetDateTime.parse(pipelineRun.getStatus().getStartTime(), formatter);
            result.append("\n\n#####################################################");
            for (var tr : taskRuns) {

                var pod = client.pods().withName(tr.getMetadata().getName() + "-pod");
                if (pod == null || pod.get() == null) {
                    result.append("Pod not found for task  ")
                            .append(tr.getMetadata().getName())
                            .append(" so unable to extract logs.");
                    continue;
                }

                List<ContainerStatus> containerStatuses = new ArrayList<>(pod.get().getStatus().getContainerStatuses());
                containerStatuses.sort(Comparator.comparing(
                        t -> OffsetDateTime.parse(t.getState().getTerminated().getFinishedAt(), formatter)));
                for (var i : containerStatuses) {
                    var p = pod.inContainer(i.getName());

                    result.append("### Logs for container ").append(i.getName());
                    result.append("#####################################################\n\n");
                    try (var w = p.watchLog(); var in = w.getOutput()) {
                        int r;
                        byte[] buff = new byte[1024];
                        while ((r = in.read(buff)) > 0) {
                            result.append(new String(buff, 0, r));
                        }
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    var finishTime = OffsetDateTime.parse(i.getState().getTerminated().getFinishedAt(), formatter);
                    Duration duration = Duration.between(startTime, finishTime);
                    startTime = finishTime;
                    result.append("\n\n#####################################################");
                    result.append("### Container ")
                            .append(i.getName())
                            .append(" finished at ")
                            .append(i.getState().getTerminated().getFinishedAt())
                            .append(" in ")
                            .append(duration.getSeconds())
                            .append(" seconds");
                }
            }
            result.append("#####################################################\n\n");
        }
        return result.toString();
    }
}
