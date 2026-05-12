package io.jenkins.plugins.mcp.server.extensions;

import hudson.Extension;
import hudson.model.*;
import hudson.scm.ChangeLogSet;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jenkins.model.Jenkins;

import java.util.*;
import java.util.stream.*;

/**
 * MCP Extension — SCM Deep Dive & Fingerprint Tracking
 *
 * Uses Jenkins INTERNAL Java API — specific classes not accessible via REST:
 * - SCMTrigger.PollingLog — internal polling log per job
 * - Fingerprint / FingerprintAction — artifact origin tracking
 * - ChangeLogSet.Entry.getAffectedFiles() — file-level change data
 * - BuildData.getScmRevisionBuildData() — multi-scm revision info
 * - AbstractBuild.getSCMRevisionState() — SCM state snapshot per build
 * - CauseOfInterruption — chain interruption introspection
 *
 * Tools (11):
 * 1. getScmPollingLog — last SCM polling log for a job
 * 2. getScmPollingHistory — polling history (triggered vs no changes)
 * 3. getFingerprintInfo — fingerprint/artifact origin tracking
 * 4. getChangeSummaryByAuthor — commits grouped by author for a build range
 * 5. getFilesChangedAcrossBuilds — all unique files changed over N builds
 * 6. getHotspotFiles — most frequently modified files (change hotspots)
 * 7. getChangeVelocity — commit rate (commits/day) trend for a job
 * 8. getBuildsByAuthor — builds that include commits from a specific author
 * 9. getScmRevisionState — SCM revision state snapshot per build
 * 10. getMultiScmBuildData — metadata for jobs with multiple SCM sources
 * 11. getChangeSetDiff — file-level diff summary between two builds
 *
 * ALL tools are READ-ONLY.
 */
