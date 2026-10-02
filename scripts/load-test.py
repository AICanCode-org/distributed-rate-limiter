#!/usr/bin/env python3
"""RateGuard load test: many concurrent checks against two app instances that share one Redis.

The point of a *distributed* limiter: however the requests are spread over the instances, exactly
`limit` of them are allowed. This script proves it and reports the latency of each check.

Usage:
  python3 scripts/load-test.py [BASE_URL ...]        (default http://localhost:8081 http://localhost:8082)
Start two instances first, for example:
  TRACK=python docker compose --profile pair up -d --build
or two `make run` with PORT=8081 and PORT=8082 next to `docker compose up -d redis`.

Environment: LOAD_ADMIN_TOKEN (default: the local Compose value), LOAD_REQUESTS (default 400),
LOAD_CONCURRENCY (default 40), LOAD_LIMIT (default 100), LOAD_ALGORITHM (TOKEN_BUCKET | SLIDING_WINDOW).
Only the Python standard library is used. Exit code 0 = the limit held, 1 = it did not.
"""

import json
import os
import statistics
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

BASES = [b.rstrip("/") for b in sys.argv[1:]] or ["http://localhost:8081", "http://localhost:8082"]
ADMIN = os.getenv("LOAD_ADMIN_TOKEN", "local-admin-token-change-me-0123456789abcdef")
REQUESTS = int(os.getenv("LOAD_REQUESTS", "400"))
CONCURRENCY = int(os.getenv("LOAD_CONCURRENCY", "40"))
LIMIT = int(os.getenv("LOAD_LIMIT", "100"))
ALGORITHM = os.getenv("LOAD_ALGORITHM", "TOKEN_BUCKET")
TENANT = f"load-{int(time.time())}"


def call(method: str, url: str, body=None, headers=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json", **(headers or {})})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            text = r.read().decode()
            return r.status, json.loads(text) if text else None
    except urllib.error.HTTPError as e:
        text = e.read().decode()
        return e.code, json.loads(text) if text else None


def percentile(values: list[float], p: int) -> float:
    ordered = sorted(values)
    return ordered[max(0, min(len(ordered) - 1, round(p / 100 * len(ordered)) - 1))]


def main() -> int:
    admin = {"Authorization": f"Bearer {ADMIN}"}
    # A one-hour window: nothing refills during the test, so the expected count is exact.
    status, created = call("POST", f"{BASES[0]}/api/v1/admin/tenants", {
        "id": TENANT, "name": "Load test", "policy": {"algorithm": ALGORITHM, "limit": LIMIT, "windowSeconds": 3600}}, admin)
    if status != 201:
        print(f"could not create the tenant: {status} {created}")
        return 1
    key = {"X-API-Key": created["apiKey"]}

    def one(i: int):
        start = time.perf_counter()
        code, body = call("POST", f"{BASES[i % len(BASES)]}/api/v1/limits/check", {"resource": "load"}, key)
        return code, bool(body and body.get("allowed")), (time.perf_counter() - start) * 1000

    started = time.perf_counter()
    with ThreadPoolExecutor(CONCURRENCY) as pool:
        results = list(pool.map(one, range(REQUESTS)))
    elapsed = time.perf_counter() - started
    call("DELETE", f"{BASES[0]}/api/v1/admin/tenants/{TENANT}", None, admin)

    errors = sum(1 for code, _, _ in results if code != 200)
    allowed = sum(1 for _, ok, _ in results if ok)
    latencies = [ms for _, _, ms in results]
    print(f"{REQUESTS} checks, {CONCURRENCY} at a time, over {len(BASES)} instance(s): {', '.join(BASES)}")
    print(f"algorithm {ALGORITHM}, limit {LIMIT} per hour")
    print(f"allowed {allowed}, denied {REQUESTS - allowed - errors}, errors {errors}")
    print(f"throughput {REQUESTS / elapsed:.0f} checks/s")
    print(f"latency ms: p50 {statistics.median(latencies):.1f}  p95 {percentile(latencies, 95):.1f}  "
          f"p99 {percentile(latencies, 99):.1f}  max {max(latencies):.1f}")
    if errors or allowed != min(LIMIT, REQUESTS):
        print(f"FAIL: expected exactly {min(LIMIT, REQUESTS)} allowed and no errors")
        return 1
    print("PASS: the limit held across instances")
    return 0


if __name__ == "__main__":
    sys.exit(main())
