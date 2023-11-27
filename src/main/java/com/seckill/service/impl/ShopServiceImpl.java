package com.seckill.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seckill.dto.Result;
import com.seckill.entity.Shop;
import com.seckill.mapper.ShopMapper;
import com.seckill.service.IShopService;
import com.seckill.utils.RedisCacheClient;
import com.seckill.utils.RedisData;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.seckill.utils.RedisConstants.*;

/**
* 商铺服务实现类
* @author: xinghai
*/
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisCacheClient cacheClient;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    @Override
    public Result queryById(Long id) {
        // 解决缓存穿透
        // return Result.ok(queryWithCacheThrough(id));
        // 解决缓存击穿——逻辑过期
        return Result.ok(queryWithLogicalExpire(id));
    }
    /**
    * 处理缓存击穿的查询——互斥锁
    * @param: id
    * @return: com.seckill.entity.Shop
    * @author: xinghai
    */
    public Shop queryWithMutex(Long id) {
        return cacheClient.getWithMutex(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }
    /**
    * 处理缓存击穿的查询——逻辑过期
    * @param: id
    * @return: com.seckill.entity.Shop
    * @author: xinghai
    */
    public Shop queryWithLogicalExpire(Long id) {
        return cacheClient.getWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }
    /**
    * 处理缓存穿透的查询
    * @param: id
    * @return: com.seckill.entity.Shop
    * @author: xinghai
    */
    public Shop queryWithCacheThrough(Long id) {
        // id2->getById(id2) id2防止和id冲突，随意定义。
        // TODO: 简写 this::getById
        return cacheClient.getWithCacheThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);
    }
    @Override
    @Transactional
    public Result update(Shop shop) {
        // 先更新数据库，再删除缓存，保证原子性
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id为空");
        }
        String key = CACHE_SHOP_KEY + id;
        updateById(shop);
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    /**
     * 逻辑过期——重建缓存
     * @param: id
     * @param: expireSeconds
     * @return: void
     * @author: xinghai
     */
    public void saveShop2Redis(Long id, Long expireSeconds) {
        // 查询数据库
        Shop shop = getById(id);
        // 封装含逻辑过期时间的数据
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 写入redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

}
