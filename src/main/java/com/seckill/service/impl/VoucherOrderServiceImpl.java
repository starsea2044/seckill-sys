package com.seckill.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.seckill.dto.Result;
import com.seckill.dto.SeckillOrderDTO;
import com.seckill.entity.VoucherOrder;
import com.seckill.kafka.KafkaConstants;
import com.seckill.kafka.ProviderService;
import com.seckill.mapper.VoucherOrderMapper;
import com.seckill.redis.RedisIDWorker;
import com.seckill.service.ISeckillVoucherService;
import com.seckill.service.IVoucherOrderService;
import com.seckill.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;

@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private RedisIDWorker idWorker; // 全局id生成器
    @Resource
    private StringRedisTemplate stringRedisTemplate; // redis操作
    @Resource
    private ProviderService providerService; // kafka生产者
    @Resource
    private ISeckillVoucherService seckillVoucherService; // 秒杀券服务
    @Resource
    private RedissonClient redissonClient; // redisson

    // lua脚本：实现扣减库存和创建订单加入消息队列
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class); // 返回值类型
    }
    private IVoucherOrderService proxy; // 当前对象代理

    /**
    * Kafka消费者——监听异步下单事件
    * @param: record
    * @return: void
    * @author: xinghai
    */
    @KafkaListener(topics = {KafkaConstants.SECKILL_ORDER_TOPIC})
    public void onMessage(ConsumerRecord<String, String> record) {
        log.info("kafka消费："+record.topic()+"-"+record.partition()+"-"+ record.value()); // 主题+分区+消息值
        SeckillOrderDTO seckillOrderDTO = JSONUtil.toBean(record.value(), SeckillOrderDTO.class);
        // 下单
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setVoucherId(seckillOrderDTO.getVoucherId());
        voucherOrder.setUserId(seckillOrderDTO.getUserId());
        voucherOrder.setId(seckillOrderDTO.getOrderId());
        handlerVoucherOrder(voucherOrder);
    }
    /**
    * 下单（异步部分）
    * @param: voucherOrder
    * @return: void
    * @author: xinghai
    */
    private void handlerVoucherOrder(VoucherOrder voucherOrder) {
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
        } finally {
            lock.unlock();
        }
    }
    /**
    * 秒杀（第一步）
    * @param: voucherId
    * @return: com.seckill.dto.Result
    * @author: xinghai
    */
    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Long orderId = idWorker.nextId("order");
        // 1. 判断秒杀资格，发送到消息队列
        Long resultCode = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString(),
                orderId.toString()
        );
        int r = resultCode.intValue();
        // 2.1 不满足秒杀下单条件，直接返回
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足":"重复下单");
        }
        // 2.2 满足秒杀下单条件，把下单事件存入消息队列
        SeckillOrderDTO seckillOrderDTO = new SeckillOrderDTO();
        seckillOrderDTO.setOrderId(orderId);
        seckillOrderDTO.setVoucherId(voucherId);
        seckillOrderDTO.setUserId(userId);
        providerService.send(KafkaConstants.SECKILL_ORDER_TOPIC, KafkaConstants.SECKILL_ORDER_KEY, seckillOrderDTO);
        // 3. 拿到当前对象的代理
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    /**
    * 创建订单
    * @param: voucherOrder
    * @return: void
    * @author: xinghai
    */
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
                .gt("stock", 0) // 乐观锁保证不超卖
                .update();
        if (!success) {
            log.error("库存不足");
            return;
        }
        // 创建订单
        save(voucherOrder);
    }
}
