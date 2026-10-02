// M5: the AI policy advisor: statistics by code, advice by rules (offline) or a validated LLM answer.
import { expect, it } from "vitest";
import {
  AdvisorService,
  LlmAdvisor,
  LlmClient,
  parseAdvice,
  RuleAdvisor,
  statsJson,
  summarize,
  SYSTEM_PROMPT,
  type Advice,
  type Chat,
} from "../../src/advisor.js";
import type { Policy, Tenant, UsagePoint } from "../../src/models.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("advisor");
const M0 = 1_790_933_400_000 / 60_000; // 2026-10-02T09:30Z as an epoch minute
const TB10: Policy = { algorithm: "TOKEN_BUCKET", limit: 10, windowSeconds: 60, failMode: "CLOSED" };
const NOW = new Date("2026-10-02T09:30:00.000Z");

const pts = (...counts: [number, number][]): UsagePoint[] =>
  counts.map(([allowed, denied], i) => ({ minute: M0 + i, allowed, denied }));
const times = (n: number, c: [number, number]): [number, number][] => Array.from({ length: n }, () => c);
const tenant = (policy: Policy = TB10): Tenant => ({
  id: "acme",
  name: "Acme Corp",
  policy,
  keyHash: "h",
  keyPrefix: "rg_000000",
  createdAt: NOW,
  updatedAt: NOW,
});
const advise = (policy: Policy, points: UsagePoint[]): Advice =>
  new RuleAdvisor().advise(policy, summarize(policy, points));

class FakeChat implements Chat {
  calls: [string, string, number][] = [];
  constructor(
    private readonly answer: string | null = null,
    private readonly error: Error | null = null,
  ) {}
  async chat(system: string, user: string, maxTokens: number): Promise<string> {
    this.calls.push([system, user, maxTokens]);
    if (this.error) throw this.error;
    return this.answer!;
  }
}

