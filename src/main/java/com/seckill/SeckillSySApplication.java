package com.seckill;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

@EnableAspectJAutoProxy(exposeProxy = true)
@MapperScan("com.seckill.mapper")
@SpringBootApplication
public class SeckillSySApplication {

    public static void main(String[] args) {
        SpringApplication.run(SeckillSySApplication.class, args);
    }

}
