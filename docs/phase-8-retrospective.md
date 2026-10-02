---
phase: 8
slug: phase-8-retrospective
title: Retrospective and portfolio
duration: 1.5 h
start_tag: phase-7-end
end_tag: phase-8-end
---
# Phase 8 · Retrospective and portfolio

## 1. Why it matters
A recruiter spends about a minute on your repository. "Another rate limiter tutorial" is forgettable;
a README that tells the story (the problem, the atomicity and failure design, the trade-offs, the
evidence, what you would do next) is proof that you can design, test, ship and operate software, and
that you use AI responsibly. "Design a rate limiter" is also one of the most common system-design
interview questions: you can now answer it with a system you built, measured and broke on purpose. The
retrospective is for you: it turns nine phases of experience into lessons you can explain in an interview.

## 2. Objectives
- Rewrite the README top section as a **case study** with real numbers from your project.
- Write a short **retrospective** (what went well, what didn't, what you'd change).
- Summarise your **AI_LOG** into three honest bullet points.
- Prepare a 2-minute walkthrough and answers to the distributed-systems questions interviewers ask about rate limiters.

## 3. Step-by-step instructions

### Step 8.1 · Collect your evidence
```bash
git log --oneline | wc -l                       # commits
git tag -l 'v*'                                  # releases
make coverage                                    # coverage % per track
scripts/smoke-test.sh | tail -1                  # e.g. "Result: 60 passed, 0 failed"
python3 scripts/load-test.py | tail -3           # allowed = limit, p95 latency
curl -s localhost:8080/metrics | grep _total     # counters after a demo session
```
Also grab: a screenshot of the CI run graph, the GHCR package page, your game-day numbers for v1.0
and v1.1, and (optionally) your live URL.

### Step 8.2 · README case study
At the top of `README.md`, before the setup instructions, add a section with:
1. **One-line pitch** and a short `curl` session (create a tenant → 5 allowed, the 6th `429` with
   `Retry-After` → the headers → a degraded decision while Redis is paused).
2. **Problem → solution** in three sentences.
3. **Architecture** (link the Mermaid diagrams) and the **three decisions** you are proudest of, linking
   ADRs (for example atomic Lua scripts with pure-function twins, Redis `TIME` as the clock, hash tags,
   per-tenant fail modes).
4. **Correctness and security evidence:** which threats from Phase 1 are covered by which tests
   (over-admission across instances, clock drift, negative costs, key-pattern injection, stolen and
   rotated keys, timing attacks on the admin token).
5. **Quality evidence:** tests, coverage, CI badge, smoke test, load test.
6. **Operations:** health, metrics, request IDs, the game day and its post-mortem.
7. **How I used AI**, with honest examples of accepted and rejected suggestions.
8. **What I'd do next** (Redis Sentinel or Cluster, OpenTelemetry traces, per-key quotas per month,
   a client library that honours `Retry-After`, an admin UI…).

### Step 8.3 · Retrospective (`docs/retrospective.md`)
Use *Start / Stop / Continue* or *What went well / What didn't / What I'd change*. Include one
technical lesson (e.g. a timeout is a decision, not a safety net: a slow dependency costs more than a
dead one), one process lesson (test-first with a pending list, parity tests between two
implementations) and one AI lesson (an assistant confidently produced a non-atomic limiter or a
blocking `KEYS` call, and a test or the docs caught it).

### Step 8.4 · Practise the walkthrough
Record yourself (2 minutes): problem, architecture diagram, one interesting piece of code (the token
bucket Lua script or the circuit breaker are good ones), the test that proves it, the CI run, what you'd
do next. Then answer out loud:
- *Token bucket or sliding window, and why?* · *How do three instances share one limit?* · *Why Lua instead of `MULTI`?*
- *Whose clock do you trust?* · *What happens when Redis is slow, down or restarted?* · *How would you move to Redis Cluster?*
- *How do you store API keys?* · *How would you limit 10 million users instead of 200 tenants?* · *Where did AI help, and where did it mislead you?*

### Step 8.5 · Share it
Pin the repository on your GitHub profile, add the topics `rate-limiter`, `redis`, `lua`,
`<your-language>`, `docker`, `github-actions`, and post the case study (aicancode.org *Build in public*, LinkedIn).

## 4. AI-assist prompts

