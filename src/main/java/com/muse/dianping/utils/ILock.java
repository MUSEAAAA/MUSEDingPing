package com.muse.dianping.utils;

public interface ILock  {
    /**
     * 尝试获取锁
     * @param timeoutSet
     * @return
     */
    boolean tryLock(long timeoutSet);

    /**
     * 释放锁
     */
    void unlock();
}
