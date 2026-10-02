// HTTP layer (Fastify). Thin: read the request, call a service, shape the JSON, map errors.
import Fastify, {
  type FastifyInstance,
  type FastifyReply,
  type FastifyRequest,
  type FastifyServerOptions,
} from "fastify";
import { statsJson, summarize, type AdvisorService } from "./advisor.js";
import { iso, isoMs, type Clock } from "./clock.js";
import { ApiError, limiterUnavailable, notFound, StoreUnavailable, validationError } from "./errors.js";
import { rateLimitHeaders, type LimiterService } from "./limiter.js";
import type { Policy, Tenant, Verdict } from "./models.js";
import type { AdminAuth } from "./security.js";
import type { Store } from "./store.js";
import type { TenantService } from "./tenants.js";

const PROBLEM_JSON = "application/problem+json";

export interface AppDeps {
  store: Store;
  admin: AdminAuth;
  tenants: TenantService;
  limiter: LimiterService;
  advisor: AdvisorService;
  clock: Clock;
  warn: (msg: string) => void;
}

// ---- JSON shapes (identical in every track, see spec/openapi.yaml) ------------------------------
export const policyJson = (p: Policy) => ({
  algorithm: p.algorithm,
  limit: p.limit,
  windowSeconds: p.windowSeconds,
});

export const tenantJson = (t: Tenant) => ({
  id: t.id,
  name: t.name,
  policy: policyJson(t.policy),
  keyPrefix: t.keyPrefix,
  createdAt: iso(t.createdAt),
  updatedAt: iso(t.updatedAt),
});

export const verdictJson = (x: Verdict) => ({
  allowed: x.decision.allowed,
  tenantId: x.tenant.id,
  resource: x.resource,
  cost: x.cost,
  algorithm: x.tenant.policy.algorithm,
  limit: x.tenant.policy.limit,
  remaining: x.decision.remaining,
  resetAfterMs: x.decision.resetAfterMs,
  retryAfterMs: x.decision.retryAfterMs,
});

type Params = Record<string, string>;
/** A repeated query parameter (?minutes=1&minutes=2) counts as its last value, like in the other tracks. */
function queryParam(req: FastifyRequest, name: string): string | undefined {
  const value = (req.query as Record<string, string | string[] | undefined>)[name];
  return Array.isArray(value) ? value[value.length - 1] : value;
}

function header(req: FastifyRequest, name: string): string | undefined {
  const value = req.headers[name];
  return Array.isArray(value) ? value[0] : value;
}

function sendProblem(reply: FastifyReply, err: ApiError) {
  return reply.code(err.status).headers(err.headers).type(PROBLEM_JSON).send(err.toProblem());
}

export function buildApp(deps: AppDeps, opts: FastifyServerOptions = {}): FastifyInstance {
  const app = Fastify(opts);
  const { store, admin, tenants, limiter, advisor, clock, warn } = deps;
  const asAdmin = (req: FastifyRequest) => {
    admin.require(req.headers.authorization);
    return tenants;
  };
  const id = (req: FastifyRequest) => (req.params as Params).id!;

  // ---- error mapping ---------------------------------------------------
  app.setErrorHandler((err, request, reply) => {
    if (err instanceof ApiError) return sendProblem(reply, err);
    if (err instanceof StoreUnavailable) {
      warn(`store unavailable: ${err.message}`);
      return sendProblem(reply, limiterUnavailable());
    }
    const status = (err as { statusCode?: number }).statusCode;
    if (status && status >= 400 && status < 500) {
      // Malformed JSON, unsupported media type...
      return sendProblem(reply, validationError("request body must be a JSON object"));
    }
    request.log.error(err);
    return sendProblem(reply, new ApiError(500, "internal-error", "Internal server error"));
  });
  app.setNotFoundHandler((_req, reply) => sendProblem(reply, notFound("route")));

  // ---- ops -----------------------------------------------------------------
  app.get("/health", async (_req, reply) => {
    const up = await store.ping();
    return reply
      .code(up ? 200 : 503)
      .send({ status: up ? "UP" : "DOWN", checks: { redis: up ? "UP" : "DOWN" } });
  });

  // ---- admin: tenants ------------------------------------------------------
  app.post("/api/v1/admin/tenants", async (req, reply) => {
    const [tenant, apiKey] = await asAdmin(req).create(req.body);
    return reply
      .code(201)
      .header("location", `/api/v1/admin/tenants/${tenant.id}`)
      .send({ ...tenantJson(tenant), apiKey });
  });
  app.get("/api/v1/admin/tenants", async (req) => ({ items: (await asAdmin(req).list()).map(tenantJson) }));
  app.get("/api/v1/admin/tenants/:id", async (req) => tenantJson(await asAdmin(req).get(id(req))));
  app.delete("/api/v1/admin/tenants/:id", async (req, reply) => {
    await asAdmin(req).delete(id(req));
    return reply.code(204).send();
  });
  app.put("/api/v1/admin/tenants/:id/policy", async (req) =>
    tenantJson(await asAdmin(req).setPolicy(id(req), req.body)),
  );
  app.post("/api/v1/admin/tenants/:id/rotate-key", async (req) => {
    const [tenant, apiKey] = await asAdmin(req).rotateKey(id(req));
    return { ...tenantJson(tenant), apiKey };
  });
  app.post("/api/v1/admin/tenants/:id/reset", async (req, reply) => {
    await asAdmin(req).reset(id(req));
    return reply.code(204).send();
  });
  app.get("/api/v1/admin/tenants/:id/usage", async (req) => {
    const [tenant, minutes, points] = await asAdmin(req).usage(id(req), queryParam(req, "minutes"));
    return {
      tenantId: tenant.id,
      minutes,
      totals: {
        allowed: points.reduce((sum, p) => sum + p.allowed, 0),
        denied: points.reduce((sum, p) => sum + p.denied, 0),
      },
      series: points.map((p) => ({ minute: isoMs(p.minute * 60_000), allowed: p.allowed, denied: p.denied })),
    };
  });
  app.post("/api/v1/admin/tenants/:id/advice", async (req) => {
    const [tenant, , points] = await asAdmin(req).usage(id(req), queryParam(req, "minutes"));
    const stats = summarize(tenant.policy, points);
    const result = await advisor.advise(tenant, stats);
    const a = result.advice;
    return {
      tenantId: tenant.id,
      provider: result.provider,
      fallback: result.fallback,
      currentPolicy: policyJson(tenant.policy),
      stats: statsJson(stats),
      action: a.action,
      suggestedPolicy: a.limit === null ? null : policyJson({ ...tenant.policy, limit: a.limit }),
      summary: a.summary,
      generatedAt: iso(clock()),
    };
  });

  // ---- tenants: the limiter -----------------------------------------------
  app.post("/api/v1/limits/check", async (req, reply) => {
    const verdict = await limiter.check(await tenants.authenticate(header(req, "x-api-key")), req.body);
    return reply.headers(rateLimitHeaders(verdict)).send(verdictJson(verdict));
  });
  app.get("/api/v1/demo/hello", async (req, reply) => {
    const tenant = await tenants.authenticate(header(req, "x-api-key"));
    const verdict = await limiter.enforce(tenant);
    return reply
      .headers(rateLimitHeaders(verdict))
      .send({ message: `Hello, ${tenant.name}!`, tenantId: tenant.id });
  });

  return app;
}
