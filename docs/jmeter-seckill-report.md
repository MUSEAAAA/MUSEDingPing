# JMeter 秒杀压测报告

> 测试日期：2026-07-07
> 测试目标：验证秒杀系统在高并发下不超卖、不重复下单，记录 QPS 与延迟

---

## 测试环境

| 组件 | 配置 |
|------|------|
| CPU | Intel Core (本机) |
| 内存 | 16GB |
| JDK | 21 |
| Spring Boot | 2.3.12 |
| Redis | Memurai (兼容 Redis) |
| MySQL | 8.0 |
| JMeter | 5.6.3 |

## 测试场景

- **接口：** `POST /voucher-order/seckill/12`
- **秒杀券：** 100元优惠券，库存 200 张
- **并发档位：** 100 / 500 / 2000（三组顺序执行）
- **限流策略：** 伪造随机 IP（X-Forwarded-For）绕过单 IP 限流
- **用户凭证：** 1000 个独立 token（一人一单）

### JMeter 配置

| 参数 | 100 并发 | 500 并发 | 2000 并发 |
|------|---------|---------|----------|
| 线程数 | 100 | 500 | 2000 |
| Ramp-up | 3s | 10s | 30s |
| 循环次数 | 1 | 1 | 1 |

每组测试前重置 Redis 库存为 200，清空已购买用户集合。

---

## 测试结果

### 总体概览

```
总请求数:   2600  (100 + 500 + 2000)
HTTP 错误:  0     (0%)
平均延迟:   ~1ms  (Lua 脚本在 Redis 内存中执行)
QPS:        ~60/s (受 ramp-up 限制)
```

### 各档位详情

| 档位 | 请求数 | HTTP 错误 | 平均延迟 | Min | Max |
|------|--------|----------|---------|-----|-----|
| 100 并发 | 100 | 0 | ~2ms | 1ms | 45ms |
| 500 并发 | 500 | 0 | ~1ms | 1ms | 5ms |
| 2000 并发 | 2000 | 0 | ~1ms | 1ms | 5ms |

### 超卖验证

| 检查项 | 预期 | 实际 | 结果 |
|--------|------|------|------|
| Redis 库存消耗 | 200 | 200 | ✅ |
| Redis Stream 消息数 | 200 | 200 | ✅ |
| 已购买用户集合(SCARD) | ≤200 | 200 | ✅ |
| 重复用户 | 0 | 0 | ✅ |
| MySQL 订单数 | 200 | 200 (异步落库) | ✅ |

**结论：零超卖。**

---

## 架构关键点

秒杀请求的完整链路：

```
JMeter(1000线程) → POST /voucher-order/seckill/12
                      ↓
              RateLimitInterceptor (读 X-Forwarded-For 做 IP 限流)
                      ↓
              VoucherOrderController.seckillVoucher()
                      ↓
              Lua 脚本 (Redis 原子执行):
                1. 检查库存 seckill:stock:12
                2. 检查是否已下单 SISMEMBER seckill:order:12
                3. INCRBY stock -1 + SADD order + XADD stream.orders
                      ↓
              返回 0 (成功) / 1 (库存不足) / 2 (重复下单)
                      ↓
              后台线程 XREADGROUP → Redisson 锁 → MySQL INSERT → XACK
```

**为什么延迟只有 1ms：** 秒杀核心逻辑在 Lua 脚本中一次 Redis 往返完成，不经过数据库。真正的数据库写入由后台线程异步消费 Redis Stream 完成。

---

## 已知局限

1. **消费者处理速度：** 单线程消费 Stream，200 条订单全部落库约需 1-2 分钟。高并发场景下可扩展消费者实例。
2. **QPS 受限于 ramp-up：** 本次测试加 ramp 是为了稳定，如果去掉 ramp 或增加线程数，可测得更高 QPS。
3. **JMeter 单机瓶颈：** 2000+ 线程在单台 Windows 上已接近 JMeter 极限，更高并发需分布式 JMeter。

---

## 文件清单

| 文件 | 说明 |
|------|------|
| `E:/develop/jmeter/jmx/秒杀压测.jmx` | 本报告的 JMeter 测试计划 |
| `E:/develop/jmeter/jmx/tokens.txt` | 1000 个测试用户 token |
| `E:/develop/jmeter/jmx/gen_tokens.sh` | Token 生成脚本 |
| `docs/seckill-evolution.md` | 秒杀业务演进文档 |
| `docs/prd/0002-ip-rate-limit-blacklist.md` | 防刷模块 PRD |
