// v1.1 (after the game day): a circuit breaker, used in front of Redis and in front of the LLM.
//
// CLOSED: calls go through; `threshold` failures in a row open the circuit.
// OPEN: calls fail immediately with CircuitOpenError (no timeout to wait for) for `cooldownMs`.
// HALF-OPEN: after the cooldown ONE trial call goes through; success closes the circuit, failure re-opens it.

export class CircuitOpenError extends Error {
  override name = "CircuitOpenError";
}

export interface BreakerOptions {
  threshold?: number;
  cooldownMs?: number;
  now?: () => number;
  /** which errors count as "the dependency is broken" (default: all). Others close the circuit. */
  isFailure?: (err: unknown) => boolean;
  onShortCircuit?: () => void;
  warn?: (msg: string) => void;
}

export class CircuitBreaker {
  private failures = 0;
  private openedAt: number | null = null;
  private trialRunning = false;

  constructor(
    readonly name: string,
    private readonly opts: BreakerOptions = {},
  ) {}

  private now() {
    return (this.opts.now ?? Date.now)();
  }

  get state(): "closed" | "open" | "half-open" {
    if (this.openedAt === null) return "closed";
    return this.now() - this.openedAt >= (this.opts.cooldownMs ?? 5000) ? "half-open" : "open";
  }

  async call<T>(fn: () => Promise<T>): Promise<T> {
    const state = this.state;
    if (state === "open" || (state === "half-open" && this.trialRunning)) {
      this.opts.onShortCircuit?.();
      throw new CircuitOpenError(`${this.name} circuit open: skipping the call`);
    }
    this.trialRunning = state === "half-open";
    let result: T;
    try {
      result = await fn();
    } catch (err) {
      this.trialRunning = false;
      if ((this.opts.isFailure ?? (() => true))(err)) {
        this.failures += 1;
        if (this.openedAt !== null || this.failures >= (this.opts.threshold ?? 3)) {
          this.openedAt = this.now();
          (this.opts.warn ?? console.warn)(`${this.name} circuit opened after ${this.failures} failure(s)`);
        }
      } else {
        this.close(); // the dependency answered (e.g. "duplicate id"): it is healthy
      }
      throw err;
    }
    this.trialRunning = false;
    this.close();
    return result;
  }

  private close() {
    this.failures = 0;
    this.openedAt = null;
  }
}
