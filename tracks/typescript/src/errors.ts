// Domain errors that map 1:1 to RFC 9457 problem responses (identical in every track).
export const PROBLEM_BASE = "https://rateguard.example/problems/";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly slug: string,
    readonly title: string,
    readonly detail?: string,
    readonly headers: Record<string, string> = {},
  ) {
    super(detail ?? title);
  }

  toProblem(): Record<string, unknown> {
    const body: Record<string, unknown> = {
      type: PROBLEM_BASE + this.slug,
      title: this.title,
      status: this.status,
    };
    if (this.detail) body.detail = this.detail;
    return body;
  }
}

export const validationError = (detail: string) =>
  new ApiError(400, "validation-error", "Validation failed", detail);
export const unauthorized = () =>
  new ApiError(401, "unauthorized", "Unauthorized", "admin token required", { "WWW-Authenticate": "Bearer" });
export const invalidApiKey = () =>
  new ApiError(401, "invalid-api-key", "Invalid API key", "a valid X-API-Key header is required");
export const notFound = (what: string) => new ApiError(404, "not-found", "Not found", `${what} not found`);
export const tenantExists = () =>
  new ApiError(409, "tenant-exists", "Tenant already exists", "a tenant with this id exists");
export const costExceedsLimit = (cost: number, limit: number) =>
  new ApiError(
    422,
    "cost-exceeds-limit",
    "Cost exceeds limit",
    `cost ${cost} is more than the limit of ${limit}`,
  );
export const rateLimited = (
  limit: number,
  windowSeconds: number,
  retryAfterS: number,
  headers: Record<string, string>,
) =>
  new ApiError(
    429,
    "rate-limited",
    "Too many requests",
    `limit of ${limit} per ${windowSeconds} s reached, retry in ${retryAfterS} s`,
    headers,
  );
export const limiterUnavailable = () =>
  new ApiError(503, "limiter-unavailable", "Limiter unavailable", "the rate-limit store is unavailable", {
    "Retry-After": "1",
  });

/** Thrown by a store when a tenant id is already taken (SADD said 0: Redis decided the race). */
export class DuplicateError extends Error {}

/** Thrown by a store when Redis cannot answer (refused, timed out, broken connection). */
export class StoreUnavailable extends Error {}
