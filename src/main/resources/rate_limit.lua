-- KEYS[1]: ratelimit:seckill:{ip}:{timestamp_seconds}
-- KEYS[2]: ratelimit:counter:{ip}
-- KEYS[3]: ratelimit:blacklist:{ip}
-- ARGV[1]: 限流阈值
-- ARGV[2]: 黑名单触发阈值
-- ARGV[3]: 黑名单 TTL（秒）
-- 返回: 0=通过, 1=限流, 2=黑名单命中, 3=限流且触发黑名单

-- 1. 检查黑名单
if redis.call('EXISTS', KEYS[3]) == 1 then
    return 2
end

-- 2. 限流计数
local count = redis.call('INCR', KEYS[1])
redis.call('EXPIRE', KEYS[1], 2)

if count <= tonumber(ARGV[1]) then
    return 0
end

-- 3. 超出限流阈值，累加计数器
local total = redis.call('INCR', KEYS[2])
redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))

if total < tonumber(ARGV[2]) then
    return 1
end

-- 4. 触发黑名单
redis.call('SET', KEYS[3], '1')
redis.call('EXPIRE', KEYS[3], tonumber(ARGV[3]))
redis.call('DEL', KEYS[2])
return 3
