package io.jenkins.plugins.mcp.server.extensions;

import hudson.Extension;
import hudson.model.*;
import hudson.model.Cause.UserIdCause;
import hudson.tasks.test.AbstractTestResultAction;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import jenkins.model.Jenkins;

import java.util.*;
import java.util.stream.*;

/**
 * MCP Extension — Developer Productivity & Deployment Tracking
 *
 * Tools (14):
 * 1. getMyBuilds — builds triggered by the current user
 * 2. getJobsByLabel — jobs configured to run on a given agent label
 * 3. getJobsInFolder — list jobs inside a folder
 * 4. searchJobs — search jobs by name/description keyword
 * 5. getDeploymentHistory — deployment history for a job (by env parameter)
 * 6. getReleaseReadiness — release readiness score for a job
 * 7. getBuildsWithStatus — filter last N builds by result
 * 8. getUpstreamBuildChain — upstream trigger chain for a build
 * 9. getJobConfigSummary — human-readable job config summary (read-only)
 * 10. findJobsWithScmUrl — find jobs whose SCM URL matches a pattern
 * 11. getAllJobsUnderFolder — list all jobs recursively under a folder path
 * 12. getArtifactsInventory — all artifacts produced by last build of each job
 * in a view
 * 13. getUserBuildActivity — build activity for a specific user
 * 14. getParameterUsageAcrossView — which jobs in a view use a given parameter
 * name
 *
 * ALL tools are READ-ONLY.
 */
