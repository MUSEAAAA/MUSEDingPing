package com.muse.dianping.controller;


import com.muse.dianping.dto.Result;
import com.muse.dianping.entity.ShopType;
import com.muse.dianping.service.IShopTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import java.util.List;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author MUSE
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @GetMapping("/list")
    public Result queryTypeList() {
        log.info("进入方法queryTypeList()");
//        List<ShopType> typeList = typeService
//                .query().orderByAsc("sort").list();
        return typeService.queryTypeList();
    }
}
