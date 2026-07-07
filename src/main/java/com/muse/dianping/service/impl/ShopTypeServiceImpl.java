package com.muse.dianping.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.muse.dianping.dto.Result;
import com.muse.dianping.entity.ShopType;
import com.muse.dianping.mapper.ShopTypeMapper;
import com.muse.dianping.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author MUSE
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        String key = "cache:shopType:list";
        //返回的是一个Json类型
        String shopType = stringRedisTemplate.opsForValue().get(key);
        //判断是否存在于redis中
        if (StrUtil.isNotBlank(shopType)) {
            List<ShopType> list = JSONUtil.toList(shopType, ShopType.class);
            return Result.ok(list);
        }
        //Redis中不存在内容使用SQL查找数据
        List<ShopType> list = this.list(new LambdaQueryWrapper<ShopType>()
                .orderByAsc(ShopType::getSort));

        //List<ShopType> list = this.list(
        //        new QueryWrapper<ShopType>().orderByAsc("sort")
        //);

        if(list==null||list.size()==0){
            return Result.fail("数据错误，店铺类型不正确");
        }
        stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(list));

        return Result.ok(list) ;


    }
}
