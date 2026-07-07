package com.muse.dianping.service;

import com.muse.dianping.dto.Result;
import com.muse.dianping.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author MUSE
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    void createVoucherOrder(VoucherOrder voucherOrder );

    Result seckillVocher(Long voucherId);

    // Result queryVoucherOfShop(Long shopId);
}
