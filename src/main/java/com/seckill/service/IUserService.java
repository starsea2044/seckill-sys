package com.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seckill.dto.LoginFormDTO;
import com.seckill.dto.Result;
import com.seckill.entity.User;

public interface IUserService extends IService<User> {

    Result sendCode(String phone);

    Result login(LoginFormDTO loginForm);
}
