# RateGuard requirements

> **Phase 1 deliverable.** Replace every `TODO` below. Keep it short: a reviewer should be able to
> read this in ten minutes. The reference version is on the `solution` branch at tag `phase-1-end`
> (`git show phase-1-end:spec/requirements.md`). Try on your own first!

## 1. Problem statement
TODO: who has the problem (a small API company whose customers share one backend), what it is
(one noisy tenant slows everyone down; per-instance limits leak when you scale out), and why a
limit inside each app instance or a proxy-only limit doesn't fit (2-3 sentences).

## 2. Users / personas and roles
TODO: at least three personas mapped to the two credentials (the admin bearer token and a tenant
API key), plus a permission matrix (who may call what, and what a refused request returns: 401 or 404).

## 3. Functional requirements (FR)
TODO: numbered, testable statements ("The system shall ..."). Cover tenants and policies, API keys
(issue, rotate, revoke), the two algorithms, the check endpoint and the demo endpoint, the
rate-limit headers, reset, usage statistics, the AI policy advisor and health.

## 4. Non-functional requirements (NFR)
TODO: correctness across instances (no over-admission), latency added per request, behaviour when
Redis is slow or down, security of keys and the admin token, memory per tenant, cost, portability.
Make each one measurable.

## 5. Threats (what could go wrong?)
TODO: at least six abuse cases (e.g. a stolen API key, brute-forcing the admin token, two instances
both admitting the "last" request, a huge `cost` or a negative one, a tenant id that hides a Redis
key pattern, prompt injection through usage data sent to the LLM) and the requirement that answers each.

## 6. User stories + acceptance criteria
TODO: at least 8 stories in the form *As a ... I want ... so that ...*, each with Given/When/Then
criteria. Create one GitHub Issue per story using the "User story" issue template.

## 7. Out of scope (v1)
TODO

## 8. Back-of-the-envelope estimates
TODO: tenants, checks/s at peak, Redis commands per check, memory per tenant and resource, latency
budget per check, what one Redis instance can handle.
