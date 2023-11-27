package com.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seckill.dto.Result;
import com.seckill.entity.Shop;

public interface IShopService extends IService<Shop> {

    Result queryById(Long id);

    Result update(Shop shop);
}
