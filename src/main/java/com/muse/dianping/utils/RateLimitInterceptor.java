package com.muse.dianping.utils;

import cn.hutool.json.JSONUtil;
import com.muse.dianping.dto.Result;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;
    private final int rateLimit;
    private final int blacklistThreshold;
    private final int blacklistTtlSeconds;

    private static final DefaultRedisScript<Long> SCRIPT;

    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setLocation(new ClassPathResource("rate_limit.lua"));
        SCRIPT.setResultType(Long.class);
    }

    public RateLimitInterceptor(StringRedisTemplate stringRedisTemplate,
                                int rateLimit,
                                int blacklistThreshold,
                                int blacklistTtlSeconds) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.rateLimit = rateLimit;
        this.blacklistThreshold = blacklistThreshold;
        this.blacklistTtlSeconds = blacklistTtlSeconds;
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {
        String ip = getClientIp(request);
        long now = System.currentTimeMillis() / 1000;

        String windowKey = "ratelimit:seckill:" + ip + ":" + now;
        String counterKey = "ratelimit:counter:" + ip;
        String blacklistKey = "ratelimit:blacklist:" + ip;

        Long result = stringRedisTemplate.execute(
                SCRIPT,
                Arrays.asList(windowKey, counterKey, blacklistKey),
                String.valueOf(rateLimit),
                String.valueOf(blacklistThreshold),
                String.valueOf(blacklistTtlSeconds)
        );

        if (result != null && result != 0L) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(429);

            if (result == 1L) {
                response.getWriter().write(JSONUtil.toJsonStr(
                        Result.fail("请求过于频繁，请稍后再试")
                ));
            } else {
                Long ttl = stringRedisTemplate.getExpire(blacklistKey, TimeUnit.SECONDS);
                long remainingMinutes = (ttl != null && ttl > 0) ? ttl / 60 : 60;
                response.getWriter().write(JSONUtil.toJsonStr(
                        Result.fail("您的IP已被限制访问，剩余 " + remainingMinutes + " 分钟")
                ));
            }
            return false;
        }

        return true;
    }

    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
