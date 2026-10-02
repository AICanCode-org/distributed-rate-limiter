// Hand-rolled Prometheus counters (text exposition format 0.0.4). v1.1.
// No client library on purpose: /metrics is just text. Names are identical in all three tracks.

export const COUNTERS = {
  rateguard_decisions_allowed_total: "Limiter checks that were allowed.",
  rateguard_decisions_denied_total: "Limiter checks that were denied.",
  rateguard_degraded_decisions_total:
    "Checks decided by the tenant's failMode because Redis was unavailable.",
  rateguard_store_errors_total: "Redis calls that failed (refused, timed out, broken connection).",
  rateguard_store_short_circuits_total: "Redis calls skipped because the circuit breaker was open.",
  rateguard_tenants_created_total: "Tenants created with POST /admin/tenants.",
  rateguard_ai_fallback_total: "Advice requests that fell back to the offline rules.",
  rateguard_llm_short_circuits_total: "LLM calls skipped because the circuit breaker was open.",
} as const;

export type CounterName = keyof typeof COUNTERS;

export class Metrics {
  private values = new Map<CounterName, number>(
    (Object.keys(COUNTERS) as CounterName[]).map((name) => [name, 0]),
  );

  inc(name: CounterName, amount = 1) {
    this.values.set(name, (this.values.get(name) ?? 0) + amount);
  }

  value(name: CounterName) {
    return this.values.get(name) ?? 0;
  }

  render(): string {
    const lines: string[] = [];
    for (const [name, help] of Object.entries(COUNTERS) as [CounterName, string][]) {
      lines.push(`# HELP ${name} ${help}`, `# TYPE ${name} counter`, `${name} ${this.value(name)}`);
    }
    return lines.join("\n") + "\n";
  }
}
