package com.training.crm.common

import com.training.crm.domain.AuditEvent
import com.training.crm.repo.AuditEventRepo
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 履约轨迹：每个业务动作都会落一条事件，
 * 校长视图据此还原「线索 → 试听 → 报名 → 上课 → 退费」的完整链路。
 */
@Service
class AuditService(private val auditEventRepo: AuditEventRepo) {

    @Transactional(propagation = Propagation.REQUIRED)
    fun record(eventType: String, detail: String, studentId: Long? = null, leadId: Long? = null, actor: String = "system") {
        auditEventRepo.save(
            AuditEvent(
                studentId = studentId,
                leadId = leadId,
                eventType = eventType,
                detail = detail,
                actor = actor
            )
        )
    }
}
