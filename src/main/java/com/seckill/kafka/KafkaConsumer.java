package com.seckill.kafka;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
@Slf4j
@Component
public class KafkaConsumer {
    @KafkaListener(topics = {"topic1"})
    public void onMessage(ConsumerRecord<?,?> record) {
        log.info("kafka消费："+record.topic()+"-"+record.partition()+"-"+record.value());
    }
}
