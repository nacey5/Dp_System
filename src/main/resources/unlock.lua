---
--- Generated by Luanalysis
--- Created by DaHuangGO.
--- DateTime: 2022/4/19 19:13
---
-- 锁的key
--local key=KEYS[1]
--当前线程标识：UUID+userid
--local threadId=ARGV[1]

-- 获取锁中的线程标识 get key
--local id=redis.call('get',KEYS[1])
--比较线程的标识与锁中的标识是否一致
if(redis.call('get',KEYS[1])==ARGV[1]) then
    --释放锁 del key
    return redis.call('del',KEYS[1])
end
--return 0表示成功
return 0


