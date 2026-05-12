package io.jenkins.plugins.mcp.server.extensions;

import hudson.Extension;
import hudson.model.AbstractProject;
import hudson.model.Computer;
import hudson.model.HealthReport;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TopLevelItem;
import hudson.model.View;
import hudson.model.Queue;
import hudson.plugins.git.util.BuildData;
import hudson.tasks.junit.TestResultAction;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jenkins.model.Jenkins;
import jenkins.scm.RunWithSCM;
import org.jenkinsci.plugins.variant.OptionalExtension;
import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

/**
 * ReadOnlyInspectionExtension — Extension MCP Server (LECTURE SEULE)
 *
 * Fournit 15 outils d'inspection avancés pour le chatbot Jenkins.
 * RÈGLE ABSOLUE : aucun outil ne modifie l'état de Jenkins.
 * Aucun build déclenché, aucune suppression, aucune modification de config.
 *
 * Placé dans : src/main/java/io/jenkins/plugins/mcp/server/extensions/
 */
@OptionalExtension(requirePlugins = { "git", "junit" })
public class ReadOnlyInspectionExtension implements McpServerExtension {

    // =========================================================================
    // CATÉGORIE 1 — INSPECTION & DIAGNOSTIC
    // =========================================================================

    /**
     * OUTIL 1 — getJobHealthScore
     */
    @Tool(description = "Get the health score (0-100) and health reports for a Jenkins job. " +
            "Higher score means more stable builds. " +
            "Returns one entry per health metric (build stability, test results, etc.).")
    public List<Map<String, Object>> getJobHealthScore(
            @ToolParam(description = "Full job name, e.g. 'my-job' or 'folder/my-job'") String jobFullName) {

        Job<?, ?> job = resolveJob(jobFullName);
        List<HealthReport> reports = job.getBuildHealthReports();

        if (reports.isEmpty()) {
            return Collections.singletonList(
                    mapOf("score", 0, "description", "No health data yet — job has never been built."));
        }

        return reports.stream()
                .map(r -> mapOf(
                        "score", r.getScore(),
                        "description", r.getDescription(),
                        "iconUrl", r.getIconUrl("16x16")))
                .collect(Collectors.toList());
    }

