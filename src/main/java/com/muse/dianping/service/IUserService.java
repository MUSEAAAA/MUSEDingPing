package com.muse.dianping.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.muse.dianping.dto.LoginFormDTO;
import com.muse.dianping.dto.Result;
import com.muse.dianping.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author MUSE
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {
    /**
     * 发送验证码
     * @param phone
     * @param session
     * @return
     */
    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);
}