describe("advisor", () => {
  it("computes integer statistics", () => {
    const s = summarize(TB10, pts([0, 0], [3, 0], [1, 1], [6, 4], [0, 0]));
    expect([s.minutes, s.allowed, s.denied, s.allowed + s.denied, s.deniedPercent]).toEqual([
      5, 10, 5, 15, 33,
    ]);
    expect([s.peak, s.peakMinute, s.peakDenied]).toEqual([10, M0 + 3, 4]);
    expect([s.median, s.p90, s.activeMinutes, s.capacityPerMinute]).toEqual([3, 10, 3, 10]);
  });

  it("summarizes no traffic", () => {
    const s = summarize(
      { algorithm: "SLIDING_WINDOW", limit: 1, windowSeconds: 3600, failMode: "CLOSED" },
      pts([0, 0], [0, 0]),
    );
    expect([s.allowed + s.denied, s.peak, s.peakMinute, s.median, s.p90, s.deniedPercent]).toEqual([
      0,
      0,
      null,
      0,
      0,
      0,
    ]);
    expect(s.capacityPerMinute).toBe(0); // 1 per hour rounds down to 0 per minute
  });

  it("returns the statistics in the API shape", () => {
    expect(statsJson(summarize(TB10, pts([0, 0], [6, 4])))).toEqual({
      minutes: 2,
      allowed: 6,
      denied: 4,
      deniedPercent: 40,
      peakPerMinute: 10,
      peakMinute: "2026-10-02T09:31:00.000Z",
      peakMinuteDenied: 4,
      medianPerMinute: 10,
      p90PerMinute: 10,
      activeMinutes: 1,
      capacityPerMinute: 10,
    });
  });

  it("keeps the policy without traffic", () => {
    expect(advise(TB10, pts(...times(5, [0, 0])))).toEqual({
      action: "KEEP",
      limit: null,
      summary: "No requests in the last 5 minutes: keep the current policy.",
    });
  });

  it("treats a spike as an anomaly to investigate", () => {
    expect(advise(TB10, pts(...times(7, [2, 0]), [10, 30], [2, 0], [2, 0]))).toEqual({
      action: "INVESTIGATE",
      limit: null,
      summary:
        "Spike at 09:37 UTC: 40 requests in one minute, 20x the usual 2. 30 of the 30 denials happened in that " +
        "minute, which looks like a retry storm or abuse, not growth. Keep the limit and check the client.",
    });
  });

  it("raises the limit with headroom for steady demand above it", () => {
    expect(advise(TB10, pts(...times(10, [10, 2])))).toEqual({
      action: "RAISE_LIMIT",
      limit: 15,
      summary:
        "16% of requests were denied (20 of 120). Steady demand is about 12 per minute (90th percentile) against " +
        "a capacity of 10 per minute. Raise the limit to 15 per 60 s (20% headroom).",
    });
  });

  it("scales a raise to the window and caps it at ten times", () => {
    expect(
      advise(
        { algorithm: "TOKEN_BUCKET", limit: 100, windowSeconds: 3600, failMode: "CLOSED" },
        pts(...times(10, [1, 2])),
      ).limit,
    ).toBe(216);
    expect(
      advise(
        { algorithm: "TOKEN_BUCKET", limit: 1, windowSeconds: 60, failMode: "CLOSED" },
        pts(...times(10, [1, 49])),
      ).limit,
    ).toBe(10);
  });

  it("skips a raise at the maximum limit", () => {
    expect(
      advise(
        { algorithm: "TOKEN_BUCKET", limit: 100_000, windowSeconds: 60, failMode: "CLOSED" },
        pts(...times(3, [100_000, 30_000])),
      ).action,
    ).toBe("KEEP");
  });

  it("lowers a limit far above any use", () => {
    expect(
      advise(
        { algorithm: "SLIDING_WINDOW", limit: 1000, windowSeconds: 60, failMode: "CLOSED" },
        pts(...times(12, [5, 0])),
      ),
    ).toEqual({
      action: "LOWER_LIMIT",
      limit: 10,
      summary:
        "Peak demand was 5 per minute, under a quarter of the capacity of 1000 per minute. 10 per 60 s (twice the " +
        "peak) still fits and limits the damage if the key leaks.",
    });
  });

  it("needs enough history to lower", () => {
    expect(
      advise(
        { algorithm: "SLIDING_WINDOW", limit: 1000, windowSeconds: 60, failMode: "CLOSED" },
        pts(...times(9, [5, 0])),
      ).action,
    ).toBe("KEEP");
  });

  it("keeps the policy otherwise", () => {
    expect(advise(TB10, pts(...times(12, [6, 0])))).toEqual({
      action: "KEEP",
      limit: null,
      summary:
        "0% denied and a peak of 6 per minute against a capacity of 10 per minute: the current policy fits.",
    });
  });

  it("accepts a valid answer and code fences", () => {
    expect(parseAdvice('{"action":"RAISE_LIMIT","limit":20,"summary":"Steady  demand."}', TB10)).toEqual({
      action: "RAISE_LIMIT",
      limit: 20,
      summary: "Steady demand.",
    });
    const fenced = '```json\n{"action": "INVESTIGATE", "limit": null, "summary": "Spike."}\n```';
    expect(parseAdvice(fenced, TB10)).toEqual({ action: "INVESTIGATE", limit: null, summary: "Spike." });
    expect(parseAdvice('{"action": "KEEP", "summary": "Fine."}', TB10).limit).toBeNull();
  });

  it.each([
    "not json",
    "[1, 2]",
    '{"action": "DELETE_TENANT", "summary": "x"}',
    '{"action": "RAISE_LIMIT", "limit": true, "summary": "x"}',
    '{"action": "RAISE_LIMIT", "limit": 10, "summary": "x"}',
    '{"action": "RAISE_LIMIT", "limit": 101, "summary": "x"}',
    '{"action": "LOWER_LIMIT", "limit": 10, "summary": "x"}',
    '{"action": "LOWER_LIMIT", "limit": 0, "summary": "x"}',
    '{"action": "KEEP", "limit": 12, "summary": "x"}',
    '{"action": "KEEP", "summary": "   "}',
    '{"action": "KEEP", "summary": 5}',
    '{"action": "KEEP", "summary": "' + "x".repeat(401) + '"}',
    '{"action": "RAISE_LIMIT", "limit": 15.5, "summary": "x"}',
  ])("rejects the unexpected answer %s", (answer) => {
    expect(() => parseAdvice(answer, TB10)).toThrow();
  });

  it("sends only numbers inside stats tags to the LLM", async () => {
    const chat = new FakeChat('{"action": "KEEP", "limit": null, "summary": "Looks fine."}');
    const s = summarize(TB10, pts([3, 0], [4, 0]));
    expect(await new LlmAdvisor(chat).advise(tenant(), s)).toEqual({
      action: "KEEP",
      limit: null,
      summary: "Looks fine.",
    });
    const [system, user, maxTokens] = chat.calls[0]!;
    expect(system).toBe(SYSTEM_PROMPT);
    expect(maxTokens).toBe(300);
    expect(user.startsWith("<stats>") && user.endsWith("</stats>")).toBe(true);
    expect(JSON.parse(user.slice("<stats>".length, -"</stats>".length))).toEqual({
      policy: { algorithm: "TOKEN_BUCKET", limit: 10, windowSeconds: 60 },
      stats: statsJson(s),
    });
    expect(user).not.toContain("Acme");
    expect(user).not.toContain("rg_");
  });

  it("uses the LLM when it answers well", async () => {
    const svc = new AdvisorService(
      new RuleAdvisor(),
      new LlmAdvisor(new FakeChat('{"action": "INVESTIGATE", "summary": "Odd spike."}')),
    );
    const r = await svc.advise(tenant(), summarize(TB10, pts([1, 0])));
    expect([r.provider, r.fallback, r.advice.action]).toEqual(["llm", false, "INVESTIGATE"]);
  });

  it.each([
    ["the LLM is down", new FakeChat(null, new Error("connect timeout"))],
    ["the LLM answers prose", new FakeChat("I think you should relax.")],
  ])("falls back to the rules when %s", async (_name, chat) => {
    const warnings: string[] = [];
    const svc = new AdvisorService(new RuleAdvisor(), new LlmAdvisor(chat), (m) => warnings.push(m));
    const r = await svc.advise(tenant(), summarize(TB10, pts([0, 0])));
    expect([r.provider, r.fallback, r.advice.action]).toEqual(["rules", true, "KEEP"]);
    expect(warnings).toHaveLength(1);
  });

  it("is offline by default", async () => {
    const r = await new AdvisorService(new RuleAdvisor()).advise(tenant(), summarize(TB10, pts([0, 0])));
    expect([r.provider, r.fallback]).toEqual(["rules", false]);
  });

  it("speaks OpenAI chat completions", async () => {
    let seen: { url: string; auth: string | null; body: Record<string, unknown> } | null = null;
    const fake = (async (url: string, init: RequestInit) => {
      seen = {
        url,
        auth: new Headers(init.headers).get("authorization"),
        body: JSON.parse(init.body as string),
      };
      return new Response(JSON.stringify({ choices: [{ message: { content: "hi" } }] }), { status: 200 });
    }) as unknown as typeof fetch;
    const client = new LlmClient("http://llm.test/v1", "tiny", "secret-key", 1000, fake);
    expect(await client.chat("sys", "user", 50)).toBe("hi");
    expect(seen!.url).toBe("http://llm.test/v1/chat/completions");
    expect(seen!.auth).toBe("Bearer secret-key");
    expect([seen!.body.model, seen!.body.temperature, seen!.body.max_tokens]).toEqual(["tiny", 0, 50]);
    expect((seen!.body.messages as { role: string }[]).map((m) => m.role)).toEqual(["system", "user"]);
  });

  it("throws on HTTP errors and empty answers", async () => {
    for (const make of [
      () => new Response("boom", { status: 500 }),
      () => new Response(JSON.stringify({ choices: [{ message: { content: " " } }] }), { status: 200 }),
    ]) {
      const client = new LlmClient("http://llm.test/v1", "tiny", null, 1000, (async () =>
        make()) as unknown as typeof fetch);
      await expect(client.chat("sys", "user", 10)).rejects.toThrow();
    }
  });
});
