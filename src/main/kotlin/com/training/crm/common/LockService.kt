package com.training.crm.common

import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * 基于 Redis 的分布式锁与幂等控制。
 * - 排班时对「老师+时段」「教室+时段」加锁，防止并发双订；
 * - 关键创建接口（线索、退费申请）支持幂等键，防重复提交。
 */
@Component
class LockService(private val redis: StringRedisTemplate) {

    fun <T> withLock(key: String, ttlSeconds: Long = 15, block: () -> T): T {
        val lockKey = "lock:$key"
        val token = UUID.randomUUID().toString()
        val acquired = redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofSeconds(ttlSeconds)) ?: false
        if (!acquired) {
            bizFail("RESOURCE_LOCKED", "资源已被占用，请稍后重试（$key）")
        }
        try {
            return block()
        } finally {
            val holder = redis.opsForValue().get(lockKey)
            if (holder == token) {
                redis.delete(lockKey)
            }
        }
    }

    /**
     * 幂等执行：同一 idemKey 在 ttl 内只会真正执行一次。
     * 首次执行把结果 id 写入 Redis；重复请求直接返回已存在的 id。
     * @return Pair(已存在?, 实体id)
     */
    fun idempotent(key: String, ttlHours: Long = 24, create: () -> Long): Pair<Boolean, Long> {
        val idemKey = "idem:$key"
        val existing = redis.opsForValue().get(idemKey)
        if (existing != null) {
            return true to existing.toLong()
        }
        val id = create()
        // 占位 24h；并发下 setIfAbsent 失败说明别的请求已创建，回读其 id
        val ok = redis.opsForValue().setIfAbsent(idemKey, id.toString(), Duration.ofHours(ttlHours)) ?: false
        if (!ok) {
            val winner = redis.opsForValue().get(idemKey)
            if (winner != null) return true to winner.toLong()
        }
        return false to id
    }
}
