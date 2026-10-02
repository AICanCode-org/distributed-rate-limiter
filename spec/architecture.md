# RateGuard architecture

> **Phase 2 deliverable.** Replace the TODOs. Diagrams use [Mermaid](https://mermaid.js.org/), which
> GitHub renders natively. Reference: `git show phase-2-end:spec/architecture.md`.

## 1. Context diagram
TODO: who/what talks to RateGuard (tenant backends, the admin, the optional LLM)?

## 2. Container diagram
TODO: several app instances, one Redis, the optional LLM. Which calls are on the request path,
and which can fail safely?

## 3. Check sequence
TODO: sequence diagram for `POST /api/v1/limits/check`: API key lookup, tenant lookup, one Lua
script call, headers. Then two instances racing for the last token.

## 4. Algorithms
TODO: token bucket vs. sliding window counter: state kept, maths, burst behaviour, error bound.

## 5. Redis key layout
TODO: every key, its type, its TTL, and why the `{tenantId}` hash tag is there.

## 6. Failure modes
TODO: Redis slow, Redis down, Redis restarted (state lost), the LLM down. What does a client see?

## 7. Key decisions
TODO: link your ADRs (`adr/0001-...`).
