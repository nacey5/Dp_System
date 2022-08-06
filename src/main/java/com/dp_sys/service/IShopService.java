package com.dp_sys.service;

import com.dp_sys.dto.Result;
import com.dp_sys.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author DAHUANG
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    Result queryShopById(Long id);

    Result updateBy(Shop shop);

    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
