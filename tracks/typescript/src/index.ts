// Process entrypoint: wire real adapters, start listening on $PORT.
import { buildApp } from "./app.js";
import { loadSettings } from "./config.js";
import { RedisStore } from "./store.js";
import { wire } from "./wiring.js";

const settings = loadSettings();
const store = RedisStore.fromUrl(settings.redisUrl, settings.redisTimeoutMs, settings.luaDir);
const app = buildApp(wire(settings, store), { logger: true });

const shutdown = async () => {
  await app.close();
  await store.close();
  process.exit(0);
};
process.on("SIGTERM", shutdown);
process.on("SIGINT", shutdown);

await app.listen({ host: "0.0.0.0", port: settings.port });
