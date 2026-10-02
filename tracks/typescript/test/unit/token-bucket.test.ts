// M2: token bucket as a pure function. Time is just a number here: no sleeping, no flakiness.
import { expect, it } from "vitest";
import { tokenBucket, type BucketState } from "../../src/algorithms.js";
import type { Decision } from "../../src/models.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("token-bucket");
const T0 = 1_790_933_400_000; // 2026-10-02T09:30:00Z in ms
const d = (allowed: boolean, remaining: number, retryAfterMs: number, resetAfterMs: number): Decision => ({
  allowed,
  remaining,
  retryAfterMs,
  resetAfterMs,
});

function run(limit: number, windowMs: number, times: number[], cost = 1): [BucketState | null, Decision[]] {
  let state: BucketState | null = null;
  const out: Decision[] = [];
  for (const t of times) {
    const [next, decision] = tokenBucket(state, limit, windowMs, cost, t);
    state = next;
    out.push(decision);
  }
  return [state, out];
}

describe("token bucket", () => {
  it("starts full", () => {
    const [state, decision] = tokenBucket(null, 5, 60_000, 1, T0);
    expect(decision).toEqual(d(true, 4, 0, 12_000));
    expect(state).toEqual({ tokens: 4, ts: T0 });
  });

  it("bursts up to the capacity, then denies", () => {
    const [, out] = run(5, 60_000, Array(6).fill(T0));
    expect(out.map((x) => x.allowed)).toEqual([true, true, true, true, true, false]);
    expect(out.map((x) => x.remaining)).toEqual([4, 3, 2, 1, 0, 0]);
  });

  it("says when one token is back", () => {
    const [, out] = run(5, 60_000, Array(6).fill(T0));
    expect(out.at(-1)).toEqual(d(false, 0, 12_000, 60_000)); // 60 s / 5 tokens = one token every 12 s
  });

  it("refills continuously", () => {
    let [state] = run(5, 60_000, Array(5).fill(T0));
    let decision: Decision;
    [state, decision] = tokenBucket(state, 5, 60_000, 1, T0 + 11_999);
    expect(decision.allowed).toBe(false);
    expect(decision.retryAfterMs).toBe(1);
    [, decision] = tokenBucket(state, 5, 60_000, 1, T0 + 12_000);
    expect(decision.allowed).toBe(true);
    expect(decision.remaining).toBe(0);
  });

  it("never refills above the capacity", () => {
    const [state] = run(5, 60_000, [T0]);
    const [next, decision] = tokenBucket(state, 5, 60_000, 1, T0 + 3_600_000);
    expect(decision.remaining).toBe(4);
    expect(next.tokens).toBe(4);
  });

  it("spends several tokens for a cost", () => {
    const [state, first] = tokenBucket(null, 10, 10_000, 4, T0);
    expect(first).toEqual(d(true, 6, 0, 4_000));
    const [, second] = tokenBucket(state, 10, 10_000, 7, T0);
    expect(second).toEqual(d(false, 6, 1_000, 4_000)); // a denial spends nothing
  });

  it("keeps fractional tokens between calls", () => {
    let [state] = tokenBucket(null, 3, 1_000, 3, T0); // empty; refill 3 per second
    let decision: Decision;
    [state, decision] = tokenBucket(state, 3, 1_000, 1, T0 + 200); // 0.6 tokens
    expect(decision.allowed).toBe(false);
    expect(decision.retryAfterMs).toBe(134); // ceil(0.4 * 1000 / 3)
    [, decision] = tokenBucket(state, 3, 1_000, 1, T0 + 334);
    expect(decision.allowed).toBe(true);
  });

  it("never creates tokens when time goes backwards", () => {
    let [state] = run(2, 60_000, [T0, T0]);
    let decision: Decision;
    [state, decision] = tokenBucket(state, 2, 60_000, 1, T0 - 30_000);
    expect(decision.allowed).toBe(false);
    expect(state.ts).toBe(T0);
    [, decision] = tokenBucket(state, 2, 60_000, 1, T0 + 30_000);
    expect(decision.allowed).toBe(true);
  });

  it("resets when the bucket is full again", () => {
    const [, out] = run(4, 8_000, [T0, T0]);
    expect(out.at(-1)!.resetAfterMs).toBe(4_000);
  });

  it("handles a slow policy of one per hour", () => {
    const [state, first] = tokenBucket(null, 1, 3_600_000, 1, T0);
    expect(first).toEqual(d(true, 0, 0, 3_600_000));
    const [, second] = tokenBucket(state, 1, 3_600_000, 1, T0 + 60_000);
    expect(second).toEqual(d(false, 0, 3_540_000, 3_540_000));
  });
});
