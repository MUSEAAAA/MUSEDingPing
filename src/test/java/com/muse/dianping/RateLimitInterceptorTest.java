package com.muse.dianping;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
class RateLimitInterceptorTest {

    @Autowired
    private MockMvc mockMvc;

    private static StringRedisTemplate mockRedis;

    @TestConfiguration
    static class MockRedisConfig {
        @Bean
        @Primary
        public StringRedisTemplate stringRedisTemplate() {
            if (mockRedis == null) {
                mockRedis = mock(StringRedisTemplate.class);
            }
            return mockRedis;
        }

        @Bean
        @Primary
        public RedissonClient redissonClient() {
            return mock(RedissonClient.class);
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        org.mockito.Mockito.reset(mockRedis);
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        HashOperations<String, Object, Object> hashOps = mock(HashOperations.class);
        when(mockRedis.opsForValue()).thenReturn(ops);
        when(mockRedis.opsForHash()).thenReturn(hashOps);
        when(ops.get(anyString())).thenReturn(null);
        when(mockRedis.hasKey(anyString())).thenReturn(false);
        when(mockRedis.getExpire(anyString(), any(TimeUnit.class))).thenReturn(3600L);
        // mock token refresh: return empty map so refresh interceptor skips, but login interceptor blocks
        Map<Object, Object> tokenMap = new HashMap<>();
        tokenMap.put("id", "1");
        tokenMap.put("nickName", "test");
        when(hashOps.entries(anyString())).thenReturn(tokenMap);
    }

    @Test
    void shouldPassWhenLuaReturns0() throws Exception {
        when(mockRedis.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(0L);

        mockMvc.perform(post("/voucher-order/seckill/1")
                        .header("X-Forwarded-For", "192.168.1.100")
                        .header("authorization", "test-token"))
                .andExpect(status().is(200));
    }

    @Test
    void shouldReturn429WhenLuaReturns1() throws Exception {
        when(mockRedis.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(1L);

        mockMvc.perform(post("/voucher-order/seckill/1")
                        .header("X-Forwarded-For", "192.168.1.100"))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.errorMsg").value("请求过于频繁，请稍后再试"));
    }

    @Test
    void shouldReturn429WhenLuaReturns2() throws Exception {
        when(mockRedis.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(2L);

        mockMvc.perform(post("/voucher-order/seckill/1")
                        .header("X-Forwarded-For", "192.168.1.100"))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.errorMsg").value("您的IP已被限制访问，剩余 60 分钟"));
    }

    @Test
    void shouldReturn429WhenLuaReturns3() throws Exception {
        when(mockRedis.execute(any(DefaultRedisScript.class), anyList(), any()))
                .thenReturn(3L);

        mockMvc.perform(post("/voucher-order/seckill/1")
                        .header("X-Forwarded-For", "192.168.1.100"))
                .andExpect(status().is(429))
                .andExpect(jsonPath("$.errorMsg").value("您的IP已被限制访问，剩余 60 分钟"));
    }
}
