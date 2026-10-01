package com.training.crm.web

import com.training.crm.domain.WaitlistStatus
import com.training.crm.service.*
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/leads")
class LeadController(
    private val leadService: LeadService,
    private val trialService: TrialService
) {

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

    /** 登记家长关注点（随预约/改约补充，学生到课后随改约历史呈现给老师） */
    @PostMapping("/{id}/concerns")
    fun addConcern(
        @PathVariable id: Long,
        @RequestBody req: ConcernReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.addConcern(id, req.trialClassId, req.content, resolveActor(role, actor))
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

    /** 候补排队：目标时段无可用老师/教室时排队，改约/取消释放档位后自动提升队首 */
    @PostMapping("/waitlist")
    fun joinWaitlist(
        @RequestBody req: WaitlistReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.joinWaitlist(req, resolveActor(role, actor))

    @PostMapping("/waitlist/{id}/cancel")
    fun cancelWaitlist(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.cancelWaitlist(id, resolveActor(role, actor))

    /** 候补队列（可按 status/leadId 过滤） */
    @GetMapping("/waitlist")
    fun listWaitlist(
        @RequestParam(required = false) status: WaitlistStatus?,
        @RequestParam(required = false) leadId: Long?
    ) = trialService.listWaitlist(status, leadId)

    /**
     * 改约：前两次直接生效并回收容量、提升候补；第三次起返回 PENDING_APPROVAL，
     * 需主管（ADMIN/PRINCIPAL）审批后才执行。
     */
    @PostMapping("/{id}/reschedule")
    fun reschedule(
        @PathVariable id: Long,
        @RequestBody req: TrialRescheduleReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.reschedule(id, req, resolveActor(role, actor))

    /** 主管审批第三次改约（仅 ADMIN/PRINCIPAL，拦截器鉴权） */
    @PostMapping("/reschedule-approvals/{id}/decision")
    fun decideApproval(
        @PathVariable id: Long,
        @RequestBody req: ApprovalDecisionReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.decideApproval(id, req, resolveActor(role, actor))

    /** 待主管审批的改约申请列表 */
    @GetMapping("/reschedule-approvals")
    fun pendingApprovals() = trialService.pendingApprovals()

    /** 老师课前视图：改约历史（原因/临近开课/顾问确认/审批/容量被候补占用）+ 家长关注点 */
    @GetMapping("/{id}/briefing")
    fun briefing(@PathVariable id: Long) = trialService.teacherBriefing(id)

    /** 顾问端转化风险视图：多次改约 / 释放档被候补占用 / 待审批 */
    @GetMapping("/risks")
    fun risks(@RequestParam(required = false) consultantId: Long?) = trialService.consultantRisks(consultantId)

    /** 试听反馈影响与改约次数的线索转化统计 */
    @GetMapping("/conversion-stats")
    fun conversionStats() = trialService.conversionStats()

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
