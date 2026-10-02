# RateGuard documentation

Start with [Phase 0](phase-0-setup.md). Each phase lists its start and end tag in the header, so you can
start any phase from the reference state (`git switch -c my-phase-N phase-(N-1)-end`).

| # | Phase | Duration | Start | End |
|---|---|---|---|---|
| 0 | [Setup and orientation](phase-0-setup.md) | 1.5 h | `main` | `phase-0-end` |
| 1 | [Requirements, threats and user stories](phase-1-requirements.md) | 2.5 h | `phase-0-end` | `phase-1-end` |
| 2 | [Design: algorithms, Lua scripts, key layout, failure policy, API contract](phase-2-design.md) | 3.5 h | `phase-1-end` | `phase-2-end` |
| 3 | [Implementation in five milestones](phase-3-implementation.md) | 14 h | `phase-2-end` | `phase-3-end` |
| 4 | [Testing: Lua parity, concurrency across instances, security, coverage gate](phase-4-testing.md) | 4 h | `phase-3-end` | `phase-4-end` |
| 5 | [CI with GitHub Actions](phase-5-ci.md) | 2.5 h | `phase-4-end` | `phase-5-end` |
| 6 | [Containerise, deliver, optional deploy](phase-6-deploy.md) | 3 h | `phase-5-end` | `phase-6-end` / `v1.0.0` |
| 7 | [Observability, game day and resilience](phase-7-observability.md) | 3 h | `phase-6-end` | `phase-7-end` / `v1.1.0` |
| 8 | [Retrospective and portfolio](phase-8-retrospective.md) | 1.5 h | `phase-7-end` | `phase-8-end` |

Reference material:
- [GitHub Actions explained](github-actions-explained.md): triggers, jobs, matrix, caching, reusable
  workflows, service containers, permissions, secrets vs variables, environments, GHCR.
- [`../spec/`](../spec/README.md): requirements, architecture, ADRs, Lua scripts, OpenAPI contract.
- Solution branch only: `runbook.md` (Phase 6), `postmortem-*.md` (Phase 7), `retrospective.md` (Phase 8).

## Page format
Every phase page has front matter (`phase`, `slug`, `title`, `duration`, `start_*`, `end_tag`,
`release`) and the same eight sections, which `scripts/build-cms.py` turns into `cms/distributed-rate-limiter.json`
(project metadata comes from `cms/project.json`):

1. Why it matters · 2. Objectives · 3. Step-by-step instructions (`### Step N.x`) ·
4. AI-assist prompts (`### Prompt n`, a `text` block, **Verify the output by**) · 5. Deliverables ·
6. Self-check quiz (`### Qn`, `- [ ]` / `- [x]`, `> **Answer:**`) · 7. What you learned ·
8. Catch-up git commands

Track-specific parts are wrapped in `<!-- track:java -->` … `<!-- /track -->` (also `python`,
`typescript`). The site shows only the learner's track. On GitHub you see all three.
