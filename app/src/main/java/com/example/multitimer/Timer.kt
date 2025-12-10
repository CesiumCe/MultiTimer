package com.example.multitimer

import kotlin.math.max

class Timer(val title: String, val initTime: Long) {
    var currTime: Long = initTime
        private set
    var active: Boolean = false
        private set
    private var lastTime: Long = 0
    private var pauseTime: Long = 0 // 新增：记录暂停时刻的时间戳

    fun start() {
        if (currTime <= 0) return // 如果时间已到，不能启动
        if (!active) { // 仅在非活动状态时执行启动逻辑
            active = true
            // 如果是从暂停状态恢复，调整lastTime以跳过暂停时间
            lastTime = if (pauseTime > 0) {
                System.currentTimeMillis() - (pauseTime - lastTime)
            } else {
                System.currentTimeMillis()
            }
            pauseTime = 0 // 重置暂停时间标记
        }
    }

    fun pause() {
        if (active) { // 仅在活动状态时执行暂停逻辑
            active = false
            pauseTime = System.currentTimeMillis() // 记录暂停时刻
        }
    }

    /**
     * 更新计时器状态。
     * @return true 如果计时器仍在运行，false 如果时间已到。
     */
    fun run(): Boolean {
        if (active && currTime > 0) {
            val currentTime = System.currentTimeMillis()
            val delta = currentTime - lastTime
            if (delta > 0) {
                currTime = max(0, initTime - delta/1000) // 转换为秒
            }
            // 如果时间到了，自动停止
            if (currTime <= 0) {
                active = false
                return false // 计时结束
            }
        }
        return active // 返回当前是否仍在活动状态
    }

    fun reset() {
        pause() // 先暂停计时器
        currTime = initTime
        pauseTime = 0 // 重置暂停时间标记
    }
}
