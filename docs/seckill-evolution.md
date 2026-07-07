# 秒杀业务演进

> 本文档记录了 hm-dianping 项目中秒杀下单和缓存策略的多个演进版本。
> 每个版本都是真实开发过程中编写过的代码，保留在此以便理解技术决策的来龙去脉。

---

## 一、秒杀下单演进

### 阶段 1：BlockingQueue 异步下单（已废弃）

**来源：** `VoucherOrderServiceImpl.java`

**思路：** Lua 脚本扣库存成功后，将订单对象丢入 JVM 内存中的 `BlockingQueue`，后台单线程从队列取出订单异步写入数据库。

```java
// 这两个是异步下单的核心。
// orderTask
// 这是一个阻塞队列，用来暂存订单任务。
// 主线程秒杀成功后，不直接创建订单，而是把订单对象扔进这里。
// SECKTLL_ORDER_EXECUTOR
// 这是一个单线程线程池。
// 它会启动一个子线程，不停地从队列里取订单处理。
private BlockingQueue<VoucherOrder> orderTask = new ArrayBlockingQueue<>(1024*1024);

// 死循环一直运行
// 从 orderTask 队列里取订单
// 调用 handleVoucherOrder(voucherOrder) 去处理订单
// 这里的 orderTask.take() 很重要：
// 如果队列里没有数据，它会阻塞等待
// 有订单进来，它就继续执行
private class VoucherOrderHandler implements Runnable {
    @Override
    public void run() {
        while(true){
            try {
                //1.获取队列中的订单信息
                VoucherOrder voucherOrder = orderTask.take();
                //2. 创建订单
                handleVoucherOrder(voucherOrder);
            } catch (Exception e) {
                log.error("处理订单异常",e);
            }
        }
    }
}

// seckillVocher 方法 — 将订单信息加入阻塞队列
//1. 执行lua脚本
Long userId = UserHolder.getUser().getId();
Long result = stringRedisTemplate.execute(
        SECKILL_SCRIPT,
        Collections.emptyList(),
        vocherId.toString(),
        userId.toString());
//2. 购买资格判断
//2.1.不为0。代表没有购买资格
int r = result.intValue();
if (r != 0) {
   return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
}
//2.2为0，有购买资格，把下单信息保存到阻塞队列
// 7.创建订单
VoucherOrder voucherOrder = new VoucherOrder();
//7.1 获取一份订单ID,全局独一份
Long orderId = redisWorker.nextId("order");
voucherOrder.setId(orderId);
//7.2用户ID
voucherOrder.setUserId(userId);
//7.3 代金倦ID
voucherOrder.setVoucherId(vocherId);

orderTask.add(voucherOrder);
//3.获取代理对象
proxy = (IVoucherOrderService) AopContext.currentProxy();
//3.返回订单ID
return Result.ok(orderId);
```

**废弃原因：** `BlockingQueue` 存在于 JVM 堆内存中，服务重启或宕机时队列中的订单全部丢失，无法恢复。

---

### 阶段 2：纯数据库操作 + 分布式锁（已废弃）

**来源：** `VoucherOrderServiceImpl.java`

**思路：** 不使用 Redis Stream，直接在 Java 层校验秒杀时间、库存，然后通过 `@Transactional` 扣库存 + 创建订单，用 Redisson 分布式锁保证一人一单。

```java
//1. 查询优惠卷
SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
//2. 判断秒杀是否开始
if (voucher.getBeginTime().isAfter(LocalDateTime.now())){
    //尚未开始
    return Result.fail("活动暂未开始");
}
//3.判断秒杀是否结束
if (voucher.getEndTime().isBefore(LocalDateTime.now())){
    //尚未开始
    return Result.fail("活动结束了小子");
}
//4.判断库存是否充足
if (voucher.getStock()<1){
    return Result.fail("库存不足！");
}
Long userId = UserHolder.getUser().getId();

//这一条是单机使用的锁
//synchronized(userId.toString().intern()) {//}

//创建锁对象
//SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
//这个Rlock这个类不太清楚，回头找找看看
RLock lock = redissonClient.getLock("lick:order:" + userId);
//获取锁
boolean islock = lock.tryLock();
if (!islock){
    //获取锁失败,返回错误
    return Result.fail("一个人只允许下一单");
}
//获取事务有关代理对象
try {
    IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
    return proxy.createVoucherOrder(voucherId);
} catch (IllegalStateException e) {
    throw new RuntimeException(e);
}finally {
    //释放锁
    lock.unlock();
}

@Transactional
public Result createVoucherOrder(Long voucherId) {
    //5.一人一单
    //5.1查询订单
    Long userId = UserHolder.getUser().getId();

    int count = Math.toIntExact(query()
            .eq("user_id", userId)
            .eq("voucher_id", voucherId)
            .count());
    if (count > 0) {
        //用户已经下过单了
        return Result.fail("已经购买过了");
    }

    //6.扣减库存
    boolean success = seckillVoucherService.update()
            .setSql("stock = stock - 1")//set stock = stock -1
            .eq("voucher_id", voucherId).gt("stock", 0)// where id = ? and stock > 0 ? ,voucher.getStock()
            .update();
    if (!success) {
        return Result.fail("添加失败");
    }

    //7.创建订单
    VoucherOrder voucherOrder = new VoucherOrder();
    //7.1 获取一份订单ID,全局独一份
    Long orderId = redisWorker.nextId("order");
    voucherOrder.setId(orderId);
    //7.2用户ID
    voucherOrder.setUserId(userId);
    //7.3 代金倦ID
    voucherOrder.setVoucherId(voucherId);
    save(voucherOrder);
    //7返回订单Id
    return Result.ok(orderId);
}
```

