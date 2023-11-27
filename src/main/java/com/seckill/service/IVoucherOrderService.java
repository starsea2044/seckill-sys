package com.seckill.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.seckill.dto.Result;
import com.seckill.entity.VoucherOrder;

public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    void createVoucherOrder(VoucherOrder voucherOrder);
}
