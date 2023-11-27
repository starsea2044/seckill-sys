package com.seckill.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private String name; // 业务名称
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    private final StringRedisTemplate stringRedisTemplate;

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    public boolean tryLock(long timeoutSec) {
        String key = KEY_PREFIX + name;
        // ？为什么getId已弃置，改用threadId，但是jdk20编译的时候告诉我没有这个方法？？？
        String value = ID_PREFIX + Thread.currentThread().getId();
        // String value = ID_PREFIX + Thread.currentThread().threadId(); // getId()在jdk19被弃用了 @Deprecated(since="19")
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);
        // public static final Boolean TRUE = new Boolean(true);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unlock() {
        String key = KEY_PREFIX + name;
        // String currentThreadId = ID_PREFIX + Thread.currentThread().threadId();
        String currentThreadId = ID_PREFIX + Thread.currentThread().getId();
        // 调用lua脚本，提前准备好脚本，不需要每次都通过io流读取
        // execute(RedisScript<T> script, List<K> keys, Object... args)
        // Collections.singletonList(key) 返回一个包含单个元素的不可变集合
        stringRedisTemplate.execute(
                UNLOCK_SCRIPT,
                Collections.singletonList(key),
                currentThreadId
        );
    }
    // @Override
    // public void unlock() {
    //     String key = KEY_PREFIX + name;
    //     String currentThreadId = ID_PREFIX + Thread.currentThread().threadId();
    //     String id = stringRedisTemplate.opsForValue().get(key);
    //     if (currentThreadId.equals(id)) {
    //         stringRedisTemplate.delete(key);
    //     }
    // }
}
