# MCP Server Plugin — Release Notes (V1.2)

Release date: 2026-05-12

Summary:
- Compatibility fixes and robustness improvements for Jenkins API variations.

Key changes:
- Safely handle SCM change sets: added `getChangeSetsSafe(Run<?,?>)` and `countChangeItems(Run<?,?>)` to avoid calling `run.getChangeSets()` on Jenkins versions where it's unavailable.
- Advanced internals compatibility: avoid compile-time dependency on optional types (e.g., `BuildDiscarder`), use runtime checks/reflection where needed (executor remaining time handling).
- Pipeline label matching: fix `LabelAtom` vs `Label` usage to avoid class/method signature mismatches.
- Packaging: built HPI and preserved previous HPI as `target/mcp-serverV1.1.hpi`; new HPI at `target/mcp-serverV1.2.hpi`.

Notes for maintainers:
- Commit/tag created: `V1.2` (annotated).
- Artifacts: see `mcp-server-plugin-main/mcp-server-plugin-main/target/mcp-serverV1.2.hpi`.
- Suggested next steps: create a GitHub release using this tag and paste these notes into the release body.

Acknowledgements:
- Automated fixes applied to make the plugin compile across multiple Jenkins core API versions.