**废弃原因：** 每次请求都直连数据库查询+扣库存，高并发时 MySQL 连接池打满，QPS 极低。`synchronized` 版本的锁只能用于单机，分布式部署时失效。

---

### 阶段 3：Lua 脚本 + Redis Stream 消息队列（当前方案）

这是当前生产代码中的实现，详见 `VoucherOrderServiceImpl.java` 和 `seckill.lua`。

**流程：**
```
用户请求 → Lua脚本(Redis原子执行：检查库存+一人一单+扣库存+XADD消息)
              ↓ 成功返回 0
         后台线程 XREADGROUP 消费 Stream 消息 → Redisson锁 → 写入MySQL → XACK
              ↓ 异常
         handlePendingList() → 重新消费未ACK的消息
```

**关键改进：** Redis Stream 持久化消息，服务重启不丢单；消费者组 + ACK 保证可靠消费；Lua 脚本保证原子性避免超卖。

---

## 二、缓存策略演进

**来源：** `ShopServiceImpl.java`

### 阶段 1：缓存穿透方案 — queryWithPassThrough()（已废弃独立实现）

**思路：** 查询 Redis → 未命中查 DB → DB 有数据则写缓存，DB 无数据则缓存空值防穿透。

```java
public Shop queryWithPassThrough(Long id){
    {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis中查询是否存在商铺ID
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在直接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        if (shopJson != null) {
            return null;
        }

        //4.不存在，根据id查询数据库
        Shop shop = getById(id);
        //5.不存在返回错误
        if (shop == null) {
            //空值写入redis
            stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //6.存在写如redis
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);

        return shop;
    }
}
```

**废弃原因：** 功能已迁移到通用工具类 `CacheClient.queryWithPassThrough()`，使用泛型 + 函数式接口，避免每个实体都写重复代码。

---

### 阶段 2：缓存击穿方案（互斥锁）— queryWithMutex()（已废弃独立实现）

**思路：** 缓存未命中时，用 SETNX 获取互斥锁，拿到锁的线程查 DB 重建缓存，未拿到的休眠重试。

```java
public Shop queryWithMutex(Long id){
    {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis中查询是否存在商铺ID
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3.存在直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {
            return null;
        }

        //4.实现缓存重建
        //4.1获取互斥锁
        String locKey = "lock:shop:" + id;
        Shop shop = null;
        boolean isLock =false;
        try {
             isLock = tryLock(locKey);
            //4.2判断是否成功获取
            if (!isLock) {
                Thread.sleep(50);
                return queryWithMutex(id);
                //TODO 这里使用递归可能不太好，回头看看能不能使用别的方法
            }
            //4.3失败，则休眠并重试
            //4.4成功，根据id查询数据库
            shop = getById(id);
            //模拟重建延时
            Thread.sleep(200);
            //5.不存在返回错误
            if (shop == null) {
                //空值写入redis
                stringRedisTemplate.opsForValue().set(key,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //6.存在写如redis
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        finally {
           if (isLock) {
               unlock(locKey);
           }
        }
        return shop;
    }
}

private boolean tryLock(String key){
    Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
    return BooleanUtil.isTrue(flag);
}

private void unlock(String key){
    stringRedisTemplate.delete(key);
}
```

**废弃原因：** 功能同样已迁移到 `CacheClient`。互斥锁方案虽然简单，但未拿到锁的线程需要自旋/递归重试，浪费 CPU 且用户体验差（请求阻塞等待）。

---

### 阶段 3：缓存击穿方案（逻辑过期）— queryWithLogicalExpire()（已废弃独立实现）

**思路：** Redis key 物理不过期，在 value 中存储"逻辑过期时间"。发现数据逻辑过期后，拿到锁的线程异步重建缓存，其他线程返回旧数据，实现"最终一致 + 零阻塞"。

```java
private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

public Shop queryWithLogicalExpire(Long id){
    {
        String key = CACHE_SHOP_KEY + id;
        //1.从redis中查询是否存在商铺ID
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否存在
        if (StrUtil.isBlank(shopJson)) {
            //3.不存在直接返回null
            return null;
        }
        //4.命中数据，需要先把json反序列为对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5.判断是否过期

        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1未过期，直接返回店铺信息
            return shop;
        }

        //5.2已过期，需要缓存重建
        //6.缓存重建
        //6.1获取互斥锁
        String lockyKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockyKey);

        // 6.2判断是否是否获取锁成功
        if (isLock) {
            //TODO 6.3成功，开启独立线程,实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //重建缓存
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }finally {
                    //释放锁
                    unlock(lockyKey);
                }
            });
        }
        //6.4返回过期的店铺信息
        return shop;
    }
}

public void saveShop2Redis(Long id, Long expirSeconds) throws InterruptedException {
    //1.查询店铺数据
    Shop shop = getById(id);
    Thread.sleep(200);
    //2.封装逻辑过期时间
    RedisData redisData = new RedisData();
    redisData.setData(shop);
    redisData.setExpireTime(LocalDateTime.now().plusSeconds(expirSeconds));
    //3.写入redis
    stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY+id, JSONUtil.toJsonStr(redisData));
}
```

**废弃原因：** 功能已迁移到 `CacheClient.queryWithLogicalExpire()`。独立的 Service 层实现代码量大且不可复用。

---

### 当前方案：CacheClient 统一封装

现在 `ShopServiceImpl.queryById()` 直接调用 `CacheClient`：

```java
// 逻辑过期方案（用于热点店铺）
Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, 10L, TimeUnit.SECONDS);

// 缓存穿透方案（普通查询）
shop = cacheClient.queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, 10L, TimeUnit.SECONDS);
```

`CacheClient` 使用泛型 + `Function<T, R>` 函数式接口，使得所有实体的缓存策略都能复用同一套代码。
