# RateGuard shared specification

Everything in this folder is **language-agnostic**. The Java, Python and TypeScript tracks all
implement exactly this, and the same black-box smoke test (`scripts/smoke-test.sh`, Phase 6) runs
against each.

| File | What it is | Written in |
|---|---|---|
| `requirements.md` | Functional + non-functional requirements, threats, user stories, acceptance criteria | Phase 1 |
| `architecture.md` | Mermaid diagrams (context, containers, check sequence, Redis key layout, failure modes) | Phase 2 |
| `adr/` | Architecture Decision Records (one decision per file) | Phase 2+ |
| `openapi.yaml` | The HTTP contract (OpenAPI 3.1). **Provided** on `main` so all tracks agree | given |
| `lua/` | The two Redis Lua scripts (token bucket, sliding window) shared by every track | Phase 2 |

The Lua scripts are shared on purpose: the limiter decision is made **inside Redis**, atomically,
so a Java, a Python and a TypeScript instance behind the same load balancer enforce one limit.

Validate the contract locally with any OpenAPI linter, e.g.
`pipx run openapi-spec-validator spec/openapi.yaml` (CI does the same from Phase 5).
