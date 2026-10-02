// Configuration from environment variables (12-factor). Same names in every track.
import { fileURLToPath } from "node:url";
import path from "node:path";

export interface Settings {
  port: number;
  redisUrl: string;
  redisTimeoutMs: number;
  luaDir: string;
  /** null -> a random per-process token (the admin API is locked, logged as a warning) */
  adminToken: string | null;
  aiProvider: "offline" | "llm";
  llmBaseUrl: string;
  llmApiKey: string | null;
  llmModel: string;
  llmTimeoutMs: number;
}

const here = path.dirname(fileURLToPath(import.meta.url));
// tracks/typescript/src -> <repo>/spec/lua (only used outside Docker)
const defaultLuaDir = path.resolve(here, "..", "..", "..", "spec", "lua");

export function loadSettings(env: NodeJS.ProcessEnv = process.env): Settings {
  return {
    port: Number(env.PORT ?? 8080),
    redisUrl: env.REDIS_URL ?? "redis://localhost:6379/0",
    redisTimeoutMs: Number(env.REDIS_TIMEOUT_MS ?? 1000),
    luaDir: env.LUA_DIR || defaultLuaDir,
    adminToken: env.ADMIN_TOKEN || null,
    aiProvider: (env.AI_PROVIDER ?? "offline").toLowerCase() === "llm" ? "llm" : "offline",
    llmBaseUrl: (env.LLM_BASE_URL ?? "http://localhost:11434/v1").replace(/\/+$/, ""),
    llmApiKey: env.LLM_API_KEY || null,
    llmModel: env.LLM_MODEL ?? "llama3.2:1b",
    llmTimeoutMs: Number(env.LLM_TIMEOUT_MS ?? 5000),
  };
}
