package com.muse.dianping.service.impl;

import com.muse.dianping.entity.UserInfo;
import com.muse.dianping.mapper.UserInfoMapper;
import com.muse.dianping.service.IUserInfoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author MUSE
 * @since 2021-12-24
 */
@Service
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements IUserInfoService {

}