@Extension
public class ScmDeepDiveExtension implements McpServerExtension {

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 1 — getScmPollingLog
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get the last SCM polling log for a job — the detailed output of "
            + "Jenkins checking if the repository has changes. "
            + "Uses SCMTrigger internal PollingLog — not available via REST API. "
            + "Helps diagnose why a job is not triggering automatically.")
    public Map<String, Object> getScmPollingLog(
            @ToolParam(description = "Full job name to check polling log for") String jobFullName,
            @ToolParam(description = "Max lines of polling log to return (max 200)") int maxLines) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(maxLines, 1), 200);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);

        try {
            // SCMTrigger.PollingLog is stored as an action on the job
            for (Object trigger : getTriggers(job)) {
                String triggerClass = trigger.getClass().getName();
                if (!triggerClass.contains("SCMTrigger"))
                    continue;

                // Get the polling log
                try {
                    Object pollingLog = trigger.getClass()
                            .getMethod("getPollingSchedule").invoke(trigger);
                    result.put("pollingSchedule", pollingLog != null ? pollingLog.toString() : "none");
                } catch (Exception ignored) {
                }

                // Get last polling run
                try {
                    Object lastPolling = trigger.getClass()
                            .getMethod("getLastSuccessfulPollingLog").invoke(trigger);
                    if (lastPolling != null) {
                        result.put("lastPollingRunFound", true);
                        try {
                            String log = (String) lastPolling.getClass()
                                    .getMethod("readLines").invoke(lastPolling);
                            String[] lines = log.split("\n");
                            int from = Math.max(0, lines.length - limit);
                            result.put("logLines", lines.length);
                            result.put("logTail", String.join("\n",
                                    Arrays.copyOfRange(lines, from, lines.length)));
                            result.put("hasChanges", log.contains("Changes found")
                                    || log.contains("Found change"));
                            result.put("noChanges", log.contains("No changes")
                                    || log.contains("Already at the revision"));
                        } catch (Exception e) {
                            result.put("logError", e.getMessage());
                        }
                    } else {
                        result.put("message", "No polling log available yet — "
                                + "polling may not have run or SCMTrigger is not configured.");
                    }
                } catch (Exception e) {
                    result.put("pollingLogError", e.getMessage());
                }
                return result;
            }
            result.put("message", "No SCMTrigger found for this job. "
                    + "The job may use webhook or manual triggering instead of polling.");
        } catch (Exception e) {
            result.put("error", "Failed to read polling log: " + e.getMessage());
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 2 — getScmPollingHistory
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get the SCM polling history for a job from internal build actions: "
            + "for each build, whether it was triggered by SCM polling or manually, "
            + "and the last known polling result. "
            + "Identifies jobs that rarely have changes (low-value polling).")
    public Map<String, Object> getScmPollingHistory(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to analyze (max 50)") int lastN) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(lastN, 1), 50);
        List<? extends Run<?, ?>> builds = job.getBuilds()
                .subList(0, Math.min(limit, job.getBuilds().size()));

        int scmTriggeredCount = 0;
        int manualCount = 0;
        int upstreamCount = 0;
        int otherCount = 0;

        List<Map<String, Object>> history = new ArrayList<>();

        for (Run<?, ?> run : builds) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("buildNumber", run.getNumber());
            entry.put("result", run.getResult() != null ? run.getResult().toString() : "?");
            entry.put("builtAt", run.getTimestampString());

            String triggerType = "other";
            for (Cause cause : run.getCauses()) {
                String cn = cause.getClass().getName();
                if (cn.contains("SCMTrigger") || cn.contains("RemoteCause")) {
                    triggerType = "SCM_POLLING";
                    scmTriggeredCount++;
                    break;
                } else if (cause instanceof Cause.UserIdCause) {
                    triggerType = "MANUAL";
                    manualCount++;
                    break;
                } else if (cause instanceof Cause.UpstreamCause) {
                    triggerType = "UPSTREAM";
                    upstreamCount++;
                    break;
                }
            }
            if ("other".equals(triggerType))
                otherCount++;

            entry.put("triggerType", triggerType);
            entry.put("changeCount", countChangeItems(run));
            history.add(entry);
        }

        int scmRate = builds.isEmpty() ? 0
                : Math.round(scmTriggeredCount * 100.0f / builds.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildsAnalyzed", builds.size());
        result.put("scmTriggeredCount", scmTriggeredCount);
        result.put("manualCount", manualCount);
        result.put("upstreamCount", upstreamCount);
        result.put("otherCount", otherCount);
        result.put("scmTriggerRate", scmRate + "%");
        result.put("recommendation", scmRate < 20
                ? "Low SCM trigger rate — consider switching to webhooks to reduce polling load"
                : "SCM polling is actively used");
        result.put("history", history);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 3 — getFingerprintInfo
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Track an artifact using Jenkins Fingerprints — the internal system "
            + "that traces which builds produced and consumed a given file. "
            + "Given an MD5 hash, returns the originating build and all builds that used it. "
            + "Uses Fingerprint internal API — not available via REST. "
            + "Enables full artifact lineage tracing across jobs.")
    public Map<String, Object> getFingerprintInfo(
            @ToolParam(description = "MD5 fingerprint hash of the artifact to trace") String md5Hash) {

        if (md5Hash == null || md5Hash.trim().length() < 8) {
            throw new IllegalArgumentException("md5Hash must be at least 8 characters");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("md5Hash", md5Hash);

        try {
            Fingerprint fp = Jenkins.get().getFingerprintMap().get(md5Hash);
            if (fp == null) {
                result.put("found", false);
                result.put("message", "No fingerprint found for MD5: " + md5Hash
                        + ". Fingerprinting must be enabled in jobs for this to work.");
                return result;
            }

            result.put("found", true);
            result.put("fileName", fp.getFileName());
            result.put("timestamp", fp.getTimestamp() != null
                    ? fp.getTimestamp().toString()
                    : null);

            // Originating build
            Fingerprint.BuildPtr orig = fp.getOriginal();
            if (orig != null) {
                Map<String, Object> originMap = new LinkedHashMap<>();
                originMap.put("jobName", orig.getName());
                originMap.put("buildNumber", orig.getNumber());
                originMap.put("url", Jenkins.get().getRootUrl()
                        + "job/" + orig.getName() + "/" + orig.getNumber() + "/");
                result.put("producedBy", originMap);
            }

            // Usages: jobs that consumed this artifact
            Map<String, ?> usages = fp.getUsages();
            List<Map<String, Object>> consumers = new ArrayList<>();
            if (usages != null) {
                for (Map.Entry<String, ?> usage : usages.entrySet()) {
                    Map<String, Object> consumer = new LinkedHashMap<>();
                    consumer.put("jobName", usage.getKey());
                    consumer.put("buildRanges", usage.getValue().toString());
                    consumers.add(consumer);
                }
            }
            result.put("consumedByCount", consumers.size());
            result.put("consumedBy", consumers);

        } catch (Exception e) {
            result.put("error", "Failed to lookup fingerprint: " + e.getMessage());
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 4 — getChangeSummaryByAuthor
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "For a job, get a summary of commits grouped by author "
            + "over the last N builds. Returns each author's commit count, "
            + "affected files count, and the builds they contributed to. "
            + "Uses ChangeLogSet internal API with author attribution.")
    public Map<String, Object> getChangeSummaryByAuthor(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to include (max 50)") int lastN) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(lastN, 1), 50);
        List<? extends Run<?, ?>> builds = job.getBuilds()
                .subList(0, Math.min(limit, job.getBuilds().size()));

        // Map: author -> {commitCount, fileCount, builds}
        Map<String, Map<String, Object>> authorMap = new LinkedHashMap<>();

        for (Run<?, ?> run : builds) {
            for (ChangeLogSet<?> cs : getChangeSetsSafe(run)) {
                for (ChangeLogSet.Entry entry : cs) {
                    String author = entry.getAuthor() != null
                            ? entry.getAuthor().getDisplayName()
                            : "unknown";

                    Map<String, Object> authorData = authorMap.computeIfAbsent(author, k -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("author", k);
                        m.put("commitCount", 0);
                        m.put("fileCount", 0);
                        m.put("builds", new ArrayList<Integer>());
                        return m;
                    });

                    authorData.put("commitCount", (int) authorData.get("commitCount") + 1);

                    int fileCount = 0;
                    try {
                        fileCount = entry.getAffectedFiles().size();
                    } catch (Exception ignored) {
                    }
                    authorData.put("fileCount", (int) authorData.get("fileCount") + fileCount);

                    @SuppressWarnings("unchecked")
                    List<Integer> buildList = (List<Integer>) authorData.get("builds");
                    if (!buildList.contains(run.getNumber()))
                        buildList.add(run.getNumber());
                }
            }
        }

        List<Map<String, Object>> authorList = new ArrayList<>(authorMap.values());
        authorList.sort((a, b) -> Integer.compare((int) b.get("commitCount"), (int) a.get("commitCount")));

        int totalCommits = authorList.stream()
                .mapToInt(a -> (int) a.get("commitCount")).sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildsAnalyzed", builds.size());
        result.put("totalCommits", totalCommits);
        result.put("authorCount", authorList.size());
        result.put("byAuthor", authorList);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 5 — getFilesChangedAcrossBuilds
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get a deduplicated list of all files that were modified "
            + "across the last N builds of a job, with how many times each was changed. "
            + "Uses ChangeLogSet.AffectedFile internal data. "
            + "Useful for understanding what areas of the codebase are most active.")
    public Map<String, Object> getFilesChangedAcrossBuilds(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to include (max 30)") int lastN,
            @ToolParam(description = "Path prefix filter, e.g. 'src/main/'. Empty string = all files.") String pathPrefix) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(lastN, 1), 30);
        List<? extends Run<?, ?>> builds = job.getBuilds()
                .subList(0, Math.min(limit, job.getBuilds().size()));

        String prefix = (pathPrefix == null) ? "" : pathPrefix;
        Map<String, Integer> fileChangeCounts = new TreeMap<>();

        for (Run<?, ?> run : builds) {
            for (ChangeLogSet<?> cs : getChangeSetsSafe(run)) {
                for (ChangeLogSet.Entry entry : cs) {
                    try {
                        for (ChangeLogSet.AffectedFile f : entry.getAffectedFiles()) {
                            String path = f.getPath();
                            if (path == null)
                                continue;
                            if (!prefix.isEmpty() && !path.startsWith(prefix))
                                continue;
                            fileChangeCounts.merge(path, 1, Integer::sum);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        }

        // Sort by change count desc
        List<Map<String, Object>> fileList = fileChangeCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", e.getKey());
                    m.put("changeCount", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildsAnalyzed", builds.size());
        result.put("pathPrefix", prefix.isEmpty() ? "(all)" : prefix);
        result.put("uniqueFiles", fileList.size());
        result.put("totalChanges", fileChangeCounts.values().stream().mapToInt(Integer::intValue).sum());
        result.put("files", fileList);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 6 — getHotspotFiles
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Identify the most frequently modified files (change hotspots) "
            + "in a job's recent builds. Returns the top N files by change frequency. "
            + "High-churn files often indicate design instability or technical debt. "
            + "Uses ChangeLogSet.AffectedFile internal traversal.")
    public Map<String, Object> getHotspotFiles(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to analyze (max 50)") int lastN,
            @ToolParam(description = "Top N hotspot files to return (max 30)") int topN) {

        Job<?, ?> job = resolveJob(jobFullName);
        int buildLimit = Math.min(Math.max(lastN, 1), 50);
        int fileLimit = Math.min(Math.max(topN, 1), 30);

        List<? extends Run<?, ?>> builds = job.getBuilds()
                .subList(0, Math.min(buildLimit, job.getBuilds().size()));

        Map<String, int[]> fileStats = new HashMap<>(); // path -> [changeCount, buildCount]
        Set<String> processedKeys = new HashSet<>();

        for (Run<?, ?> run : builds) {
            Set<String> filesInThisBuild = new HashSet<>();
            for (ChangeLogSet<?> cs : getChangeSetsSafe(run)) {
                for (ChangeLogSet.Entry entry : cs) {
                    try {
                        for (ChangeLogSet.AffectedFile f : entry.getAffectedFiles()) {
                            String path = f.getPath();
                            if (path == null)
                                continue;
                            fileStats.computeIfAbsent(path, k -> new int[] { 0, 0 })[0]++;
                            filesInThisBuild.add(path);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            // Count distinct builds per file
            for (String p : filesInThisBuild) {
                if (fileStats.containsKey(p))
                    fileStats.get(p)[1]++;
            }
        }

        List<Map<String, Object>> hotspots = fileStats.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]))
                .limit(fileLimit)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("path", e.getKey());
                    m.put("totalChanges", e.getValue()[0]);
                    m.put("buildsChanged", e.getValue()[1]);
                    m.put("churnRate", builds.isEmpty() ? "0%"
                            : Math.round(e.getValue()[1] * 100.0 / builds.size()) + "%");
                    // Heuristic: classify by extension
                    String ext = e.getKey().contains(".")
                            ? e.getKey().substring(e.getKey().lastIndexOf('.') + 1)
                            : "?";
                    m.put("fileType", ext);
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildsAnalyzed", builds.size());
        result.put("uniqueFiles", fileStats.size());
        result.put("topN", fileLimit);
        result.put("hotspots", hotspots);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 7 — getChangeVelocity
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Calculate the commit velocity (commits per day) trend for a job "
            + "over its last N builds. Returns the trend per calendar day, "
            + "busiest days, and a velocity trend (increasing/decreasing/stable). "
            + "Powered by ChangeLogSet internal API.")
    public Map<String, Object> getChangeVelocity(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to analyze (max 100)") int lastN) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(lastN, 2), 100);
        List<? extends Run<?, ?>> builds = job.getBuilds()
                .subList(0, Math.min(limit, job.getBuilds().size()));

        // Commits per day
        Map<String, Integer> commitsByDay = new LinkedHashMap<>();
        int totalCommits = 0;

        for (Run<?, ?> run : builds) {
            for (ChangeLogSet<?> cs : getChangeSetsSafe(run)) {
                for (ChangeLogSet.Entry entry : cs) {
                    totalCommits++;
                    // Use build timestamp as proxy for commit day
                    Calendar cal = Calendar.getInstance();
                    cal.setTimeInMillis(run.getStartTimeInMillis());
                    String day = cal.get(Calendar.YEAR) + "-"
                            + String.format("%02d", cal.get(Calendar.MONTH) + 1) + "-"
                            + String.format("%02d", cal.get(Calendar.DAY_OF_MONTH));
                    commitsByDay.merge(day, 1, Integer::sum);
                }
            }
        }

        if (commitsByDay.isEmpty()) {
            return Map.of("jobName", jobFullName,
                    "message", "No SCM changes recorded in the last " + builds.size() + " builds.");
        }

        // Sort days chronologically
        List<String> sortedDays = new ArrayList<>(commitsByDay.keySet());
        Collections.sort(sortedDays);

        double avgPerDay = commitsByDay.values().stream()
                .mapToInt(Integer::intValue).average().orElse(0);

        // Trend: compare first half vs second half of time window
        String trend = "STABLE";
        if (sortedDays.size() >= 4) {
            int half = sortedDays.size() / 2;
            double olderAvg = sortedDays.subList(0, half).stream()
                    .mapToInt(d -> commitsByDay.getOrDefault(d, 0)).average().orElse(0);
            double recentAvg = sortedDays.subList(half, sortedDays.size()).stream()
                    .mapToInt(d -> commitsByDay.getOrDefault(d, 0)).average().orElse(0);
            if (recentAvg > olderAvg * 1.3)
                trend = "ACCELERATING";
            else if (recentAvg < olderAvg * 0.7)
                trend = "SLOWING_DOWN";
        }

        // Busiest day
        String busiestDay = commitsByDay.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey).orElse(null);

        // Build the day-by-day timeline
        List<Map<String, Object>> timeline = sortedDays.stream().map(d -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("date", d);
            m.put("commits", commitsByDay.get(d));
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildsAnalyzed", builds.size());
        result.put("totalCommits", totalCommits);
        result.put("activeDays", commitsByDay.size());
        result.put("avgCommitsPerDay", Math.round(avgPerDay * 10.0) / 10.0);
        result.put("busiestDay", busiestDay);
        result.put("busiestDayCommits", busiestDay != null ? commitsByDay.get(busiestDay) : 0);
        result.put("velocityTrend", trend);
        result.put("dailyTimeline", timeline);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 8 — getBuildsByAuthor
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Find builds of a job that include commits from a specific author. "
            + "Returns each matching build with the author's commit count in that build. "
            + "Uses ChangeLogSet author data — internal API. "
            + "Answers: 'which builds include Alice's commits?'")
    public Map<String, Object> getBuildsByAuthor(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Author name or user ID to search for (case-insensitive)") String authorName,
            @ToolParam(description = "Number of recent builds to scan (max 100)") int scanLast) {

        if (authorName == null || authorName.trim().isEmpty()) {
            throw new IllegalArgumentException("authorName cannot be empty");
        }
        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(scanLast, 1), 100);
        List<? extends Run<?, ?>> builds = job.getBuilds()
                .subList(0, Math.min(limit, job.getBuilds().size()));

        String lowerAuthor = authorName.toLowerCase();
        List<Map<String, Object>> matches = new ArrayList<>();

        for (Run<?, ?> run : builds) {
            int commitCount = 0;
            int fileCount = 0;
            List<String> msgs = new ArrayList<>();
            for (ChangeLogSet<?> cs : getChangeSetsSafe(run)) {
                for (ChangeLogSet.Entry entry : cs) {
                    String entryAuthor = entry.getAuthor() != null
                            ? entry.getAuthor().getDisplayName()
                            : "";
                    if (!entryAuthor.toLowerCase().contains(lowerAuthor))
                        continue;

                    commitCount++;
                    msgs.add(entry.getMsg());
                    try {
                        fileCount += entry.getAffectedFiles().size();
                    } catch (Exception ignored) {
                    }
                }
            }

            if (commitCount == 0)
                continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("buildNumber", run.getNumber());
            entry.put("result", run.getResult() != null ? run.getResult().toString() : "?");
            entry.put("builtAt", run.getTimestampString());
            entry.put("authorCommits", commitCount);
            entry.put("filesChanged", fileCount);
            entry.put("commitMessages", msgs);
            entry.put("url", run.getAbsoluteUrl());
            matches.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("author", authorName);
        result.put("buildsScanned", builds.size());
        result.put("matchCount", matches.size());
        result.put("builds", matches);
        if (matches.isEmpty()) {
            result.put("message", "No builds found with commits from author '" + authorName
                    + "' in the last " + builds.size() + " builds.");
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 9 — getScmRevisionState
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get the SCM revision state snapshot recorded by Jenkins for a build. "
            + "SCMRevisionState captures the exact state of the repository at build time — "
            + "internal data used by Jenkins to detect changes for the next poll. "
            + "More reliable than changelog for identifying exact code state. "
            + "Use buildNumber=-1 for the last build.")
    public Map<String, Object> getScmRevisionState(
            @ToolParam(description = "Full job name (must be a classic FreeStyle/Maven job)") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber) {

        Run<?, ?> run = resolveRun(jobFullName, buildNumber);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildNumber", run.getNumber());

        // SCMRevisionState is accessible on AbstractBuild
        if (run instanceof AbstractBuild) {
            AbstractBuild<?, ?> ab = (AbstractBuild<?, ?>) run;
            try {
                hudson.scm.SCMRevisionState revState = ab.getAction(
                        hudson.scm.SCMRevisionState.class);
                if (revState != null) {
                    result.put("revisionStateType", revState.getClass().getSimpleName());
                    result.put("revisionStateString", revState.toString());

                    // Try to extract meaningful fields via reflection
                    try {
                        Object rev = revState.getClass().getMethod("getRevision").invoke(revState);
                        result.put("revision", rev != null ? rev.toString() : null);
                    } catch (Exception ignored) {
                    }
                    try {
                        Object sha = revState.getClass().getMethod("getSHA1").invoke(revState);
                        result.put("sha1", sha != null ? sha.toString() : null);
                    } catch (Exception ignored) {
                    }
                } else {
                    result.put("message", "No SCMRevisionState found for this build. "
                            + "This is normal for Pipeline jobs — use getGitBuildMetadata instead.");
                }
            } catch (Exception e) {
                result.put("error", "Could not retrieve SCMRevisionState: " + e.getMessage());
            }
        } else {
            // For Pipeline jobs, pull from BuildData actions
            List<Map<String, Object>> revisions = new ArrayList<>();
            for (Action action : run.getAllActions()) {
                if (!action.getClass().getName().contains("BuildData"))
                    continue;
                Map<String, Object> rev = new LinkedHashMap<>();
                rev.put("actionType", action.getClass().getSimpleName());
                try {
                    Object lastRev = action.getClass()
                            .getMethod("getLastBuiltRevision").invoke(action);
                    if (lastRev != null) {
                        try {
                            rev.put("sha1", lastRev.getClass()
                                    .getMethod("getSha1String").invoke(lastRev));
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                }
                if (!rev.isEmpty())
                    revisions.add(rev);
            }
            result.put("revisions", revisions);
            result.put("note", "Pipeline job — revision data comes from BuildData (Git plugin).");
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 10 — getMultiScmBuildData
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "For jobs that use multiple SCM sources (Multi-SCM plugin or "
            + "multiple checkouts in a Pipeline), return the revision metadata for "
            + "each SCM source independently. "
            + "Uses all BuildData actions on the build — internal API. "
            + "Use buildNumber=-1 for the last build.")
    public Map<String, Object> getMultiScmBuildData(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber) {

        Run<?, ?> run = resolveRun(jobFullName, buildNumber);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildNumber", run.getNumber());

        List<Map<String, Object>> scmSources = new ArrayList<>();

        for (Action action : run.getAllActions()) {
            String cn = action.getClass().getName();
            if (!cn.contains("BuildData") && !cn.contains("GitBuildData"))
                continue;

            Map<String, Object> scmEntry = new LinkedHashMap<>();
            scmEntry.put("scmType", action.getClass().getSimpleName());

            // Remote URLs
            try {
                Object urls = action.getClass().getMethod("getRemoteUrls").invoke(action);
                scmEntry.put("remoteUrls", urls);
            } catch (Exception ignored) {
            }

            // Last built revision
            try {
                Object revision = action.getClass()
                        .getMethod("getLastBuiltRevision").invoke(action);
                if (revision != null) {
                    try {
                        scmEntry.put("sha1",
                                revision.getClass().getMethod("getSha1String").invoke(revision));
                    } catch (Exception ignored) {
                    }
                    try {
                        @SuppressWarnings("unchecked")
                        Collection<Object> branches = (Collection<Object>) revision.getClass().getMethod("getBranches")
                                .invoke(revision);
                        scmEntry.put("branches", branches.stream()
                                .map(b -> {
                                    try {
                                        return b.getClass().getMethod("getName").invoke(b).toString();
                                    } catch (Exception e) {
                                        return "?";
                                    }
                                }).collect(Collectors.toList()));
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }

            if (scmEntry.size() > 1)
                scmSources.add(scmEntry);
        }

        result.put("scmSourceCount", scmSources.size());
        result.put("scmSources", scmSources);
        if (scmSources.isEmpty()) {
            result.put("message", "No multi-SCM build data found. "
                    + "Ensure Git plugin is installed and configured.");
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 11 — getChangeSetDiff
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Compare the changelog (SCM commits and files) between two builds. "
            + "Returns: commits in buildB not in buildA (new commits), "
            + "files changed in one but not the other, and author differences. "
            + "Uses ChangeLogSet internal traversal. Use -1 for last build.")
    public Map<String, Object> getChangeSetDiff(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Reference build number (older build)") int buildNumberA,
            @ToolParam(description = "Comparison build number (newer build), or -1 for last") int buildNumberB) {

        Run<?, ?> runA = resolveRun(jobFullName, buildNumberA);
        Run<?, ?> runB = resolveRun(jobFullName, buildNumberB);

        Set<String> commitsA = extractCommitIds(runA);
        Set<String> commitsB = extractCommitIds(runB);
        Set<String> filesA = extractFiles(runA);
        Set<String> filesB = extractFiles(runB);
        Set<String> authorsA = extractAuthors(runA);
        Set<String> authorsB = extractAuthors(runB);

        // New commits in B not in A
        Set<String> newCommits = new HashSet<>(commitsB);
        newCommits.removeAll(commitsA);

        // Files in B but not A
        Set<String> newFiles = new HashSet<>(filesB);
        newFiles.removeAll(filesA);

        // Files in A but not B
        Set<String> removedFiles = new HashSet<>(filesA);
        removedFiles.removeAll(filesB);

        // New authors in B
        Set<String> newAuthors = new HashSet<>(authorsB);
        newAuthors.removeAll(authorsA);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildA", runA.getNumber());
        result.put("buildB", runB.getNumber());
        result.put("newCommitCount", newCommits.size());
        result.put("newCommits", new ArrayList<>(newCommits));
        result.put("newFilesCount", newFiles.size());
        result.put("newFiles", new ArrayList<>(newFiles));
        result.put("removedFilesCount", removedFiles.size());
        result.put("removedFiles", new ArrayList<>(removedFiles));
        result.put("newAuthors", new ArrayList<>(newAuthors));
        result.put("totalCommitsA", commitsA.size());
        result.put("totalCommitsB", commitsB.size());
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // PRIVATE HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private Iterable<ChangeLogSet<?>> getChangeSetsSafe(Run<?, ?> run) {
        if (run == null)
            return Collections.emptyList();
        try {
            if (run instanceof jenkins.scm.RunWithSCM) {
                return ((jenkins.scm.RunWithSCM) run).getChangeSets();
            }
        } catch (NoClassDefFoundError | Exception ignored) {
        }
        return Collections.emptyList();
    }

    private int countChangeItems(Run<?, ?> run) {
        int cnt = 0;
        for (ChangeLogSet<?> cs : getChangeSetsSafe(run)) {
            try {
                cnt += cs.getItems().length;
            } catch (Exception ignored) {
            }
        }
        return cnt;
    }

    private Job<?, ?> resolveJob(String jobFullName) {
        Job<?, ?> job = Jenkins.get().getItemByFullName(jobFullName, Job.class);
        if (job == null)
            throw new IllegalArgumentException("Job not found: " + jobFullName);
        return job;
    }

    private Run<?, ?> resolveRun(String jobFullName, int buildNumber) {
        Job<?, ?> job = resolveJob(jobFullName);
        Run<?, ?> run = buildNumber == -1 ? job.getLastBuild()
                : job.getBuildByNumber(buildNumber);
        if (run == null)
            throw new IllegalArgumentException(
                    "Build not found: " + jobFullName + " #" + buildNumber);
        return run;
    }

    private Set<String> extractCommitIds(Run<?, ?> run) {
        Set<String> ids = new LinkedHashSet<>();
        for (ChangeLogSet<?> cs : getChangeSetsSafe(run)) {
            for (ChangeLogSet.Entry e : cs) {
                if (e.getCommitId() != null)
                    ids.add(e.getCommitId());
            }
        }
        return ids;
    }

    private Set<String> extractFiles(Run<?, ?> run) {
        Set<String> files = new LinkedHashSet<>();
        for (ChangeLogSet<?> cs : getChangeSetsSafe(run)) {
            for (ChangeLogSet.Entry e : cs) {
                try {
                    for (ChangeLogSet.AffectedFile f : e.getAffectedFiles()) {
                        if (f.getPath() != null)
                            files.add(f.getPath());
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return files;
    }

    private Set<String> extractAuthors(Run<?, ?> run) {
        Set<String> authors = new LinkedHashSet<>();
        for (ChangeLogSet<?> cs : getChangeSetsSafe(run)) {
            for (ChangeLogSet.Entry e : cs) {
                if (e.getAuthor() != null)
                    authors.add(e.getAuthor().getDisplayName());
            }
        }
        return authors;
    }

    private List<Object> getTriggers(Job<?, ?> job) {
        List<Object> triggers = new ArrayList<>();
        if (!(job instanceof AbstractProject))
            return triggers;
        try {
            @SuppressWarnings("unchecked")
            Map<hudson.triggers.TriggerDescriptor, hudson.triggers.Trigger<?>> tm = ((AbstractProject<?, ?>) job)
                    .getTriggers();
            triggers.addAll(tm.values());
        } catch (Exception ignored) {
        }
        return triggers;
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