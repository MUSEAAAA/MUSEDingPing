package com.muse.dianping.service;

import com.muse.dianping.dto.Result;
import com.muse.dianping.entity.Voucher;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author MUSE
 * @since 2021-12-22
 */
public interface IVoucherService extends IService<Voucher> {

    Result queryVoucherOfShop(Long shopId);

    void addSeckillVoucher(Voucher voucher);

    //Result seckillVocher(Long voucherId);

}
