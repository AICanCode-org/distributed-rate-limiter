// v1.1 (Phase 7): the circuit breaker in front of Redis and the LLM.
import { describe, expect, it } from "vitest";
import { CircuitBreaker, CircuitOpenError } from "../../src/breaker.js";
import { DuplicateError, StoreUnavailable } from "../../src/errors.js";

class Flaky {
  calls = 0;
  fail = true;
  readonly fn = async () => {
    this.calls += 1;
    if (this.fail) throw new StoreUnavailable("connection refused");
    return "ok";
  };
}

function make(isFailure?: (err: unknown) => boolean) {
  const clock = { t: 1_000_000 };
  const skipped: number[] = [];
  const breaker = new CircuitBreaker("redis", {
    threshold: 3,
    cooldownMs: 5000,
    now: () => clock.t,
    onShortCircuit: () => skipped.push(1),
    warn: () => {},
    ...(isFailure ? { isFailure } : {}),
  });
  return { breaker, dep: new Flaky(), clock, skipped };
}

async function failTimes(breaker: CircuitBreaker, dep: Flaky, n: number) {
  for (let i = 0; i < n; i++) await expect(breaker.call(dep.fn)).rejects.toBeInstanceOf(StoreUnavailable);
}

describe("circuit breaker", () => {
  it("opens after three failures in a row and then skips the call", async () => {
    const { breaker, dep, skipped } = make();
    await failTimes(breaker, dep, 3);
    expect(breaker.state).toBe("open");
    await expect(breaker.call(dep.fn)).rejects.toBeInstanceOf(CircuitOpenError);
    expect([dep.calls, skipped.length]).toEqual([3, 1]);
  });

  it("resets the count after a success", async () => {
    const { breaker, dep } = make();
    await failTimes(breaker, dep, 2);
    dep.fail = false;
    expect(await breaker.call(dep.fn)).toBe("ok");
    dep.fail = true;
    await failTimes(breaker, dep, 2);
    expect(breaker.state).toBe("closed");
  });

  it("closes after a successful half-open trial and re-opens after a failed one", async () => {
    const { breaker, dep, clock } = make();
    await failTimes(breaker, dep, 3);
    clock.t += 5000;
    expect(breaker.state).toBe("half-open");
    await failTimes(breaker, dep, 1);
    expect([breaker.state, dep.calls]).toEqual(["open", 4]);
    clock.t += 5000;
    dep.fail = false;
    expect(await breaker.call(dep.fn)).toBe("ok");
    expect(breaker.state).toBe("closed");
  });

  it("lets only one trial call through while half-open", async () => {
    const { breaker, dep, clock, skipped } = make();
    await failTimes(breaker, dep, 3);
    clock.t += 5000;
    let release: () => void = () => {};
    const trial = breaker.call(() => new Promise<string>((resolve) => (release = () => resolve("trial"))));
    await expect(breaker.call(async () => "second")).rejects.toBeInstanceOf(CircuitOpenError);
    release();
    expect(await trial).toBe("trial");
    expect(skipped.length).toBe(1);
  });

  it("does not count answers that are not failures", async () => {
    const { breaker } = make((err) => err instanceof StoreUnavailable);
    for (let i = 0; i < 5; i++) {
      await expect(
        breaker.call(async () => {
          throw new DuplicateError("acme");
        }),
      ).rejects.toBeInstanceOf(DuplicateError);
    }
    expect(breaker.state).toBe("closed");
  });
});
