-- RateGuard · sliding window counter (ADR-0001, ADR-0002). Atomic inside Redis.
-- Two fixed windows (previous, current); the previous one counts in proportion to how much of it
-- still overlaps the sliding window. All maths is scaled by window_ms so it stays in integers.
--
-- KEYS[1]  window hash    rg:{<tenant>}:sw:<resource>   fields: w (window index), cur, prev
-- KEYS[2]  usage hash     rg:{<tenant>}:usage           ring of 180 one-minute slots
-- ARGV[1]  limit          requests (cost units) allowed per sliding window
-- ARGV[2]  window_ms      window length
-- ARGV[3]  cost           units this request needs (1 <= cost <= limit)
-- ARGV[4]  now_ms         OPTIONAL, tests only: replaces the Redis clock
-- returns  {allowed (1|0), remaining, retry_after_ms, reset_after_ms}
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local cost = tonumber(ARGV[3])
local now = tonumber(ARGV[4])
if not now then
  local t = redis.call('TIME')
  now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
end

local idx = math.floor(now / window)
local elapsed = now - idx * window
local state = redis.call('HMGET', KEYS[1], 'w', 'cur', 'prev')
local w = tonumber(state[1])
local cur = tonumber(state[2]) or 0
local prev = tonumber(state[3]) or 0
if w == nil then
  cur, prev = 0, 0
elseif idx < w then
  idx, elapsed = w, 0 -- the clock went backwards: stay in the stored window
elseif idx == w + 1 then
  prev, cur = cur, 0
elseif idx > w + 1 then
  prev, cur = 0, 0
end

-- estimate * window = prev * (window - elapsed) + cur * window
local weighted = prev * (window - elapsed) + cur * window
local allowed = 0
local retry = 0
if weighted + cost * window <= limit * window then
  cur = cur + cost
  weighted = weighted + cost * window
  allowed = 1
elseif cur + cost <= limit then
  -- fits in this window once enough of the previous window has slid out
  retry = window - math.floor((limit - cost - cur) * window / prev) - elapsed
else
  -- only fits in the next window, once enough of this one has slid out
  retry = (window - elapsed) + window - math.floor((limit - cost) * window / cur)
end
local remaining = math.floor((limit * window - weighted) / window)
if remaining < 0 then remaining = 0 end
local reset = 0
if cur > 0 then reset = 2 * window - elapsed elseif prev > 0 then reset = window - elapsed end

redis.call('HSET', KEYS[1], 'w', idx, 'cur', cur, 'prev', prev)
redis.call('PEXPIRE', KEYS[1], 2 * window + 1000)

local minute = math.floor(now / 60000)
local slot = minute % 180
if tonumber(redis.call('HGET', KEYS[2], 'm' .. slot)) ~= minute then
  redis.call('HSET', KEYS[2], 'm' .. slot, minute, 'a' .. slot, 0, 'd' .. slot, 0)
end
redis.call('HINCRBY', KEYS[2], (allowed == 1 and 'a' or 'd') .. slot, 1)
redis.call('PEXPIRE', KEYS[2], 10800000)

return {allowed, remaining, retry, reset}
