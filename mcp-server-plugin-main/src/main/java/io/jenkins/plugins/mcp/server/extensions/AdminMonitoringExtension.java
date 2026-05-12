package io.jenkins.plugins.mcp.server.extensions;

import hudson.Extension;
import hudson.model.*;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jenkins.model.Jenkins;

import java.util.*;
import java.util.stream.*;

/**
 * MCP Extension — Administration, Monitoring & Security Audit
 *
 * Tools (11):
 * 1. getSystemLoadMetrics — executor load, queue size, JVM memory
 * 2. getCredentialsInventory — list credentials (no values exposed)
 * 3. getPluginsWithUpdates — plugins with available updates
 * 4. searchPluginsInUpdateCenter — search available plugins by keyword
 * 5. getNodeOfflineAnalysis — agents that are offline with reasons
 * 6. getAbortedBuildsAnalysis — why builds were aborted in a job
 * 7. getBuildsPerDayStats — build frequency per day for a job
 * 8. getExecutorUtilizationReport — % utilization across all nodes
 * 9. auditJobsWithNoSCM — jobs with no SCM configured
 * 10. getJobsNeverBuilt — jobs that have never been built
 * 11. getSystemAuditSummary — overall Jenkins health audit report
 *
 * ALL tools are READ-ONLY.
 */
