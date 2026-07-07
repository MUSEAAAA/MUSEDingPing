package com.muse.dianping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.TestPropertySource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class AntiFraudConcurrentTest {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final DefaultRedisScript<Long> SCRIPT;

    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new org.springframework.core.io.ClassPathResource("rate_limit.lua"));
        SCRIPT.setResultType(Long.class);
    }

    private static final String TEST_IP = "10.0.0.99";
    private static final int RATE_LIMIT = 10;
    private static final int BLACKLIST_THRESHOLD = 30;
    private static final int BLACKLIST_TTL = 3600;

    @BeforeEach
    void cleanKeys() {
        stringRedisTemplate.delete(stringRedisTemplate.keys("ratelimit:*"));
    }

    @AfterEach
    void cleanKeysAfter() {
        stringRedisTemplate.delete(stringRedisTemplate.keys("ratelimit:*"));
    }

    @Test
    void shouldLimitConcurrentRequestsAccurately() throws InterruptedException {
        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger passed = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);

        long timestamp = System.currentTimeMillis() / 1000;
        String windowKey = "ratelimit:seckill:" + TEST_IP + ":" + timestamp;
        String counterKey = "ratelimit:counter:" + TEST_IP;
        String blacklistKey = "ratelimit:blacklist:" + TEST_IP;

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                Long result = stringRedisTemplate.execute(
                        SCRIPT,
                        Arrays.asList(windowKey, counterKey, blacklistKey),
                        String.valueOf(RATE_LIMIT),
                        String.valueOf(BLACKLIST_THRESHOLD),
                        String.valueOf(BLACKLIST_TTL)
                );
                if (result != null && result == 0L) {
                    passed.incrementAndGet();
                } else {
                    rejected.incrementAndGet();
                }
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        assertTrue(passed.get() <= RATE_LIMIT,
                "50 并发中最多 " + RATE_LIMIT + " 个通过，实际通过: " + passed.get());
        assertTrue(rejected.get() > 0,
                "超出限流的请求应该被拒绝");
    }

    @Test
    void shouldTriggerBlacklistAutomatically() throws InterruptedException {
        int threads = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Long> allResults = Collections.synchronizedList(new ArrayList<>());

        long baseTime = System.currentTimeMillis() / 1000;

        for (int round = 0; round < 30; round++) {
            CountDownLatch latch = new CountDownLatch(threads);
            long ts = baseTime + round;
            String windowKey = "ratelimit:seckill:" + TEST_IP + ":" + ts;
            String counterKey = "ratelimit:counter:" + TEST_IP;
            String blacklistKey = "ratelimit:blacklist:" + TEST_IP;

            for (int i = 0; i < threads; i++) {
                executor.submit(() -> {
                    Long result = stringRedisTemplate.execute(
                            SCRIPT,
                            Arrays.asList(windowKey, counterKey, blacklistKey),
                            "1",
                            String.valueOf(BLACKLIST_THRESHOLD),
                            String.valueOf(BLACKLIST_TTL)
                    );
                    allResults.add(result);
                    latch.countDown();
                });
            }
            latch.await();
        }

        executor.shutdown();

        boolean blacklisted = allResults.stream().anyMatch(r -> r != null && r == 3L);
        assertTrue(blacklisted,
                "累计限流 " + BLACKLIST_THRESHOLD + " 次后应触发黑名单（返回 3），实际结果: " + allResults);
    }

    @Test
    void shouldBlockAfterBlacklisted() throws InterruptedException {
        String blacklistKey = "ratelimit:blacklist:" + TEST_IP;
        stringRedisTemplate.opsForValue().set(blacklistKey, "1");

        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        AtomicInteger passed = new AtomicInteger(0);
        AtomicInteger blacklisted = new AtomicInteger(0);

        long timestamp = System.currentTimeMillis() / 1000;

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                Long result = stringRedisTemplate.execute(
                        SCRIPT,
                        Arrays.asList(
                                "ratelimit:seckill:" + TEST_IP + ":" + timestamp,
                                "ratelimit:counter:" + TEST_IP,
                                blacklistKey
                        ),
                        String.valueOf(RATE_LIMIT),
                        String.valueOf(BLACKLIST_THRESHOLD),
                        String.valueOf(BLACKLIST_TTL)
                );
                if (result != null && result == 0L) {
                    passed.incrementAndGet();
                } else if (result != null && result == 2L) {
                    blacklisted.incrementAndGet();
                }
                latch.countDown();
            });
        }

        latch.await();
        executor.shutdown();

        assertEquals(0, passed.get(), "黑名单中的 IP 不应有任何请求通过");
        assertEquals(threads, blacklisted.get(),
                "黑名单中的 IP 所有请求都应返回 2");
    }
}
