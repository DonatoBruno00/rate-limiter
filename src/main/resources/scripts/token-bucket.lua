-- Token Bucket rate limiter executed atomically in Redis.
-- Lua scripts in Redis run without interruption — no race conditions possible.
--
-- KEYS[1] = rate limit key (e.g. "rl:192.168.1.1:/api/quotes")
-- ARGV[1] = bucket size (max tokens)
-- ARGV[2] = refill rate per second (tokens/sec as decimal)
-- ARGV[3] = current timestamp in milliseconds

local bucketSize       = tonumber(ARGV[1])
local refillRatePerSec = tonumber(ARGV[2])
local nowMillis        = tonumber(ARGV[3])

-- Read current state from Redis hash
local stored = redis.call('HMGET', KEYS[1], 'tokens', 'lastRefillMillis')
local currentTokens    = tonumber(stored[1])
local lastRefillMillis = tonumber(stored[2])

-- First request for this key: initialize with a full bucket
if currentTokens == nil then
    currentTokens    = bucketSize
    lastRefillMillis = nowMillis
end

-- Refill: calculate how many tokens regenerated since last request
local elapsedSeconds = (nowMillis - lastRefillMillis) / 1000.0
local tokensToAdd    = elapsedSeconds * refillRatePerSec

-- Cap at bucketSize so idle clients don't accumulate unlimited tokens
local availableTokens = math.min(currentTokens + tokensToAdd, bucketSize)

if availableTokens >= 1.0 then
    -- Consume one token and persist the new state
    local remainingAfterConsume = availableTokens - 1.0
    redis.call('HMSET', KEYS[1], 'tokens', remainingAfterConsume, 'lastRefillMillis', nowMillis)
    -- TTL ensures keys for inactive clients are cleaned up automatically
    redis.call('EXPIRE', KEYS[1], 3600)
    return {1, math.floor(remainingAfterConsume), 0}
end

-- Not enough tokens: calculate how long until one regenerates
-- ceil so the client waits long enough to actually have a token when it retries
local tokensNeeded       = 1.0 - availableTokens
local retryAfterSeconds  = math.ceil(tokensNeeded / refillRatePerSec)
redis.call('HMSET', KEYS[1], 'tokens', availableTokens, 'lastRefillMillis', nowMillis)
redis.call('EXPIRE', KEYS[1], 3600)
return {0, 0, retryAfterSeconds}
