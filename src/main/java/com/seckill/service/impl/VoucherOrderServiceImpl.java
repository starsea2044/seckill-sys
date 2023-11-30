package com.seckill.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seckill.dto.Result;
import com.seckill.entity.VoucherOrder;
import com.seckill.mapper.VoucherOrderMapper;
import com.seckill.redis.RedisIDWorker;
import com.seckill.service.ISeckillVoucherService;
import com.seckill.service.IVoucherOrderService;
import com.seckill.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedisIDWorker idWorker; // 全局id生成器
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedissonClient redissonClient; // redisson

    // lua脚本：实现扣减库存和创建订单加入消息队列
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class); // 返回值类型
    }
    // 异步下单线程
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    private IVoucherOrderService proxy;
    // 类初始化即开始执行下单功能，循环从消息队列中取订单
    // @PostConstruct
    // private void init() {
    //     SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    // }

    private class VoucherOrderHandler implements Runnable {
        String queueName = "stream.orders";
        @Override
        public void run() {
            while (true) {
                try {
                    // 监听消息队列
                    // 判断是否获取到消息，如果有则下单，如果没有则进行下一次循环
                    // public static ReadOffset lastConsumed() { return new ReadOffset(">");}
                    // 这里是多消费者吗？
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.lastConsumed())
                    );
                    if (list == null || list.isEmpty()) {
                        continue;
                    }
                    log.info("获取到消息");
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handlerVoucherOrder(voucherOrder);
                    // 下单结束，ack；XACK
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());

                } catch (Exception e) {
                    log.error("处理订单出现异常", e);
                    // 出现异常（处理消息时出现异常），需要额外处理pending-list
                    handlePendingList();
                }
            }
        }
        private void handlePendingList() {
            while (true) {
                try {
                    // 获取pending-list中的消息
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create(queueName, ReadOffset.from("0"))
                    );
                    if (list == null || list.isEmpty()) {
                        // pending-list中没有消息，直接结束
                        break;
                    }
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> values = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(values, new VoucherOrder(), true);
                    handlerVoucherOrder(voucherOrder);
                    // 下单结束，ack；XACK
                    stringRedisTemplate.opsForStream().acknowledge(queueName, "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理pending-list出现异常", e);
                    // 如果处理pending-list时也出现异常，会直接进行下次循环
                    // 可以设置线程休眠避免循环次数过多
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ex) {
                        ex.printStackTrace();
                    }
                }
            }
        }
    }

    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
        log.info("写数据库");
        // Long userid = UserHolder.getUser().getId();
        // 因为是新线程处理写订单的过程，所以无法从threadLocal里获取userId
        Long userId = voucherOrder.getUserId();
        // 冗余判断，做兜底
        // 创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        // 获取锁
        boolean isLock = lock.tryLock();
        if (!isLock) {
            log.error("获取锁失败");
            return;
        }
        try {
            // 这里同userId，currentProxy也是基于threadLocal，新线程无法获取
            proxy.createVoucherOrder(voucherOrder);
            // return proxy.createVoucherOrder(voucherOrder);
        } finally {
            lock.unlock();
        }
    }
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = idWorker.nextId("order");
        // log.info(voucherId.toString());
        // 判断秒杀资格，发送到消息队列
        Long resultCode = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString()
        );
        int r = resultCode.intValue(); // 可能空指针？
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足":"重复下单");
        }
        // 预定一个订单号
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        Long userid = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        Long count = query().eq("user_id", userid).eq("voucher_id", voucherId).count();
        // 冗余判断
        if (count >= 1) {
            log.error("重复下单");
            return;
        }
        // 扣减库存
        // 两个update()不一样，第一个返回updatewrapper，第二个是void方法
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock -1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0) // CAS算法
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }
        // 创建订单
        save(voucherOrder);
    }
}