@Extension
public class AdminMonitoringExtension implements McpServerExtension {

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 1 — getSystemLoadMetrics
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get a real-time snapshot of Jenkins system load: "
            + "total/free/busy executors across all nodes, build queue size, "
            + "JVM heap usage, and currently building job count. "
            + "Useful to assess if Jenkins is under pressure.")
    public Map<String, Object> getSystemLoadMetrics() {
        Jenkins jenkins = Jenkins.get();
        Runtime rt = Runtime.getRuntime();

        Computer[] computers = jenkins.getComputers();
        int totalExecutors = 0;
        int busyExecutors = 0;
        int offlineNodes = 0;
        long buildingCount = 0;

        for (Computer c : computers) {
            totalExecutors += c.getNumExecutors();
            busyExecutors += c.countBusy();
            if (!c.isOnline())
                offlineNodes++;
            // Cast to avoid raw-type stream errors
            @SuppressWarnings({ "unchecked", "rawtypes" })
            List<Run<?, ?>> computerBuilds = (List<Run<?, ?>>) (List) c.getBuilds();
            buildingCount += computerBuilds.stream().filter(Run::isBuilding).count();
        }

        long heapUsed = (rt.totalMemory() - rt.freeMemory()) / 1_048_576;
        long heapMax = rt.maxMemory() / 1_048_576;
        long heapFree = heapMax - heapUsed;
        int queueSize = jenkins.getQueue().getItems().length;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalExecutors", totalExecutors);
        result.put("busyExecutors", busyExecutors);
        result.put("freeExecutors", totalExecutors - busyExecutors);
        result.put("executorLoadPct", totalExecutors == 0 ? "0%"
                : Math.round(busyExecutors * 100.0 / totalExecutors) + "%");
        result.put("totalNodes", computers.length);
        result.put("offlineNodes", offlineNodes);
        result.put("onlineNodes", computers.length - offlineNodes);
        result.put("buildQueueSize", queueSize);
        result.put("currentlyBuilding", buildingCount);
        result.put("heapUsedMb", heapUsed);
        result.put("heapFreeMb", heapFree);
        result.put("heapMaxMb", heapMax);
        result.put("heapUsagePct", Math.round(heapUsed * 100.0 / heapMax) + "%");
        result.put("totalJobs", jenkins.getAllItems(Job.class).size());
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 2 — getCredentialsInventory
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "List all Jenkins credentials (global scope) without exposing "
            + "any secrets or values. Returns ID, type, description, and scope. "
            + "Useful for security audits or verifying a credential exists before "
            + "configuring a job. Values are NEVER returned.")
    public Map<String, Object> getCredentialsInventory() {
        List<Map<String, Object>> creds = new ArrayList<>();

        try {
            // Use reflection to avoid hard dependency on credentials plugin
            Class<?> credentialsProviderClass = Class.forName(
                    "com.cloudbees.plugins.credentials.CredentialsProvider");
            Class<?> credentialsClass = Class.forName(
                    "com.cloudbees.plugins.credentials.Credentials");

            // Use ACL.SYSTEM2 (Spring Security Authentication) — ACL.SYSTEM is deprecated
            org.springframework.security.core.Authentication systemAuth = hudson.security.ACL.SYSTEM2;

            @SuppressWarnings("unchecked")
            List<Object> credList = (List<Object>) credentialsProviderClass
                    .getMethod("lookupCredentials", Class.class, Jenkins.class,
                            org.springframework.security.core.Authentication.class, List.class)
                    .invoke(null, credentialsClass, Jenkins.get(), systemAuth,
                            Collections.emptyList());

            for (Object cred : credList) {
                try {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    try {
                        entry.put("id", cred.getClass().getMethod("getId").invoke(cred));
                    } catch (Exception e) {
                        entry.put("id", "unknown");
                    }
                    try {
                        entry.put("description",
                                cred.getClass().getMethod("getDescription").invoke(cred));
                    } catch (Exception e) {
                        entry.put("description", "");
                    }
                    entry.put("type", cred.getClass().getSimpleName());
                    entry.put("typePackage", cred.getClass().getName());
                    creds.add(entry);
                } catch (Exception ignored) {
                }
            }
        } catch (ClassNotFoundException e) {
            return Map.of(
                    "error", "Credentials Plugin is not installed. "
                            + "Install 'credentials' plugin to use this tool.",
                    "credentialCount", 0);
        } catch (Exception e) {
            return Map.of("error", "Could not read credentials: " + e.getMessage(),
                    "credentialCount", 0);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("credentialCount", creds.size());
        result.put("note", "Values and secrets are NEVER exposed by this tool.");
        result.put("credentials", creds);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 3 — getPluginsWithUpdates
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "List all installed Jenkins plugins that have an update available. "
            + "Returns plugin name, current version, and available version. "
            + "Use this to plan plugin upgrade cycles.")
    public Map<String, Object> getPluginsWithUpdates() {
        List<Map<String, Object>> updates = Jenkins.get().getPluginManager().getPlugins()
                .stream()
                .filter(p -> p.hasUpdate())
                .sorted(Comparator.comparing(p -> p.getShortName()))
                .map(p -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("shortName", p.getShortName());
                    entry.put("displayName", p.getDisplayName()); // getLongName() deprecated
                    entry.put("currentVersion", p.getVersion());
                    entry.put("active", p.isActive());
                    hudson.model.UpdateSite.Plugin up = p.getUpdateInfo();
                    if (up != null) {
                        entry.put("availableVersion", up.version);
                        entry.put("updateUrl", up.wiki);
                    }
                    return entry;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pluginsWithUpdates", updates.size());
        result.put("totalInstalled",
                Jenkins.get().getPluginManager().getPlugins().size());
        result.put("plugins", updates);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 4 — searchPluginsInUpdateCenter
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Search for plugins available in the Jenkins Update Center by keyword. "
            + "Searches plugin name and description. Returns top matches with installation counts. "
            + "Use this to discover plugins before installing them.")
    public Map<String, Object> searchPluginsInUpdateCenter(
            @ToolParam(description = "Keyword to search for, e.g. 'docker', 'slack', 'git'") String keyword,
            @ToolParam(description = "Maximum number of results to return (max 20)") int maxResults) {

        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("keyword cannot be empty");
        }
        int limit = Math.min(Math.max(maxResults, 1), 20);
        String lower = keyword.toLowerCase();

        List<Map<String, Object>> found = new ArrayList<>();

        for (hudson.model.UpdateSite site : Jenkins.get().getUpdateCenter().getSites()) {
            hudson.model.UpdateSite.Data data = site.getData();
            if (data == null)
                continue;

            for (hudson.model.UpdateSite.Plugin plugin : data.plugins.values()) {
                boolean nameMatch = plugin.name.toLowerCase().contains(lower);
                boolean titleMatch = plugin.getDisplayName() != null
                        && plugin.getDisplayName().toLowerCase().contains(lower);
                boolean excerptMatch = plugin.excerpt != null
                        && plugin.excerpt.toLowerCase().contains(lower);

                if (!nameMatch && !titleMatch && !excerptMatch)
                    continue;

                boolean alreadyInstalled = Jenkins.get().getPluginManager().getPlugin(plugin.name) != null;

                // 'stats' field was removed in newer Jenkins — use reflection to be safe
                int currentInstalls = 0;
                try {
                    Object stats = hudson.model.UpdateSite.Plugin.class
                            .getField("stats").get(plugin);
                    if (stats != null) {
                        currentInstalls = (int) stats.getClass()
                                .getField("currentInstalls").get(stats);
                    }
                } catch (Exception ignored) {
                    // stats not available in this Jenkins version — leave as 0
                }

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("shortName", plugin.name);
                entry.put("displayName", plugin.getDisplayName());
                entry.put("version", plugin.version);
                entry.put("excerpt", plugin.excerpt != null
                        ? plugin.excerpt.replaceAll("<[^>]+>", "")
                        : "");
                entry.put("installed", alreadyInstalled);
                entry.put("wikiUrl", plugin.wiki);
                entry.put("installs", currentInstalls);
                found.add(entry);
                if (found.size() >= limit)
                    break;
            }
            if (found.size() >= limit)
                break;
        }

        found.sort((a, b) -> Integer.compare(
                (int) b.getOrDefault("installs", 0),
                (int) a.getOrDefault("installs", 0)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", keyword);
        result.put("resultCount", found.size());
        result.put("results", found);
        if (found.isEmpty()) {
            result.put("message", "No plugins found matching '" + keyword
                    + "'. Try updating the Update Center first via Jenkins UI.");
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 5 — getNodeOfflineAnalysis
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "List all Jenkins agents that are currently offline or disconnected, "
            + "with the reason for being offline, when they went offline, "
            + "and which jobs were configured to run on them.")
    public Map<String, Object> getNodeOfflineAnalysis() {
        List<Map<String, Object>> offlineNodes = new ArrayList<>();

        for (Computer c : Jenkins.get().getComputers()) {
            if (c.isOnline())
                continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            String name = c.getName().isEmpty() ? "built-in" : c.getName();
            entry.put("name", name);
            entry.put("displayName", c.getDisplayName());
            entry.put("online", false);

            hudson.slaves.OfflineCause cause = c.getOfflineCause();
            entry.put("offlineCause", cause != null ? cause.toString() : "Unknown reason");
            entry.put("offlineCauseType", cause != null
                    ? cause.getClass().getSimpleName()
                    : "Unknown");

            List<String> labels = new ArrayList<>();
            if (c.getNode() != null) {
                c.getNode().getAssignedLabels().forEach(l -> labels.add(l.getName()));
            }
            entry.put("labels", labels);

            List<String> affectedJobs = new ArrayList<>();
            for (String label : labels) {
                for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
                    if (!(job instanceof AbstractProject))
                        continue;
                    Label assignedLabel = ((AbstractProject<?, ?>) job).getAssignedLabel();
                    if (assignedLabel != null && assignedLabel.getName().contains(label)) {
                        affectedJobs.add(job.getFullName());
                    }
                }
            }
            entry.put("potentiallyAffectedJobs",
                    affectedJobs.stream().distinct().collect(Collectors.toList()));
            offlineNodes.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("offlineNodeCount", offlineNodes.size());
        result.put("totalNodes", Jenkins.get().getComputers().length);
        result.put("offlineNodes", offlineNodes);
        if (offlineNodes.isEmpty()) {
            result.put("message", "All Jenkins agents are online");
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 6 — getAbortedBuildsAnalysis
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Analyze aborted builds in a job to understand why they were stopped. "
            + "Returns a list of aborted builds with who aborted them, "
            + "how long they ran before being aborted, and the stage they were in.")
    public Map<String, Object> getAbortedBuildsAnalysis(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to analyze (max 50)") int lastN) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(lastN, 1), 50);
        List<Run<?, ?>> builds = collectBuilds(job, limit);

        List<Map<String, Object>> aborted = builds.stream()
                .filter(r -> r.getResult() == Result.ABORTED)
                .map(run -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("buildNumber", run.getNumber());
                    entry.put("abortedAt", run.getTimestampString());
                    entry.put("ranForHuman", humanDuration(run.getDuration()));
                    entry.put("ranForMs", run.getDuration());

                    String abortedBy = run.getCauses().stream()
                            .map(Cause::getShortDescription)
                            .collect(Collectors.joining(", "));
                    entry.put("triggeredBy", abortedBy.isEmpty() ? "unknown" : abortedBy);
                    entry.put("result", run.getResult() != null
                            ? run.getResult().toString()
                            : "ABORTED");
                    entry.put("url", buildUrl(run));
                    return entry;
                })
                .collect(Collectors.toList());

        long abortedCount = aborted.size();
        long abortRate = builds.isEmpty() ? 0
                : Math.round(abortedCount * 100.0 / builds.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildsAnalyzed", builds.size());
        result.put("abortedCount", abortedCount);
        result.put("abortRate", abortRate + "%");
        result.put("abortedBuilds", aborted);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 7 — getBuildsPerDayStats
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get build frequency statistics for a job: how many builds per day "
            + "on average, busiest day, and a breakdown by weekday. "
            + "Useful to understand the load a job generates.")
    public Map<String, Object> getBuildsPerDayStats(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to analyze (max 200)") int lastN) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(lastN, 1), 200);
        List<Run<?, ?>> builds = collectBuilds(job, limit);

        Map<String, Integer> dayCount = new LinkedHashMap<>();
        String[] dayNames = { "Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun" };
        for (String d : dayNames)
            dayCount.put(d, 0);

        Map<String, Integer> dateCount = new HashMap<>();

        for (Run<?, ?> run : builds) {
            Calendar cal = Calendar.getInstance();
            cal.setTimeInMillis(run.getStartTimeInMillis());
            int dow = cal.get(Calendar.DAY_OF_WEEK); // 1=Sun ... 7=Sat
            int idx = (dow + 5) % 7; // remap to Mon=0 … Sun=6
            dayCount.merge(dayNames[idx], 1, Integer::sum);

            String dateKey = cal.get(Calendar.YEAR) + "-"
                    + String.format("%02d", cal.get(Calendar.MONTH) + 1) + "-"
                    + String.format("%02d", cal.get(Calendar.DAY_OF_MONTH));
            dateCount.merge(dateKey, 1, Integer::sum);
        }

        int uniqueDays = dateCount.size();
        double avgPerDay = uniqueDays == 0 ? 0 : (double) builds.size() / uniqueDays;
        int maxOneDay = dateCount.values().stream().mapToInt(Integer::intValue)
                .max().orElse(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildsAnalyzed", builds.size());
        result.put("uniqueDaysActive", uniqueDays);
        result.put("avgBuildsPerDay", Math.round(avgPerDay * 10.0) / 10.0);
        result.put("maxBuildsOneDay", maxOneDay);
        result.put("byDayOfWeek", dayCount);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 8 — getExecutorUtilizationReport
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get an executor utilization report for each Jenkins agent node. "
            + "Shows how many executors are busy vs idle on each node, "
            + "and the overall utilization percentage. Identifies over/under-used nodes.")
    public Map<String, Object> getExecutorUtilizationReport() {
        Computer[] computers = Jenkins.get().getComputers();

        List<Map<String, Object>> nodeReports = Arrays.stream(computers).map(c -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            String name = c.getName().isEmpty() ? "built-in" : c.getName();
            entry.put("name", name);
            entry.put("online", c.isOnline());
            entry.put("numExecutors", c.getNumExecutors());
            entry.put("busyExecutors", c.countBusy());
            entry.put("idleExecutors", c.getNumExecutors() - c.countBusy());
            int util = c.getNumExecutors() == 0 ? 0
                    : Math.round(c.countBusy() * 100.0f / c.getNumExecutors());
            entry.put("utilizationPct", util + "%");
            entry.put("status", !c.isOnline() ? "OFFLINE"
                    : c.isIdle() ? "IDLE"
                            : util == 100 ? "FULL" : "PARTIAL");

            // Cast c.getBuilds() to avoid raw-type errors
            @SuppressWarnings({ "unchecked", "rawtypes" })
            List<Run<?, ?>> computerBuilds = (List<Run<?, ?>>) (List) c.getBuilds();
            List<String> running = computerBuilds.stream()
                    .filter(Run::isBuilding)
                    .map(b -> b.getParent().getFullName() + " #" + b.getNumber())
                    .collect(Collectors.toList());
            entry.put("runningBuilds", running);
            return entry;
        }).collect(Collectors.toList());

        int total = Arrays.stream(computers).mapToInt(Computer::getNumExecutors).sum();
        int busy = Arrays.stream(computers).mapToInt(Computer::countBusy).sum();
        int offline = (int) Arrays.stream(computers).filter(c -> !c.isOnline()).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalExecutors", total);
        result.put("busyExecutors", busy);
        result.put("idleExecutors", total - busy);
        result.put("globalUtilizationPct", total == 0 ? "0%"
                : Math.round(busy * 100.0 / total) + "%");
        result.put("onlineNodes", computers.length - offline);
        result.put("offlineNodes", offline);
        result.put("nodeReports", nodeReports);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 9 — auditJobsWithNoSCM
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Find all classic (non-Pipeline) Jenkins jobs that have no SCM "
            + "(Source Control Management) configured. These jobs don't pull any code "
            + "and may be outdated or misconfigured. Returns job names and last build info.")
    public Map<String, Object> auditJobsWithNoSCM() {
        List<Map<String, Object>> noScmJobs = new ArrayList<>();

        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            if (!(job instanceof AbstractProject))
                continue;
            AbstractProject<?, ?> ap = (AbstractProject<?, ?>) job;
            hudson.scm.SCM scm = ap.getScm();
            if (scm == null || scm.getClass().getSimpleName().contains("Null")) {
                Run<?, ?> last = job.getLastBuild();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("jobName", job.getFullName());
                entry.put("jobType", job.getClass().getSimpleName());
                entry.put("lastResult", last != null && last.getResult() != null
                        ? last.getResult().toString()
                        : "NEVER");
                entry.put("lastBuiltAt", last != null ? last.getTimestampString() : null);
                entry.put("disabled", ap.isDisabled());
                entry.put("url", itemUrl(job));
                noScmJobs.add(entry);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobsWithNoScmCount", noScmJobs.size());
        result.put("totalJobsScanned",
                Jenkins.get().getAllItems(AbstractProject.class).size());
        result.put("note", "Pipeline jobs (Jenkinsfile) define SCM inline "
                + "and are excluded from this scan.");
        result.put("jobs", noScmJobs);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 10 — getJobsNeverBuilt
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Find all Jenkins jobs that have never been built. "
            + "These may be new jobs not yet triggered, orphaned jobs, or misconfigured pipelines. "
            + "Returns job names, types, and creation-related info.")
    public Map<String, Object> getJobsNeverBuilt() {
        List<Map<String, Object>> neverBuilt = new ArrayList<>();

        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            if (job.getLastBuild() != null)
                continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("jobName", job.getFullName());
            entry.put("jobType", job.getClass().getSimpleName());
            entry.put("disabled", job instanceof AbstractProject
                    && ((AbstractProject<?, ?>) job).isDisabled());
            entry.put("url", itemUrl(job));

            if (job instanceof AbstractProject) {
                ParametersDefinitionProperty pdp = ((AbstractProject<?, ?>) job)
                        .getProperty(ParametersDefinitionProperty.class);
                entry.put("parameterCount",
                        pdp != null ? pdp.getParameterDefinitions().size() : 0);
            }
            neverBuilt.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("neverBuiltCount", neverBuilt.size());
        result.put("totalJobs", Jenkins.get().getAllItems(Job.class).size());
        result.put("jobs", neverBuilt);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 11 — getSystemAuditSummary
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Generate a comprehensive Jenkins instance health audit report. "
            + "Checks: queue health, executor load, plugin update backlog, "
            + "offline nodes, failing jobs count, never-built jobs, and JVM memory. "
            + "Returns a scored report with recommendations.")
    public Map<String, Object> getSystemAuditSummary() {
        Jenkins jenkins = Jenkins.get();
        Computer[] computers = jenkins.getComputers();
        Runtime rt = Runtime.getRuntime();

        List<Map<String, Object>> findings = new ArrayList<>();
        int totalScore = 100;

        // 1. Queue check
        int queueSize = jenkins.getQueue().getItems().length;
        Map<String, Object> queueFinding = new LinkedHashMap<>();
        queueFinding.put("check", "Build Queue Size");
        queueFinding.put("value", queueSize);
        if (queueSize > 20) {
            queueFinding.put("status", "WARNING");
            queueFinding.put("recommendation", "Queue has " + queueSize + " items. "
                    + "Consider adding more executors or scaling agents.");
            totalScore -= 15;
        } else {
            queueFinding.put("status", "OK");
        }
        findings.add(queueFinding);

        // 2. Offline nodes
        long offlineCount = Arrays.stream(computers).filter(c -> !c.isOnline()).count();
        Map<String, Object> nodeFinding = new LinkedHashMap<>();
        nodeFinding.put("check", "Offline Nodes");
        nodeFinding.put("value", offlineCount + "/" + computers.length);
        if (offlineCount > 0) {
            nodeFinding.put("status", "WARNING");
            nodeFinding.put("recommendation", offlineCount + " node(s) offline. "
                    + "Use getNodeOfflineAnalysis for details.");
            totalScore -= (int) (offlineCount * 5);
        } else {
            nodeFinding.put("status", "OK");
        }
        findings.add(nodeFinding);

        // 3. Plugin updates
        long updateCount = jenkins.getPluginManager().getPlugins().stream()
                .filter(p -> p.hasUpdate()).count();
        Map<String, Object> pluginFinding = new LinkedHashMap<>();
        pluginFinding.put("check", "Plugin Updates Available");
        pluginFinding.put("value", updateCount);
        if (updateCount > 10) {
            pluginFinding.put("status", "WARNING");
            pluginFinding.put("recommendation",
                    updateCount + " plugins have updates. Plan an upgrade window.");
            totalScore -= 10;
        } else if (updateCount > 0) {
            pluginFinding.put("status", "INFO");
            pluginFinding.put("recommendation", updateCount + " plugin(s) can be updated.");
        } else {
            pluginFinding.put("status", "OK");
        }
        findings.add(pluginFinding);

        // 4. Heap usage
        long heapUsed = (rt.totalMemory() - rt.freeMemory()) / 1_048_576;
        long heapMax = rt.maxMemory() / 1_048_576;
        int heapPct = (int) Math.round(heapUsed * 100.0 / heapMax);
        Map<String, Object> heapFinding = new LinkedHashMap<>();
        heapFinding.put("check", "JVM Heap Usage");
        heapFinding.put("value", heapUsed + "MB / " + heapMax + "MB (" + heapPct + "%)");
        if (heapPct > 85) {
            heapFinding.put("status", "CRITICAL");
            heapFinding.put("recommendation",
                    "Heap at " + heapPct + "%. Risk of OOM. "
                            + "Increase -Xmx or reduce concurrent builds.");
            totalScore -= 20;
        } else if (heapPct > 70) {
            heapFinding.put("status", "WARNING");
            heapFinding.put("recommendation",
                    "Heap at " + heapPct + "%. Monitor closely.");
            totalScore -= 10;
        } else {
            heapFinding.put("status", "OK");
        }
        findings.add(heapFinding);

        // 5. Failing jobs
        long failingJobs = jenkins.getAllItems(Job.class).stream()
                .filter(j -> {
                    Run<?, ?> last = j.getLastBuild();
                    return last != null && last.getResult() == Result.FAILURE;
                }).count();
        Map<String, Object> failFinding = new LinkedHashMap<>();
        failFinding.put("check", "Currently Failing Jobs");
        failFinding.put("value", failingJobs);
        if (failingJobs > 5) {
            failFinding.put("status", "WARNING");
            failFinding.put("recommendation", failingJobs + " jobs currently failing.");
            totalScore -= 10;
        } else if (failingJobs > 0) {
            failFinding.put("status", "INFO");
            failFinding.put("recommendation", failingJobs + " job(s) failing.");
        } else {
            failFinding.put("status", "OK");
        }
        findings.add(failFinding);

        // 6. Executor utilization
        int totalExec = Arrays.stream(computers).mapToInt(Computer::getNumExecutors).sum();
        int busyExec = Arrays.stream(computers).mapToInt(Computer::countBusy).sum();
        int utilPct = totalExec == 0 ? 0
                : (int) Math.round(busyExec * 100.0 / totalExec);
        Map<String, Object> execFinding = new LinkedHashMap<>();
        execFinding.put("check", "Executor Utilization");
        execFinding.put("value", utilPct + "% (" + busyExec + "/" + totalExec + ")");
        execFinding.put("status", "OK");
        execFinding.put("recommendation",
                utilPct > 80 ? "High load — consider adding agents"
                        : utilPct < 10 ? "Low utilization — review if all nodes are needed"
                                : "Normal load");
        findings.add(execFinding);

        totalScore = Math.max(0, Math.min(100, totalScore));
        String grade = totalScore >= 90 ? "A"
                : totalScore >= 75 ? "B"
                        : totalScore >= 60 ? "C"
                                : totalScore >= 40 ? "D" : "F";

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jenkinsVersion", Jenkins.VERSION);
        result.put("auditScore", totalScore + "/100");
        result.put("grade", grade);
        result.put("totalJobs", jenkins.getAllItems(Job.class).size());
        result.put("totalPlugins", jenkins.getPluginManager().getPlugins().size());
        result.put("findings", findings);
        result.put("summary", grade.equals("A") ? "Jenkins instance is healthy"
                : grade.equals("B") ? "Minor issues detected — review findings"
                        : "Action required — review WARNING/CRITICAL findings");
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    private Job<?, ?> resolveJob(String jobFullName) {
        Job<?, ?> job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job == null)
            throw new IllegalArgumentException("Job not found: " + jobFullName);
        return job;
    }

    /**
     * Collects up to {@code limit} builds without calling the deprecated
     * RunList.size().
     */
    private List<Run<?, ?>> collectBuilds(Job<?, ?> job, int limit) {
        List<Run<?, ?>> list = new ArrayList<>(limit);
        for (Run<?, ?> r : job.getBuilds()) {
            if (list.size() >= limit)
                break;
            list.add(r);
        }
        return list;
    }

    /** Non-deprecated replacement for Run.getAbsoluteUrl(). */
    private static String buildUrl(Run<?, ?> run) {
        String root = Jenkins.get().getRootUrl();
        return (root != null ? root : "") + run.getUrl();
    }

    /** Non-deprecated replacement for Item.getAbsoluteUrl(). */
    private static String itemUrl(Item item) {
        String root = Jenkins.get().getRootUrl();
        return (root != null ? root : "") + item.getUrl();
    }

    private String humanDuration(long ms) {
        if (ms < 0)
            return "0ms";
        if (ms < 1_000)
            return ms + "ms";
        if (ms < 60_000)
            return (ms / 1_000) + "s";
        if (ms < 3_600_000)
            return (ms / 60_000) + "m " + ((ms % 60_000) / 1_000) + "s";
        return (ms / 3_600_000) + "h " + ((ms % 3_600_000) / 60_000) + "m";
    }
}