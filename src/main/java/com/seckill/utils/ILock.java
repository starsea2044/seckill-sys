package com.seckill.utils;

public interface ILock {
    boolean tryLock(long timeoutSec);
    void unlock();
}
