package com.dp_sys.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.dp_sys.dto.LoginFormDTO;
import com.dp_sys.dto.Result;
import com.dp_sys.entity.User;

import javax.servlet.http.HttpSession;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author DAHUANG
 * @since 2021-12-22
 */
public interface IUserService extends IService<User> {

    Result sendCode(String phone, HttpSession session);

    Result login(LoginFormDTO loginForm, HttpSession session);

    Result queryUserById(Long id);

    Result sign();

    Result signCount();

}