    /**
     * OUTIL 2 — getPipelineStageTimings
     *
     * Utilise l'API WorkflowRun + FlowExecution pour extraire les durées par stage.
     * Compatible Pipeline Declarative et Scripted.
     */
    @Tool(description = "Get the duration of each stage for a Pipeline (Workflow) build. " +
            "Use buildNumber=-1 for the last build. " +
            "Returns stage name, duration in ms, human-readable duration, and status. " +
            "Only works on Pipeline jobs (WorkflowJob). Returns an error for freestyle jobs.")
    public List<Map<String, Object>> getPipelineStageTimings(
            @ToolParam(description = "Full Pipeline job name, e.g. 'my-pipeline'") String jobFullName,
            @ToolParam(description = "Build number. Use -1 for the last build.") int buildNumber) {

        // Guard: workflow-job plugin may not be present
        try {
            Class.forName("org.jenkinsci.plugins.workflow.job.WorkflowJob");
        } catch (ClassNotFoundException e) {
            return Collections.singletonList(
                    mapOf("error", "Pipeline (workflow-job) plugin is not installed."));
        }

        Run<?, ?> run = resolveRun(jobFullName, buildNumber);

        // Check it's a WorkflowRun
        if (!(run instanceof org.jenkinsci.plugins.workflow.job.WorkflowRun)) {
            return Collections.singletonList(
                    mapOf("error", "Job '" + jobFullName + "' is not a Pipeline job."));
        }

        org.jenkinsci.plugins.workflow.job.WorkflowRun wfRun = (org.jenkinsci.plugins.workflow.job.WorkflowRun) run;

        org.jenkinsci.plugins.workflow.flow.FlowExecution exec = wfRun.getExecution();
        if (exec == null) {
            return Collections.singletonList(
                    mapOf("error", "Build has no execution record (build may not have started yet)."));
        }

        // Walk all nodes and collect stage boundaries
        List<Map<String, Object>> result = new ArrayList<>();
        try {
            List<org.jenkinsci.plugins.workflow.graph.FlowNode> allNodes = new org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner()
                    .allNodes(exec);

            for (org.jenkinsci.plugins.workflow.graph.FlowNode node : allNodes) {
                // Stage nodes have a StageAction or are named stage steps
                if (node.getDisplayFunctionName() != null &&
                        node.getDisplayFunctionName().equals("stage")) {

                    String stageName = node.getDisplayName();
                    org.jenkinsci.plugins.workflow.actions.TimingAction timing = node
                            .getAction(org.jenkinsci.plugins.workflow.actions.TimingAction.class);
                    org.jenkinsci.plugins.workflow.actions.ErrorAction error = node
                            .getAction(org.jenkinsci.plugins.workflow.actions.ErrorAction.class);
                    org.jenkinsci.plugins.workflow.actions.WarningAction warning = node
                            .getAction(org.jenkinsci.plugins.workflow.actions.WarningAction.class);

                    long startTime = timing != null ? timing.getStartTime() : 0;
                    long durationMs = 0;

                    if (startTime > 0) {
                        // Duration: from startTime to next sibling start, or build end
                        durationMs = System.currentTimeMillis() - startTime;
                        if (!wfRun.isBuilding()) {
                            durationMs = (wfRun.getStartTimeInMillis() + wfRun.getDuration()) - startTime;
                        }
                    }

                    String status = "SUCCESS";
                    if (error != null)
                        status = "FAILURE";
                    else if (warning != null)
                        status = "UNSTABLE";

                    result.add(mapOf(
                            "stage", stageName,
                            "durationMs", Math.max(0, durationMs),
                            "durationHuman", humanDuration(Math.max(0, durationMs)),
                            "status", status));
                }
            }
        } catch (Exception e) {
            return Collections.singletonList(
                    mapOf("error", "Could not read pipeline stages: " + e.getMessage()));
        }

        if (result.isEmpty()) {
            result.add(mapOf("info",
                    "No stage nodes found. Build may use scripted pipeline without explicit stage() calls."));
        }
        return result;
    }

