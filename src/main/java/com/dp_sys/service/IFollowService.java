package com.dp_sys.service;

import com.dp_sys.dto.Result;
import com.dp_sys.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author DAHUANG
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    Result follow(Long followUserID, Boolean isFollow);

    Result isFollow(Long followUserID);

    Result commonFollow(Long id);
}
