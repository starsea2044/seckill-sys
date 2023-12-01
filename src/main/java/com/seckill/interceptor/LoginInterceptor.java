package com.seckill.interceptor;

import com.seckill.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@Slf4j
public class LoginInterceptor implements HandlerInterceptor {
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // refresh拦截器已经做了用户token校验，这里只需要判断用户校验结果即可
        if (UserHolder.getUser() == null) {
            response.setStatus(401);
            log.info("拦截请求");
            // log.info("当前线程id={}", Thread.currentThread().getId());
            return false; // 拦截
        }
        // log.info("放行");
        // log.info("当前线程id={}", Thread.currentThread().getId());
        return true; // 放行
    }
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        // log.info("afterCompletion执行UserHolder数据清除工作");
        UserHolder.removeUser();
    }
}
