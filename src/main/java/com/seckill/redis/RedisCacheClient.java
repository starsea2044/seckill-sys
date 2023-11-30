package com.seckill.redis;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.seckill.redis.RedisConstants.*;

@Slf4j
@Component
public class RedisCacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public RedisCacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        // 把object value序列化为String
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
    }

    /**
    * 将数据存入redis并设置逻辑过期字段
    */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        // 注意时间转换
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        // 把object value序列化为String
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    // TODO: 泛型 ID类型怎么推断
    public <R, ID> R getWithCacheThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        // 优先从redis中查询，如果redis存在直接返回
        String json = stringRedisTemplate.opsForValue().get(keyPrefix + id);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type); // 反序列化
        }
        // 查询结果没有值，但又不是null，即空对象 ”“
        if (json != null) {
            return null;
        }
        // 如果redis中没有，则去查数据库
        // TODO: 查询逻辑交给调用者，函数式编程，有参数有返回值
        R r = dbFallback.apply(id);
        // 如果数据库没有，返回404，如果有则要同时写回redis
        if (r == null) {
            stringRedisTemplate.opsForValue()
                    .set(keyPrefix + id, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        // this.set(keyPrefix + id, JSONUtil.toJsonStr(r), time, unit);
        this.setWithLogicalExpire(keyPrefix + id, r, time, unit);
        return r;
    }

    public <R, ID> R getWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                          Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        // 1. 首先从redis中get数据——json
        // 1.1 如果json是空字符串，说明之前出现过缓存穿透，可以直接返回null了
        // 1.2 如果json是null或者json中的逻辑过期时间已经到了，则查询数据库；
        // 1.2.1 这两种情况重建缓存的操作一样吗？逻辑过期的可以返回过期的数据，但是如果是null？
        // 1.3 如果json的逻辑过期时间未到，正常返回json
        if (StrUtil.isNotBlank(json)) {
            // 取出数据，判断是否过期，如果过期则重建缓存
            // redis存储的数据及结构 {data: Object, expireTime: LocalDateTime}
            RedisData redisData = JSONUtil.toBean(json, RedisData.class);
            // 取出Object，转成Shop
            JSONObject data = (JSONObject) redisData.getData();
            R r = JSONUtil.toBean(data, type);
            // 取出过期时间
            LocalDateTime expireTime = redisData.getExpireTime();
            if (expireTime.isAfter(LocalDateTime.now())) {
                return r;
            }
            // 过期，重建缓存
            // log.info("重建redis数据");
            String lockKey = LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(lockKey);
            if (isLock) { // 获取到锁
                try {
                    // 当前线程会等重建线程结束后再结束吗？从流程图来看不会等待，只要缓存过期，不管是否获取到锁都会返回过期数据
                    // 区别在于获取到锁的会开启线程做重建然后返回过期数据，没获取到锁就直接返回过期数据；
                    // 开启一个独立线程去做缓存重建
                    CACHE_REBUILD_EXECUTOR.submit(()->{
                        // 查询数据库
                        R r1 = dbFallback.apply(id);
                        // 写入redis
                        this.setWithLogicalExpire(key, r1, time, unit);
                    });
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    // 保证锁正常释放
                    unlock(lockKey);
                }
            }
            // 如果没有获取到锁，就直接返回过期数据，不会等待
            return r;
        }
        return null;
    }
    public <R, ID> R getWithMutex(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback,
                                  Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        // 优先从redis中查询，如果redis存在直接返回
        String json = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type); // 反序列化
        }
        // 查询结果没有值，但又不是null，即空对象 ”“
        if (json != null) {
            return null;
        }
        // 缓存重建
        String lockKey = LOCK_SHOP_KEY + id;
        R r = null;
        try {
            boolean isLock = tryLock(lockKey);
            if (!isLock) {
                // 休眠
                Thread.sleep(10);
                return getWithMutex(keyPrefix, id, type, dbFallback, time, unit);
            }
            // 如果redis中没有，则去查数据库
            r = dbFallback.apply(id);
            // 如果数据库没有，返回404，如果有则要同时写回redis
            if (r == null) {
                // 解决缓存穿透
                stringRedisTemplate.opsForValue()
                        .set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            this.set(key, r, time, unit);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            // 释放锁，为什么放在finally里？ 因为不管是否抛出异常都需要释放锁
            unlock(lockKey);
        }
        return r;
    }
    /**
     * 尝试获取锁
     */
    private boolean tryLock(String key) {
        Boolean flag = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        // return flag; 会做拆箱操作，可能出现空指针
        return BooleanUtil.isTrue(flag);
    }

    /**
     * 释放锁
     */
    private void unlock(String key) {
        stringRedisTemplate.delete(key);
    }
}