### Prompt 1: Draft the case study from evidence
```text
Write a README "case study" section (max 400 words) for my distributed rate limiter project from these
facts ONLY: <paste your evidence: test counts, coverage, load-test output, ADR titles, threats and the
tests that cover them, metrics, game-day numbers for v1.0 and v1.1, AI_LOG highlights>. Use short
headings. Do not invent numbers, features or users. Mark anything you are unsure about with [CHECK].
```
**Verify the output by:** checking every number and claim against your evidence list and deleting
anything you cannot show in the repository. If a claim has no link to a file, test or run, cut it.
Watch for over-claims ("handles millions of requests", "never fails"): replace them with what you
measured and what you designed for.

### Prompt 2: Mock system-design interview
```text
Act as a backend interviewer. Ask me 8 questions about this rate limiter, one at a time, from easy to
hard, based on this README, the threat list and the ADRs: <paste>. Include follow-ups about scale,
Redis failure, clock drift and Redis Cluster. After each answer, tell me what a strong answer would add.
Start now with question 1.
```
**Verify the output by:** checking its "strong answers" against your ADRs, the Redis documentation
(EVAL and scripting, `TIME`, Cluster key hash tags, `SCAN`), the IETF draft on RateLimit header fields and
RFC 9457. An interviewer persona can still be wrong about details.

## 5. Deliverables
- [ ] README case study with real evidence and links.
- [ ] `docs/retrospective.md`.
- [ ] AI usage summary (three bullets) in the README, backed by `AI_LOG.md`.
- [ ] A recorded 2-minute walkthrough (optional, but recommended).

## 6. Self-check quiz

### Q1. Which README claim is strongest?
- [ ] "Enterprise-grade, infinitely scalable rate limiting."
- [ ] "Uses Redis best practices."
- [x] "400 parallel checks over two instances against a limit of 100: exactly 100 allowed, p95 under 10 ms, proven by scripts/load-test.py and an integration test (link)."
- [ ] "Built with AI."
> **Answer:** Specific, verifiable, linked. Reviewers trust evidence, not adjectives.

### Q2. What belongs in "What I'd do next"?
- [x] Concrete, prioritised improvements with a reason (e.g. Redis Sentinel, because the game day showed Redis is the single point of failure and fail modes only soften it)
- [ ] Everything that is missing
- [ ] Apologies for unfinished work
- [ ] Nothing, it signals weakness
> **Answer:** Knowing the next steps and why shows judgement, and gives the interviewer something to discuss.

### Q3. How should you describe AI use in your portfolio?
- [ ] Hide it
- [ ] Claim the AI wrote everything
- [x] Honestly, with examples of what you verified, changed and rejected
- [ ] Only mention it if asked
> **Answer:** Responsible AI use is a skill employers look for. Your AI_LOG is the evidence.

### Q4. An interviewer asks "What happens when Redis hangs?" Which answer is best?
- [ ] "Redis is in memory, so it does not hang."
- [ ] "Requests fail until it comes back."
- [x] "After three timeouts a circuit breaker stops calling Redis for 5 s, and each tenant's failMode decides: 503, allow, or a local per-instance limit; health stays 200 DEGRADED. A game day measured v1.0 answering every check after a 1 s timeout and v1.1 answering in a few milliseconds."
- [ ] "I would use a bigger Redis."
> **Answer:** Describe the designed behaviour, how you proved it (tests + game day), and what you learned
> (correct but slow is still an incident, see the reference post-mortem).

### Q5. Which retrospective item is most useful?
- [ ] "Distributed systems are hard."
- [x] "Our failure tests used a stopped Redis; next time we also test a frozen one and assert a latency budget for the 503."
- [ ] "Everything went well."
- [ ] "The AI made mistakes."
> **Answer:** A good retrospective item names a specific cause and a concrete, testable change.

## 7. What you learned
- Turning a project into a case study backed by evidence, including correctness and security evidence.
- Running a retrospective and extracting transferable lessons.
- Communicating trade-offs and AI usage clearly and honestly.
- The full lifecycle: requirements and threats → design → implementation → tests → CI/CD → operations → reflection.

## 8. Catch-up git commands
```bash
git fetch upstream --tags
git show phase-8-end:README.md | less           # reference case study
git show phase-8-end:docs/retrospective.md
git diff phase-7-end phase-8-end --stat         # docs only
```
