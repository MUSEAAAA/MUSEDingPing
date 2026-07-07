package com.muse.dianping;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.muse.dianping.entity.Shop;
import com.muse.dianping.service.impl.ShopServiceImpl;
import com.muse.dianping.utils.CacheClient;
import com.muse.dianping.utils.RedisWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.muse.dianping.utils.RedisConstants.CACHE_SHOP_KEY;

@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private ShopServiceImpl shopService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private RedisWorker redisWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);
    @Test
    void testIdWorker() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisWorker.nextId("order");
                System.out.println("id: "+id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for(int i = 0 ; i < 300 ; i++){
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("<UNK>"+(end - begin));

    }

    @Test
    void testSavesShop() {
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicExpire(CACHE_SHOP_KEY +1L,shop,10L, TimeUnit.SECONDS);
    }


}
