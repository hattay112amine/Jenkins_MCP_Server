package io.jenkins.plugins.mcp.server.extensions;

import hudson.Extension;
import hudson.model.*;
import io.jenkins.plugins.mcp.server.McpServerExtension;
import io.jenkins.plugins.mcp.server.annotation.Tool;
import io.jenkins.plugins.mcp.server.annotation.ToolParam;
import hudson.EnvVars;
import jenkins.model.Jenkins;

import java.util.*;
import java.util.stream.*;

/**
 * MCP Extension — Pipeline Graph & Execution Internals
 *
 * Uses Jenkins INTERNAL Java API exclusively — not available via REST API:
 * - FlowExecution / FlowNode graph traversal
 * - TimingAction on individual FlowNodes
 * - LabelAction / ArgumentsAction on steps
 * - InterruptedBuildAction for interruption details
 * - EnvironmentContributors for env var resolution
 * - WorkspaceList for workspace locking state
 * - BuildData (GitBuildData) injected by Git plugin
 *
 * Tools (13):
 * 1. getPipelineFullGraph — full FlowNode graph with type/timing per node
 * 2. getPipelineParallelBranches — detect parallel branches and their results
 * 3. getBuildEnvironmentVars — ALL env vars resolved for a build
 * 4. getBuildInterruptionDetails — who interrupted, how, and at which step
 * 5. getWorkspaceStatus — workspace paths and lock state per node
 * 6. getStepArguments — arguments passed to each step in a pipeline
 * 7. getGitBuildMetadata — Git SHA, branch, remote URL from BuildData
 * 8. getPipelineInputStepHistory — pending/completed input steps in a pipeline
 * 9. getBuildQueueWaitTime — how long a build waited in queue before running
 * 10. getStageNodeMapping — which agent/node each stage ran on
 * 11. getLabelMatchingNodes — nodes that satisfy a label expression
 * 12. getParallelStageTimingMatrix — timing matrix for parallel stages side by
 * side
 * 13. getPipelineSuspectedBottleneck — stage with highest avg duration
 * (bottleneck)
 *
 * ALL tools are READ-ONLY.
 */
