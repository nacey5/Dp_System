package com.dp_sys.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.dp_sys.dto.Result;
import com.dp_sys.entity.Shop;
import com.dp_sys.mapper.ShopMapper;
import com.dp_sys.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.dp_sys.utils.CacheClient;
import com.dp_sys.utils.RedisConstants;
import com.dp_sys.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author DAHUANG
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private  StringRedisTemplate redisTemplate;

    @Resource
    private CacheClient cacheClient;

    /**
     * 创建包含10个线程的线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    @Override
    public Result queryShopById(Long id) {
        //缓存穿透
//        Shop shop=cacheClient.queryWithPassThrough(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,RedisConstants.CACHE_SHOP_TTL,TimeUnit.MINUTES);
        //互斥锁解决缓存击穿
        /*Shop shop=queryWithMutex(id);*/
        //使用逻辑过期解决缓存击穿
        Shop shop=cacheClient.queryWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY,id,Shop.class,this::getById,20L,TimeUnit.SECONDS);

        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        //返回数据
        return Result.ok(shop);
    }

    /**
     * 逻辑过期插入
     * @param id
     */
    /*public void saveShop2Redis(Long id,Long expireSeconds){
        //查询店铺数据
        Shop shop = getById(id);
        try {
            Thread.sleep(200);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        //封装逻辑时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //写入redis
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }*/

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return*/
    /*public Shop queryWithLogicalExpire(Long id){
        //从redis查询商铺缓存
        String shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        //不存在直接返回null就ok了
        if (StrUtil.isBlank(shopJson)) {//判断只判单非null，“”，“ ” 的字符串，这些字符串不会被识别
            return null;
        }
        //命中了，需要把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回店铺信息
            return shop;
        }

        //过期了，缓存重建
        //尝试获取互斥锁
        boolean islock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
        //判断是否获取到了锁
        if (islock) {
            //获取到了互斥锁，开启独立线程
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    //重建缓存,根据id查询数据库，将数据库数据写入redis,//重新设置超时时间
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    e.printStackTrace();
                }finally {
                    //重建缓存之后，释放锁
                    unlock(RedisConstants.LOCK_SHOP_KEY+id);
                }
            });
        }
        //返回商铺过期信息

        return shop;
    }*/

    /**
     * 互斥锁
     * @return
     */
    /*public  Shop queryWithMutex(Long id){
        //从redis查询商铺缓存
        String shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {//判断只判单非null，“”，“ ” 的字符串，这些字符串不会被识别
            //存在直真实值接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson!=null){
            //返回一个错误信息
            return null;
        }

        //实现缓存重建
        //获取互斥
        Shop shop = null;
        try {
            boolean isLock = tryLock(RedisConstants.LOCK_SHOP_KEY + id);
            //判断是否获取成功
            if (!isLock){
                //失败，休眠并重试
                Thread.sleep(50);
                 return queryWithMutex(id);
            }
            //成功，根据id查询数据库，判断是否存在
            shop = getById(id);
            //模拟重建的延时
            Thread.sleep(200);
            //不存在，返回错误
            if (shop == null) {
                //将空值写入redis
                redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //存在，将数据写入redis,并添加30分钟作为超时时间
            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }finally {
            //释放互斥锁
            unlock(RedisConstants.LOCK_SHOP_KEY + id);
        }

        //返回数据
        return shop;
    }*/

    //缓存穿透的代码-->已被工具类包装
      /*public Shop queryWithPassThrough(Long id){
        //从redis查询商铺缓存
        String shopJson = redisTemplate.opsForValue().get(RedisConstants.CACHE_SHOP_KEY + id);
        //判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {//判断只判单非null，“”，“ ” 的字符串，这些字符串不会被识别
            //存在直真实值接返回
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //判断命中的是否是空值
        if (shopJson!=null){
            //返回一个错误信息
            return null;
        }
        //不存在，根据id查询数据库，判断是否存在
        Shop shop = getById(id);
        //不存在，返回错误
        if (shop == null) {
            //将空值写入redis
            redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY + id,"",RedisConstants.CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //存在，将数据写入redis,并添加30分钟作为超时时间
        redisTemplate.opsForValue().set(RedisConstants.CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(shop),RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //返回数据
        return shop;
    }*/

    /*private boolean tryLock(String key){
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(RedisConstants.LOCK_SHOP_KEY + key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }*/

    /*private void unlock(String key){
        redisTemplate.delete(RedisConstants.LOCK_SHOP_KEY + key);
    }*/
    @Override
    @Transactional
    public Result updateBy(Shop shop) {
        if (shop.getId()==null){
            return Result.fail("店铺的id不能为空");
        }
        //1,先写入数据库
        updateById(shop);
        //2，删除缓存
        redisTemplate.delete(RedisConstants.CACHE_SHOP_KEY+shop.getId());
        //更新缓存只有当用户去查询这个数据在redis中没有命中才会去数据库中茶轴数据，在其他方法中已经完成了
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        //1判断是否需要根据坐标查询
        if (true){
            //不需要坐标查询，直接查数据库
            Page<Shop> shops = query().
                    eq("type_id", typeId).page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
            //返回数据
            return Result.ok(shops);
        }
        //2计算分页参数
        int from=(current-1)*SystemConstants.MAX_PAGE_SIZE;
        int end=current*SystemConstants.MAX_PAGE_SIZE;
        //3查询redis，按照距离排序，结果:shopId，distance
        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = redisTemplate.opsForGeo().
                search(RedisConstants.SHOP_GEO_KEY + typeId,
                        GeoReference.fromCoordinate(new Point(x, y)),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );

        //4解析出id
        if (geoResults == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = geoResults.getContent();

        if (list.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        //4.1截取从from到end的部分
        List<Long> ids=new ArrayList<>(list.size());
        Map<String,Distance> distanceMap=new HashMap<>(list.size());
        list.stream().skip(from).forEach(result->{
            //获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            //获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr,distance);
        });
        //5根据id查询shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shopList = query().in("id", ids).last(" order by field(id," + idStr + ")").list();
        for (Shop shop : shopList) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        //6返回
        return Result.ok(shopList);
    }
}
