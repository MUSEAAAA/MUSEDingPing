package com.muse.dianping.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.muse.dianping.dto.Result;
import com.muse.dianping.entity.SeckillVoucher;
import com.muse.dianping.entity.VoucherOrder;
import com.muse.dianping.mapper.VoucherOrderMapper;
import com.muse.dianping.service.ISeckillVoucherService;
import com.muse.dianping.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.muse.dianping.service.IVoucherService;
import com.muse.dianping.utils.CacheClient;
import com.muse.dianping.utils.RedisWorker;
import com.muse.dianping.utils.SimpleRedisLock;
import com.muse.dianping.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.BeanUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author MUSE
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisWorker redisWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;


    private static final DefaultRedisScript<Long> SECKILL_SCRIPT ;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }
    private ExecutorService SECKTLL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    /*
    启动一个线程，让它去执行 VoucherOrderHandler。
    这个方法现在只是定义了，没有自动执行。
    正常应该加：@PostConstruct否则线程不会启动。
     */
    String queueName = "stream.orders";

    @PostConstruct
    private void init() {
        try {
            stringRedisTemplate.opsForStream().createGroup(queueName, "g1");
        } catch (Exception e) {
            log.debug("Consumer group already exists, skipping creation");
        }
        SECKTLL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {

                    //1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 streams.order >
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    //2.判断消息是否获取成功
                    if (list == null || list.isEmpty()) {
                        //2.1如果获取失败，说明没有消息，继续下一次循环
                         continue;
                    }
                    //解析消息中的订单信息
                    MapRecord<String, Object, Object> mapRecord = list.get(0);
                    Map<Object, Object> value = mapRecord.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    //3如果获取成功,可以下单
                    handleVoucherOrder(voucherOrder);
                    //4. ACK确认 SACK stream.orders g1 id
                    stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",mapRecord.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    handlePendingList();
                }
            }
        }
    }

    private void handlePendingList() {
        while (true) {
            try {
                //1.获取pending_list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 STREAMS streams.order 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create(queueName, ReadOffset.from("0"))
                );
                //2.判断消息是否获取成功
                if (list == null || list.isEmpty()) {
                    //2.1如果获取失败，说明pending_list没有消息，结束下一次循环
                    break;
                }
                //解析消息中的订单信息
                MapRecord<String, Object, Object> mapRecord = list.get(0);
                Map<Object, Object> value = mapRecord.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                //3如果获取成功,可以下单
                handleVoucherOrder(voucherOrder);
                //4. ACK确认 SACK stream.orders g1 id
                stringRedisTemplate.opsForStream().acknowledge(queueName,"g1",mapRecord.getId());
            } catch (Exception e) {
                log.error("处理pending-list订单异常", e);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException ex) {
                    ex.printStackTrace();
                }
            }
        }
    }




    //因为如果 createVoucherOrder() 上加了 @Transactional，必须通过代理对象调用，事务才会生效。
    private  IVoucherOrderService proxy;
    @Override
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //5.一人一单
        //5.1查询订单
        log.info("进入子线程尝试获取 voucherId={}", voucherOrder.getVoucherId());
        Long userId = voucherOrder.getUserId();

        int count = Math.toIntExact(query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherOrder.getVoucherId())
                .count());
        log.info("count值是" + count);
        if (count > 0) {
            //用户已经下过单了
           log.error("已经购买过一次了");
           return;
        }

        //6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")//set stock = stock -1
                .eq("voucher_id",voucherOrder.getVoucherId()).gt("stock", 0)// where id = ? and stock > 0 ? ,voucher.getStock()
                .update();
        if (!success) {
           log.error("库存不足");
           return;
        }
        log.info("voucher_id={}", voucherOrder.getVoucherId());
        save(voucherOrder);

    }

    public Result seckillVocher(Long vocherId) {
        Long userId = UserHolder.getUser().getId();
        long orderId = redisWorker.nextId("order");

        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                vocherId.toString(),
                userId.toString(),
                String.valueOf(orderId)
        );
        int r = result.intValue();
        if(r !=0 ){
            return Result.fail(r==1 ? "库存不足" : "别重复下单");
        }
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }


    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //创建用户
        Long userId = voucherOrder.getUserId();

        RLock lock = redissonClient.getLock("lock:order:" + userId);
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            proxy.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }
}

