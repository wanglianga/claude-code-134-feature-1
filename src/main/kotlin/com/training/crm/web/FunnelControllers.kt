package com.training.crm.web

import com.training.crm.common.bizFail
import com.training.crm.service.*
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/leads")
class LeadController(private val leadService: LeadService) {

    @PostMapping
    fun create(
        @RequestBody req: LeadCreateReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?,
        @RequestHeader(value = "X-Idempotency-Key", required = false) idemKey: String?
    ): Any {
        val (existed, lead) = leadService.createLead(req, idemKey, resolveActor(role, actor))
        return mapOf("idempotentReplay" to existed, "lead" to lead)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long) = leadService.get(id)

    /** 线索事件流：改约记录/候补补位/反馈等履约事件，供顾问与老师跟进 */
    @GetMapping("/{id}/events")
    fun events(@PathVariable id: Long) = leadService.events(id)

    @PostMapping("/{id}/campus")
    fun changeCampus(
        @PathVariable id: Long,
        @RequestBody req: Map<String, Long>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = leadService.changeCampus(id, req["campusId"]!!, resolveActor(role, actor))

    @PostMapping("/{id}/promises")
    fun addPromise(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = leadService.addPromise(id, req["content"]!!, req["promisedBy"] ?: resolveActor(role, actor), resolveActor(role, actor))

    @PostMapping("/{id}/lost")
    fun markLost(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = leadService.markLost(id, req["reason"] ?: "未说明", resolveActor(role, actor))
}

@RestController
@RequestMapping("/api/trials")
class TrialController(private val trialService: TrialService) {

    @PostMapping("/book")
    fun book(
        @RequestBody req: TrialBookReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.book(req, resolveActor(role, actor))

    /** 改约：前 2 次直接执行容量回收；第 3 次起返回 approvalRequired=true 等待主管审批 */
    @PostMapping("/{id}/reschedule")
    fun reschedule(
        @PathVariable id: Long,
        @RequestBody req: TrialRescheduleReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.reschedule(id, req, resolveActor(role, actor))

    /** 候补排队：目标老师时段已满时登记，他人改约释放后自动补位 */
    @PostMapping("/waitlist")
    fun joinWaitlist(
        @RequestBody req: TrialWaitlistReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.joinWaitlist(req, resolveActor(role, actor))

    @PostMapping("/waitlist/{id}/cancel")
    fun cancelWaitlist(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.cancelWaitlist(id, resolveActor(role, actor))

    /** 老师到课简报：改约历史、家长关注点、临近开课与容量补位情况 */
    @GetMapping("/{id}/briefing")
    fun briefing(@PathVariable id: Long) = trialService.teacherBriefing(id)

    /** 待主管审批的改约单（仅 PRINCIPAL/ADMIN） */
    @GetMapping("/reschedule-approvals/pending")
    fun pendingApprovals(@RequestHeader("X-Role") role: String): Any {
        requireSupervisor(role)
        return trialService.pendingApprovals()
    }

    @PostMapping("/reschedule-approvals/{id}/approve")
    fun approveReschedule(
        @PathVariable id: Long,
        @RequestBody(required = false) req: Map<String, String>?,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ): Any {
        requireSupervisor(role)
        return trialService.approveReschedule(id, req?.get("note"), resolveActor(role, actor))
    }

    @PostMapping("/reschedule-approvals/{id}/reject")
    fun rejectReschedule(
        @PathVariable id: Long,
        @RequestBody(required = false) req: Map<String, String>?,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ): Any {
        requireSupervisor(role)
        return trialService.rejectReschedule(id, req?.get("note"), resolveActor(role, actor))
    }

    @PostMapping("/{id}/attend")
    fun attend(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.attend(id, resolveActor(role, actor))

    @PostMapping("/{id}/absent")
    fun absent(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.absent(id, resolveActor(role, actor))

    @PostMapping("/{id}/teacher-leave")
    fun teacherLeave(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.teacherLeave(id, req["reason"] ?: "老师临时请假", resolveActor(role, actor))

    @PostMapping("/{id}/feedback")
    fun feedback(
        @PathVariable id: Long,
        @RequestBody req: FeedbackReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.submitFeedback(id, req, resolveActor(role, actor))

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long) = trialService.get(id)

    private fun requireSupervisor(role: String) {
        if (role != "PRINCIPAL" && role != "ADMIN") {
            bizFail("FORBIDDEN", "仅顾问主管（PRINCIPAL/ADMIN）可审批第3次及以上改约", HttpStatus.FORBIDDEN)
        }
    }
}

/** 顾问端：容量变化与转化风险 */
@RestController
@RequestMapping("/api/consultants")
class ConsultantRiskController(private val trialService: TrialService) {

    @GetMapping("/risks")
    fun risks(@RequestParam(required = false) consultantId: Long?) = trialService.consultantRisks(consultantId)
}

@RestController
@RequestMapping("/api/conversions")
class ConversionController(private val conversionService: ConversionService) {

    @PostMapping
    fun convert(
        @RequestBody req: ConvertReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = conversionService.convert(req, resolveActor(role, actor))

    @PostMapping("/installments/{id}/pay")
    fun payInstallment(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = conversionService.payInstallment(id, resolveActor(role, actor))
}
