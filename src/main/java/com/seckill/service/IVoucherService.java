package com.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seckill.dto.Result;
import com.seckill.entity.Voucher;

public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);
}
