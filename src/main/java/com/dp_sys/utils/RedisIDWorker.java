package com.dp_sys.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @author DAHUANG
 * @date 2022/4/18
 * redisID生成器，基于雪花算法实现
 */
@Component
public class RedisIDWorker {
    /**
     * 开始的时间戳
     */
    private static final long BEGIN_TIMESTAMP=1640995200L;
    /**
     * 序列号的位数
     */
    private static final int COUNT_BITS=32;

    @Autowired
    private StringRedisTemplate redisTemplate;

    public RedisIDWorker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public RedisIDWorker() {
    }

    public long nextId(String keyPreFix){
        //1生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp=nowSecond-BEGIN_TIMESTAMP;
        //2生成序列号
        //2.1获取当前的时间
        String dateFormat = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long count = redisTemplate.opsForValue().increment("icr" + keyPreFix + ":" + dateFormat);
        //3拼接并返回
        return timeStamp<<COUNT_BITS|count;
    }


    public static void main(String[] args) {
        LocalDateTime time = LocalDateTime.of(2022, 1, 1, 0, 0, 0);
        long second = time.toEpochSecond(ZoneOffset.UTC);
        System.out.println("second="+second);
    }
}
