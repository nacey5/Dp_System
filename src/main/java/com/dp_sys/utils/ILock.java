package com.dp_sys.utils;

/**
 * @author DAHUANG
 * @date 2022/4/19
 */
public interface ILock {

    /**
     * 尝试获取锁，成功返回true，失败返回false
     * @param timeOutSec
     * @return
     */
    boolean tryLock(long timeOutSec);

    /**
     * 释放锁
     */
    void unLock();
}
