// M2: sliding window counter as a pure function (integer maths, scaled by the window).
import { expect, it } from "vitest";
import { slidingWindow, type WindowState } from "../../src/algorithms.js";
import type { Decision } from "../../src/models.js";
import { pendingDescribe } from "../pending.js";

const describe = pendingDescribe("sliding-window");
const W = 60_000;
const T0 = 29_848_884 * W; // the start of a window (2026-10-02T09:24:00Z)
const I = T0 / W;
const ws = (w: number, cur: number, prev: number): WindowState => ({ w, cur, prev });
const d = (allowed: boolean, remaining: number, retryAfterMs: number, resetAfterMs: number): Decision => ({
  allowed,
  remaining,
  retryAfterMs,
  resetAfterMs,
});

describe("sliding window", () => {
  it("counts within one window", () => {
    let state: WindowState | null = null;
    const out: Decision[] = [];
    for (let i = 0; i < 4; i++) {
      const [next, decision] = slidingWindow(state, 3, W, 1, T0 + 1_000);
      state = next;
      out.push(decision);
    }
    expect(out.map((x) => x.allowed)).toEqual([true, true, true, false]);
    expect(out.map((x) => x.remaining)).toEqual([2, 1, 0, 0]);
    expect(state).toEqual(ws(I, 3, 0));
  });

  it("counts the previous window by its overlap", () => {
    let [state] = slidingWindow(ws(I - 1, 10, 0), 10, W, 1, T0 + 15_000); // prev=10, 25 % slid out
    expect(state).toEqual(ws(I, 1, 10));
    let decision: Decision;
    [state, decision] = slidingWindow(state, 10, W, 1, T0 + 15_000);
    expect(decision.allowed).toBe(true);
    expect(decision.remaining).toBe(0);
    [, decision] = slidingWindow(state, 10, W, 1, T0 + 15_000);
    expect(decision.allowed).toBe(false);
  });

  it("retries once the previous window slid out further", () => {
    expect(slidingWindow(ws(I, 2, 10), 10, W, 1, T0 + 30_000)[1].allowed).toBe(true);
    // needs 10 * (1 - f) + 6 + 1 <= 10  ->  f >= 0.7  ->  at 42 000 ms: 12 s from now
    expect(slidingWindow(ws(I, 6, 10), 10, W, 1, T0 + 30_000)[1]).toEqual(
      d(false, 0, 12_000, 2 * W - 30_000),
    );
  });

  it("retries in the next window when only that one can fit it", () => {
    // next window: 3 * (1 - f) + 1 <= 3  ->  f >= 1/3  ->  15 s left here + 20 s into the next
    expect(slidingWindow(ws(I, 3, 0), 3, W, 1, T0 + 45_000)[1]).toEqual(d(false, 0, 35_000, 75_000));
  });

  it("rolls the window forward", () => {
    const [state, decision] = slidingWindow(ws(I - 1, 4, 9), 5, W, 1, T0 + 59_999);
    expect(state).toEqual(ws(I, 1, 4));
    expect(decision.allowed).toBe(true);
  });

  it("forgets everything after a long pause", () => {
    const [state, decision] = slidingWindow(ws(I - 5, 5, 5), 5, W, 1, T0);
    expect(state).toEqual(ws(I, 1, 0));
    expect(decision).toEqual(d(true, 4, 0, 2 * W));
  });

  it("stays in the stored window when time goes backwards", () => {
    const [state, decision] = slidingWindow(ws(I + 1, 2, 0), 3, W, 1, T0 + 10);
    expect(state).toEqual(ws(I + 1, 3, 0));
    expect(decision.allowed).toBe(true);
  });

  it("counts a cost as several units", () => {
    const [state, first] = slidingWindow(null, 10, W, 4, T0);
    expect(first).toEqual(d(true, 6, 0, 2 * W));
    expect(state.cur).toBe(4);
    const [, second] = slidingWindow(state, 10, W, 7, T0);
    expect(second.allowed).toBe(false);
    expect(second.remaining).toBe(6);
  });

  it("resets depending on what is still counted", () => {
    const [, decision] = slidingWindow(ws(I - 1, 2, 0), 5, W, 5, T0 + 10_000);
    expect(decision.allowed).toBe(false);
    expect(decision.resetAfterMs).toBe(W - 10_000);
  });

  it("needs an empty window for a cost equal to the limit", () => {
    // next window: 1 * (1 - f) + 3 <= 3 -> f = 1 -> 40 s here + 60 s
    expect(slidingWindow(ws(I, 1, 0), 3, W, 3, T0 + 20_000)[1]).toEqual(d(false, 2, 100_000, 100_000));
  });
});
