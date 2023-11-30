package com.seckill.redis;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
* Redis全局id生成器
* @author: xinghai
*/
@Component
public class RedisIDWorker {
    // 初始时间戳 2023年1月1日0时0分0秒
    private static final long BEGIN_TIMESTAMP = 1672531200L;
    private static final int SERIAL_NUMBER_BITS = 32;
    private final StringRedisTemplate stringRedisTemplate;
    public RedisIDWorker(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    public long nextId(String prefix) {
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = nowSecond - BEGIN_TIMESTAMP;
        // 序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        // 使用基本类型后面需要计算，不会出现空指针，如果没有key会自动创建
        // 每天一个key，保证序列号不会太大超出范围，同时方便统计
        long serialNumber = stringRedisTemplate.opsForValue().increment("icr:" + prefix + ":" + date);
        // 拼接：为了保证结果是long，时间戳左移<< n位，然后和序列号做或运算
        return (timeStamp << SERIAL_NUMBER_BITS) | serialNumber;
    }
}
