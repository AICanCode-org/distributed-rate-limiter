# CMS export: `cms/distributed-rate-limiter.json`

Machine-readable version of the course for the AiCanCode site. It is **generated** by `scripts/build-cms.py`
from two sources: `cms/project.json` (the `project` metadata: title, tracks,
outcomes, repo, …) and `docs/phase-*.md` (everything per phase). Edit those, never the generated JSON:

```bash
python3 scripts/build-cms.py          # regenerate (standard library only, Python 3.9+)
python3 scripts/build-cms.py --check  # exit 1 if the JSON is stale (use in CI or a pre-commit hook)
```

The script also **validates** the docs and exits with code 2 and a clear message when a page breaks the
format: a missing or misordered section, a prompt without a ```` ```text ```` block or *Verify the output by*,
a quiz question without exactly one correct option, fewer than 5 questions, or an unknown track marker.

## Top level
| Field | Type | Notes |
|---|---|---|
| `schema_version` | string | `"2.0"`, the "schema v2" proposed for the site (phases, prompts, quizzes) |
| `generator` | string | `scripts/build-cms.py` |
| `project` | object | project metadata, see below |
| `phases` | array | one object per `docs/phase-N-*.md`, ordered 0..8 |
| `stats` | object | counts of `phases`, `steps`, `prompts`, `quiz_questions`, `track_snippets` (sanity check after import) |
| `legacy_import` | object | the same content in today's `ProjectCreate` shape (`title, description, difficulty, slug, tech_stack` CSV, `is_published: false`, `steps[{order, title, content}]`, one step per phase). Usable with `PUT /api/v1/admin/projects/import` before schema v2 exists. |

## `project`
| Field | Type | Notes |
|---|---|---|
| `slug`, `title`, `tagline`, `description` | string | |
| `difficulty` | string | `beginner` \| `intermediate` \| `advanced` |
| `estimated_hours` | number | sum of the phases' `estimated_minutes` / 60 |
| `tech_stack` | string[] | shared technologies; per-track stacks are in `tracks` |
| `tracks` | object[] | `{id, label, tech_stack[], folder}`; `id` ∈ `java`, `python`, `typescript` |
| `prerequisites` | object[] | `{title, url}` (`url` may be `null`) |
| `outcomes` | string[] | what the learner can do afterwards |
| `ai_feature` | string | |
| `requires_paid_services` | boolean | always `false` for RateGuard |
| `access` | object | `{default: "pro", plans: ["pro","elite","campus"], free_phases: [0]}` |
| `repo` | object | `{url, starter_ref: "main", solution_ref: "solution", phase_tags[], releases[]}` |
| `live_demo_url` | string \| null | |
| `portfolio_blurb` | string | suggested text for the learner's CV / LinkedIn |

## `phases[]`
| Field | Type | Source in the doc |
|---|---|---|
| `number`, `slug`, `title` | int, string, string | front matter `phase`, `slug`, `title` |
| `heading` | string | the `# Phase N · …` line |
| `estimated_minutes` | int | front matter `duration` (`2.5 h` → 150) |
| `access` | string | `free` for `free_phases`, otherwise `pro` |
| `git` | object | `{start_ref, end_tag, release}`: front matter `start_tag`/`start_ref`, `end_tag`, `release` (`null` if absent). Drives the "catch up from tag" box. |
| `source_path` | string | e.g. `docs/phase-3-implementation.md` |
| `why_md` | markdown | §1 *Why it matters* |
| `objectives` | string[] | §2 bullet list |
| `steps_intro` | step-like object \| null | text in §3 before the first `### Step` |
| `steps` | object[] | §3, one per `### Step N.x · Title` (see below) |
| `ai_prompts` | object[] | §4 (see below) |
| `deliverables` | `{text}`[] | §5 checkbox list |
| `quiz` | object[] | §6 (see below) |
| `recap` | string[] | §7 *What you learned* |
| `catch_up` | object | §8: `{md, commands}`; `commands` = the first ```` ```bash ```` block |

### Step
```json
{
  "order": 2,
  "number": "0.2",
  "title": "Install Docker and the tools for ONE track",
  "content_md": "… full markdown, including all three track blocks with their HTML-comment markers …",
  "track_snippets": [{"track": "java", "content_md": "…"}, {"track": "python", "content_md": "…"}],
  "content_md_by_track": {"java": "…", "python": "…", "typescript": "…"}
}
```
`track_snippets` and `content_md_by_track` exist only when the step contains track-specific blocks.
`content_md_by_track.<t>` is the step with only track `t`'s blocks kept (markers removed). Render it for a
learner who picked track `t`; fall back to `content_md` otherwise. In the docs, track blocks are written as
`<!-- track:java -->` … `<!-- /track -->`, which GitHub hides, so the markdown also reads well on GitHub.

### Prompt
```json
{"order": 1, "title": "Fix my toolchain install", "tool": "any",
 "prompt": "I'm on <Windows 11 + WSL2 …> …", "verify_by_md": "running each verification command …"}
```
`prompt` is the verbatim ```` ```text ```` block (show it with a *Copy* button). Placeholders in `<angle | brackets>`
are for the learner to fill in.

### Quiz question
```json
{"order": 1, "type": "mcq", "q": "Why must you untick …?",
 "options": ["…", "…", "…", "…"], "answer": 1, "explanation": "Forks copy only main by default …"}
```
`answer` is the **0-based index** into `options`. `explanation` is shown after answering. Suggested
completion rule: ≥ 70 % correct to mark the phase done.

## Importing
1. Schema v2 backend: map `project` + `phases` 1:1. Server-side, omit `steps`, `ai_prompts` and `quiz` of
   phases whose `access` is not `free` for users without a Pro/Elite/Campus plan.
2. Current backend: send `legacy_import` to the admin import endpoint, then publish it when it has been reviewed.