    /**
     * OUTIL 3 — getJobBuildStatistics
     */
    @Tool(description = "Compute build statistics for a job over the last N builds (max 50). " +
            "Returns: success rate %, average/min/max duration, failure counts, " +
            "and a stability trend: IMPROVING, DEGRADING, or STABLE.")
    public Map<String, Object> getJobBuildStatistics(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to analyze (1–50)") int lastN) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(1, lastN), 50);
        List<? extends Run<?, ?>> allBuilds = job.getBuilds();

        if (allBuilds.isEmpty()) {
            return mapOf("jobName", jobFullName, "error", "Job has never been built.");
        }

        List<? extends Run<?, ?>> builds = allBuilds.subList(0, Math.min(limit, allBuilds.size()));

        long success = builds.stream().filter(b -> b.getResult() == Result.SUCCESS).count();
        long failed = builds.stream().filter(b -> b.getResult() == Result.FAILURE).count();
        long unstable = builds.stream().filter(b -> b.getResult() == Result.UNSTABLE).count();
        long aborted = builds.stream().filter(b -> b.getResult() == Result.ABORTED).count();

        LongSummaryStatistics dStats = builds.stream()
                .mapToLong(Run::getDuration)
                .summaryStatistics();

        double successRate = builds.isEmpty() ? 0.0 : (success * 100.0 / builds.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("analyzedBuilds", builds.size());
        result.put("successCount", success);
        result.put("failureCount", failed);
        result.put("unstableCount", unstable);
        result.put("abortedCount", aborted);
        result.put("successRatePercent", Math.round(successRate * 10.0) / 10.0);
        result.put("avgDurationMs", (long) dStats.getAverage());
        result.put("avgDurationHuman", humanDuration((long) dStats.getAverage()));
        result.put("minDurationMs", dStats.getMin() == Long.MAX_VALUE ? 0 : dStats.getMin());
        result.put("maxDurationMs", dStats.getMax() == Long.MIN_VALUE ? 0 : dStats.getMax());
        result.put("trend", computeTrend(builds));
        return result;
    }

    /**
     * OUTIL 4 — getBuildArtifacts
     */
    @Tool(description = "List all artifacts produced by a build with their file name, size in bytes, " +
            "relative path, and direct download URL. " +
            "Use buildNumber=-1 for the last build.")
    public List<Map<String, Object>> getBuildArtifacts(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Build number. Use -1 for the last build.") int buildNumber) {

        Run<?, ?> run = resolveRun(jobFullName, buildNumber);
        List<? extends Run.Artifact> artifacts = run.getArtifacts();

        if (artifacts.isEmpty()) {
            return Collections.singletonList(
                    mapOf("info", "Build #" + run.getNumber() + " produced no archived artifacts."));
        }

        String rootUrl = Jenkins.get().getRootUrl();
        if (rootUrl == null)
            rootUrl = "/";

        final String baseUrl = rootUrl + run.getUrl() + "artifact/";
        return artifacts.stream()
                .map(a -> {
                    File file = new File(run.getArtifactsDir(), a.relativePath);
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("fileName", a.getFileName());
                    entry.put("displayPath", a.getDisplayPath());
                    entry.put("relativePath", a.relativePath);
                    entry.put("sizeBytes", file.exists() ? file.length() : -1L);
                    entry.put("sizeHuman", file.exists() ? humanFileSize(file.length()) : "unknown");
                    entry.put("downloadUrl", baseUrl + a.relativePath);
                    return entry;
                })
                .collect(Collectors.toList());
    }

    /**
     * OUTIL 5 — getLastBuildSummary
     */
    @Tool(description = "Get a rich summary of the last build of a job in a single call: " +
            "result, duration, who triggered it, how many commits were included, " +
            "number of artifacts, and test results (passed/failed/skipped) if available. " +
            "Best tool to answer: 'What happened in the last build of X?'")
    public Map<String, Object> getLastBuildSummary(
            @ToolParam(description = "Full job name") String jobFullName) {

        Job<?, ?> job = resolveJob(jobFullName);
        Run<?, ?> run = job.getLastBuild();
        if (run == null) {
            return mapOf("jobName", jobFullName, "error", "Job has never been built.");
        }

        String triggeredBy = run.getCauses().stream()
                .map(hudson.model.Cause::getShortDescription)
                .collect(Collectors.joining(", "));
        if (triggeredBy.isEmpty())
            triggeredBy = "unknown";

        int changeCount = 0;
        if (run instanceof RunWithSCM) {
            changeCount = ((RunWithSCM) run).getChangeSets().size();
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildNumber", run.getNumber());
        result.put("result", run.getResult() != null ? run.getResult().toString() : "BUILDING");
        result.put("building", run.isBuilding());
        result.put("startedAt", run.getTimestampString2());
        result.put("durationMs", run.getDuration());
        result.put("durationHuman", humanDuration(run.getDuration()));
        result.put("triggeredBy", triggeredBy);
        result.put("commitCount", changeCount);
        result.put("artifactCount", run.getArtifacts().size());
        result.put("buildUrl",
                Jenkins.get().getRootUrl() != null
                        ? Jenkins.get().getRootUrl() + run.getUrl()
                        : run.getUrl());

        // Test results — guard for missing JUnit plugin
        try {
            TestResultAction tra = run.getAction(TestResultAction.class);
            if (tra != null) {
                Map<String, Object> tests = new LinkedHashMap<>();
                tests.put("total", tra.getTotalCount());
                tests.put("passed", tra.getTotalCount() - tra.getFailCount() - tra.getSkipCount());
                tests.put("failed", tra.getFailCount());
                tests.put("skipped", tra.getSkipCount());
                result.put("testResults", tests);
            } else {
                result.put("testResults", null);
            }
        } catch (Exception e) {
            result.put("testResults", null);
        }

        return result;
    }

    // =========================================================================
    // CATÉGORIE 2 — INFRASTRUCTURE & AGENTS
    // =========================================================================

    /**
     * OUTIL 6 — getNodeDetails
     */
    @Tool(description = "Get detailed information about a specific Jenkins agent/node: " +
            "online status, idle state, number of executors (free/busy), " +
            "assigned labels, currently running builds, and offline cause. " +
            "Use 'built-in' or '' (empty string) for the Jenkins controller.")
    public Map<String, Object> getNodeDetails(
            @ToolParam(description = "Node name. Use '' or 'built-in' for the Jenkins controller.") String nodeName) {

        // Normalize: empty string or "built-in" → master computer
        String lookupName = (nodeName == null || nodeName.isBlank()
                || nodeName.equalsIgnoreCase("built-in"))
                        ? ""
                        : nodeName;

        Computer computer = Jenkins.get().getComputer(lookupName);
        if (computer == null) {
            throw new IllegalArgumentException("Node not found: '" + nodeName + "'");
        }

        List<Map<String, Object>> runningBuilds = new ArrayList<>();
        for (hudson.model.Executor executor : computer.getExecutors()) {
            Run<?, ?> current = (Run<?, ?>) executor.getCurrentExecutable();
            if (current != null) {
                runningBuilds.add(mapOf(
                        "jobName", current.getParent().getFullName(),
                        "buildNumber", current.getNumber(),
                        "duration", humanDuration(System.currentTimeMillis() - current.getStartTimeInMillis())));
            }
        }

        List<String> labels = new ArrayList<>();
        if (computer.getNode() != null) {
            computer.getNode().getAssignedLabels().forEach(l -> labels.add(l.getName()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", computer.getName().isEmpty() ? "built-in" : computer.getName());
        result.put("displayName", computer.getDisplayName());
        result.put("online", computer.isOnline());
        result.put("idle", computer.isIdle());
        result.put("numExecutors", computer.getNumExecutors());
        result.put("busyExecutors", computer.countBusy());
        result.put("freeExecutors", computer.getNumExecutors() - computer.countBusy());
        result.put("labels", labels);
        result.put("runningBuilds", runningBuilds);
        result.put("offlineCause",
                computer.getOfflineCause() != null ? computer.getOfflineCause().toString() : null);
        result.put("offlineCauseReason",
                computer.getOfflineCauseReason() != null ? computer.getOfflineCauseReason() : null);
        return result;
    }

    /**
     * OUTIL 7 — getAllNodes
     */
    @Tool(description = "List all Jenkins agents/nodes with their current status: " +
            "online state, idle/busy, executor capacity, current load, and assigned labels. " +
            "Best tool to answer: 'How many agents are available?' or 'Which agents are busy?'")
    public List<Map<String, Object>> getAllNodes() {
        return Arrays.stream(Jenkins.get().getComputers())
                .map(c -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", c.getName().isEmpty() ? "built-in" : c.getName());
                    entry.put("displayName", c.getDisplayName());
                    entry.put("online", c.isOnline());
                    entry.put("idle", c.isIdle());
                    entry.put("numExecutors", c.getNumExecutors());
                    entry.put("busyExecutors", c.countBusy());
                    entry.put("freeExecutors", c.getNumExecutors() - c.countBusy());

                    List<String> labels = new ArrayList<>();
                    if (c.getNode() != null) {
                        c.getNode().getAssignedLabels().forEach(l -> labels.add(l.getName()));
                    }
                    entry.put("labels", labels);
                    return entry;
                })
                .collect(Collectors.toList());
    }

    /**
     * OUTIL 8 — getJenkinsSystemInfo
     */
    @Tool(description = "Get Jenkins instance system information: version, Java runtime, OS, " +
            "total job count, plugin count, node count, current queue size, " +
            "and JVM memory usage (heap used/max). " +
            "Best tool to answer: 'What version of Jenkins are we running?'")
    public Map<String, Object> getJenkinsSystemInfo() {
        Jenkins jenkins = Jenkins.get();
        Runtime rt = Runtime.getRuntime();
        long heapUsed = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L;
        long heapMax = rt.maxMemory() / 1_048_576L;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jenkinsVersion", Jenkins.VERSION);
        result.put("javaVersion", System.getProperty("java.version", "unknown"));
        result.put("javaVendor", System.getProperty("java.vendor", "unknown"));
        result.put("os", System.getProperty("os.name", "unknown")
                + " " + System.getProperty("os.arch", ""));
        result.put("totalJobCount", jenkins.getAllItems(Job.class).size());
        result.put("pluginCount", jenkins.getPluginManager().getPlugins().size());
        result.put("nodeCount", jenkins.getComputers().length);
        result.put("queueSize", jenkins.getQueue().getItems().length);
        result.put("heapUsedMb", heapUsed);
        result.put("heapMaxMb", heapMax);
        result.put("heapUsagePercent", heapMax > 0 ? Math.round(heapUsed * 100.0 / heapMax) : 0);
        result.put("systemMessage",
                jenkins.getSystemMessage() != null ? jenkins.getSystemMessage() : "");
        result.put("rootUrl",
                jenkins.getRootUrl() != null ? jenkins.getRootUrl() : "not configured");
        return result;
    }

    // =========================================================================
    // CATÉGORIE 3 — ORGANISATION & CONFIGURATION
    // =========================================================================

    /**
     * OUTIL 9 — getJobParameterDefinitions
     */
    @Tool(description = "List all parameter definitions for a parameterized Jenkins job. " +
            "Returns name, type, default value, description, and available choices " +
            "(for Choice parameters). Returns an empty list if the job has no parameters. " +
            "Best tool to answer: 'What parameters does this job accept?'")
    public List<Map<String, Object>> getJobParameterDefinitions(
            @ToolParam(description = "Full job name") String jobFullName) {

        Job<?, ?> job = resolveJob(jobFullName);

        if (!(job instanceof AbstractProject)) {
            // WorkflowJob also supports parameters via property
            hudson.model.ParametersDefinitionProperty prop = (ParametersDefinitionProperty) job
                    .getProperty(ParametersDefinitionProperty.class);
            if (prop == null)
                return Collections.emptyList();
            return buildParamList(prop);
        }

        ParametersDefinitionProperty prop = ((AbstractProject<?, ?>) job)
                .getProperty(ParametersDefinitionProperty.class);
        if (prop == null)
            return Collections.emptyList();
        return buildParamList(prop);
    }

    /**
     * OUTIL 10 — getJobDependencies
     */
    @Tool(description = "Get the upstream (jobs that trigger this job) and downstream " +
            "(jobs triggered by this job) dependency graph for a Jenkins job. " +
            "Note: Pipeline 'build' step dependencies may not appear here — " +
            "this works best for classic freestyle and Maven projects.")
    public Map<String, Object> getJobDependencies(
            @ToolParam(description = "Full job name") String jobFullName) {

        Job<?, ?> job = resolveJob(jobFullName);

        if (!(job instanceof AbstractProject)) {
            return mapOf(
                    "jobName", jobFullName,
                    "upstreamJobs", Collections.emptyList(),
                    "downstreamJobs", Collections.emptyList(),
                    "note", "Pipeline jobs (WorkflowJob) do not expose upstream/downstream via this API. " +
                            "Use getBuildChangeSets and triggerBuild links instead.");
        }

        AbstractProject<?, ?> project = (AbstractProject<?, ?>) job;
        List<String> upstream = project.getUpstreamProjects().stream()
                .map(AbstractProject::getFullName)
                .collect(Collectors.toList());
        List<String> downstream = project.getDownstreamProjects().stream()
                .map(AbstractProject::getFullName)
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("upstreamJobs", upstream);
        result.put("downstreamJobs", downstream);
        result.put("upstreamCount", upstream.size());
        result.put("downstreamCount", downstream.size());
        return result;
    }

    /**
     * OUTIL 11 — getAllViews
     */
    @Tool(description = "List all Jenkins views (tabs on the dashboard) with their name, type, " +
            "job count, and description. " +
            "Best tool to answer: 'What views/tabs exist in Jenkins?'")
    public List<Map<String, Object>> getAllViews() {
        return Jenkins.get().getViews().stream()
                .map(view -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", view.getDisplayName());
                    entry.put("type", view.getClass().getSimpleName());
                    entry.put("jobCount", view.getAllItems().size());
                    entry.put("description", view.getDescription() != null ? view.getDescription() : "");
                    entry.put("url", view.getAbsoluteUrl());
                    return entry;
                })
                .collect(Collectors.toList());
    }

    /**
     * OUTIL 12 — getViewContents
     */
    @Tool(description = "List all jobs in a specific Jenkins view with their last build result, " +
            "status, and timestamp. Returns NEVER if the job has never been built. " +
            "Best tool to answer: 'Show me all jobs in the Production view.'")
    public List<Map<String, Object>> getViewContents(
            @ToolParam(description = "View name exactly as shown in the Jenkins UI") String viewName) {

        View view = Jenkins.get().getView(viewName);
        if (view == null) {
            throw new IllegalArgumentException(
                    "View not found: '" + viewName + "'. Use getAllViews() to list available views.");
        }

        return view.getAllItems().stream()
                .filter(item -> item instanceof Job)
                .map(item -> {
                    Job<?, ?> job = (Job<?, ?>) item;
                    Run<?, ?> last = job.getLastBuild();
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("jobName", job.getFullName());
                    entry.put("url", job.getAbsoluteUrl());
                    entry.put("lastBuildNum", last != null ? last.getNumber() : null);
                    entry.put("lastResult",
                            last != null
                                    ? (last.isBuilding() ? "BUILDING"
                                            : (last.getResult() != null ? last.getResult().toString() : "BUILDING"))
                                    : "NEVER");
                    entry.put("building", last != null && last.isBuilding());
                    entry.put("lastBuiltAt", last != null ? last.getTimestampString() : null);
                    return entry;
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // CATÉGORIE 4 — SÉCURITÉ & GOUVERNANCE
    // =========================================================================

    /**
     * OUTIL 13 — getPendingScriptApprovals
     *
     * Utilise la réflexion pour ne pas créer de dépendance obligatoire
     * au plugin Script Security.
     */
    @Tool(description = "List Groovy scripts and method signatures pending admin approval " +
            "in the Script Security plugin. Returns counts and signature list. " +
            "Returns an error message if the Script Security plugin is not installed. " +
            "Best tool to answer: 'Are there any pending Groovy script approvals?'")
    public Map<String, Object> getPendingScriptApprovals() {
        try {
            Class<?> approvalClass = Class.forName(
                    "org.jenkinsci.plugins.scriptsecurity.scripts.ScriptApproval");
            Object approval = approvalClass.getMethod("get").invoke(null);

            Collection<?> pendingScripts = (Collection<?>) approvalClass.getMethod("getPendingScripts")
                    .invoke(approval);
            Collection<?> pendingSigs = (Collection<?>) approvalClass.getMethod("getPendingSignatures")
                    .invoke(approval);

            List<String> sigList = pendingSigs.stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pendingScriptCount", pendingScripts.size());
            result.put("pendingSignatureCount", pendingSigs.size());
            result.put("pendingSignatures", sigList);
            result.put("requiresAdminAction",
                    !pendingScripts.isEmpty() || !pendingSigs.isEmpty());
            return result;

        } catch (ClassNotFoundException e) {
            return mapOf(
                    "error", "Script Security Plugin is not installed.",
                    "pendingScriptCount", 0,
                    "pendingSignatureCount", 0,
                    "requiresAdminAction", false);
        } catch (Exception e) {
            return mapOf("error", "Could not read script approvals: " + e.getMessage());
        }
    }

    /**
     * OUTIL 14 — getInstalledPluginsDetails
     */
    @Tool(description = "List installed Jenkins plugins with version, active/enabled state, " +
            "update availability, and direct dependencies. " +
            "Use filter='all' for everything, 'active' for active plugins only, " +
            "'inactive' for disabled plugins, 'has-update' for plugins with available updates. " +
            "Best tool to answer: 'Which plugins have updates available?'")
    public List<Map<String, Object>> getInstalledPluginsDetails(
            @ToolParam(description = "Filter: 'all', 'active', 'inactive', or 'has-update'") String filter) {

        String f = filter == null ? "all" : filter.toLowerCase().trim();

        return Jenkins.get().getPluginManager().getPlugins().stream()
                .filter(p -> {
                    switch (f) {
                        case "active":
                            return p.isActive();
                        case "inactive":
                            return !p.isActive();
                        case "has-update":
                            return p.hasUpdate();
                        default:
                            return true;
                    }
                })
                .sorted(Comparator.comparing(hudson.PluginWrapper::getShortName))
                .map(p -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("shortName", p.getShortName());
                    entry.put("longName", p.getLongName());
                    entry.put("version", p.getVersion());
                    entry.put("active", p.isActive());
                    entry.put("enabled", p.isEnabled());
                    entry.put("hasUpdate", p.hasUpdate());
                    entry.put("dependencies", p.getDependencies().stream()
                            .map(d -> d.shortName + "@" + d.version)
                            .collect(Collectors.toList()));
                    return entry;
                })
                .collect(Collectors.toList());
    }

    /**
     * OUTIL 15 — getBuildQueueDetails
     */
    @Tool(description = "Get detailed information about all items currently waiting in the Jenkins build queue. " +
            "Shows job name, human-readable blocking reason, time spent waiting, " +
            "build parameters, and required agent label. " +
            "Returns an empty list if the queue is empty. " +
            "Best tool to answer: 'Why is my build not starting?' or 'What is stuck in the queue?'")
    public List<Map<String, Object>> getBuildQueueDetails() {
        Queue.Item[] items = Jenkins.get().getQueue().getItems();
        long now = System.currentTimeMillis();

        return Arrays.stream(items)
                .map(item -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", item.getId());
                    entry.put("jobName", item.task.getFullDisplayName());
                    entry.put("why", item.getWhy());
                    entry.put("blocked", item.isBlocked());
                    entry.put("buildable", item.isBuildable());
                    entry.put("inQueueSince", humanDuration(now - item.getInQueueSince()));
                    entry.put("inQueueMs", now - item.getInQueueSince());
                    entry.put("params", item.getParams());

                    // Assigned label if it's a BuildableItem
                    if (item instanceof Queue.BuildableItem) {
                        Queue.BuildableItem bi = (Queue.BuildableItem) item;
                        try {
                            Object label = bi.getClass().getField("assignedLabel").get(bi);
                            entry.put("assignedLabel",
                                    label != null ? label.getClass().getMethod("getName").invoke(label) : null);
                        } catch (Exception e) {
                            entry.put("assignedLabel", null);
                        }
                    }
                    return entry;
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // HELPERS PRIVÉS
    // =========================================================================

    /**
     * Résout un Job par son nom complet. Lance IllegalArgumentException si absent.
     */
    private Job<?, ?> resolveJob(String jobFullName) {
        if (jobFullName == null || jobFullName.isBlank()) {
            throw new IllegalArgumentException("jobFullName must not be null or empty.");
        }
        Job<?, ?> job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job == null) {
            throw new IllegalArgumentException(
                    "Job not found: '" + jobFullName + "'. " +
                            "Use getJobs() to list available jobs.");
        }
        return job;
    }

    /**
     * Résout un Run. buildNumber=-1 → dernier build.
     */
    private Run<?, ?> resolveRun(String jobFullName, int buildNumber) {
        Job<?, ?> job = resolveJob(jobFullName);
        Run<?, ?> run;
        if (buildNumber == -1) {
            run = job.getLastBuild();
            if (run == null) {
                throw new IllegalArgumentException(
                        "Job '" + jobFullName + "' has never been built.");
            }
        } else {
            run = job.getBuildByNumber(buildNumber);
            if (run == null) {
                throw new IllegalArgumentException(
                        "Build #" + buildNumber + " not found for job '" + jobFullName + "'.");
            }
        }
        return run;
    }

    /**
     * Convertit une durée en ms en format lisible.
     */
    private String humanDuration(long ms) {
        if (ms < 0)
            return "0ms";
        if (ms < 1_000)
            return ms + "ms";
        if (ms < 60_000)
            return (ms / 1_000) + "s";
        if (ms < 3_600_000) {
            long min = ms / 60_000;
            long sec = (ms % 60_000) / 1_000;
            return sec > 0 ? min + "m " + sec + "s" : min + "m";
        }
        long hr = ms / 3_600_000;
        long min = (ms % 3_600_000) / 60_000;
        return min > 0 ? hr + "h " + min + "m" : hr + "h";
    }

    /**
     * Convertit une taille de fichier en bytes en format lisible.
     */
    private String humanFileSize(long bytes) {
        if (bytes < 1_024)
            return bytes + " B";
        if (bytes < 1_048_576)
            return (bytes / 1_024) + " KB";
        if (bytes < 1_073_741_824)
            return String.format("%.1f MB", bytes / 1_048_576.0);
        return String.format("%.2f GB", bytes / 1_073_741_824.0);
    }

    /**
     * Calcule la tendance de stabilité d'un job.
     * Compare le taux de succès de la première moitié (récente) vs la seconde.
     */
    private String computeTrend(List<? extends Run<?, ?>> builds) {
        if (builds.size() < 4)
            return "INSUFFICIENT_DATA";
        int half = builds.size() / 2;
        // La liste Jenkins est triée du plus récent au plus ancien
        double recentRate = builds.subList(0, half).stream()
                .filter(b -> b.getResult() == Result.SUCCESS).count() * 100.0 / half;
        double olderRate = builds.subList(half, builds.size()).stream()
                .filter(b -> b.getResult() == Result.SUCCESS).count() * 100.0 / (builds.size() - half);

        if (recentRate - olderRate > 10)
            return "IMPROVING";
        if (olderRate - recentRate > 10)
            return "DEGRADING";
        return "STABLE";
    }

    /**
     * Construit la liste de paramètres à partir d'une ParametersDefinitionProperty.
     */
    private List<Map<String, Object>> buildParamList(ParametersDefinitionProperty prop) {
        return prop.getParameterDefinitions().stream().map(pd -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", pd.getName());
            entry.put("type", pd.getClass().getSimpleName()
                    .replace("ParameterDefinition", ""));
            entry.put("description", pd.getDescription() != null ? pd.getDescription() : "");

            // Valeur par défaut
            try {
                entry.put("defaultValue",
                        pd.getDefaultParameterValue() != null
                                ? pd.getDefaultParameterValue().getValue()
                                : null);
            } catch (Exception e) {
                entry.put("defaultValue", null);
            }

            // Choix pour ChoiceParameterDefinition
            try {
                if (pd.getClass().getSimpleName().equals("ChoiceParameterDefinition")) {
                    List<?> choices = (List<?>) pd.getClass().getMethod("getChoices").invoke(pd);
                    entry.put("choices", choices);
                }
            } catch (Exception ignored) {
                /* pas un Choice param */ }

            return entry;
        }).collect(Collectors.toList());
    }

    /**
     * Helper pour créer des Map.of() avec des clés/valeurs sans limite de 10
     * entrées.
     * (Map.of() est limité à 10 paires en Java standard)
     */
    @SafeVarargs
    private static Map<String, Object> mapOf(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("mapOf requires an even number of arguments");
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }
}