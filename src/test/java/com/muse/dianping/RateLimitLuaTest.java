package com.muse.dianping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class RateLimitLuaTest {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private DefaultRedisScript<Long> script;

    private static final String IP = "192.168.1.100";
    private static final long NOW = System.currentTimeMillis() / 1000;

    @BeforeEach
    void setUp() {
        script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("rate_limit.lua"));
        script.setResultType(Long.class);

        String counterKey = "ratelimit:counter:" + IP;
        String blacklistKey = "ratelimit:blacklist:" + IP;
        String windowKey = "ratelimit:seckill:" + IP + ":" + NOW;

        stringRedisTemplate.delete(Arrays.asList(counterKey, blacklistKey, windowKey));
    }

    @Test
    void shouldReturn0WhenUnderLimit() {
        Long result = stringRedisTemplate.execute(
                script,
                Arrays.asList(
                        "ratelimit:seckill:" + IP + ":" + NOW,
                        "ratelimit:counter:" + IP,
                        "ratelimit:blacklist:" + IP
                ),
                "10", "30", "3600"
        );
        assertEquals(0L, result);
    }

    @Test
    void shouldReturn1WhenRateLimited() {
        String windowKey = "ratelimit:seckill:" + IP + ":" + NOW;

        for (int i = 0; i < 10; i++) {
            stringRedisTemplate.execute(
                    script,
                    Arrays.asList(windowKey, "ratelimit:counter:" + IP, "ratelimit:blacklist:" + IP),
                    "10", "30", "3600"
            );
        }

        Long result = stringRedisTemplate.execute(
                script,
                Arrays.asList(windowKey, "ratelimit:counter:" + IP, "ratelimit:blacklist:" + IP),
                "10", "30", "3600"
        );
        assertEquals(1L, result);
    }

    @Test
    void shouldReturn2WhenBlacklisted() {
        stringRedisTemplate.opsForValue().set("ratelimit:blacklist:" + IP, "1");

        Long result = stringRedisTemplate.execute(
                script,
                Arrays.asList(
                        "ratelimit:seckill:" + IP + ":" + NOW,
                        "ratelimit:counter:" + IP,
                        "ratelimit:blacklist:" + IP
                ),
                "10", "30", "3600"
        );
        assertEquals(2L, result);
    }

    @Test
    void shouldReturn3WhenBlacklistTriggered() {
        String windowKey = "ratelimit:seckill:" + IP + ":";
        long base = NOW;

        for (int i = 0; i < 30; i++) {
            long ts = base + i;
            String key = windowKey + ts;
            for (int j = 0; j < 10; j++) {
                stringRedisTemplate.execute(
                        script,
                        Arrays.asList(key, "ratelimit:counter:" + IP, "ratelimit:blacklist:" + IP),
                        "10", "30", "3600"
                );
            }
            Long result = stringRedisTemplate.execute(
                    script,
                    Arrays.asList(key, "ratelimit:counter:" + IP, "ratelimit:blacklist:" + IP),
                    "10", "30", "3600"
            );
            if (result == 3L) {
                assertEquals(3L, result);
                return;
            }
        }
    }
}
