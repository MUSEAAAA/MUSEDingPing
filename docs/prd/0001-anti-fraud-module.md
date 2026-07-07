# PRD-001：优惠券秒杀防刷模块

## 背景

现有秒杀系统已实现"一人一单"，但无法防止黄牛批量注册多账号从同一 IP 高频请求。需要增加 IP 维度的限流与黑名单机制。

## 术语表

| 术语 | 定义 |
|------|------|
| IP 限流 | 同一 IP 在 1 秒内请求次数超过阈值时拒绝后续请求 |
| 固定窗口 | 以秒为单位的计数周期，每秒计数器重置（key 含时间戳） |
| 黑名单 | 累计被限流次数超过阈值的 IP 集合，封禁一定时长 |
| 风控日志 | 记录每次限流/解封事件的审计日志 |
| 拦截层级 | IP 限流 → Token 认证 → 业务逻辑，由外到内的防守链 |

## 功能需求

### FR-1：IP 请求频率限制

- 同一 IP 每秒最多发起 10 次秒杀请求（阈值可配置）
- 超出阈值返回 HTTP 429，错误信息区分"限流"和"黑名单"
- 使用固定窗口算法（key 含秒级时间戳）

### FR-2：IP 黑名单

- 同一 IP 累计被限流 30 次后自动进入黑名单（阈值可配置）
- 黑名单有效期 1 小时（可配置）
- 黑名单内的 IP 请求直接拒绝，返回剩余封禁时间
- 有效期过后自动解封

### FR-3：拦截器集成

- 以 Spring Interceptor 实现，order=0（最先拦截）
- 拦截路径：`/voucher-order/seckill/**`
- 不拦截其他路径，不影响正常用户访问其他功能

### FR-4：错误响应规范

```json
// 限流
HTTP 429
{ "success": false, "errorMsg": "请求过于频繁，请稍后再试" }

// 黑名单
HTTP 429
{ "success": false, "errorMsg": "您的IP已被限制访问，剩余 47 分钟" }
```

## 技术设计

### 拦截器位置

```
请求 → RateLimitInterceptor(order=0) → RefreshTokenInterceptor(order=1) → LoginInterceptor(order=2) → Controller
```

被封 IP 在 RateLimitInterceptor 即被拦截，不消耗后续资源。

### Redis Key 设计

| Key | 类型 | 说明 | TTL |
|-----|------|------|-----|
| `ratelimit:seckill:{ip}:{timestamp_seconds}` | String (INCR) | 当前秒的请求计数 | 2s |
| `ratelimit:blacklist:{ip}` | String | 黑名单标记，值为剩余秒数 | 3600s |
| `ratelimit:counter:{ip}` | String (INCR) | 累计被限流次数 | 3600s |

### Lua 脚本：限流 + 计数 + 黑名单判断（原子执行）

```lua
-- KEYS[1]: ratelimit:seckill:{ip}:{timestamp}
-- KEYS[2]: ratelimit:counter:{ip}
-- KEYS[3]: ratelimit:blacklist:{ip}
-- ARGV[1]: 限流阈值 (10)
-- ARGV[2]: 黑名单触发阈值 (30)
-- ARGV[3]: 黑名单 TTL 秒 (3600)
-- 返回值: 0=通过, 1=限流, 2=黑名单命中, 3=限流且已触发黑名单

-- 1. 检查黑名单
local blacklisted = redis.call('EXISTS', KEYS[3])
if blacklisted == 1 then
    return 2
end

-- 2. 限流计数
local count = redis.call('INCR', KEYS[1])
redis.call('EXPIRE', KEYS[1], 2)

if count > tonumber(ARGV[1]) then
    -- 3. 累加被限流计数器
    local total = redis.call('INCR', KEYS[2])
    redis.call('EXPIRE', KEYS[2], tonumber(ARGV[3]))
    
    if total >= tonumber(ARGV[2]) then
        -- 触发黑名单
        redis.call('SETEX', KEYS[3], tonumber(ARGV[3]), '1')
        redis.call('DEL', KEYS[2])
        return 3
    end
    return 1
end

return 0
```

一次 Redis 往返完成：黑名单检查 → 限流计数 → 累计计数 → 自动拉黑，避免多次网络 IO 带来的竞态。

### 黑名单剩余时间计算

拦截器返回时，用 `TTL ratelimit:blacklist:{ip}` 获取剩余秒数，转换为分钟展示给用户。

## 可配置项

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `anti-fraud.rate-limit` | 10 | 每秒每 IP 最大请求数 |
| `anti-fraud.blacklist-threshold` | 30 | 触发黑名单的累计限流次数 |
| `anti-fraud.blacklist-ttl-hours` | 1 | 黑名单封禁时长（小时） |

## 验收标准

- [ ] 同一 IP 每秒第 11 次请求返回 429 + "请求过于频繁"
- [ ] 同一 IP 累计被限流 30 次后自动进黑名单，返回 429 + "已被限制" + 剩余时间
- [ ] 黑名单 1 小时后自动解封，IP 可正常请求
- [ ] 正常 IP 不受影响，非秒杀接口不受拦截
- [ ] 300 并发下拦截器本身不成为瓶颈（通过单测/压测验证）

## 不在此 PRD 范围

- 设备指纹（后续 PRD）
- 用户维度限流（已有"一人一单"覆盖）
- 风控管理后台（暂时通过 Redis 命令行管理）
