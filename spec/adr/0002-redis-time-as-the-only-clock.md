# ADR-0002: Redis TIME is the only clock

* **Status:** accepted
* **Date:** 2026-10-02

## Context
Both algorithms depend on "now": the token bucket refills by elapsed time, the sliding window
weights the previous window by how much of it has slid out. App instances run on different hosts
whose clocks drift by tens or hundreds of milliseconds, and a container's clock can jump after a
VM resume. If instance A thinks it is 12:00:00.300 and instance B thinks it is 12:00:00.100, B
"un-refills" A's bucket or counts into a window A has already left.

## Options considered
1. **Each app passes its own clock to the script**: simple, wrong under drift (see above).
2. **NTP everywhere and hope**: reduces drift, never removes it; nothing to test.
3. **The script reads `redis.call('TIME')`**: one clock for every caller, by construction.

## Decision
Option 3. The scripts read `TIME` (seconds + microseconds, converted to ms). `ARGV[4] now_ms` exists
**only for tests**, so the parity tests are deterministic; production code never passes it.
The pure functions take `now` as a parameter, and both also cope with a clock that goes backwards
(the bucket does not refill; the sliding window stays in the stored window).

## Consequences
* Instances can drift freely; decisions do not change.
* Redis 5+ replicates scripts by effect, so reading `TIME` inside a script is allowed.
* The degraded local limiter added in v1.1 (ADR-0006) uses the app clock; that is acceptable
  because its state is per instance anyway.
