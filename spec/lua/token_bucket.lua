-- RateGuard · token bucket (ADR-0001, ADR-0002). Runs atomically inside Redis: no other command can
-- run between the read and the write, so two app instances can never spend the same token.
--
-- KEYS[1]  bucket hash    rg:{<tenant>}:tb:<resource>   fields: tokens (float), ts (ms)
-- KEYS[2]  usage hash     rg:{<tenant>}:usage           ring of 180 one-minute slots (see usage below)
-- ARGV[1]  limit          bucket capacity (tokens)
-- ARGV[2]  window_ms      the bucket refills `limit` tokens per window
-- ARGV[3]  cost           tokens this request needs (1 <= cost <= limit)
-- ARGV[4]  now_ms         OPTIONAL, tests only: replaces the Redis clock so tests are deterministic
-- returns  {allowed (1|0), remaining, retry_after_ms, reset_after_ms}   (integers)
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])
local now = tonumber(ARGV[4])
if not now then
  -- One clock for every app instance: the Redis server's (ADR-0002). App clocks drift.
  local t = redis.call('TIME')
  now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

local state = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
local tokens = tonumber(state[1])
local ts = tonumber(state[2])
if tokens == nil or ts == nil then
  tokens = limit -- a new (or expired) bucket starts full
  ts = now
end
if now > ts then
  tokens = math.min(limit, tokens + (now - ts) * limit / window)
  ts = now
end

local allowed = 0
local retry = 0
if tokens >= cost then
  tokens = tokens - cost
  allowed = 1
else
  retry = math.ceil((cost - tokens) * window / limit)
end
local reset = math.ceil((limit - tokens) * window / limit)

redis.call('HSET', KEYS[1], 'tokens', string.format('%.17g', tokens), 'ts', ts)
-- A bucket that would be full again carries no information: let Redis forget it.
redis.call('PEXPIRE', KEYS[1], reset + 1000)

-- usage: slot = minute % 180; m<slot> remembers which minute the slot holds
local minute = math.floor(now / 60000)
local slot = minute % 180
if tonumber(redis.call('HGET', KEYS[2], 'm' .. slot)) ~= minute then
  redis.call('HSET', KEYS[2], 'm' .. slot, minute, 'a' .. slot, 0, 'd' .. slot, 0)
end
redis.call('HINCRBY', KEYS[2], (allowed == 1 and 'a' or 'd') .. slot, 1)
redis.call('PEXPIRE', KEYS[2], 10800000)

return {allowed, math.floor(tokens), retry, reset}
