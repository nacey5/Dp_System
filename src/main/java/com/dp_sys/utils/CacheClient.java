package com.dp_sys.utils;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * @author DAHUANG
 * @date 2022/4/16
 */
@Slf4j
@Component
public class CacheClient {

    @Autowired
    private final StringRedisTemplate redisTemplate;

    /**
     * 创建包含10个线程的线程池
     */
    private static final ExecutorService CACHE_REBUILD_EXECUTOR= Executors.newFixedThreadPool(10);

    public CacheClient(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 只包含有效时间的redis存入
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void set(String key, Object value, Long time, TimeUnit unit){
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value),time,unit);
    }

    /**
     * 包含逻辑过期的redis存入
     * @param key
     * @param value
     * @param time
     * @param unit
     */
    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit){
        //设置逻辑过期
        RedisData redisData=new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));
        //写入redis
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }

    /**
     * 解决了缓存穿透的redis的得到值
     * @param keyPrefix key的前缀
     * @param id id
     * @param type 返回类型--》R
     * @param dbFallBack 传入调用数据库的方法 ID为参数，R为返回类型
     * @param <R> 返回类型
     * @param <ID> id的类型，可能为Integer也可能为Long，使用泛型可以解决
     * @param time
     * @param unit
     * @return
     */
    public <R,ID> R queryWithPassThrough(String keyPrefix,
                                         ID id,
                                         Class<R> type,
                                         Function<ID,R> dbFallBack,
                                         Long time, TimeUnit unit
    ){
        String key=keyPrefix+id;
        //从redis查询商铺缓存
        String json = redisTemplate.opsForValue().get(key);
        //判断是否存在
        if (StrUtil.isNotBlank(json)) {//判断只判单非null，“”，“ ” 的字符串，这些字符串不会被识别
            //存在直真实值接返回
            return JSONUtil.toBean(json,type);
        }
        //判断命中的是否是空值
        if (json!=null){
            //返回一个错误信息
            return null;
        }
        //不存在，根据id查询数据库，判断是否存在
        R r =dbFallBack.apply(id);
        //不存在，返回错误
        if (r == null) {
            //将空值写入redis
            redisTemplate.opsForValue().set(key,"",time,unit);
            return null;
        }
        //存在，将数据写入redis,并添加30分钟作为超时时间
        this.set(key,r,time,unit);
        //返回数据
        return r;
    }

    /**
     * 逻辑过期解决缓存击穿
     * @param id
     * @return
     */
    public <R,ID>R queryWithLogicalExpire(String keyPrefix,
                                          ID id,
                                          Class<R> type,
                                          Function<ID,R> dbFallBack,
                                          Long time, TimeUnit unit
    ){

        String key=keyPrefix+id;
        //从redis查询商铺缓存
        String json = redisTemplate.opsForValue().get(key);
        //判断是否存在
        //不存在直接返回null就ok了
        if (StrUtil.isBlank(json)) {//判断只判单非null，“”，“ ” 的字符串，这些字符串不会被识别
            return null;
        }
        //命中了，需要把json反序列化成对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        R r= JSONUtil.toBean(data, type);
        LocalDateTime expireTime = redisData.getExpireTime();
        // 判断缓存是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //未过期，直接返回店铺信息
            return r;
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
                    R apply = dbFallBack.apply(id);
                    this.setWithLogicalExpire(key,apply,time,unit);
                } catch (Exception e) {
                    e.printStackTrace();
                }finally {
                    //重建缓存之后，释放锁
                    unlock(RedisConstants.LOCK_SHOP_KEY+id);
                }
            });
        }
        //返回商铺过期信息
        return r;
    }

    private boolean tryLock(String key){
        Boolean flag = redisTemplate.opsForValue().setIfAbsent(RedisConstants.LOCK_SHOP_KEY + key, "1", RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unlock(String key){
        redisTemplate.delete(RedisConstants.LOCK_SHOP_KEY + key);
    }


}
