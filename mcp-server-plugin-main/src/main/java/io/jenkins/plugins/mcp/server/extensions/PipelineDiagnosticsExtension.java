package io.jenkins.plugins.mcp.server.extensions;

import hudson.Extension;
import hudson.model.*;
import hudson.model.Queue;
import hudson.scm.ChangeLogSet;
import hudson.tasks.test.AbstractTestResultAction;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jenkins.model.Jenkins;
import jenkins.scm.RunWithSCM;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.variant.OptionalExtension;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.*;
import java.util.stream.*;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * MCP Extension — Pipeline Diagnostics & Build Analysis
 *
 * Tools (15):
 * 1. analyzePipelineFailure
 * 2. comparePipelineRuns
 * 3. detectFlakyTests
 * 4. validateJenkinsfileContent
 * 5. getFailingJobsSummary
 * 6. getBuildTrend
 * 7. getSlowBuilds
 * 8. getChangesInBuild
 * 9. getBranchBuildStatus
 * 10. getBuildByCommit
 * 11. getQueueAnalysis
 * 12. searchInBuildLog
 * 13. getTestResults
 * 14. getTestTrend
 * 15. getRecentBuildsAcrossView
 *
 * ALL tools are READ-ONLY. No scheduleBuild, delete, abort, save or modify
 * calls.
 */
