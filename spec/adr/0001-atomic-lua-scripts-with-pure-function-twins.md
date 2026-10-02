# ADR-0001: Limiter decisions in atomic Lua scripts, with pure-function twins

* **Status:** accepted
* **Date:** 2026-10-02

## Context
RateGuard runs as several app instances behind one load balancer, all sharing one Redis. A limit
of 100 requests per minute must hold for the tenant as a whole, not per instance (NFR-1). The naive
code reads the counter, decides in the application and writes the new value. Two instances that read
"99 used" at the same time both admit request 100, and both write 100: 101 requests got through.
Under a burst this over-admission grows with the number of instances (threat T3).

## Options considered
1. **Read, decide, write from the app**: two round trips and the race described above.
2. **`WATCH`/`MULTI`/`EXEC` (optimistic transaction)**: correct, but a hot key under a burst makes
   most transactions abort and retry; the retry loop has to be written three times (one per track).
3. **`INCR` + `EXPIRE` fixed window**: atomic and simple, but allows twice the limit across a window
   boundary (100 at 0:59, 100 at 1:00), and cannot do a token bucket or a `cost` > 1 cleanly.
4. **A Lua script per algorithm** (`EVALSHA`): Redis runs a script atomically, no other command runs
   in between. One round trip per check, the same script for all three tracks.

## Decision
Option 4: `spec/lua/token_bucket.lua` and `spec/lua/sliding_window.lua` make the whole decision
inside Redis. Each track also has the **same maths as pure functions** (`algorithms.*`: state in,
state + decision out, no I/O, no clock). The unit tests pin the pure functions down exhaustively;
the integration tests replay the same sequences against the Lua scripts (with a fixed `now_ms`)
and require identical answers. The in-memory store used by the unit tests calls the pure functions.

## Consequences
* No over-admission, whatever the number of instances; one network round trip per check.
* Two implementations of each algorithm must stay in step: the parity tests are what make that safe.
* Scripts are loaded once with `SCRIPT LOAD` and called by SHA; after a Redis restart the store
  reloads them on `NOSCRIPT`.
* A long script blocks Redis for everyone, so scripts stay O(1): no loops over keys.
