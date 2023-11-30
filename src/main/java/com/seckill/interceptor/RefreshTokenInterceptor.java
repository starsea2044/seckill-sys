package com.seckill.interceptor;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.seckill.dto.UserDTO;
import com.seckill.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.seckill.redis.RedisConstants.LOGIN_USER_KEY;
import static com.seckill.redis.RedisConstants.LOGIN_USER_TTL;

/**
* 更新token的拦截器
* @author: xinghai
*/
@Slf4j
public class RefreshTokenInterceptor implements HandlerInterceptor {
    private final StringRedisTemplate stringRedisTemplate;

    public RefreshTokenInterceptor(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 前置拦截器
        String token = request.getHeader("authorization");
        // log.info("当前请求携带的token：{}",token);
        if (StrUtil.isBlank(token)) {
            // 没有携带token，直接放行
            return true;
        }
        // 获取用户信息，判断用户是否存在
        String key = LOGIN_USER_KEY + token;
        Map<Object, Object> userMap = stringRedisTemplate.opsForHash().entries(key);
        // log.info(userMap.toString());
        if (userMap.isEmpty()) {
            return true;
        }
        // 将hash数据转换成userdto
        UserDTO userDTO = BeanUtil.fillBeanWithMap(userMap, new UserDTO(), false);
        // 为什么要保存至threadlocal里？ 方便其他方法使用
        UserHolder.saveUser(userDTO);
        // 刷新token有效期
        stringRedisTemplate.expire(key, LOGIN_USER_TTL, TimeUnit.MINUTES);
        return true; // 所有的都放行，只做刷新token操作
    }
}
