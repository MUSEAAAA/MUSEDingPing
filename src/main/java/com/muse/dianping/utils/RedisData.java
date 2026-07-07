package com.muse.dianping.utils;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class RedisData {
    //逻辑过期时间
    private LocalDateTime expireTime;

    private Object data;
}
