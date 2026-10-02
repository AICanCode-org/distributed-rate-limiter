// Time. Services never call Date.now() directly: they get a clock, so tests can travel in time.
export type Clock = () => Date;

export const systemClock: Clock = () => new Date();

/** Epoch milliseconds (what the limiter algorithms and the Lua scripts work in). */
export const toMs = (d: Date): number => d.getTime();

/** ISO-8601 UTC with millisecond precision and a 'Z' suffix, identical in every track. */
export const iso = (d: Date): string => d.toISOString();
export const isoMs = (ms: number): string => new Date(ms).toISOString();
