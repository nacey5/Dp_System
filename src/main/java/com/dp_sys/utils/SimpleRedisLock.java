package com.dp_sys.utils;

import cn.hutool.core.lang.UUID;
import lombok.AllArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @author DAHUANG
 * @date 2022/4/19
 */

@AllArgsConstructor
public class SimpleRedisLock implements ILock{

    private StringRedisTemplate redisTemplate;
    private String name;
    private static final String KEY_PREFIX="lock:";
    private static final String ID_PREFIX= UUID.randomUUID().toString(true)+"-";
    private static volatile DefaultRedisScript<Long> UNLOCK_SCRIPT;

    static {
        synchronized (DefaultRedisScript.class){
            if (UNLOCK_SCRIPT==null){
                synchronized (DefaultRedisScript.class){
                    UNLOCK_SCRIPT=new DefaultRedisScript<>();
                    UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
                    UNLOCK_SCRIPT.setResultType(Long.class);
                }
            }

        }

    }

    @Override
    public boolean tryLock(long timeOutSec) {
        //获取当前线程的标识
        String threadId = ID_PREFIX+ Thread.currentThread().getId();
        Boolean success = redisTemplate.opsForValue().setIfAbsent(KEY_PREFIX + name, threadId, timeOutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 基于lua教本解锁
     */
    @Override
    public void unLock() {
        //调用lua脚本
        redisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX+ Thread.currentThread().getId());
    }

    /*@Override
    public void unLock() {
        //获取线程标识
        String threadId=ID_PREFIX+Thread.currentThread().getId();
        //获取锁中的标识
        String id = redisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断标识是否一致
        if (threadId.equals(id)) {
            //释放锁
            redisTemplate.delete(KEY_PREFIX + name);
        }
    }*/
}
