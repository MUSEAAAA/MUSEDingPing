package com.muse.dianping.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.muse.dianping.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.sql.Time;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.muse.dianping.utils.RedisConstants.*;

@Slf4j
@Component

public class CacheClient {

    private final StringRedisTemplate stringRedisTemplate;
    //构造器注入
    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    //将任意 Java 对象序列化为 JSON 字符串后存入 Redis，并设置 TTL 过期时间，适用于普通缓存数据写入。
    public void set(String key, Object value, Long time , TimeUnit unit) {
                stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }
    //实现逻辑过期将数值存入设置逻辑过期时间，存入RedisDate中，一般用于处理热门事件
    public void setWithLogicExpire(String key, Object value, Long time,TimeUnit unit) {
        //写入RedisData
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        //写入Redis
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    //处理穿透问题，如果值不存在则往数据库中返回一个空值但用户查找时返回一个空值
    public <R,ID> R queryWithPassThrough(
            String keyPrefix, ID id , Class<R> type, Function<ID,R> dbFallback,
            Long time,TimeUnit unit) {
        {
            String key = keyPrefix + id;
            //1.从redis中查询是否存在商铺ID
            String json = stringRedisTemplate.opsForValue().get(key);
            //2.判断是否存在
            if (StrUtil.isNotBlank(json)) {
                //3.存在直接返回
                return JSONUtil.toBean(json, type);
            }
            // 2) 命中但为空串：说明之前缓存了空值（防穿透）
            if ("".equals(json)) {   // 关键：明确判断空串
                return null;
            }

            //4.不存在，根据id查询数据库
            R r = dbFallback.apply(id);
            //5.不存在返回错误
            if (r == null) {
                //空值写入redis
                stringRedisTemplate.opsForValue().set(key, "", 2L, TimeUnit.MINUTES);
                return null;
            }
            //6.存在写如redis
            this.set(key, r, time, unit);

            return r;
        }
    }
    //开启线程池
    private  static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    //防止缓存击穿，先查看RedisData中的数据是否逻辑过期，如果逻辑过期则使用一个线程实现缓存重建
    //使用线程锁tryLock去实现缓存重建，这个里面方法是setIfAbsent来查询这个里面是否存在来实现线程锁
    //如果线程过期了，那么先返回一个旧的数据给他先
    public <R,ID>R queryWithLogicalExpire(String keyPrefix,ID id,Class<R> type,Function<ID,R> dbFallback, Long time,TimeUnit unit) {
        {
            String key = keyPrefix + id;
            //1.从redis中查询是否存在商铺ID
            String json = stringRedisTemplate.opsForValue().get(key);
            //2.判断是否存在
            if (StrUtil.isBlank(json)) {
                //3.不存在直接返回null
                return null;
            }
            //4.命中数据，需要先把json反序列为对象
            RedisData redisData = JSONUtil.toBean(json,RedisData.class );
            //JSONObject data = (JSONObject) redisData.getData();
            R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
            LocalDateTime expireTime = redisData.getExpireTime();
            //5.判断是否过期

            if (expireTime.isAfter(LocalDateTime.now())) {
                //5.1未过期，直接返回店铺信息
                return r;
            }

            //5.2已过期，需要缓存重建
            //6.缓存重建
            //6.1获取互斥锁
            String lockyKey = LOCK_SHOP_KEY +id ;
            boolean isLock =tryLock(lockyKey);

            // 6.2判断是否是否获取锁成功
            if (isLock) {
                //TODO 6.3成功，开启独立线程,实现缓存重建
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        //重建缓存
                        //查询数据库，这是一个TM的函数接口，用来查数据库的
                        R r1 = dbFallback.apply(id);
                        //写入Redis
                        this.setWithLogicExpire(key,r1,time,unit);

                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }finally {
                        //释放锁
                        unlock(lockyKey);
                    }
                });
            }
            //6.4返回过期的店铺信息
            return r;
        }

    }

    private boolean tryLock(String key){
        //如果这个锁 key 不存在，就写进去，并返回 true
        //如果已经存在，说明别人已经加锁了，返回 false
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }
    private void unlock(String key){
        stringRedisTemplate.delete(key);
    }
}
