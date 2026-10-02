# ADR-0003: Key layout with a `{tenantId}` hash tag

* **Status:** accepted
* **Date:** 2026-10-02

## Context
A Lua script may only touch the keys passed in `KEYS`, and in Redis Cluster all of them must live
in the **same hash slot**, or the call fails with `CROSSSLOT`. Each check touches two keys: the
limiter state for the resource and the tenant's usage ring. We start with one Redis node, but the
key layout is the one thing that is painful to change later (every key would need migrating).

## Options considered
1. **Plain keys** `rg:tb:<tenant>:<resource>` and `rg:usage:<tenant>`: works on one node, breaks in
   a cluster because the two keys hash to different slots.
2. **One hash per tenant holding everything**: one slot, but TTLs work per key, not per field, so idle
   resources would never expire; one hot tenant also makes one huge key.
3. **Hash tags**: `rg:{<tenantId>}:tb:<resource>`, `rg:{<tenantId>}:sw:<resource>`,
   `rg:{<tenantId>}:usage`. Redis Cluster hashes only the part inside `{}`, so all keys of a tenant
   share a slot, and each key keeps its own TTL.

## Decision
Option 3 for everything a script touches. Tenant records and key lookups are read outside scripts
and need no tag: `rg:tenants` (set of ids), `rg:tenant:<id>` (hash), `rg:key:<sha256(apiKey)>` (id).
Tenant ids are validated (`^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$`) and resources too
(`^[a-z0-9._:-]{1,64}$`): neither can contain `{`, `}`, `*` or spaces, so no input can move a key
into another tenant's slot or turn into a pattern. All keys are built in one place (`keys.*`), with
unit tests.

## Consequences
* Moving to Redis Cluster later needs no key migration.
* All of a tenant's limiter keys sit on one node: one very hot tenant cannot be spread out. Fine at
  our scale (see the estimates in `requirements.md`).
* `reset` and tenant deletion must find a tenant's limiter keys: they use `SCAN` with the tag as the
  pattern prefix (`rg:{<id>}:*`), never `KEYS`.
