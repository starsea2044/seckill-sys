package com.seckill.kafka;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Slf4j
@Component
public class ProviderService {
    @Resource
    private KafkaTemplate kafkaTemplate;

    public <K,V> void send(String topic, K key, V value) {
        log.info("send to kafka");
        kafkaTemplate.send(topic, key, JSONUtil.toJsonStr(value));
    }
}