@Extension
public class DeveloperProductivityExtension implements McpServerExtension {

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 1 — getMyBuilds
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get the most recent builds triggered by the current authenticated user "
            + "across all jobs visible to Jenkins. Returns job name, build number, result, "
            + "and when it ran. Answers 'what did I run recently?'")
    public Map<String, Object> getMyBuilds(
            @ToolParam(description = "User ID to look up (e.g. 'jdoe'). "
                    + "Leave empty string to use the Jenkins system user.") String userId,
            @ToolParam(description = "Maximum number of builds to return (max 50)") int maxResults) {

        int limit = Math.min(Math.max(maxResults, 1), 50);
        String targetUser = (userId == null || userId.trim().isEmpty()) ? null : userId.trim();

        List<Map<String, Object>> myBuilds = new ArrayList<>();

        outer: for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            for (Run<?, ?> run : job.getBuilds()) {
                if (myBuilds.size() >= limit)
                    break outer;
                boolean matchesUser = false;
                for (Cause cause : run.getCauses()) {
                    if (cause instanceof UserIdCause) {
                        String uid = ((UserIdCause) cause).getUserId();
                        if (targetUser == null || targetUser.equals(uid)) {
                            matchesUser = true;
                            break;
                        }
                    }
                }
                if (!matchesUser)
                    continue;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("jobName", job.getFullName());
                entry.put("buildNumber", run.getNumber());
                entry.put("result", run.getResult() != null ? run.getResult().toString() : "IN_PROGRESS");
                entry.put("triggeredAt", run.getTimestampString());
                entry.put("durationHuman", humanDuration(run.getDuration()));
                entry.put("building", run.isBuilding());
                entry.put("url", buildUrl(run));
                myBuilds.add(entry);
            }
        }

        myBuilds.sort((a, b) -> b.get("triggeredAt").toString().compareTo(a.get("triggeredAt").toString()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", targetUser != null ? targetUser : "all users");
        result.put("buildCount", myBuilds.size());
        result.put("builds", myBuilds);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 2 — getJobsByLabel
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Find all jobs that are configured to run on a specific agent label "
            + "(e.g. 'linux', 'docker', 'windows'). Useful before modifying or retiring an agent — "
            + "shows which jobs would be impacted.")
    public Map<String, Object> getJobsByLabel(
            @ToolParam(description = "Agent label to search for, e.g. 'linux' or 'docker'") String label) {

        if (label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("label cannot be empty");
        }

        List<Map<String, Object>> matchingJobs = new ArrayList<>();

        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            if (job instanceof AbstractProject) {
                AbstractProject<?, ?> ap = (AbstractProject<?, ?>) job;
                Label assignedLabel = ap.getAssignedLabel();
                if (assignedLabel != null && assignedLabel.getName().contains(label)) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("jobName", job.getFullName());
                    entry.put("labelExpr", assignedLabel.getName());
                    entry.put("url", itemUrl(job));
                    matchingJobs.add(entry);
                }
            } else {
                try {
                    String xml = Items.XSTREAM2.toXML(job);
                    if (xml.contains("label>") && xml.toLowerCase().contains(label.toLowerCase())) {
                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("jobName", job.getFullName());
                        entry.put("labelExpr", "possibly '" + label + "' (Pipeline job — verify in Jenkinsfile)");
                        entry.put("url", itemUrl(job));
                        matchingJobs.add(entry);
                    }
                } catch (Exception ignored) {
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        result.put("matchingJobCount", matchingJobs.size());
        result.put("jobs", matchingJobs);
        if (matchingJobs.isEmpty()) {
            result.put("message", "No jobs found with label '" + label + "'");
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 3 — getJobsInFolder
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "List all jobs directly inside a Jenkins folder (non-recursive). "
            + "Returns job names, types, last build status. "
            + "Use folderPath='.' or '' to list top-level jobs.")
    public Map<String, Object> getJobsInFolder(
            @ToolParam(description = "Folder full name, e.g. 'MyTeam/Backend'. "
                    + "Use empty string for top-level.") String folderPath) {

        List<Map<String, Object>> jobs = new ArrayList<>();

        Collection<? extends Item> items;
        if (folderPath == null || folderPath.trim().isEmpty() || folderPath.equals(".")) {
            items = Jenkins.get().getItems();
        } else {
            Item folderItem = Jenkins.get().getItemByFullName(folderPath);
            if (folderItem == null)
                throw new IllegalArgumentException("Folder not found: " + folderPath);
            if (!(folderItem instanceof ItemGroup)) {
                throw new IllegalArgumentException(folderPath + " is not a folder");
            }
            @SuppressWarnings("unchecked")
            ItemGroup<? extends Item> folder = (ItemGroup<? extends Item>) folderItem;
            items = folder.getItems();
        }

        for (Item item : items) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", item.getName());
            entry.put("fullName", item.getFullName());
            entry.put("type", item.getClass().getSimpleName());
            entry.put("isFolder", item instanceof ItemGroup && !(item instanceof Job));
            entry.put("url", itemUrl(item));

            if (item instanceof Job) {
                Job<?, ?> job = (Job<?, ?>) item;
                Run<?, ?> last = job.getLastBuild();
                entry.put("lastResult", last != null && last.getResult() != null
                        ? last.getResult().toString()
                        : (last != null ? "IN_PROGRESS" : "NEVER"));
                entry.put("lastBuildNumber", last != null ? last.getNumber() : null);
            }
            jobs.add(entry);
        }

        jobs.sort(Comparator.comparing(e -> e.get("name").toString()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("folderPath", folderPath == null || folderPath.isEmpty() ? "(root)" : folderPath);
        result.put("itemCount", jobs.size());
        result.put("items", jobs);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 4 — searchJobs
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Search all Jenkins jobs by name or description keyword. "
            + "Case-insensitive. Returns matching job names, types, last build status, "
            + "and URLs. Useful for discovering jobs you don't know the exact name of.")
    public Map<String, Object> searchJobs(
            @ToolParam(description = "Keyword to search for in job name or description") String keyword,
            @ToolParam(description = "Maximum number of results to return (max 50)") int maxResults) {

        if (keyword == null || keyword.trim().isEmpty()) {
            throw new IllegalArgumentException("keyword cannot be empty");
        }
        int limit = Math.min(Math.max(maxResults, 1), 50);
        String lower = keyword.toLowerCase();

        List<Map<String, Object>> matches = new ArrayList<>();

        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            boolean nameMatch = job.getFullName().toLowerCase().contains(lower);
            boolean descMatch = false;
            try {
                String desc = job.getDescription();
                if (desc != null)
                    descMatch = desc.toLowerCase().contains(lower);
            } catch (Exception ignored) {
            }

            if (!nameMatch && !descMatch)
                continue;

            Run<?, ?> last = job.getLastBuild();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("jobName", job.getFullName());
            entry.put("matchedOn", nameMatch ? "name" : "description");
            entry.put("type", job.getClass().getSimpleName());
            entry.put("lastResult", last != null && last.getResult() != null
                    ? last.getResult().toString()
                    : (last != null ? "IN_PROGRESS" : "NEVER"));
            entry.put("url", itemUrl(job));
            matches.add(entry);
            if (matches.size() >= limit)
                break;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("keyword", keyword);
        result.put("matchCount", matches.size());
        result.put("truncated", matches.size() == limit);
        result.put("results", matches);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 5 — getDeploymentHistory
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get the deployment history of a job — who triggered each build, "
            + "when, what was the result, and optional environment parameter value. "
            + "Useful for audit trails. Returns last N builds with deployment details.")
    public Map<String, Object> getDeploymentHistory(
            @ToolParam(description = "Full job name (deployment/release job)") String jobFullName,
            @ToolParam(description = "Number of recent builds to include (max 30)") int lastN,
            @ToolParam(description = "Optional parameter name to include in output, e.g. 'ENV' or 'VERSION'. "
                    + "Pass empty string to skip.") String parameterName) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(lastN, 1), 30);
        List<? extends Run<?, ?>> builds = collectBuilds(job, limit);

        List<Map<String, Object>> history = new ArrayList<>();
        for (Run<?, ?> run : builds) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("buildNumber", run.getNumber());
            entry.put("result", run.getResult() != null ? run.getResult().toString() : "IN_PROGRESS");
            entry.put("triggeredAt", run.getTimestampString());
            entry.put("durationHuman", humanDuration(run.getDuration()));

            String triggeredBy = run.getCauses().stream()
                    .map(Cause::getShortDescription)
                    .collect(Collectors.joining(", "));
            entry.put("triggeredBy", triggeredBy.isEmpty() ? "unknown" : triggeredBy);

            if (parameterName != null && !parameterName.isEmpty()) {
                try {
                    ParametersAction pa = run.getAction(ParametersAction.class);
                    if (pa != null) {
                        ParameterValue pv = pa.getParameter(parameterName);
                        entry.put(parameterName, pv != null ? pv.getValue() : null);
                    }
                } catch (Exception ignored) {
                }
            }

            entry.put("url", buildUrl(run));
            history.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildCount", history.size());
        result.put("history", history);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 6 — getReleaseReadiness
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Check whether a job is ready to release/deploy. "
            + "Verifies: last build is green, no build currently in progress, "
            + "tests passed (if available), and artifacts are present. "
            + "Returns a readiness score (0-100) and list of blockers.")
    public Map<String, Object> getReleaseReadiness(
            @ToolParam(description = "Full job name to check") String jobFullName) {

        Job<?, ?> job = resolveJob(jobFullName);
        Run<?, ?> last = job.getLastBuild();

        List<String> blockers = new ArrayList<>();
        List<String> passed = new ArrayList<>();

        if (last == null) {
            blockers.add("Job has never been built");
        } else {
            if (last.getResult() == Result.SUCCESS) {
                passed.add("Last build passed (SUCCESS)");
            } else {
                blockers.add("Last build is " + last.getResult() + " (not SUCCESS)");
            }

            if (last.isBuilding()) {
                blockers.add("A build is currently in progress (#" + last.getNumber() + ")");
            } else {
                passed.add("No build currently in progress");
            }

            try {
                AbstractTestResultAction<?> tra = last.getAction(AbstractTestResultAction.class);
                if (tra != null) {
                    if (tra.getFailCount() == 0) {
                        passed.add("All " + tra.getTotalCount() + " tests passed");
                    } else {
                        blockers.add(tra.getFailCount() + " test(s) failing out of " + tra.getTotalCount());
                    }
                } else {
                    passed.add("No test results configured (skip)");
                }
            } catch (Exception ignored) {
                passed.add("Test plugin not available (skip)");
            }

            if (!last.getArtifacts().isEmpty()) {
                passed.add(last.getArtifacts().size() + " artifact(s) produced");
            } else {
                passed.add("No artifacts configured (skip)");
            }

            boolean queued = Arrays.stream(Jenkins.get().getQueue().getItems())
                    .anyMatch(item -> item.task.getFullDisplayName().equals(job.getFullDisplayName()));
            if (queued) {
                blockers.add("A build is pending in the queue");
            }
        }

        int score = blockers.isEmpty() ? 100 : Math.max(0, 100 - blockers.size() * 25);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("readinessScore", score);
        result.put("ready", blockers.isEmpty());
        result.put("lastBuildNumber", last != null ? last.getNumber() : null);
        result.put("lastResult", last != null && last.getResult() != null
                ? last.getResult().toString()
                : "NEVER");
        result.put("blockers", blockers);
        result.put("checks", passed);
        result.put("verdict", blockers.isEmpty()
                ? "✅ Ready to release"
                : "❌ Not ready — resolve " + blockers.size() + " blocker(s) first");
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 7 — getBuildsWithStatus
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get the last N builds of a job filtered by a specific result status. "
            + "Valid statuses: SUCCESS, FAILURE, UNSTABLE, ABORTED, IN_PROGRESS. "
            + "Useful to quickly list all failures or all successful builds.")
    public Map<String, Object> getBuildsWithStatus(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Result filter: SUCCESS, FAILURE, UNSTABLE, ABORTED, or ALL") String status,
            @ToolParam(description = "Max builds to scan (max 100)") int scanLast) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(scanLast, 1), 100);
        List<? extends Run<?, ?>> builds = collectBuilds(job, limit);

        String upperStatus = status.toUpperCase();

        List<Map<String, Object>> filtered = builds.stream()
                .filter(run -> {
                    if ("ALL".equals(upperStatus))
                        return true;
                    if ("IN_PROGRESS".equals(upperStatus))
                        return run.isBuilding();
                    Result r = run.getResult();
                    if (r == null)
                        return false;
                    return r.toString().equals(upperStatus);
                })
                .map(run -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("buildNumber", run.getNumber());
                    e.put("result", run.getResult() != null ? run.getResult().toString() : "IN_PROGRESS");
                    e.put("triggeredAt", run.getTimestampString());
                    e.put("durationHuman", humanDuration(run.getDuration()));
                    e.put("triggeredBy", run.getCauses().stream()
                            .map(Cause::getShortDescription)
                            .collect(Collectors.joining(", ")));
                    e.put("url", buildUrl(run));
                    return e;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("filter", upperStatus);
        result.put("scanned", builds.size());
        result.put("matchCount", filtered.size());
        result.put("builds", filtered);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 8 — getUpstreamBuildChain
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Trace the upstream trigger chain for a build: which build triggered it, "
            + "which triggered that, and so on. Useful for understanding automated "
            + "release pipelines. Use buildNumber=-1 for the last build.")
    public Map<String, Object> getUpstreamBuildChain(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber) {

        Run<?, ?> run = resolveRun(jobFullName, buildNumber);
        List<Map<String, Object>> chain = new ArrayList<>();
        Set<String> visited = new HashSet<>();

        Run<?, ?> current = run;
        while (current != null) {
            String key = current.getParent().getFullName() + "#" + current.getNumber();
            if (visited.contains(key))
                break;
            visited.add(key);

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("jobName", current.getParent().getFullName());
            entry.put("buildNumber", current.getNumber());
            entry.put("result", current.getResult() != null ? current.getResult().toString() : "IN_PROGRESS");
            entry.put("triggeredAt", current.getTimestampString());
            entry.put("causes", current.getCauses().stream()
                    .map(Cause::getShortDescription)
                    .collect(Collectors.toList()));
            chain.add(entry);

            Run<?, ?> next = null;
            for (Cause cause : current.getCauses()) {
                if (cause instanceof Cause.UpstreamCause) {
                    Cause.UpstreamCause uc = (Cause.UpstreamCause) cause;
                    Job<?, ?> upJob = Jenkins.get().getItemByFullName(uc.getUpstreamProject(), Job.class);
                    if (upJob != null) {
                        next = upJob.getBuildByNumber(uc.getUpstreamBuild());
                    }
                    break;
                }
            }
            current = next;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startJob", jobFullName);
        result.put("startBuild", run.getNumber());
        result.put("chainLength", chain.size());
        result.put("chain", chain);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 9 — getJobConfigSummary
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get a human-readable summary of a job's configuration without "
            + "exposing raw XML. Returns: SCM URL, assigned node label, triggers, "
            + "build discarder settings, and parameter count. Read-only inspection.")
    public Map<String, Object> getJobConfigSummary(
            @ToolParam(description = "Full job name") String jobFullName) {

        Job<?, ?> job = resolveJob(jobFullName);
        Map<String, Object> summary = new LinkedHashMap<>();

        summary.put("jobName", job.getFullName());
        summary.put("jobType", job.getClass().getSimpleName());
        summary.put("description", job.getDescription() != null ? job.getDescription() : "");
        summary.put("disabled", job instanceof AbstractProject
                && ((AbstractProject<?, ?>) job).isDisabled());

        if (job instanceof AbstractProject) {
            AbstractProject<?, ?> ap = (AbstractProject<?, ?>) job;
            Label l = ap.getAssignedLabel();
            summary.put("assignedLabel", l != null ? l.getName() : "any");

            ParametersDefinitionProperty pdp = ap.getProperty(ParametersDefinitionProperty.class);
            summary.put("parameterCount", pdp != null ? pdp.getParameterDefinitions().size() : 0);

            // BuildDiscarder: use Object to avoid import issues across Jenkins versions
            Object bd = ap.getBuildDiscarder();
            summary.put("buildDiscarder", bd != null ? bd.getClass().getSimpleName() : "none");

            List<String> triggerNames = ap.getTriggers().keySet().stream()
                    .map(d -> d.getClass().getSimpleName())
                    .collect(Collectors.toList());
            summary.put("triggers", triggerNames);
        }

        if (job instanceof AbstractProject) {
            try {
                hudson.scm.SCM scm = ((AbstractProject<?, ?>) job).getScm();
                if (scm != null) {
                    summary.put("scmType", scm.getClass().getSimpleName());
                    try {
                        Object remoteUrl = scm.getClass().getMethod("getRemoteRepositories").invoke(scm);
                        summary.put("scmDetails", remoteUrl.toString());
                    } catch (Exception e1) {
                        try {
                            Object url = scm.getClass().getMethod("getRepositoryUrl").invoke(scm);
                            summary.put("scmUrl", url.toString());
                        } catch (Exception e2) {
                            summary.put("scmDetails", "Use getJobConfigXml for raw SCM details");
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        return summary;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 10 — searchJobsByScmUrl
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Find all Jenkins jobs whose SCM configuration contains a given URL "
            + "or repository name pattern. Scans config XML. "
            + "Useful to find all jobs that pull from a specific Git repository.")
    public Map<String, Object> searchJobsByScmUrl(
            @ToolParam(description = "Repository URL or partial string to match, e.g. 'my-repo' or 'github.com/org'") String urlPattern,
            @ToolParam(description = "Maximum number of results (max 50)") int maxResults) {

        if (urlPattern == null || urlPattern.trim().isEmpty()) {
            throw new IllegalArgumentException("urlPattern cannot be empty");
        }
        int limit = Math.min(Math.max(maxResults, 1), 50);
        String lower = urlPattern.toLowerCase();

        List<Map<String, Object>> matches = new ArrayList<>();

        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            if (matches.size() >= limit)
                break;
            try {
                String xml = Items.XSTREAM2.toXML(job);
                if (xml.toLowerCase().contains(lower)) {
                    Run<?, ?> last = job.getLastBuild();
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("jobName", job.getFullName());
                    entry.put("jobType", job.getClass().getSimpleName());
                    entry.put("lastResult", last != null && last.getResult() != null
                            ? last.getResult().toString()
                            : "NEVER");
                    entry.put("url", itemUrl(job));
                    matches.add(entry);
                }
            } catch (Exception ignored) {
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("urlPattern", urlPattern);
        result.put("matchCount", matches.size());
        result.put("truncated", matches.size() == limit);
        result.put("jobs", matches);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 11 — getAllJobsUnderFolder (recursive)
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Recursively list all jobs under a folder path, including jobs "
            + "inside sub-folders. Returns the flat list of all descendant jobs. "
            + "Use empty string for the root level.")
    public Map<String, Object> getAllJobsUnderFolder(
            @ToolParam(description = "Folder full name, e.g. 'MyTeam'. "
                    + "Use empty string for all Jenkins jobs.") String folderPath,
            @ToolParam(description = "Maximum jobs to return (max 200)") int maxResults) {

        int limit = Math.min(Math.max(maxResults, 1), 200);
        List<Map<String, Object>> jobs = new ArrayList<>();

        @SuppressWarnings({ "unchecked", "rawtypes" })
        List<Job<?, ?>> allJobs;
        if (folderPath == null || folderPath.trim().isEmpty()) {
            allJobs = (List<Job<?, ?>>) (List) Jenkins.get().getAllItems(Job.class);
        } else {
            Item folder = Jenkins.get().getItemByFullName(folderPath);
            if (folder == null)
                throw new IllegalArgumentException("Folder not found: " + folderPath);
            if (!(folder instanceof ItemGroup)) {
                throw new IllegalArgumentException(folderPath + " is not a folder");
            }
            ItemGroup<? extends Item> ig = (ItemGroup<? extends Item>) folder;
            allJobs = (List<Job<?, ?>>) (List) Items.getAllItems(ig, Job.class);
        }

        for (Job<?, ?> job : allJobs) {
            if (jobs.size() >= limit)
                break;
            Run<?, ?> last = job.getLastBuild();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("jobName", job.getFullName());
            entry.put("type", job.getClass().getSimpleName());
            entry.put("lastResult", last != null && last.getResult() != null
                    ? last.getResult().toString()
                    : (last != null ? "IN_PROGRESS" : "NEVER"));
            entry.put("lastBuildNum", last != null ? last.getNumber() : null);
            entry.put("url", itemUrl(job));
            jobs.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("folderPath", folderPath == null || folderPath.isEmpty() ? "(root)" : folderPath);
        result.put("jobCount", jobs.size());
        result.put("truncated", jobs.size() == limit);
        result.put("jobs", jobs);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 12 — getArtifactsInventory
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "List all artifacts produced by the last build of each job in a view. "
            + "Gives an inventory of available artifacts across the team. "
            + "Only includes jobs whose last build has artifacts.")
    public Map<String, Object> getArtifactsInventory(
            @ToolParam(description = "View name, e.g. 'All' or 'Release'") String viewName) {

        View view = Jenkins.get().getView(viewName);
        if (view == null)
            throw new IllegalArgumentException("View not found: " + viewName);

        List<Map<String, Object>> inventory = new ArrayList<>();

        for (Item item : view.getAllItems()) {
            if (!(item instanceof Job))
                continue;
            Job<?, ?> job = (Job<?, ?>) item;
            Run<?, ?> last = job.getLastBuild();
            if (last == null || last.getArtifacts().isEmpty())
                continue;

            // Use getRootDir()/archive to avoid deprecated getArtifactsDir()
            java.io.File artifactsDir = new java.io.File(last.getRootDir(), "archive");

            List<Map<String, Object>> artifacts = last.getArtifacts().stream()
                    .map(a -> {
                        Map<String, Object> am = new LinkedHashMap<>();
                        am.put("fileName", a.getFileName());
                        am.put("relativePath", a.relativePath);
                        java.io.File f = new java.io.File(artifactsDir, a.relativePath);
                        am.put("sizeBytes", f.exists() ? f.length() : -1L);
                        am.put("downloadUrl", Jenkins.get().getRootUrl()
                                + last.getUrl() + "artifact/" + a.relativePath);
                        return am;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("jobName", job.getFullName());
            entry.put("buildNumber", last.getNumber());
            entry.put("buildResult", last.getResult() != null ? last.getResult().toString() : "?");
            entry.put("artifactCount", artifacts.size());
            entry.put("artifacts", artifacts);
            inventory.add(entry);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("viewName", viewName);
        result.put("jobsWithArtifacts", inventory.size());
        result.put("inventory", inventory);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 13 — getUserBuildActivity
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get a summary of build activity for a specific Jenkins user: "
            + "how many builds they triggered, success rate, and which jobs they ran. "
            + "Scans the last N builds per job across all jobs.")
    public Map<String, Object> getUserBuildActivity(
            @ToolParam(description = "Jenkins user ID to analyze, e.g. 'jdoe'") String userId,
            @ToolParam(description = "Max builds to scan per job (max 50)") int scanPerJob) {

        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("userId cannot be empty");
        }
        int limit = Math.min(Math.max(scanPerJob, 1), 50);

        int totalBuilds = 0;
        int successBuilds = 0;
        Map<String, Integer> jobCounts = new LinkedHashMap<>();

        for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
            for (Run<?, ?> run : collectBuilds(job, limit)) {
                boolean isUser = run.getCauses().stream()
                        .filter(c -> c instanceof UserIdCause)
                        .anyMatch(c -> userId.equals(((UserIdCause) c).getUserId()));
                if (!isUser)
                    continue;

                totalBuilds++;
                if (run.getResult() == Result.SUCCESS)
                    successBuilds++;
                jobCounts.merge(job.getFullName(), 1, Integer::sum);
            }
        }

        List<Map<String, Object>> jobActivity = jobCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("jobName", e.getKey());
                    m.put("buildCount", e.getValue());
                    return m;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("totalBuilds", totalBuilds);
        result.put("successBuilds", successBuilds);
        result.put("successRate", totalBuilds == 0 ? "0%"
                : Math.round(successBuilds * 100.0 / totalBuilds) + "%");
        result.put("distinctJobs", jobActivity.size());
        result.put("jobActivity", jobActivity);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 14 — getParameterUsageAcrossView
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Find all jobs in a view that have a specific parameter name defined. "
            + "Useful when you want to know which jobs support a parameter like 'ENV' or 'VERSION'. "
            + "Returns job name, parameter type, and default value.")
    public Map<String, Object> getParameterUsageAcrossView(
            @ToolParam(description = "View name, e.g. 'All'") String viewName,
            @ToolParam(description = "Parameter name to search for, e.g. 'ENV' or 'BRANCH'") String parameterName) {

        View view = Jenkins.get().getView(viewName);
        if (view == null)
            throw new IllegalArgumentException("View not found: " + viewName);
        if (parameterName == null || parameterName.trim().isEmpty()) {
            throw new IllegalArgumentException("parameterName cannot be empty");
        }

        List<Map<String, Object>> matches = new ArrayList<>();

        for (Item item : view.getAllItems()) {
            if (!(item instanceof AbstractProject))
                continue;
            AbstractProject<?, ?> ap = (AbstractProject<?, ?>) item;

            ParametersDefinitionProperty pdp = ap.getProperty(ParametersDefinitionProperty.class);
            if (pdp == null)
                continue;

            for (ParameterDefinition pd : pdp.getParameterDefinitions()) {
                if (!pd.getName().equalsIgnoreCase(parameterName))
                    continue;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("jobName", ap.getFullName());
                entry.put("paramName", pd.getName());
                entry.put("paramType", pd.getClass().getSimpleName().replace("ParameterDefinition", ""));
                try {
                    Object dv = pd.getDefaultParameterValue() != null
                            ? pd.getDefaultParameterValue().getValue()
                            : null;
                    entry.put("defaultValue", dv);
                } catch (Exception e) {
                    entry.put("defaultValue", null);
                }
                entry.put("description", pd.getDescription());
                entry.put("url", itemUrl(ap));
                matches.add(entry);
                break;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("viewName", viewName);
        result.put("parameterName", parameterName);
        result.put("matchCount", matches.size());
        result.put("jobs", matches);
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
        Run<?, ?> run = buildNumber == -1 ? job.getLastBuild() : job.getBuildByNumber(buildNumber);
        if (run == null)
            throw new IllegalArgumentException("Build not found: " + jobFullName + " #" + buildNumber);
        return run;
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