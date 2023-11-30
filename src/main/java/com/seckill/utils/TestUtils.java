package com.seckill.utils;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.lang.UUID;
import com.seckill.dto.UserDTO;
import com.seckill.entity.User;
import com.seckill.service.IUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.seckill.redis.RedisConstants.*;

/**
* 测试-jmeter
* @author: xinghai
*/
@Slf4j
@Component
public class TestUtils {
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IUserService userService;
    /**
    * 创建模拟用户并生成token存入redis
    * @param: number 创建数量
    * @return: void
    * @author: xinghai
    */
    public void createUser(int number) {
        // 创建number个用户
        List<User> userList = new ArrayList<>(number);
        try (FileWriter writer = new FileWriter("user_tokens.txt", true);
             PrintWriter printWriter = new PrintWriter(writer)) {
            for (int i=0; i<number; i++) {
                User user = new User();
                String phone = String.valueOf(13000000000L + i);
                user.setPhone(phone);
                user.setNickName("test-user-"+i);
                user.setCreateTime(LocalDateTime.now());
                user.setUpdateTime(LocalDateTime.now());
                // 插入数据库
                userService.save(user);
                String token = UUID.randomUUID().toString(true);
                UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);

                Map<String, Object> userMap = BeanUtil.beanToMap(userDTO, new HashMap<>(),
                        CopyOptions.create()
                                .setIgnoreNullValue(true)
                                .setFieldValueEditor((fieldName, fileValue) -> fileValue.toString()));
                stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, userMap);
                // 设置有效期
                stringRedisTemplate.expire(LOGIN_USER_KEY + token, LOGIN_USER_TTL, TimeUnit.MINUTES);
                // 写入文件
                printWriter.println(token);
            }
            printWriter.close();
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
