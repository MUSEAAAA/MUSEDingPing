package com.muse.dianping.config;

import com.muse.dianping.utils.LoginInterceptor;
import com.muse.dianping.utils.RateLimitInterceptor;
import com.muse.dianping.utils.RefreshTokenInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.Resource;

@Configuration
public class MvcConfig implements WebMvcConfigurer {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Value("${anti-fraud.rate-limit:10}")
    private int rateLimit;

    @Value("${anti-fraud.blacklist-threshold:30}")
    private int blacklistThreshold;

    @Value("${anti-fraud.blacklist-ttl-seconds:3600}")
    private int blacklistTtlSeconds;

    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(
                        stringRedisTemplate, rateLimit, blacklistThreshold, blacklistTtlSeconds))
                .addPathPatterns("/voucher-order/seckill/**")
                .order(0);

        registry.addInterceptor(new RefreshTokenInterceptor(stringRedisTemplate))
                .addPathPatterns("/**").order(1);

        registry.addInterceptor(new LoginInterceptor())
                .excludePathPatterns(
                        "/shop/**",
                        "/voucher/**",
                        "/shop-type/**",
                        "/upload/**",
                        "/blog/hot",
                        "/user/code",
                        "/user/login"
                ).order(2);
    }
}
