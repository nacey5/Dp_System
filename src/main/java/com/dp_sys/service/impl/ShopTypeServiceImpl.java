package com.dp_sys.service.impl;

import cn.hutool.json.JSONUtil;
import com.dp_sys.dto.Result;
import com.dp_sys.entity.ShopType;
import com.dp_sys.mapper.ShopTypeMapper;
import com.dp_sys.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp_sys.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author DAHUANG
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public Result queryAllList() {
        List<String> rangeList=new ArrayList<>();
        //先从列表中取出数据
        rangeList = redisTemplate.opsForList().range(RedisConstants.CACHE_SHOP_TYPE_KEY, 0, -1);
        //如果存在数据，将数据通过redis输出
        if (!rangeList.isEmpty()){
            List<ShopType> shopTypes=new ArrayList<>();
            for (String one : rangeList) {
                ShopType shopType = JSONUtil.toBean(one, ShopType.class);
                shopTypes.add(shopType);
            }
            return Result.ok(shopTypes);
        }
        //如果redis不存在数据，需要从数据库中查找
        List<ShopType> sort = query().orderByAsc("sort").list();
        //查找不到，返回错误
        if (sort.size()==0){
            return Result.fail("没有任何数据");
        }
        //如果有数据,将数据存入一个列表中
        List<String> jsonList=new ArrayList<>();
        for (ShopType shopType : sort) {
            String s = JSONUtil.toJsonStr(shopType);
            jsonList.add(s);
        }
        redisTemplate.opsForList().leftPushAll(RedisConstants.CACHE_SHOP_TYPE_KEY,jsonList);
        return Result.ok(sort);
    }
}