@Extension
public class PipelineGraphExtension implements McpServerExtension {

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 1 — getPipelineFullGraph
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get the full internal FlowNode execution graph of a Pipeline build. "
            + "Returns every node in the graph with its type (Stage, Step, Block), "
            + "display name, start time, duration, and status. "
            + "This is the raw execution DAG that Jenkins uses internally — "
            + "far more granular than stage-level REST API data. "
            + "Use buildNumber=-1 for the last build.")
    public Map<String, Object> getPipelineFullGraph(
            @ToolParam(description = "Full Pipeline job name, e.g. 'folder/my-pipeline'") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber,
            @ToolParam(description = "Node type filter: 'all', 'stage', 'step', 'block'") String filter) {

        try {
            Class<?> wfRunClass = Class.forName(
                    "org.jenkinsci.plugins.workflow.job.WorkflowRun");
            Run<?, ?> run = resolveRun(jobFullName, buildNumber);
            if (!wfRunClass.isInstance(run)) {
                return Map.of("error", "Job '" + jobFullName + "' is not a Pipeline job");
            }

            Object execution = wfRunClass.getMethod("getExecution").invoke(run);
            if (execution == null) {
                return Map.of("error", "Pipeline has no execution (build not started or incomplete)");
            }

            Class<?> depthFirstClass = Class.forName(
                    "org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner");
            Object scanner = depthFirstClass.getDeclaredConstructor().newInstance();
            Class<?> flowNodeClass = Class.forName("org.jenkinsci.plugins.workflow.graph.FlowNode");

            // Get all nodes
            @SuppressWarnings("unchecked")
            Iterable<Object> allNodes = (Iterable<Object>) depthFirstClass
                    .getMethod("allNodes", Class.forName("org.jenkinsci.plugins.workflow.flow.FlowExecution"))
                    .invoke(scanner, execution);

            List<Map<String, Object>> nodes = new ArrayList<>();
            String lowerFilter = filter == null ? "all" : filter.toLowerCase();

            for (Object node : allNodes) {
                String typeName = node.getClass().getSimpleName();
                String displayName = (String) flowNodeClass.getMethod("getDisplayName").invoke(node);
                String id = (String) flowNodeClass.getMethod("getId").invoke(node);

                // Filter by type
                if (!"all".equals(lowerFilter)) {
                    boolean isStage = typeName.contains("Stage") || (displayName != null
                            && displayName.toLowerCase().startsWith("stage"));
                    boolean isStep = typeName.contains("Step") || typeName.contains("Atom");
                    boolean isBlock = typeName.contains("Block") || typeName.contains("Start")
                            || typeName.contains("End");
                    if ("stage".equals(lowerFilter) && !isStage)
                        continue;
                    if ("step".equals(lowerFilter) && !isStep)
                        continue;
                    if ("block".equals(lowerFilter) && !isBlock)
                        continue;
                }

                Map<String, Object> nodeMap = new LinkedHashMap<>();
                nodeMap.put("id", id);
                nodeMap.put("displayName", displayName);
                nodeMap.put("type", typeName);

                // Timing via TimingAction
                try {
                    Class<?> timingClass = Class.forName(
                            "org.jenkinsci.plugins.workflow.actions.TimingAction");
                    Object timingAction = flowNodeClass
                            .getMethod("getAction", Class.class)
                            .invoke(node, timingClass);
                    if (timingAction != null) {
                        long startTime = (long) timingClass.getMethod("getStartTime").invoke(timingAction);
                        nodeMap.put("startTimeMs", startTime);
                        nodeMap.put("startTimeHuman", new java.util.Date(startTime).toString());
                    }
                } catch (Exception ignored) {
                }

                // Error action
                try {
                    Class<?> errorClass = Class.forName(
                            "org.jenkinsci.plugins.workflow.actions.ErrorAction");
                    Object errorAction = flowNodeClass
                            .getMethod("getAction", Class.class)
                            .invoke(node, errorClass);
                    if (errorAction != null) {
                        Object err = errorClass.getMethod("getError").invoke(errorAction);
                        nodeMap.put("error", err != null ? err.toString() : "unknown error");
                    }
                } catch (Exception ignored) {
                }

                // Parent IDs
                try {
                    @SuppressWarnings("unchecked")
                    List<Object> parents = (List<Object>) flowNodeClass
                            .getMethod("getParents").invoke(node);
                    List<String> parentIds = parents.stream()
                            .map(p -> {
                                try {
                                    return (String) flowNodeClass.getMethod("getId").invoke(p);
                                } catch (Exception e) {
                                    return "?";
                                }
                            })
                            .collect(Collectors.toList());
                    nodeMap.put("parentIds", parentIds);
                } catch (Exception ignored) {
                }

                nodes.add(nodeMap);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobName", jobFullName);
            result.put("buildNumber", resolveRun(jobFullName, buildNumber).getNumber());
            result.put("filter", lowerFilter);
            result.put("nodeCount", nodes.size());
            result.put("graph", nodes);
            return result;

        } catch (ClassNotFoundException e) {
            return Map.of("error",
                    "Pipeline plugins not available. Install workflow-job and workflow-api plugins.");
        } catch (Exception e) {
            return Map.of("error", "Failed to traverse pipeline graph: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 2 — getPipelineParallelBranches
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Detect parallel branches in a Pipeline build and return each branch's "
            + "name, start time, duration, status, and which steps it contained. "
            + "The REST API only shows stage-level data — this tool uses the internal "
            + "FlowNode graph to identify actual parallel { } blocks. "
            + "Use buildNumber=-1 for the last build.")
    public Map<String, Object> getPipelineParallelBranches(
            @ToolParam(description = "Full Pipeline job name") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber) {

        try {
            Class<?> wfRunClass = Class.forName("org.jenkinsci.plugins.workflow.job.WorkflowRun");
            Run<?, ?> run = resolveRun(jobFullName, buildNumber);
            if (!wfRunClass.isInstance(run)) {
                return Map.of("error", "Not a Pipeline job");
            }

            Object execution = wfRunClass.getMethod("getExecution").invoke(run);
            if (execution == null)
                return Map.of("error", "No execution available");

            Class<?> scannerClass = Class.forName(
                    "org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner");
            Object scanner = scannerClass.getDeclaredConstructor().newInstance();
            Class<?> flowNodeClass = Class.forName("org.jenkinsci.plugins.workflow.graph.FlowNode");

            @SuppressWarnings("unchecked")
            Iterable<Object> allNodes = (Iterable<Object>) scannerClass
                    .getMethod("allNodes", Class.forName(
                            "org.jenkinsci.plugins.workflow.flow.FlowExecution"))
                    .invoke(scanner, execution);

            // Parallel branch detection: ParallelLabelAction is set on branch start nodes
            Class<?> parallelLabelClass = null;
            try {
                parallelLabelClass = Class.forName(
                        "org.jenkinsci.plugins.workflow.actions.ThreadNameAction");
            } catch (ClassNotFoundException e) {
                try {
                    parallelLabelClass = Class.forName(
                            "org.jenkinsci.plugins.workflow.cps.actions.ParallelLabelAction");
                } catch (ClassNotFoundException ignored) {
                }
            }

            Map<String, Map<String, Object>> branches = new LinkedHashMap<>();

            for (Object node : allNodes) {
                if (parallelLabelClass != null) {
                    Object labelAction = flowNodeClass
                            .getMethod("getAction", Class.class)
                            .invoke(node, parallelLabelClass);
                    if (labelAction != null) {
                        String branchName = (String) labelAction.getClass()
                                .getMethod("getThreadName").invoke(labelAction);

                        Map<String, Object> branchInfo = branches.computeIfAbsent(
                                branchName, k -> new LinkedHashMap<>());
                        branchInfo.put("branchName", branchName);

                        try {
                            Class<?> timingClass = Class.forName(
                                    "org.jenkinsci.plugins.workflow.actions.TimingAction");
                            Object timing = flowNodeClass
                                    .getMethod("getAction", Class.class)
                                    .invoke(node, timingClass);
                            if (timing != null) {
                                long st = (long) timingClass.getMethod("getStartTime").invoke(timing);
                                branchInfo.put("startTimeMs", st);
                            }
                        } catch (Exception ignored) {
                        }

                        // Error check
                        try {
                            Class<?> errorClass = Class.forName(
                                    "org.jenkinsci.plugins.workflow.actions.ErrorAction");
                            Object err = flowNodeClass.getMethod("getAction", Class.class)
                                    .invoke(node, errorClass);
                            branchInfo.put("failed", err != null);
                            if (err != null) {
                                Object errObj = errorClass.getMethod("getError").invoke(err);
                                branchInfo.put("errorMessage", errObj != null ? errObj.toString() : "");
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            }

            // Mark branches without explicit failure as succeeded
            branches.values().forEach(b -> b.putIfAbsent("failed", false));

            List<Map<String, Object>> branchList = new ArrayList<>(branches.values());
            long failedCount = branchList.stream()
                    .filter(b -> Boolean.TRUE.equals(b.get("failed"))).count();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobName", jobFullName);
            result.put("buildNumber", run.getNumber());
            result.put("branchCount", branchList.size());
            result.put("failedBranches", failedCount);
            result.put("branches", branchList);
            if (branchList.isEmpty()) {
                result.put("message", "No parallel branches detected in this build. "
                        + "The pipeline may be sequential only.");
            }
            return result;

        } catch (ClassNotFoundException e) {
            return Map.of("error", "workflow-api plugin not available");
        } catch (Exception e) {
            return Map.of("error", "Failed to analyze parallel branches: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 3 — getBuildEnvironmentVars
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get ALL environment variables that were injected into a build "
            + "by Jenkins and its plugins. This includes: Jenkins built-ins (BUILD_NUMBER, "
            + "JOB_NAME, WORKSPACE...), SCM variables (GIT_COMMIT, GIT_BRANCH...), "
            + "parameter values, and any injected env. "
            + "Not available via REST API — requires internal EnvVars resolution. "
            + "Use buildNumber=-1 for the last build. "
            + "Pass filterPrefix to narrow results (e.g. 'GIT_' or 'JAVA_').")
    public Map<String, Object> getBuildEnvironmentVars(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber,
            @ToolParam(description = "Optional prefix filter, e.g. 'GIT_'. Empty string = return all.") String filterPrefix) {

        Run<?, ?> run = resolveRun(jobFullName, buildNumber);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildNumber", run.getNumber());

        try {
            // EnvVars is the internal class holding all resolved env vars for a build
            Class<?> envVarsClass = Class.forName("hudson.EnvVars");
            Object envVars = run.getClass().getMethod("getEnvironment",
                    Class.forName("hudson.model.TaskListener"))
                    .invoke(run, hudson.model.TaskListener.NULL);

            @SuppressWarnings("unchecked")
            Map<String, String> rawEnv = (Map<String, String>) envVars;

            String prefix = (filterPrefix == null) ? "" : filterPrefix.toUpperCase();

            Map<String, String> filtered = new TreeMap<>();
            for (Map.Entry<String, String> e : rawEnv.entrySet()) {
                if (prefix.isEmpty() || e.getKey().toUpperCase().startsWith(prefix)) {
                    // Mask sensitive-looking values
                    String key = e.getKey();
                    boolean isSensitive = key.toLowerCase().contains("password")
                            || key.toLowerCase().contains("secret")
                            || key.toLowerCase().contains("token")
                            || key.toLowerCase().contains("credential")
                            || key.toLowerCase().contains("api_key");
                    filtered.put(key, isSensitive ? "***MASKED***" : e.getValue());
                }
            }

            result.put("varCount", filtered.size());
            result.put("filterPrefix", prefix.isEmpty() ? "(none)" : prefix);
            result.put("note", "Sensitive keys (password/secret/token/credential) are masked.");
            result.put("envVars", filtered);

        } catch (Exception e) {
            // Fallback: use getCharacteristicEnvVars which is always available
            try {
                EnvVars env = run.getCharacteristicEnvVars();
                String prefix = (filterPrefix == null) ? "" : filterPrefix.toUpperCase();
                Map<String, String> filtered = new TreeMap<>();
                env.forEach((k, v) -> {
                    if (prefix.isEmpty() || k.toUpperCase().startsWith(prefix)) {
                        filtered.put(k, v);
                    }
                });
                result.put("varCount", filtered.size());
                result.put("envVars", filtered);
                result.put("note", "Partial env vars (characteristic only — full resolution unavailable).");
            } catch (Exception e2) {
                result.put("error", "Could not resolve environment variables: " + e2.getMessage());
            }
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 4 — getBuildInterruptionDetails
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get detailed information about who interrupted/aborted a build "
            + "and at which step it was stopped. Uses InterruptedBuildAction "
            + "which is only accessible via internal API. "
            + "Answers: 'who killed this build and why?' with more detail than just Cause. "
            + "Use buildNumber=-1 for the last build.")
    public Map<String, Object> getBuildInterruptionDetails(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber) {

        Run<?, ?> run = resolveRun(jobFullName, buildNumber);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildNumber", run.getNumber());
        result.put("result", run.getResult() != null ? run.getResult().toString() : "IN_PROGRESS");

        if (run.getResult() != Result.ABORTED) {
            result.put("interrupted", false);
            result.put("message", "Build was not aborted (result: " + run.getResult() + ")");
            return result;
        }

        result.put("interrupted", true);
        result.put("ranForHuman", humanDuration(run.getDuration()));

        // InterruptedBuildAction — internal class
        try {
            Class<?> ibaClass = Class.forName(
                    "jenkins.model.InterruptedBuildAction");
            Object iba = run.getAction(ibaClass.asSubclass(InvisibleAction.class));
            // Note: InterruptedBuildAction implements Action, not InvisibleAction
            // Try getAllActions approach
            for (Action action : run.getAllActions()) {
                if (action.getClass().getName().contains("InterruptedBuildAction")) {
                    iba = action;
                    break;
                }
            }

            if (iba != null) {
                @SuppressWarnings("unchecked")
                List<Object> causes = (List<Object>) iba.getClass()
                        .getMethod("getCauses").invoke(iba);
                List<Map<String, Object>> causeDetails = new ArrayList<>();
                for (Object cause : causes) {
                    Map<String, Object> cd = new LinkedHashMap<>();
                    cd.put("type", cause.getClass().getSimpleName());
                    try {
                        cd.put("user", cause.getClass().getMethod("getUser").invoke(cause).toString());
                    } catch (Exception ignored) {
                    }
                    try {
                        cd.put("description", cause.getClass()
                                .getMethod("getShortDescription").invoke(cause).toString());
                    } catch (Exception ignored) {
                    }
                    causeDetails.add(cd);
                }
                result.put("interruptionCauses", causeDetails);
            } else {
                result.put("interruptionCauses", List.of());
                result.put("note", "InterruptedBuildAction not found — "
                        + "build may have been aborted before InterruptedBuildAction was set");
            }
        } catch (Exception e) {
            result.put("interruptionCausesError", e.getMessage());
        }

        // Fallback: standard causes
        List<String> standardCauses = run.getCauses().stream()
                .map(Cause::getShortDescription)
                .collect(Collectors.toList());
        result.put("standardCauses", standardCauses);

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 5 — getWorkspaceStatus
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Inspect the workspace of a job on each node: "
            + "path, whether it is currently locked by a running build, "
            + "and its size on disk. Uses Jenkins internal WorkspaceList — "
            + "not available via REST API. Helps diagnose workspace contention issues.")
    public Map<String, Object> getWorkspaceStatus(
            @ToolParam(description = "Full job name") String jobFullName) {

        Job<?, ?> job = resolveJob(jobFullName);
        List<Map<String, Object>> workspaces = new ArrayList<>();

        for (Computer computer : Jenkins.get().getComputers()) {
            if (!computer.isOnline())
                continue;
            try {
                hudson.FilePath rootPath = computer.getNode() != null
                        ? computer.getNode().getRootPath()
                        : null;
                if (rootPath == null)
                    continue;

                // Standard workspace path: <node_root>/workspace/<job_name>
                String wsPath = rootPath.getRemote() + "/workspace/" + job.getName();
                hudson.FilePath wsFp = new hudson.FilePath(
                        computer.getNode().getRootPath().getChannel(),
                        wsPath);

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("nodeName", computer.getName().isEmpty() ? "built-in" : computer.getName());
                entry.put("path", wsPath);

                boolean exists = false;
                long sizeBytes = -1;
                try {
                    exists = wsFp.exists();
                    sizeBytes = exists ? wsFp.act(new WorkspaceSizeCallable()) : -1;
                } catch (Exception ignored) {
                }
                entry.put("exists", exists);
                entry.put("sizeMb", sizeBytes >= 0
                        ? Math.round(sizeBytes / 1_048_576.0 * 10) / 10.0
                        : -1);

                // Check if workspace is currently locked by an active build
                boolean locked = false;
                for (Run<?, ?> run : job.getBuilds()) {
                    if (run.isBuilding()) {
                        locked = true;
                        break;
                    }
                }
                entry.put("lockedByActiveBuild", locked);
                workspaces.add(entry);

            } catch (Exception ignored) {
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("workspaceCount", workspaces.size());
        result.put("workspaces", workspaces);
        return result;
    }

    // Helper for workspace size computation
    private static class WorkspaceSizeCallable
            implements hudson.remoting.Callable<Long, Exception> {
        @Override
        public Long call() throws Exception {
            java.io.File f = new java.io.File(".");
            return folderSize(f);
        }

        private long folderSize(java.io.File dir) {
            long size = 0;
            java.io.File[] files = dir.listFiles();
            if (files == null)
                return 0;
            for (java.io.File file : files) {
                size += file.isFile() ? file.length() : folderSize(file);
            }
            return size;
        }

        @Override
        public void checkRoles(org.jenkinsci.remoting.RoleChecker checker) {
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 6 — getStepArguments
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get the arguments passed to each step in a Pipeline build. "
            + "Uses ArgumentsAction (internal API) to reveal what parameters "
            + "were given to steps like sh, git, docker, echo, etc. "
            + "Helps understand exactly what a pipeline executed without reading raw logs. "
            + "Use buildNumber=-1 for the last build.")
    public Map<String, Object> getStepArguments(
            @ToolParam(description = "Full Pipeline job name") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber,
            @ToolParam(description = "Step name filter (e.g. 'sh', 'git', 'docker'). "
                    + "Empty string = return all steps.") String stepNameFilter) {

        try {
            Class<?> wfRunClass = Class.forName("org.jenkinsci.plugins.workflow.job.WorkflowRun");
            Run<?, ?> run = resolveRun(jobFullName, buildNumber);
            if (!wfRunClass.isInstance(run)) {
                return Map.of("error", "Not a Pipeline job");
            }

            Object execution = wfRunClass.getMethod("getExecution").invoke(run);
            if (execution == null)
                return Map.of("error", "No execution available");

            Class<?> scannerClass = Class.forName(
                    "org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner");
            Object scanner = scannerClass.getDeclaredConstructor().newInstance();
            Class<?> flowNodeClass = Class.forName("org.jenkinsci.plugins.workflow.graph.FlowNode");

            @SuppressWarnings("unchecked")
            Iterable<Object> allNodes = (Iterable<Object>) scannerClass
                    .getMethod("allNodes", Class.forName(
                            "org.jenkinsci.plugins.workflow.flow.FlowExecution"))
                    .invoke(scanner, execution);

            Class<?> argsActionClass = null;
            try {
                argsActionClass = Class.forName(
                        "org.jenkinsci.plugins.workflow.cps.actions.ArgumentsActionImpl");
            } catch (ClassNotFoundException e) {
                argsActionClass = Class.forName(
                        "org.jenkinsci.plugins.workflow.actions.ArgumentsAction");
            }

            String lower = (stepNameFilter == null) ? "" : stepNameFilter.toLowerCase();
            List<Map<String, Object>> steps = new ArrayList<>();

            for (Object node : allNodes) {
                String displayName = (String) flowNodeClass.getMethod("getDisplayName").invoke(node);
                if (displayName == null)
                    continue;

                // Apply filter
                if (!lower.isEmpty() && !displayName.toLowerCase().contains(lower))
                    continue;

                // Look for ArgumentsAction
                Object argsAction = flowNodeClass.getMethod("getAction", Class.class)
                        .invoke(node, argsActionClass);
                if (argsAction == null)
                    continue;

                Map<String, Object> stepMap = new LinkedHashMap<>();
                stepMap.put("nodeId", flowNodeClass.getMethod("getId").invoke(node));
                stepMap.put("stepName", displayName);

                try {
                    Object args = argsActionClass.getMethod("getArgumentsWithSensitiveVariablesMasked")
                            .invoke(argsAction);
                    stepMap.put("arguments", args != null ? args.toString() : null);
                } catch (Exception e1) {
                    try {
                        Object args = argsActionClass.getMethod("getArguments").invoke(argsAction);
                        stepMap.put("arguments", args != null ? args.toString() : null);
                    } catch (Exception e2) {
                        stepMap.put("arguments", "(could not retrieve — masked or unavailable)");
                    }
                }

                // Timing
                try {
                    Class<?> timingClass = Class.forName(
                            "org.jenkinsci.plugins.workflow.actions.TimingAction");
                    Object timing = flowNodeClass.getMethod("getAction", Class.class)
                            .invoke(node, timingClass);
                    if (timing != null) {
                        stepMap.put("startTimeMs",
                                timingClass.getMethod("getStartTime").invoke(timing));
                    }
                } catch (Exception ignored) {
                }

                steps.add(stepMap);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobName", jobFullName);
            result.put("buildNumber", run.getNumber());
            result.put("filter", lower.isEmpty() ? "(all steps)" : stepNameFilter);
            result.put("stepCount", steps.size());
            result.put("steps", steps);
            return result;

        } catch (ClassNotFoundException e) {
            return Map.of("error", "workflow-cps plugin not available");
        } catch (Exception e) {
            return Map.of("error", "Failed to retrieve step arguments: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 7 — getGitBuildMetadata
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get Git-specific build metadata injected by the Git plugin: "
            + "exact commit SHA, branch name, remote URL, and all Git refs built. "
            + "Uses BuildData (internal GitBuildData action) — "
            + "more complete than changelog entries and not available via REST API. "
            + "Use buildNumber=-1 for the last build.")
    public Map<String, Object> getGitBuildMetadata(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber) {

        Run<?, ?> run = resolveRun(jobFullName, buildNumber);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildNumber", run.getNumber());

        List<Map<String, Object>> allGitData = new ArrayList<>();

        for (Action action : run.getAllActions()) {
            String className = action.getClass().getName();
            // BuildData is the class injected by git plugin
            if (!className.contains("hudson.plugins.git.util.BuildData")
                    && !className.contains("GitBuildData"))
                continue;

            Map<String, Object> gd = new LinkedHashMap<>();
            try {
                // Remote URLs
                Object remoteUrls = action.getClass().getMethod("getRemoteUrls").invoke(action);
                gd.put("remoteUrls", remoteUrls);
            } catch (Exception ignored) {
            }

            try {
                // Last built revision
                Object revision = action.getClass().getMethod("getLastBuiltRevision").invoke(action);
                if (revision != null) {
                    try {
                        Object sha = revision.getClass().getMethod("getSha1String").invoke(revision);
                        gd.put("commitSha", sha);
                    } catch (Exception ignored) {
                    }

                    try {
                        @SuppressWarnings("unchecked")
                        Collection<Object> branches = (Collection<Object>) revision.getClass().getMethod("getBranches")
                                .invoke(revision);
                        List<String> branchNames = new ArrayList<>();
                        for (Object b : branches) {
                            try {
                                branchNames.add(b.getClass().getMethod("getName").invoke(b).toString());
                            } catch (Exception ignored) {
                            }
                        }
                        gd.put("branches", branchNames);
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            }

            // Index (remote name)
            try {
                Object index = action.getClass().getMethod("getIndex").invoke(action);
                gd.put("index", index);
            } catch (Exception ignored) {
            }

            if (!gd.isEmpty())
                allGitData.add(gd);
        }

        if (allGitData.isEmpty()) {
            result.put("message", "No Git build data found. "
                    + "Ensure Git plugin is installed and job uses Git SCM.");
        } else {
            result.put("repositoryCount", allGitData.size());
            result.put("gitRepositories", allGitData);
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 8 — getPipelineInputStepHistory
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get the history of 'input' steps in a Pipeline job: "
            + "which inputs were approved, who approved them, and when. "
            + "Also shows any currently pending input steps waiting for approval. "
            + "Uses InputAction internal API — not available via REST.")
    public Map<String, Object> getPipelineInputStepHistory(
            @ToolParam(description = "Full Pipeline job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to scan (max 20)") int lastN) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(lastN, 1), 20);
        List<? extends Run<?, ?>> builds = job.getBuilds()
                .subList(0, Math.min(limit, job.getBuilds().size()));

        List<Map<String, Object>> history = new ArrayList<>();

        for (Run<?, ?> run : builds) {
            List<Map<String, Object>> inputs = new ArrayList<>();

            for (Action action : run.getAllActions()) {
                String cn = action.getClass().getName();
                // InputAction stores completed input steps
                if (!cn.contains("InputAction") && !cn.contains("PendingInputStepExecution"))
                    continue;

                Map<String, Object> inputInfo = new LinkedHashMap<>();
                inputInfo.put("actionType", action.getClass().getSimpleName());

                try {
                    Object executions = action.getClass().getMethod("getExecutions").invoke(action);
                    inputInfo.put("executions", executions != null ? executions.toString() : null);
                } catch (Exception ignored) {
                }

                try {
                    Object id = action.getClass().getMethod("getId").invoke(action);
                    inputInfo.put("id", id);
                } catch (Exception ignored) {
                }

                try {
                    Object message = action.getClass().getMethod("getMessage").invoke(action);
                    inputInfo.put("message", message);
                } catch (Exception ignored) {
                }

                inputs.add(inputInfo);
            }

            // Check for pending input in currently building runs
            boolean hasPendingInput = false;
            if (run.isBuilding()) {
                try {
                    Class<?> inputStepClass = Class.forName(
                            "org.jenkinsci.plugins.workflow.support.steps.input.InputStep");
                    hasPendingInput = run.getAllActions().stream()
                            .anyMatch(a -> a.getClass().getName().contains("InputAction"));
                } catch (ClassNotFoundException ignored) {
                }
            }

            if (!inputs.isEmpty() || hasPendingInput) {
                Map<String, Object> buildEntry = new LinkedHashMap<>();
                buildEntry.put("buildNumber", run.getNumber());
                buildEntry.put("result", run.getResult() != null
                        ? run.getResult().toString()
                        : "IN_PROGRESS");
                buildEntry.put("building", run.isBuilding());
                buildEntry.put("hasPendingInput", hasPendingInput);
                buildEntry.put("inputSteps", inputs);
                history.add(buildEntry);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildsScanned", builds.size());
        result.put("buildsWithInput", history.size());
        result.put("history", history);
        if (history.isEmpty()) {
            result.put("message", "No input steps found in the last " + builds.size() + " builds.");
        }
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 9 — getBuildQueueWaitTime
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Get how long each of the last N builds of a job waited in the queue "
            + "before starting execution. Uses internal QueueTimeoutAction. "
            + "Answers: 'are our builds being delayed by queue congestion?' "
            + "Returns per-build queue wait times and average across N builds.")
    public Map<String, Object> getBuildQueueWaitTime(
            @ToolParam(description = "Full job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to analyze (max 50)") int lastN) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(lastN, 1), 50);
        List<? extends Run<?, ?>> builds = job.getBuilds()
                .subList(0, Math.min(limit, job.getBuilds().size()));

        List<Map<String, Object>> waitTimes = new ArrayList<>();

        for (Run<?, ?> run : builds) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("buildNumber", run.getNumber());
            entry.put("result", run.getResult() != null ? run.getResult().toString() : "?");

            long queueWaitMs = -1;
            // Try internal queue duration action
            for (Action action : run.getAllActions()) {
                String cn = action.getClass().getName();
                if (cn.contains("QueueTimingAction") || cn.contains("TimeInQueueAction")) {
                    try {
                        Object dur = action.getClass().getMethod("getQueuingDurationMillis")
                                .invoke(action);
                        queueWaitMs = ((Number) dur).longValue();
                    } catch (Exception e1) {
                        try {
                            Object dur = action.getClass().getMethod("getDurationMillis")
                                    .invoke(action);
                            queueWaitMs = ((Number) dur).longValue();
                        } catch (Exception ignored) {
                        }
                    }
                    break;
                }
            }

            // Fallback: estimate from build start time vs queue entry
            // Not precise but useful when QueueTimingAction is absent
            if (queueWaitMs < 0) {
                entry.put("queueWaitNote", "QueueTimingAction not available — "
                        + "install 'build-metrics' plugin for precise queue timing");
            } else {
                entry.put("queueWaitMs", queueWaitMs);
                entry.put("queueWaitHuman", humanDuration(queueWaitMs));
            }

            entry.put("buildDurationMs", run.getDuration());
            entry.put("buildDurationHuman", humanDuration(run.getDuration()));
            waitTimes.add(entry);
        }

        // Compute average
        OptionalDouble avgQueueMs = waitTimes.stream()
                .filter(e -> e.containsKey("queueWaitMs"))
                .mapToLong(e -> (long) e.get("queueWaitMs"))
                .average();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildsAnalyzed", builds.size());
        result.put("avgQueueWaitHuman", avgQueueMs.isPresent()
                ? humanDuration((long) avgQueueMs.getAsDouble())
                : "N/A");
        result.put("builds", waitTimes);
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 10 — getStageNodeMapping
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Show which Jenkins agent node each Pipeline stage ran on. "
            + "Uses LabelAction and WorkspaceAction on FlowNodes — internal API only. "
            + "Useful to verify that stages ran on the correct agents (e.g., 'did the "
            + "deploy stage really run on the prod node?'). "
            + "Use buildNumber=-1 for the last build.")
    public Map<String, Object> getStageNodeMapping(
            @ToolParam(description = "Full Pipeline job name") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber) {

        try {
            Class<?> wfRunClass = Class.forName("org.jenkinsci.plugins.workflow.job.WorkflowRun");
            Run<?, ?> run = resolveRun(jobFullName, buildNumber);
            if (!wfRunClass.isInstance(run))
                return Map.of("error", "Not a Pipeline job");

            Object execution = wfRunClass.getMethod("getExecution").invoke(run);
            if (execution == null)
                return Map.of("error", "No execution available");

            Class<?> scannerClass = Class.forName(
                    "org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner");
            Object scanner = scannerClass.getDeclaredConstructor().newInstance();
            Class<?> flowNodeClass = Class.forName("org.jenkinsci.plugins.workflow.graph.FlowNode");

            @SuppressWarnings("unchecked")
            Iterable<Object> allNodes = (Iterable<Object>) scannerClass
                    .getMethod("allNodes", Class.forName(
                            "org.jenkinsci.plugins.workflow.flow.FlowExecution"))
                    .invoke(scanner, execution);

            List<Map<String, Object>> stageNodes = new ArrayList<>();

            for (Object node : allNodes) {
                String displayName = (String) flowNodeClass.getMethod("getDisplayName").invoke(node);
                if (displayName == null)
                    continue;

                // LabelAction holds the node/label this step ran on
                Map<String, Object> entry = null;
                for (Action action : (List<Action>) flowNodeClass.getMethod("getAllActions").invoke(node)) {
                    String cn = action.getClass().getName();
                    if (cn.contains("LabelAction") || cn.contains("ExecutorStepExecution")) {
                        if (entry == null) {
                            entry = new LinkedHashMap<>();
                            entry.put("stepName", displayName);
                            entry.put("nodeId", flowNodeClass.getMethod("getId").invoke(node));
                        }
                        try {
                            Object lbl = action.getClass().getMethod("getDisplayName").invoke(action);
                            entry.put("agentLabel", lbl);
                        } catch (Exception ignored) {
                        }
                        try {
                            Object comp = action.getClass().getMethod("getComputer").invoke(action);
                            if (comp != null) {
                                entry.put("agentName",
                                        comp.getClass().getMethod("getName").invoke(comp));
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
                if (entry != null)
                    stageNodes.add(entry);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("jobName", jobFullName);
            result.put("buildNumber", run.getNumber());
            result.put("stageCount", stageNodes.size());
            result.put("stageNodeMapping", stageNodes);
            if (stageNodes.isEmpty()) {
                result.put("message", "No stage-to-node mapping found. "
                        + "This build may run entirely on the built-in node.");
            }
            return result;

        } catch (ClassNotFoundException e) {
            return Map.of("error", "workflow-api not available");
        } catch (Exception e) {
            return Map.of("error", "Failed to get stage-node mapping: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 11 — getLabelMatchingNodes
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Given a Jenkins label expression (e.g. 'linux && docker && !prod'), "
            + "return all nodes that currently match this expression. "
            + "Uses Label.parseExpression() — internal API. "
            + "Helps verify which agents will pick up a job before triggering it.")
    public Map<String, Object> getLabelMatchingNodes(
            @ToolParam(description = "Label expression, e.g. 'linux', 'linux && docker', 'prod || staging'") String labelExpression) {

        if (labelExpression == null || labelExpression.trim().isEmpty()) {
            throw new IllegalArgumentException("labelExpression cannot be empty");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("labelExpression", labelExpression);

        try {
            Label label = Label.parseExpression(labelExpression);
            if (label == null) {
                result.put("error", "Could not parse label expression: " + labelExpression);
                return result;
            }

            List<Map<String, Object>> matchingNodes = new ArrayList<>();
            for (Computer computer : Jenkins.get().getComputers()) {
                if (computer.getNode() == null)
                    continue;
                Set<hudson.model.labels.LabelAtom> nodeLabels = computer.getNode().getAssignedLabels();
                boolean matches = label.matches((java.util.Collection) nodeLabels);
                if (matches) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("name", computer.getName().isEmpty() ? "built-in" : computer.getName());
                    entry.put("online", computer.isOnline());
                    entry.put("idle", computer.isIdle());
                    entry.put("numExecutors", computer.getNumExecutors());
                    entry.put("busyExecutors", computer.countBusy());
                    entry.put("labels", nodeLabels.stream()
                            .map(l -> l.getName())
                            .collect(Collectors.toList()));
                    matchingNodes.add(entry);
                }
            }

            result.put("matchingNodeCount", matchingNodes.size());
            result.put("availableForBuild", matchingNodes.stream()
                    .filter(n -> (boolean) n.get("online") && !(boolean) n.get("idle") == false)
                    .count());
            result.put("nodes", matchingNodes);
            if (matchingNodes.isEmpty()) {
                result.put("message", "No nodes match '" + labelExpression
                        + "'. Builds requiring this label will stay in queue indefinitely.");
            }

        } catch (Exception e) {
            result.put("error", "Failed to parse or evaluate label expression: " + e.getMessage());
        }

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 12 — getParallelStageTimingMatrix
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "For a Pipeline that uses parallel stages, build a timing matrix "
            + "showing each branch side-by-side: when it started, when it ended, "
            + "its duration, and whether it was the critical path (longest branch). "
            + "Answers: 'which parallel branch is slowing down my pipeline?' "
            + "Use buildNumber=-1 for the last build.")
    public Map<String, Object> getParallelStageTimingMatrix(
            @ToolParam(description = "Full Pipeline job name") String jobFullName,
            @ToolParam(description = "Build number, or -1 for the last build") int buildNumber) {

        // Re-use parallel branch detection + timing
        Map<String, Object> branchData = getPipelineParallelBranches(jobFullName, buildNumber);
        if (branchData.containsKey("error"))
            return branchData;

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> branches = (List<Map<String, Object>>) branchData.getOrDefault("branches", List.of());

        // Identify critical path (branch with highest duration estimate)
        // Since we may not have end times, we use startTime ordering as proxy
        long minStart = Long.MAX_VALUE;
        for (Map<String, Object> b : branches) {
            Object st = b.get("startTimeMs");
            if (st instanceof Long && (long) st < minStart)
                minStart = (long) st;
        }

        final long refStart = minStart;
        long maxOffset = 0;
        String criticalBranch = null;

        for (Map<String, Object> b : branches) {
            Object st = b.get("startTimeMs");
            if (st instanceof Long) {
                long offset = (long) st - refStart;
                b.put("offsetFromFirstBranchMs", offset);
                b.put("offsetFromFirstBranchHuman", humanDuration(offset));
                if (offset > maxOffset) {
                    maxOffset = offset;
                    criticalBranch = (String) b.get("branchName");
                }
            }
        }

        // Mark critical path
        for (Map<String, Object> b : branches) {
            b.put("isCriticalPath", b.get("branchName") != null
                    && b.get("branchName").equals(criticalBranch));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildNumber", branchData.get("buildNumber") != null
                ? branchData.get("buildNumber")
                : "?");
        result.put("branchCount", branches.size());
        result.put("criticalPath", criticalBranch);
        result.put("timingMatrix", branches);
        result.put("note", "Critical path = branch with latest start time (proxy for longest branch). "
                + "Full end-time precision requires Pipeline Graph Analysis plugin.");
        return result;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // TOOL 13 — getPipelineSuspectedBottleneck
    // ─────────────────────────────────────────────────────────────────────────

    @Tool(description = "Analyze the last N builds of a Pipeline and identify which stage "
            + "is the bottleneck (highest average duration). "
            + "Returns a ranked list of stages by average time, min/max, and "
            + "the percentage of total pipeline time each stage consumes. "
            + "Powered by internal TimingAction on FlowNodes.")
    public Map<String, Object> getPipelineSuspectedBottleneck(
            @ToolParam(description = "Full Pipeline job name") String jobFullName,
            @ToolParam(description = "Number of recent builds to analyze (2-20)") int lastN) {

        Job<?, ?> job = resolveJob(jobFullName);
        int limit = Math.min(Math.max(lastN, 2), 20);
        List<? extends Run<?, ?>> builds = job.getBuilds()
                .subList(0, Math.min(limit, job.getBuilds().size()));

        // Map: stageName -> list of durations
        Map<String, List<Long>> stageTimings = new LinkedHashMap<>();

        for (Run<?, ?> run : builds) {
            try {
                Class<?> wfRunClass = Class.forName("org.jenkinsci.plugins.workflow.job.WorkflowRun");
                if (!wfRunClass.isInstance(run))
                    continue;

                Object execution = wfRunClass.getMethod("getExecution").invoke(run);
                if (execution == null)
                    continue;

                Class<?> scannerClass = Class.forName(
                        "org.jenkinsci.plugins.workflow.graphanalysis.DepthFirstScanner");
                Object scanner = scannerClass.getDeclaredConstructor().newInstance();
                Class<?> flowNodeClass = Class.forName("org.jenkinsci.plugins.workflow.graph.FlowNode");
                Class<?> timingClass = Class.forName(
                        "org.jenkinsci.plugins.workflow.actions.TimingAction");

                @SuppressWarnings("unchecked")
                Iterable<Object> allNodes = (Iterable<Object>) scannerClass
                        .getMethod("allNodes", Class.forName(
                                "org.jenkinsci.plugins.workflow.flow.FlowExecution"))
                        .invoke(scanner, execution);

                // Collect stages with timing
                List<Object[]> stagesWithTime = new ArrayList<>(); // [name, startMs]
                for (Object node : allNodes) {
                    String displayName = (String) flowNodeClass.getMethod("getDisplayName").invoke(node);
                    if (displayName == null)
                        continue;

                    // Only stage-start nodes
                    if (!node.getClass().getSimpleName().contains("StepStart")
                            && !node.getClass().getSimpleName().contains("StageStart")
                            && !displayName.toLowerCase().startsWith("stage"))
                        continue;

                    Object timing = flowNodeClass.getMethod("getAction", Class.class)
                            .invoke(node, timingClass);
                    if (timing == null)
                        continue;
                    long startMs = (long) timingClass.getMethod("getStartTime").invoke(timing);
                    stagesWithTime.add(new Object[] { displayName, startMs, node });
                }

                // Compute durations as difference between consecutive stage starts
                for (int i = 0; i < stagesWithTime.size(); i++) {
                    String name = (String) stagesWithTime.get(i)[0];
                    long start = (long) stagesWithTime.get(i)[1];
                    long end = (i + 1 < stagesWithTime.size())
                            ? (long) stagesWithTime.get(i + 1)[1]
                            : run.getStartTimeInMillis() + run.getDuration();
                    long duration = Math.max(0, end - start);
                    stageTimings.computeIfAbsent(name, k -> new ArrayList<>()).add(duration);
                }
            } catch (Exception ignored) {
            }
        }

        if (stageTimings.isEmpty()) {
            return Map.of("jobName", jobFullName, "message",
                    "No stage timing data found. Ensure builds are Pipeline jobs with named stages.");
        }

        // Compute stats per stage
        List<Map<String, Object>> stageStats = new ArrayList<>();
        long totalAvgMs = stageTimings.values().stream()
                .mapToLong(list -> (long) list.stream().mapToLong(Long::longValue).average().orElse(0))
                .sum();

        for (Map.Entry<String, List<Long>> e : stageTimings.entrySet()) {
            LongSummaryStatistics stats = e.getValue().stream()
                    .mapToLong(Long::longValue).summaryStatistics();
            long avgMs = (long) stats.getAverage();
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("stageName", e.getKey());
            sm.put("avgMs", avgMs);
            sm.put("avgHuman", humanDuration(avgMs));
            sm.put("minMs", stats.getMin());
            sm.put("minHuman", humanDuration(stats.getMin()));
            sm.put("maxMs", stats.getMax());
            sm.put("maxHuman", humanDuration(stats.getMax()));
            sm.put("sampleCount", stats.getCount());
            sm.put("pctOfTotal", totalAvgMs == 0 ? "0%"
                    : Math.round(avgMs * 100.0 / totalAvgMs) + "%");
            stageStats.add(sm);
        }
        stageStats.sort((a, b) -> Long.compare((long) b.get("avgMs"), (long) a.get("avgMs")));

        String bottleneck = stageStats.isEmpty() ? null
                : (String) stageStats.get(0).get("stageName");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jobName", jobFullName);
        result.put("buildsAnalyzed", builds.size());
        result.put("bottleneckStage", bottleneck);
        result.put("stages", stageStats);
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
        Run<?, ?> run = buildNumber == -1 ? job.getLastBuild()
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