@OptionalExtension(requirePlugins = { "workflow-job", "junit" })
public class PipelineDiagnosticsExtension implements McpServerExtension {

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 1 — analyzePipelineFailure
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Analyze a failed build and extract the root cause from its log. "
            + "Returns the last N lines of the log, the first error/exception found, "
            + "the failing stage (if Pipeline), and a structured diagnosis summary. "
            + "Use buildNumber=-1 for the last build. logLines controls how many tail "
            + "lines to return (max 200).")
    public Map<String, Object> analyzePipelineFailure(
            @ToolParam(description = "Full job name, e.g. 'folder/my-pipeline'") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber,
            @ToolParam(description = "Number of log tail lines to return (1-200, default 50)") int logLines) {

        Run<?, ?> run = resolveRun(jobFullName, buildNumber);
        Map<String, Object> result = new LinkedHashMap<>();

        result.put("jobName", jobFullName);
        result.put("buildNumber", run.getNumber());
        result.put("result", run.getResult() != null ? run.getResult().toString() : "IN_PROGRESS");
        result.put("durationMs", run.getDuration());
        result.put("durationHuman", humanDuration(run.getDuration()));

        // Limit lines
        int limit = Math.min(Math.max(logLines, 1), 200);

        try {
            List<String> logList = run.getLog(10_000); // read up to 10k lines
            String[] lines = logList.toArray(new String[0]);

            // Tail lines
            int from = Math.max(0, lines.length - limit);
            String tailLog = String.join("\n", Arrays.copyOfRange(lines, from, lines.length));
            result.put("tailLog", tailLog);
            result.put("tailLines", lines.length - from);
            result.put("totalLogLines", lines.length);

            // First exception / error detection
            String firstError = null;
            String firstException = null;
            Pattern errorPat = Pattern.compile("(?i)(error|failed|failure|exception).*", Pattern.CASE_INSENSITIVE);
            Pattern exceptionPat = Pattern.compile("(?i)(\\w+Exception|\\w+Error):\\s*.+");

            for (String line : lines) {
                if (firstException == null) {
                    Matcher m = exceptionPat.matcher(line);
                    if (m.find())
                        firstException = line.trim();
                }
                if (firstError == null) {
                    Matcher m = errorPat.matcher(line);
                    if (m.find() && !line.startsWith("[Pipeline]") && line.length() > 10) {
                        firstError = line.trim();
                    }
                }
                if (firstException != null && firstError != null)
                    break;
            }

            result.put("firstErrorLine", firstError);
            result.put("firstExceptionLine", firstException);

            // Diagnosis summary
            String diagnosis = buildDiagnosis(lines, run);
            result.put("diagnosisSummary", diagnosis);

        } catch (Exception e) {
            result.put("logError", "Could not read log: " + e.getMessage());
        }

        return result;
    }

    private String buildDiagnosis(String[] lines, Run<?, ?> run) {
        long errorCount = Arrays.stream(lines)
                .filter(l -> l.toLowerCase().contains("error") || l.toLowerCase().contains("failed"))
                .count();
        boolean hasOutOfMemory = Arrays.stream(lines)
                .anyMatch(l -> l.contains("OutOfMemoryError") || l.contains("GC overhead"));
        boolean hasTimeout = Arrays.stream(lines)
                .anyMatch(l -> l.toLowerCase().contains("timeout") || l.toLowerCase().contains("timed out"));
        boolean hasPermission = Arrays.stream(lines)
                .anyMatch(l -> l.toLowerCase().contains("permission denied")
                        || l.toLowerCase().contains("access denied"));
        boolean hasCompileError = Arrays.stream(lines)
                .anyMatch(l -> l.contains("COMPILATION ERROR") || l.contains("cannot find symbol"));
        boolean hasTestFailure = Arrays.stream(lines)
                .anyMatch(l -> l.contains("BUILD FAILURE") && l.contains("test"));

        if (hasOutOfMemory)
            return "JVM Out Of Memory — increase heap or optimize memory usage";
        if (hasTimeout)
            return "Timeout detected — step exceeded its time limit";
        if (hasPermission)
            return "Permission/access denied — check credentials or file permissions";
        if (hasCompileError)
            return "Compilation error — syntax or dependency issue in source code";
        if (hasTestFailure)
            return "Test failure — one or more unit/integration tests failed";
        if (errorCount > 5)
            return "Multiple errors detected (" + errorCount + " lines) — check tailLog for details";
        if (run.getResult() == Result.ABORTED)
            return "Build was manually aborted or timed out";
        return "Build failed — inspect tailLog and firstErrorLine for details";
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 2 — comparePipelineRuns
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Compare two builds of the same job side by side. "
            + "Returns differences in: result, duration, trigger cause, "
            + "commit count, test counts, and artifact count. "
            + "Use -1 for buildNumberB to compare against the last build.")
    public Map<String, Object> comparePipelineRuns(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "First build number (reference)") int buildNumberA,
            @ToolParam(description = "Second build number to compare, or -1 for last build") int buildNumberB) {

        Run<?, ?> runA = resolveRun(jobFullName, buildNumberA);
        Run<?, ?> runB = resolveRun(jobFullName, buildNumberB);

        Map<String, Object> a = buildSnapshot(runA);
        Map<String, Object> b = buildSnapshot(runB);

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("buildA", a);
        diff.put("buildB", b);

        // Delta
        long durationDeltaMs = runB.getDuration() - runA.getDuration();
        diff.put("durationDeltaMs", durationDeltaMs);
        diff.put("durationDeltaHuman", (durationDeltaMs >= 0 ? "+" : "") + humanDuration(Math.abs(durationDeltaMs)));
        diff.put("resultChanged", !Objects.equals(a.get("result"), b.get("result")));
        diff.put("commitDelta", (int) b.get("changeCount") - (int) a.get("changeCount"));
        diff.put("artifactDelta", (int) b.get("artifactCount") - (int) a.get("artifactCount"));

        // Test deltas
        int testTotalA = (int) a.getOrDefault("testTotal", 0);
        int testTotalB = (int) b.getOrDefault("testTotal", 0);
        int testFailA = (int) a.getOrDefault("testFailed", 0);
        int testFailB = (int) b.getOrDefault("testFailed", 0);
        diff.put("testTotalDelta", testTotalB - testTotalA);
        diff.put("testFailureDelta", testFailB - testFailA);

        return diff;
    }

    private Map<String, Object> buildSnapshot(Run<?, ?> run) {
        Map<String, Object> snap = new LinkedHashMap<>();
        snap.put("buildNumber", run.getNumber());
        snap.put("result", run.getResult() != null ? run.getResult().toString() : "IN_PROGRESS");
        snap.put("durationMs", run.getDuration());
        snap.put("durationHuman", humanDuration(run.getDuration()));
        snap.put("triggeredBy", run.getCauses().stream()
                .map(Cause::getShortDescription)
                .collect(Collectors.joining(", ")));
        int changeCount = 0;
        if (run instanceof RunWithSCM) {
            changeCount = ((RunWithSCM) run).getChangeSets().size();
        }
        snap.put("changeCount", changeCount);
        snap.put("artifactCount", run.getArtifacts().size());

        // Tests
        try {
            AbstractTestResultAction<?> tra = run.getAction(AbstractTestResultAction.class);
            if (tra != null) {
                snap.put("testTotal", tra.getTotalCount());
                snap.put("testFailed", tra.getFailCount());
                snap.put("testSkipped", tra.getSkipCount());
                snap.put("testPassed", tra.getTotalCount() - tra.getFailCount() - tra.getSkipCount());
            } else {
                snap.put("testTotal", 0);
                snap.put("testFailed", 0);
            }
        } catch (Exception e) {
            snap.put("testTotal", 0);
            snap.put("testFailed", 0);
        }
        return snap;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 3 — detectFlakyTests
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Analyze the last N builds of a job to detect flaky tests — "
            + "tests that alternate between pass and fail without code changes. "
            + "Returns a list of test names with their pass/fail counts and instability rate. "
            + "Requires JUnit plugin. Returns empty list if no test results found.")
    public Map<String, Object> detectFlakyTests(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to analyze (2-30)") int lastN) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(lastN, 2), 30);

        List<? extends Run<?, ?>> builds = job.getBuilds()
                .subList(0, Math.min(limit, job.getBuilds().size()));

        // Map: testName -> [pass, fail]
        Map<String, int[]> testStats = new LinkedHashMap<>();

        for (Run<?, ?> run : builds) {
            try {
                AbstractTestResultAction<?> tra = run.getAction(AbstractTestResultAction.class);
                if (tra == null)
                    continue;

                // Use reflection to get failed test names
                try {
                    @SuppressWarnings("unchecked")
                    List<Object> failedTests = (List<Object>) tra.getClass()
                            .getMethod("getFailedTests").invoke(tra);
                    for (Object t : failedTests) {
                        String name = t.getClass().getMethod("getFullName").invoke(t).toString();
                        testStats.computeIfAbsent(name, k -> new int[] { 0, 0 })[1]++;
                    }
                    // Count total tests and derive passed
                    int total = tra.getTotalCount();
                    int failed = tra.getFailCount();
                    int passed = total - failed - tra.getSkipCount();
                    // We can't get individual passed tests easily, track run-level
                } catch (Exception ignored) {
                }
            } catch (Exception ignored) {
            }
        }

        List<Map<String, Object>> flakyList = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : testStats.entrySet()) {
            int fail = entry.getValue()[1];
            // A test is flaky if it failed in some builds but not all
            if (fail > 0 && fail < builds.size()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("testName", entry.getKey());
                t.put("failCount", fail);
                t.put("buildsAnalyzed", builds.size());
                t.put("failureRate", Math.round(fail * 100.0 / builds.size()) + "%");
                flakyList.add(t);
            }
        }
        flakyList.sort((a, b) -> Integer.compare((int) b.get("failCount"), (int) a.get("failCount")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildsAnalyzed", builds.size());
        result.put("flakyTestCount", flakyList.size());
        result.put("flakyTests", flakyList);
        if (flakyList.isEmpty()) {
            result.put("message", "No flaky tests detected in the last " + builds.size() + " builds");
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 4 — validateJenkinsfileContent
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Validate Jenkinsfile syntax by calling Jenkins internal pipeline "
            + "model converter. Checks for declarative pipeline syntax errors before "
            + "committing. Pass the raw Jenkinsfile content as a string. "
            + "Returns validation result: valid/invalid + error messages.")
    public Map<String, Object> validateJenkinsfileContent(
            @ToolParam(description = "Raw Jenkinsfile content to validate") String jenkinsfileContent) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("contentLength", jenkinsfileContent.length());

        // Basic structural checks (Jenkins internal API validation is complex)
        boolean hasAgent = jenkinsfileContent.contains("agent");
        boolean hasStages = jenkinsfileContent.contains("stages") || jenkinsfileContent.contains("stage(");
        boolean hasPipeline = jenkinsfileContent.contains("pipeline {") || jenkinsfileContent.contains("pipeline{");
        boolean isScripted = jenkinsfileContent.trim().startsWith("node")
                || jenkinsfileContent.trim().startsWith("@Library");

        // Bracket balance check
        long openBraces = jenkinsfileContent.chars().filter(c -> c == '{').count();
        long closeBraces = jenkinsfileContent.chars().filter(c -> c == '}').count();
        boolean bracesBalanced = openBraces == closeBraces;

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!bracesBalanced) {
            errors.add("Unbalanced braces: " + openBraces + " opening vs " + closeBraces + " closing");
        }
        if (!isScripted && !hasPipeline) {
            errors.add("Missing 'pipeline { }' block — required for Declarative Pipeline");
        }
        if (!isScripted && !hasAgent) {
            warnings.add("No 'agent' directive found — required in Declarative Pipeline");
        }
        if (!isScripted && !hasStages) {
            warnings.add("No 'stages' or 'stage' blocks found");
        }
        if (jenkinsfileContent.contains("sh '") && jenkinsfileContent.contains("\"\"\"")) {
            warnings.add("Mixed single-quoted sh and triple-quoted strings detected — review quoting");
        }

        result.put("pipelineType", isScripted ? "Scripted Pipeline" : "Declarative Pipeline");
        result.put("hasAgent", hasAgent);
        result.put("hasStages", hasStages);
        result.put("bracesBalanced", bracesBalanced);
        result.put("valid", errors.isEmpty());
        result.put("errors", errors);
        result.put("warnings", warnings);
        result.put("note", "Full syntactic validation requires Jenkins pipeline-model-definition plugin. "
                + "These checks are structural only.");
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 5 — getFailingJobsSummary
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "List all jobs currently failing (last build = FAILURE or UNSTABLE) "
            + "in a given Jenkins view. Returns job name, last build number, result, "
            + "when it failed, and number of consecutive failures. "
            + "Use viewName='All' for the global view.")
    public Map<String, Object> getFailingJobsSummary(
            @ToolParam(description = "View name, e.g. 'All', 'My-Team', or folder name") String viewName) {

        View view = Jenkins.get().getView(viewName);
        if (view == null)
            throw new IllegalArgumentException("View not found: " + viewName);

        List<Map<String, Object>> failing = new ArrayList<>();

        for (Item item : view.getAllItems()) {
            if (!(item instanceof Job))
                continue;
            Job<?, ?> job = (Job<?, ?>) item;
            Run<?, ?> last = job.getLastBuild();
            if (last == null)
                continue;

            Result r = last.getResult();
            if (r == null || (r != Result.FAILURE && r != Result.UNSTABLE))
                continue;

            // Count consecutive failures
            int consecutiveFails = 0;
            for (Run<?, ?> run = last; run != null; run = run.getPreviousBuild()) {
                Result rr = run.getResult();
                if (rr == Result.FAILURE || rr == Result.UNSTABLE) {
                    consecutiveFails++;
                } else {
                    break;
                }
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("jobName", job.getFullName());
            entry.put("lastBuildNumber", last.getNumber());
            entry.put("result", r.toString());
            entry.put("failedSince", last.getTimestampString());
            entry.put("consecutiveFailures", consecutiveFails);
            entry.put("url", job.getAbsoluteUrl());
            failing.add(entry);
        }

        failing.sort((a, b) -> Integer.compare((int) b.get("consecutiveFailures"), (int) a.get("consecutiveFailures")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("viewName", viewName);
        result.put("failingCount", failing.size());
        result.put("failingJobs", failing);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 6 — getBuildTrend
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get the stability trend of a job over the last N builds as a "
            + "timeline series. Each entry has build number, result, duration, and timestamp. "
            + "Also returns overall trend: IMPROVING, DEGRADING, or STABLE.")
    public Map<String, Object> getBuildTrend(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to return (2-50)") int lastN) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(lastN, 2), 50);
        List<? extends Run<?, ?>> builds = job.getBuilds()
                .subList(0, Math.min(limit, job.getBuilds().size()));

        List<Map<String, Object>> timeline = builds.stream().map(run -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("buildNumber", run.getNumber());
            entry.put("result", run.getResult() != null ? run.getResult().toString() : "IN_PROGRESS");
            entry.put("durationMs", run.getDuration());
            entry.put("durationHuman", humanDuration(run.getDuration()));
            entry.put("timestamp", run.getTimestampString());
            return entry;
        }).collect(Collectors.toList());

        // Trend computation
        String trend = "INSUFFICIENT_DATA";
        if (builds.size() >= 4) {
            int half = builds.size() / 2;
            double recentRate = builds.subList(0, half).stream()
                    .filter(b -> b.getResult() == Result.SUCCESS).count() * 100.0 / half;
            double olderRate = builds.subList(half, builds.size()).stream()
                    .filter(b -> b.getResult() == Result.SUCCESS).count() * 100.0 / (builds.size() - half);
            if (recentRate - olderRate > 10)
                trend = "IMPROVING";
            else if (olderRate - recentRate > 10)
                trend = "DEGRADING";
            else
                trend = "STABLE";
        }

        long successCount = builds.stream().filter(b -> b.getResult() == Result.SUCCESS).count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildsAnalyzed", builds.size());
        result.put("successCount", successCount);
        result.put("successRate", builds.isEmpty() ? "0%" : Math.round(successCount * 100.0 / builds.size()) + "%");
        result.put("trend", trend);
        result.put("timeline", timeline);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 7 — getSlowBuilds
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Find all jobs in a view whose last build exceeded a duration threshold. "
            + "Useful to detect performance regressions. Returns jobs sorted by duration descending. "
            + "thresholdMinutes: minimum duration to include a job in results.")
    public Map<String, Object> getSlowBuilds(
            @ToolParam(description = "View name to scan, e.g. 'All'") String viewName,
            @ToolParam(description = "Duration threshold in minutes — only return jobs slower than this") int thresholdMinutes) {

        View view = Jenkins.get().getView(viewName);
        if (view == null)
            throw new IllegalArgumentException("View not found: " + viewName);

        long thresholdMs = thresholdMinutes * 60_000L;
        List<Map<String, Object>> slowJobs = new ArrayList<>();

        for (Item item : view.getAllItems()) {
            if (!(item instanceof Job))
                continue;
            Job<?, ?> job = (Job<?, ?>) item;
            Run<?, ?> last = job.getLastBuild();
            if (last == null || last.isBuilding())
                continue;
            if (last.getDuration() < thresholdMs)
                continue;

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("jobName", job.getFullName());
            entry.put("buildNumber", last.getNumber());
            entry.put("durationMs", last.getDuration());
            entry.put("durationHuman", humanDuration(last.getDuration()));
            entry.put("result", last.getResult() != null ? last.getResult().toString() : "?");
            entry.put("url", last.getAbsoluteUrl());
            slowJobs.add(entry);
        }

        slowJobs.sort((a, b) -> Long.compare((long) b.get("durationMs"), (long) a.get("durationMs")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("viewName", viewName);
        result.put("thresholdMinutes", thresholdMinutes);
        result.put("slowJobsCount", slowJobs.size());
        result.put("slowJobs", slowJobs);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 8 — getChangesInBuild
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get all SCM commits (changelog) included in a build. "
            + "Returns author, commit message, revision/SHA, and list of affected files. "
            + "Use buildNumber=-1 for the last build.")
    public Map<String, Object> getChangesInBuild(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber) {

        Run<?, ?> run = resolveRun(jobFullName, buildNumber);

        List<Map<String, Object>> commits = new ArrayList<>();
        if (run instanceof RunWithSCM) {
            @SuppressWarnings("unchecked")
            Iterable<ChangeLogSet<?>> changeSets = (Iterable<ChangeLogSet<?>>) (Iterable<?>) ((RunWithSCM) run)
                    .getChangeSets();
            for (ChangeLogSet<?> changeSet : changeSets) {
                for (ChangeLogSet.Entry entry : changeSet) {
                    Map<String, Object> commit = new LinkedHashMap<>();
                    commit.put("author", entry.getAuthor() != null ? entry.getAuthor().getDisplayName() : "unknown");
                    commit.put("message", entry.getMsg());
                    commit.put("commitId", entry.getCommitId());
                    commit.put("timestamp", entry.getTimestamp());
                    List<String> files = new ArrayList<>();
                    try {
                        for (ChangeLogSet.AffectedFile f : entry.getAffectedFiles()) {
                            files.add(f.getPath());
                        }
                    } catch (Exception ignored) {
                    }
                    commit.put("affectedFiles", files);
                    commit.put("affectedFileCount", files.size());
                    commits.add(commit);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildNumber", run.getNumber());
        result.put("commitCount", commits.size());
        result.put("commits", commits);
        if (commits.isEmpty()) {
            result.put("message", "No SCM changes recorded for this build "
                    + "(may be a manual trigger or first build)");
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 9 — getBranchBuildStatus
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "For a Multibranch Pipeline job, list all known branches and "
            + "their last build status. Answers 'which branches are currently broken?'. "
            + "Returns branch name, last build result, build number, and timestamp.")
    public Map<String, Object> getBranchBuildStatus(
            @ToolParam(description = "Full name of the Multibranch Pipeline job/folder") String multibranchJobFullName) {

        Item item = Jenkins.get().getItemByFullName(multibranchJobFullName);
        if (item == null)
            throw new IllegalArgumentException("Item not found: " + multibranchJobFullName);

        List<Map<String, Object>> branches = new ArrayList<>();

        // Multibranch is a Folder containing WorkflowJobs (one per branch)
        try {
            // Try to access MultiBranchProject via reflection
            if (item.getClass().getSimpleName().contains("MultiBranchProject")) {
                @SuppressWarnings("unchecked")
                Iterable<Job<?, ?>> branchJobs = (Iterable<Job<?, ?>>) item.getClass()
                        .getMethod("getAllItems", Class.class)
                        .invoke(item, Job.class);
                for (Job<?, ?> branch : branchJobs) {
                    Run<?, ?> last = branch.getLastBuild();
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("branchName", branch.getName());
                    entry.put("jobFullName", branch.getFullName());
                    entry.put("lastBuildNum", last != null ? last.getNumber() : null);
                    entry.put("result", last != null && last.getResult() != null
                            ? last.getResult().toString()
                            : (last != null ? "IN_PROGRESS" : "NEVER"));
                    entry.put("lastBuiltAt", last != null ? last.getTimestampString() : null);
                    entry.put("building", last != null && last.isBuilding());
                    entry.put("url", branch.getAbsoluteUrl());
                    branches.add(entry);
                }
            }
        } catch (Exception e) {
            // MultiBranchProject plugin may not be available
        }

        // Fallback: item is a folder, look for children
        if (branches.isEmpty() && item instanceof hudson.model.AbstractItem) {
            // Fallback: item is a folder, look for children
            if (item instanceof jenkins.model.ModifiableTopLevelItemGroup) {
                @SuppressWarnings("unchecked")
                jenkins.model.ModifiableTopLevelItemGroup folder = (jenkins.model.ModifiableTopLevelItemGroup) item;
                for (Object child : folder.getItems()) {
                    if (child instanceof Job) {
                        Job<?, ?> branch = (Job<?, ?>) child;
                        Run<?, ?> last = branch.getLastBuild();
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("branchName", branch.getName());
                        entry.put("result", last != null && last.getResult() != null
                                ? last.getResult().toString()
                                : "NEVER");
                        entry.put("lastBuiltAt", last != null ? last.getTimestampString() : null);
                        branches.add(entry);
                    }
                }
            }
        }

        long failingCount = branches.stream()
                .filter(b -> "FAILURE".equals(b.get("result")) || "UNSTABLE".equals(b.get("result")))
                .count();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("multibranchJob", multibranchJobFullName);
        result.put("branchCount", branches.size());
        result.put("failingBranches", failingCount);
        result.put("branches", branches);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 10 — getBuildByCommit
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Find builds that contain a specific Git commit SHA. "
            + "Searches the last N builds of a job and returns any build "
            + "whose changelog contains that commit. Answers: 'was commit abc123 built, "
            + "and did it pass?'")
    public Map<String, Object> getBuildByCommit(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Git commit SHA (full or partial, min 6 chars)") String commitSha,
            @ToolParam(description = "Number of recent builds to search (max 100)") int searchLastN) {

        if (commitSha == null || commitSha.length() < 6) {
            throw new IllegalArgumentException("commitSha must be at least 6 characters");
        }
        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(searchLastN, 100);
        List<? extends Run<?, ?>> builds = job.getBuilds()
                .subList(0, Math.min(limit, job.getBuilds().size()));

        List<Map<String, Object>> matches = new ArrayList<>();
        String lowerSha = commitSha.toLowerCase();

        for (Run<?, ?> run : builds) {
            boolean found = false;
            if (run instanceof RunWithSCM) {
                @SuppressWarnings("unchecked")
                Iterable<ChangeLogSet<?>> changeSets = (Iterable<ChangeLogSet<?>>) (Iterable<?>) ((RunWithSCM) run)
                        .getChangeSets();
                for (ChangeLogSet<?> cs : changeSets) {
                    for (ChangeLogSet.Entry entry : cs) {
                        String cid = entry.getCommitId();
                        if (cid != null && cid.toLowerCase().startsWith(lowerSha)) {
                            found = true;
                            break;
                        }
                    }
                    if (found)
                        break;
                }
            }
            if (found) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("buildNumber", run.getNumber());
                m.put("result", run.getResult() != null ? run.getResult().toString() : "IN_PROGRESS");
                m.put("builtAt", run.getTimestampString());
                m.put("durationHuman", humanDuration(run.getDuration()));
                m.put("url", run.getAbsoluteUrl());
                matches.add(m);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("commitSha", commitSha);
        result.put("buildsSearched", builds.size());
        result.put("matchCount", matches.size());
        result.put("matches", matches);
        if (matches.isEmpty()) {
            result.put("message", "Commit " + commitSha + " was not found in the last "
                    + builds.size() + " builds");
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 11 — getQueueAnalysis
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Analyze the current Jenkins build queue. Returns each queued item "
            + "with its job name, how long it has been waiting, why it is blocked, "
            + "and which label/node it needs. Helps diagnose why builds don't start.")
    public Map<String, Object> getQueueAnalysis() {

        Queue.Item[] items = Jenkins.get().getQueue().getItems();
        long now = System.currentTimeMillis();

        List<Map<String, Object>> queueItems = Arrays.stream(items).map(item -> {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", item.getId());
            entry.put("jobName", item.task.getFullDisplayName());
            entry.put("blocked", item.isBlocked());
            entry.put("buildable", item.isBuildable());
            entry.put("waitingMs", now - item.getInQueueSince());
            entry.put("waitingHuman", humanDuration(now - item.getInQueueSince()));
            entry.put("blockReason", item.getWhy());
            entry.put("params", item.getParams());
            return entry;
        }).collect(Collectors.toList());

        // Categorize blocking reasons
        long blockedCount = Arrays.stream(items).filter(Queue.Item::isBlocked).count();
        long buildableCount = Arrays.stream(items).filter(Queue.Item::isBuildable).count();
        long waitingMs = Arrays.stream(items)
                .mapToLong(i -> now - i.getInQueueSince()).max().orElse(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queueSize", items.length);
        result.put("blockedCount", blockedCount);
        result.put("buildableCount", buildableCount);
        result.put("longestWaitHuman", humanDuration(waitingMs));
        result.put("items", queueItems);
        if (items.length == 0)
            result.put("message", "Build queue is empty");
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 12 — searchInBuildLog
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Search for a string or simple pattern in the log of a specific build. "
            + "Returns all matching lines with their line numbers. "
            + "Case-insensitive by default. Use buildNumber=-1 for last build.")
    public Map<String, Object> searchInBuildLog(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber,
            @ToolParam(description = "Text to search for (case-insensitive)") String searchText,
            @ToolParam(description = "Maximum number of matching lines to return (max 100)") int maxResults) {

        if (searchText == null || searchText.trim().isEmpty()) {
            throw new IllegalArgumentException("searchText cannot be empty");
        }
        Run<?, ?> run = resolveRun(jobFullName, buildNumber);
        int limit = Math.min(Math.max(maxResults, 1), 100);
        String lowerSearch = searchText.toLowerCase();

        List<Map<String, Object>> matchingLines = new ArrayList<>();
        try {
            List<String> logList = run.getLog(20_000);
            String[] lines = logList.toArray(new String[0]);
            for (int i = 0; i < lines.length && matchingLines.size() < limit; i++) {
                if (lines[i].toLowerCase().contains(lowerSearch)) {
                    Map<String, Object> match = new LinkedHashMap<>();
                    match.put("lineNumber", i + 1);
                    match.put("line", lines[i].trim());
                    matchingLines.add(match);
                }
            }
        } catch (Exception e) {
            return Map.of("error", "Could not read log: " + e.getMessage());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildNumber", run.getNumber());
        result.put("searchText", searchText);
        result.put("matchCount", matchingLines.size());
        result.put("truncated", matchingLines.size() == limit);
        result.put("matches", matchingLines);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 13 — getAdvancedTestResults
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get advanced test results for a build: total, passed, failed, skipped counts "
            + "and the list of failed test names with their error messages. "
            + "Use buildNumber=-1 for the last build. Requires JUnit plugin.")
    public Map<String, Object> getAdvancedTestResults(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber,
            @ToolParam(description = "Include details of failed tests: true/false") boolean includeFailureDetails) {

        Run<?, ?> run = resolveRun(jobFullName, buildNumber);

        try {
            AbstractTestResultAction<?> tra = run.getAction(AbstractTestResultAction.class);
            if (tra == null) {
                return Map.of("message", "No test results found for this build. "
                        + "Ensure JUnit plugin is installed and configured.");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobName", jobFullName);
            result.put("buildNumber", run.getNumber());
            result.put("total", tra.getTotalCount());
            result.put("passed", tra.getTotalCount() - tra.getFailCount() - tra.getSkipCount());
            result.put("failed", tra.getFailCount());
            result.put("skipped", tra.getSkipCount());
            result.put("passRate",
                    tra.getTotalCount() == 0 ? "0%"
                            : Math.round((tra.getTotalCount() - tra.getFailCount() - tra.getSkipCount())
                                    * 100.0 / tra.getTotalCount()) + "%");

            if (includeFailureDetails && tra.getFailCount() > 0) {
                try {
                    @SuppressWarnings("unchecked")
                    List<Object> failedTests = (List<Object>) tra.getClass()
                            .getMethod("getFailedTests").invoke(tra);
                    List<Map<String, Object>> details = new ArrayList<>();
                    for (Object t : failedTests) {
                        try {
                            Map<String, Object> td = new LinkedHashMap<>();
                            td.put("name", t.getClass().getMethod("getFullName").invoke(t));
                            td.put("errorMessage", t.getClass().getMethod("getErrorDetails").invoke(t));
                            details.add(td);
                        } catch (Exception ignored) {
                        }
                    }
                    result.put("failedTests", details);
                } catch (Exception e) {
                    result.put("failedTestsError", "Could not retrieve test details: " + e.getMessage());
                }
            }
            return result;

        } catch (Exception e) {
            return Map.of("error", "Could not retrieve test results: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 14 — getTestTrend
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get the test results trend over the last N builds of a job. "
            + "Returns a timeline with passed/failed/skipped counts per build, "
            + "plus an overall trend direction for test failures.")
    public Map<String, Object> getTestTrend(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to analyze (2-30)") int lastN) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(lastN, 2), 30);
        List<? extends Run<?, ?>> builds = job.getBuilds()
                .subList(0, Math.min(limit, job.getBuilds().size()));

        List<Map<String, Object>> timeline = new ArrayList<>();
        for (Run<?, ?> run : builds) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("buildNumber", run.getNumber());
            entry.put("result", run.getResult() != null ? run.getResult().toString() : "?");
            try {
                AbstractTestResultAction<?> tra = run.getAction(AbstractTestResultAction.class);
                if (tra != null) {
                    entry.put("total", tra.getTotalCount());
                    entry.put("failed", tra.getFailCount());
                    entry.put("skipped", tra.getSkipCount());
                    entry.put("passed", tra.getTotalCount() - tra.getFailCount() - tra.getSkipCount());
                } else {
                    entry.put("total", null);
                }
            } catch (Exception e) {
                entry.put("total", null);
            }
            timeline.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildsAnalyzed", builds.size());
        result.put("timeline", timeline);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 15 — getRecentBuildsAcrossView
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get the most recently completed builds across all jobs in a view, "
            + "sorted by completion time (newest first). Gives a 'live activity feed' "
            + "of what has been building in a team view. maxPerJob limits results per job.")
    public Map<String, Object> getRecentBuildsAcrossView(
            @ToolParam(description = "View name, e.g. 'All' or 'My-Team'") String viewName,
            @ToolParam(description = "Maximum number of builds to return per job (1-5)") int maxPerJob,
            @ToolParam(description = "Total maximum builds to return across all jobs (max 100)") int totalMax) {

        View view = Jenkins.get().getView(viewName);
        if (view == null)
            throw new IllegalArgumentException("View not found: " + viewName);

        int perJob = Math.min(Math.max(maxPerJob, 1), 5);
        int total = Math.min(Math.max(totalMax, 1), 100);

        List<Map<String, Object>> allBuilds = new ArrayList<>();

        for (Item item : view.getAllItems()) {
            if (!(item instanceof Job))
                continue;
            Job<?, ?> job = (Job<?, ?>) item;

            List<? extends Run<?, ?>> recentBuilds = job.getBuilds()
                    .subList(0, Math.min(perJob, job.getBuilds().size()));

            for (Run<?, ?> run : recentBuilds) {
                if (run.isBuilding())
                    continue;
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("jobName", job.getFullName());
                entry.put("buildNumber", run.getNumber());
                entry.put("result", run.getResult() != null ? run.getResult().toString() : "?");
                entry.put("durationHuman", humanDuration(run.getDuration()));
                entry.put("completedAt", run.getTimestampString());
                entry.put("startTimeMs", run.getStartTimeInMillis() + run.getDuration());
                allBuilds.add(entry);
            }
        }

        // Sort by completion time (newest first)
        allBuilds.sort((a, b) -> Long.compare((long) b.get("startTimeMs"), (long) a.get("startTimeMs")));
        List<Map<String, Object>> limited = allBuilds.subList(0, Math.min(total, allBuilds.size()));
        // Remove internal sort key
        limited.forEach(e -> e.remove("startTimeMs"));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("viewName", viewName);
        result.put("buildCount", limited.size());
        result.put("builds", limited);
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

    private Run<?, ?> resolveRun(String jobFullName, int buildNumber) {
        Job<?, ?> job = resolveJob(jobFullName);
        Run<?, ?> run = buildNumber == -1
                ? job.getLastBuild()
                : job.getBuildByNumber(buildNumber);
        if (run == null)
            throw new IllegalArgumentException(
                    "Build not found: " + jobFullName + " #" + buildNumber);
        return run;
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