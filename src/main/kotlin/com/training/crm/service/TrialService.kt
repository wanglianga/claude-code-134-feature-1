package com.training.crm.service

import com.training.crm.common.AuditService
import com.training.crm.common.BusinessException
import com.training.crm.common.LockService
import com.training.crm.common.bizFail
import com.training.crm.domain.*
import com.training.crm.repo.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime

/** 科目默认教材版本 */
val DEFAULT_TEXTBOOK: Map<Subject, String> = mapOf(
    Subject.MATH to "人教版数学",
    Subject.ENGLISH to "新概念青少版",
    Subject.CHINESE to "部编版语文",
    Subject.PHYSICS to "人教版物理",
    Subject.CHEMISTRY to "人教版化学"
)

/** 改约结果：前两次直接生效；第三次起进入主管审批，trial 暂不变化 */
data class RescheduleResult(
    /** APPLIED=已生效；PENDING_APPROVAL=已提交主管审批；REJECTED 等走审批接口 */
    val status: String,
    val rescheduleCount: Int,
    val trial: TrialClass? = null,
    val approval: TrialScheduleApproval? = null,
    /** 本次释放的档位是否被候补学生立即占用 */
    val releasedSlotTakenByWaitlist: Boolean = false,
    val promotedWaitlistTrialId: Long? = null
)

@Service
class TrialService(
    private val trialClassRepo: TrialClassRepo,
    private val trialFeedbackRepo: TrialFeedbackRepo,
    private val rescheduleRecordRepo: TrialRescheduleRecordRepo,
    private val waitlistRepo: TrialWaitlistEntryRepo,
    private val approvalRepo: TrialScheduleApprovalRepo,
    private val concernRepo: ParentConcernRepo,
    private val leadRepo: LeadRepo,
    private val teacherRepo: TeacherRepo,
    private val teacherAvailabilityRepo: TeacherAvailabilityRepo,
    private val classroomRepo: ClassroomRepo,
    private val lessonRepo: LessonRepo,
    private val giftRecordRepo: GiftRecordRepo,
    private val lockService: LockService,
    private val auditService: AuditService,
    @Value("\${app.trial-near-start-hours:24}") private val nearStartHours: Long
) {

    /**
     * 预约试听：匹配老师空档、班级容量（教室容量）、教室、教材版本和学生水平。
     * 老师/教室时段用 Redis 锁防并发双订。
     */
    @Transactional
    fun book(req: TrialBookReq, actor: String): TrialClass {
        val lead = leadRepo.findById(req.leadId).orElseThrow { BusinessException("NOT_FOUND", "线索不存在: ${req.leadId}") }
        if (lead.status != LeadStatus.NEW && lead.status != LeadStatus.TRIAL_SCHEDULED) {
            bizFail("INVALID_STATE", "当前线索状态(${lead.status})不能预约试听")
        }
        if (!req.endTime.isAfter(req.startTime)) bizFail("INVALID_TIME", "结束时间必须晚于开始时间")
        // 曾释放又被候补占用的档位，原家长需要重新排队
        assertNotReclaimedSlot(lead.id, req.startTime, req.endTime)

        val teacher = pickTeacher(lead, req.startTime, req.endTime, req.teacherId)
        val classroom = pickClassroom(lead.campusId, req.startTime, req.endTime, req.classroomId)
        val textbook = req.textbookVersion ?: DEFAULT_TEXTBOOK[lead.subject]!!

        // Redis 锁：老师时段 + 教室时段
        return withSlotLocks(teacher.id, classroom.id, req.startTime) {
            assertTeacherFree(teacher.id, req.startTime, req.endTime)
            assertClassroomFree(classroom.id, req.startTime, req.endTime)
            val trial = trialClassRepo.save(
                TrialClass(
                    leadId = lead.id,
                    teacherId = teacher.id,
                    classroomId = classroom.id,
                    campusId = lead.campusId,
                    startTime = req.startTime,
                    endTime = req.endTime,
                    textbookVersion = textbook,
                    level = lead.baseLevel,
                    classType = req.classType
                )
            )
            lead.status = LeadStatus.TRIAL_SCHEDULED
            leadRepo.save(lead)
            // 约上后，该线索同一时段的候补自动关闭
            waitlistRepo.findByLeadIdAndStatus(lead.id, WaitlistStatus.WAITING)
                .filter { it.wantedStartTime == req.startTime && it.wantedEndTime == req.endTime }
                .forEach {
                    it.status = WaitlistStatus.BOOKED_ELSEWHERE
                    it.updatedAt = LocalDateTime.now()
                    waitlistRepo.save(it)
                }
            auditService.record(
                "TRIAL_BOOKED",
                "预约试听：${req.startTime}~${req.endTime}，老师=${teacher.name}，教室=${classroom.name}，教材=$textbook，水平=${lead.baseLevel}",
                leadId = lead.id, actor = actor
            )
            trial
        }
    }

    /**
     * 改约试听。
     * - 第 1、2 次：立即生效，释放原老师/教室档期，记录改约原因、是否临近开课、顾问确认情况；
     *   释放档位若有候补学生排队，队首自动占用，原家长再选该时段需重新排队。
     * - 第 3 次（含）以后：不直接执行，生成主管审批单（PENDING），审批通过后才释放/重分配容量，
     *   避免热门老师试听位被长期占用。
     */
    @Transactional
    fun reschedule(trialId: Long, req: TrialRescheduleReq, actor: String): RescheduleResult {
        val trial = get(trialId)
        if (trial.status != TrialStatus.SCHEDULED) bizFail("INVALID_STATE", "仅待上的试听课可改约")
        if (!req.endTime.isAfter(req.startTime)) bizFail("INVALID_TIME", "结束时间必须晚于开始时间")
        val lead = leadRepo.findById(trial.leadId).get()
        if (approvalRepo.findByTrialClassIdAndStatus(trialId, TrialApprovalStatus.PENDING).isNotEmpty()) {
            bizFail("APPROVAL_PENDING", "该试听已有待主管审批的改约申请，请等待审批结果")
        }
        // 原家长再选此前释放、已被候补占用的时段 → 必须重新排队
        assertNotReclaimedSlot(lead.id, req.startTime, req.endTime)

        val nextSeq = trial.rescheduleCount + 1
        val reason = req.reason?.takeIf { it.isNotBlank() } ?: "家长申请改期"
        val nearStart = isNearStart(trial.startTime)
        val consultantConfirmed = req.consultantConfirmed

        if (nextSeq >= 3) {
            // 第三次改约：校验目标档期可约（仅提示，不锁定；审批通过时再次校验），随后进入审批
            val targetTeacher = pickTeacher(lead, req.startTime, req.endTime, req.teacherId)
            val targetClassroom = pickClassroom(lead.campusId, req.startTime, req.endTime, req.classroomId)
            assertTeacherFree(targetTeacher.id, req.startTime, req.endTime)
            assertClassroomFree(targetClassroom.id, req.startTime, req.endTime)
            val approval = approvalRepo.save(
                TrialScheduleApproval(
                    trialClassId = trial.id,
                    leadId = lead.id,
                    seq = nextSeq,
                    toTeacherId = targetTeacher.id,
                    toClassroomId = targetClassroom.id,
                    toStartTime = req.startTime,
                    toEndTime = req.endTime,
                    reason = reason,
                    nearStart = nearStart,
                    consultantConfirmed = consultantConfirmed,
                    submittedBy = actor
                )
            )
            trial.conversionRisk = "HIGH_RISK"
            trialClassRepo.save(trial)
            auditService.record(
                "TRIAL_RESCHEDULE_APPROVAL_PENDING",
                "第${nextSeq}次改约需主管审批：${req.startTime}~${req.endTime}，老师=${targetTeacher.name}；" +
                    "原因=$reason，临近开课=$nearStart，顾问已确认=$consultantConfirmed（防止热门老师试听位被长期占用）",
                leadId = lead.id, actor = actor
            )
            auditService.record(
                "TRIAL_RESCHEDULE_RISK",
                "风险：家长已申请改约${nextSeq}次，热门老师档期占用风险高，转化意向存疑",
                leadId = lead.id, actor = actor
            )
            return RescheduleResult(
                status = "PENDING_APPROVAL",
                rescheduleCount = trial.rescheduleCount,
                trial = trial,
                approval = approval
            )
        }

        val applied = applyReschedule(trial, reason, nearStart, consultantConfirmed, null, actor, req)
        return RescheduleResult(
            status = "APPLIED",
            rescheduleCount = applied.trial.rescheduleCount,
            trial = applied.trial,
            releasedSlotTakenByWaitlist = applied.slotTaken,
            promotedWaitlistTrialId = applied.promotedTrialId
        )
    }

    /** 主管审批第三次改约：通过则真正释放/重排容量；驳回则维持原档期 */
    @Transactional
    fun decideApproval(approvalId: Long, req: ApprovalDecisionReq, actor: String): RescheduleResult {
        val approval = approvalRepo.findById(approvalId)
            .orElseThrow { BusinessException("NOT_FOUND", "改约审批单不存在: $approvalId") }
        if (approval.status != TrialApprovalStatus.PENDING) bizFail("INVALID_STATE", "审批单已处理: ${approval.status}")
        val trial = get(approval.trialClassId)

        if (!req.approved) {
            approval.status = TrialApprovalStatus.REJECTED
            approval.decidedBy = actor
            approval.decisionNote = req.note
            approval.decidedAt = LocalDateTime.now()
            approvalRepo.save(approval)
            trial.conversionRisk = "RISK"
            trialClassRepo.save(trial)
            auditService.record(
                "TRIAL_RESCHEDULE_APPROVAL_REJECTED",
                "第${approval.seq}次改约被主管驳回：${req.note ?: "未说明"}，维持原档期 ${trial.startTime}~${trial.endTime}",
                leadId = approval.leadId, actor = actor
            )
            return RescheduleResult(status = "REJECTED", rescheduleCount = trial.rescheduleCount, trial = trial, approval = approval)
        }

        if (trial.status != TrialStatus.SCHEDULED) bizFail("INVALID_STATE", "试听课状态已变化(${trial.status})，无法执行审批改约")
        if (trial.rescheduleCount != approval.seq - 1) {
            bizFail("STALE_APPROVAL", "试听已发生过其他改约（当前${trial.rescheduleCount}次），请驳回后重新提交")
        }
        val reschedReq = TrialRescheduleReq(
            startTime = approval.toStartTime,
            endTime = approval.toEndTime,
            teacherId = approval.toTeacherId,
            classroomId = approval.toClassroomId,
            reason = approval.reason,
            consultantConfirmed = approval.consultantConfirmed
        )
        val applied = applyReschedule(trial, approval.reason, approval.nearStart, approval.consultantConfirmed, approval.id, actor, reschedReq)
        approval.status = TrialApprovalStatus.APPROVED
        approval.decidedBy = actor
        approval.decisionNote = req.note
        approval.decidedAt = LocalDateTime.now()
        approvalRepo.save(approval)
        auditService.record(
            "TRIAL_RESCHEDULE_APPROVAL_APPROVED",
            "主管批准第${approval.seq}次改约：${req.note ?: ""}",
            leadId = approval.leadId, actor = actor
        )
        return RescheduleResult(
            status = "APPLIED",
            rescheduleCount = applied.trial.rescheduleCount,
            trial = applied.trial,
            approval = approval,
            releasedSlotTakenByWaitlist = applied.slotTaken,
            promotedWaitlistTrialId = applied.promotedTrialId
        )
    }

    private data class AppliedReschedule(val trial: TrialClass, val slotTaken: Boolean, val promotedTrialId: Long?)

    /**
     * 真正执行改约（调用方已完成审批判定）：
     * 在「旧老师档 + 旧教室档 + 新老师档 + 新教室档」锁内更新试听、写改约快照，
     * 旧档期随即释放并尝试提升候补队首。
     */
    private fun applyReschedule(
        trial: TrialClass,
        reason: String,
        nearStart: Boolean,
        consultantConfirmed: Boolean,
        approvalId: Long?,
        actor: String,
        req: TrialRescheduleReq
    ): AppliedReschedule {
        val lead = leadRepo.findById(trial.leadId).get()
        val newTeacher = pickTeacher(lead, req.startTime, req.endTime, req.teacherId)
        val newClassroom = pickClassroom(lead.campusId, req.startTime, req.endTime, req.classroomId)

        val oldTeacherId = trial.teacherId
        val oldClassroomId = trial.classroomId
        val oldStart = trial.startTime
        val oldEnd = trial.endTime

        val lockKeys = sortedSetOf(
            "slot:teacher:$oldTeacherId:$oldStart",
            "slot:classroom:$oldClassroomId:$oldStart",
            "slot:teacher:${newTeacher.id}:${req.startTime}",
            "slot:classroom:${newClassroom.id}:${req.startTime}"
        )
        return withLocksOrdered(lockKeys) {
            assertTeacherFree(newTeacher.id, req.startTime, req.endTime)
            assertClassroomFree(newClassroom.id, req.startTime, req.endTime)

            val seq = trial.rescheduleCount + 1
            val record = rescheduleRecordRepo.save(
                TrialRescheduleRecord(
                    trialClassId = trial.id,
                    leadId = lead.id,
                    seq = seq,
                    fromTeacherId = oldTeacherId,
                    fromClassroomId = oldClassroomId,
                    fromStartTime = oldStart,
                    fromEndTime = oldEnd,
                    toTeacherId = newTeacher.id,
                    toClassroomId = newClassroom.id,
                    toStartTime = req.startTime,
                    toEndTime = req.endTime,
                    reason = reason,
                    nearStart = nearStart,
                    consultantConfirmed = consultantConfirmed,
                    confirmedBy = actor,
                    approvalId = approvalId
                )
            )

            trial.teacherId = newTeacher.id
            trial.classroomId = newClassroom.id
            trial.startTime = req.startTime
            trial.endTime = req.endTime
            trial.rescheduleCount = seq
            if (seq == 2 && trial.conversionRisk == "NONE") trial.conversionRisk = "RISK"
            if (seq >= 3) trial.conversionRisk = "HIGH_RISK"
            trialClassRepo.save(trial)

            auditService.record(
                "TRIAL_RESCHEDULED",
                "试听改约（第$seq 次）：原档期 $oldStart~$oldEnd → 新档期 ${req.startTime}~${req.endTime}，" +
                    "老师=${newTeacher.name}，原因=$reason，临近开课=$nearStart，顾问已确认=$consultantConfirmed" +
                        (if (approvalId != null) "，主管审批单=#$approvalId" else ""),
                leadId = lead.id, actor = actor
            )
            if (seq >= 2) {
                auditService.record(
                    "TRIAL_RESCHEDULE_RISK",
                    "风险：家长已改约$seq 次，转化意向存疑",
                    leadId = lead.id, actor = actor
                )
            }

            // ---- 容量回收：旧老师/教室档期已释放，提升候补队首 ----
            val promotedTrial = promoteWaitlistForFreedSlot(oldTeacherId, oldClassroomId, trial.campusId, oldStart, oldEnd, actor)
            if (promotedTrial != null) {
                record.releasedSlotTakenByWaitlist = true
                record.takenByTrialId = promotedTrial.id
                rescheduleRecordRepo.save(record)
                trial.releasedSlotTaken = true
                trialClassRepo.save(trial)
                auditService.record(
                    "TRIAL_SLOT_TAKEN_BY_WAITLIST",
                    "容量变化：释放的档期 $oldStart~$oldEnd（老师=#$oldTeacherId，教室=#$oldClassroomId）" +
                        "已被候补学生占用（新试听=#${promotedTrial.id}，线索=${promotedTrial.leadId}）；原家长再选该时段需重新排队",
                    leadId = lead.id, actor = actor
                )
            }
            AppliedReschedule(trial, promotedTrial != null, promotedTrial?.id)
        }
    }

    /**
     * 候补提升：找出等待释放窗口（同一老师/教室意向、时段完全相等）的最早候补，
     * 直接把释放出的试听位分配给它。仅提升完全匹配者，部分重叠的候补继续排队。
     */
    private fun promoteWaitlistForFreedSlot(
        teacherId: Long,
        classroomId: Long,
        campusId: Long,
        start: LocalDateTime,
        end: LocalDateTime,
        actor: String
    ): TrialClass? {
        val candidate = waitlistRepo.findByStatusOrderByCreatedAtAsc(WaitlistStatus.WAITING).firstOrNull { w ->
            w.wantedStartTime == start && w.wantedEndTime == end &&
                (w.teacherId == null || w.teacherId == teacherId) &&
                (w.classroomId == null || w.classroomId == classroomId)
        } ?: return null

        val lead = leadRepo.findById(candidate.leadId).orElse(null) ?: return null
        if (lead.status != LeadStatus.NEW && lead.status != LeadStatus.TRIAL_SCHEDULED) {
            candidate.status = WaitlistStatus.CANCELLED
            candidate.updatedAt = LocalDateTime.now()
            waitlistRepo.save(candidate)
            return null
        }
        val teacher = teacherRepo.findById(teacherId).orElse(null) ?: return null
        if (lead.subject !in teacher.subjectSet() || campusId !in teacher.campusIdSet()) return null
        // 双重确认：老师/教室此刻确实空闲
        if (!isTeacherFree(teacherId, start, end) || !isClassroomFree(classroomId, start, end)) return null

        val newTrial = trialClassRepo.save(
            TrialClass(
                leadId = lead.id,
                teacherId = teacherId,
                classroomId = classroomId,
                campusId = campusId,
                startTime = start,
                endTime = end,
                textbookVersion = DEFAULT_TEXTBOOK[lead.subject]!!,
                level = lead.baseLevel,
                classType = candidate.classType
            )
        )
        lead.status = LeadStatus.TRIAL_SCHEDULED
        leadRepo.save(lead)
        candidate.status = WaitlistStatus.PROMOTED
        candidate.promotedTrialId = newTrial.id
        candidate.updatedAt = LocalDateTime.now()
        waitlistRepo.save(candidate)
        auditService.record(
            "TRIAL_WAITLIST_PROMOTED",
            "候补转正：候补#${candidate.id} 自动占用改约释放的档期 $start~$end，老师=${teacher.name}，生成试听=#${newTrial.id}",
            leadId = lead.id, actor = actor
        )
        return newTrial
    }

    /** 加入试听候补（目标时段已满时排队等位） */
    @Transactional
    fun joinWaitlist(req: WaitlistReq, actor: String): TrialWaitlistEntry {
        val lead = leadRepo.findById(req.leadId).orElseThrow { BusinessException("NOT_FOUND", "线索不存在: ${req.leadId}") }
        if (!req.endTime.isAfter(req.startTime)) bizFail("INVALID_TIME", "结束时间必须晚于开始时间")
        if (waitlistRepo.findByLeadIdAndStatus(lead.id, WaitlistStatus.WAITING).any {
                it.wantedStartTime == req.startTime && it.wantedEndTime == req.endTime
            }
        ) bizFail("DUPLICATE", "该线索已在该时段候补中")
        // 目标老师/教室意向校验
        req.teacherId?.let { teacherRepo.findById(it).orElseThrow { BusinessException("NOT_FOUND", "老师不存在: $it") } }
        req.classroomId?.let { classroomRepo.findById(it).orElseThrow { BusinessException("NOT_FOUND", "教室不存在: $it") } }
        // 该档仍可直接预约时不必排队
        val stillFree = teacherRepo.findAll().any { t ->
            lead.subject in t.subjectSet() && lead.campusId in t.campusIdSet() &&
                teacherAvailabilityRepo.findByTeacherId(t.id).any { av ->
                    av.dayOfWeek == req.startTime.dayOfWeek.value &&
                        !av.startTime.isAfter(req.startTime.toLocalTime()) && !av.endTime.isBefore(req.endTime.toLocalTime())
                } &&
                isTeacherFree(t.id, req.startTime, req.endTime)
        } && classroomRepo.findByCampusId(lead.campusId).any { isClassroomFree(it.id, req.startTime, req.endTime) }
        if (stillFree) bizFail("SLOT_AVAILABLE", "该时段仍有可匹配的老师与教室，请直接预约试听")

        val entry = waitlistRepo.save(
            TrialWaitlistEntry(
                leadId = lead.id,
                teacherId = req.teacherId,
                classroomId = req.classroomId,
                campusId = lead.campusId,
                wantedStartTime = req.startTime,
                wantedEndTime = req.endTime,
                classType = req.classType,
                note = req.note
            )
        )
        auditService.record(
            "TRIAL_WAITLIST_JOIN",
            "加入试听候补：${req.startTime}~${req.endTime}，意向老师=${req.teacherId ?: "不限"}，意向教室=${req.classroomId ?: "不限"}",
            leadId = lead.id, actor = actor
        )
        return entry
    }

    /** 候补列表（可按状态/线索过滤） */
    @Transactional(readOnly = true)
    fun listWaitlist(status: WaitlistStatus?, leadId: Long?): List<TrialWaitlistEntry> {
        val all = when {
            status != null -> waitlistRepo.findByStatusOrderByCreatedAtAsc(status)
            else -> waitlistRepo.findAll().sortedBy { it.createdAt }
        }
        return if (leadId != null) all.filter { it.leadId == leadId } else all
    }

    /** 取消候补 */
    @Transactional
    fun cancelWaitlist(entryId: Long, actor: String): TrialWaitlistEntry {
        val entry = waitlistRepo.findById(entryId).orElseThrow { BusinessException("NOT_FOUND", "候补记录不存在: $entryId") }
        if (entry.status != WaitlistStatus.WAITING) bizFail("INVALID_STATE", "仅排队中的候补可取消")
        entry.status = WaitlistStatus.CANCELLED
        entry.updatedAt = LocalDateTime.now()
        auditService.record("TRIAL_WAITLIST_CANCELLED", "取消试听候补#$entryId", leadId = entry.leadId, actor = actor)
        return waitlistRepo.save(entry)
    }

    /** 待主管审批的改约申请列表（可按顾问过滤） */
    @Transactional(readOnly = true)
    fun pendingApprovals(consultantId: Long? = null): List<TrialScheduleApproval> {
        val pending = approvalRepo.findByStatusOrderByCreatedAtAsc(TrialApprovalStatus.PENDING)
        if (consultantId == null) return pending
        return pending.filter { leadRepo.findById(it.leadId).map { l -> l.consultantId }.orElse(null) == consultantId }
    }

    /**
     * 老师课前视图：学生到课后老师可查看该线索完整改约历史（跨试听聚合：
     * 原因/临近开课/顾问确认/审批/释放档被候补占用）与家长关注点，便于针对性沟通与反馈。
     */
    @Transactional(readOnly = true)
    fun teacherBriefing(trialId: Long): Map<String, Any?> {
        val trial = get(trialId)
        val lead = leadRepo.findById(trial.leadId).orElse(null)
        val teacherName = { id: Long -> teacherRepo.findById(id).map { it.name }.orElse("#$id") }
        val classroomName = { id: Long -> classroomRepo.findById(id).map { it.name }.orElse("#$id") }
        // 按线索聚合：即使最终到课的是改约后重新预约的另一堂试听，也能看到此前的改约历史
        val allRecords = rescheduleRecordRepo.findByLeadIdOrderByCreatedAtAsc(trial.leadId)
        val history = allRecords.map { r ->
            mapOf(
                "trialClassId" to r.trialClassId,
                "seq" to r.seq,
                "from" to mapOf("teacher" to teacherName(r.fromTeacherId), "classroom" to classroomName(r.fromClassroomId), "startTime" to r.fromStartTime, "endTime" to r.fromEndTime),
                "to" to mapOf("teacher" to teacherName(r.toTeacherId), "classroom" to classroomName(r.toClassroomId), "startTime" to r.toStartTime, "endTime" to r.toEndTime),
                "reason" to r.reason,
                "nearStart" to r.nearStart,
                "consultantConfirmed" to r.consultantConfirmed,
                "confirmedBy" to r.confirmedBy,
                "approvalId" to r.approvalId,
                "releasedSlotTakenByWaitlist" to r.releasedSlotTakenByWaitlist,
                "takenByTrialId" to r.takenByTrialId
            )
        }
        val concerns = concernRepo.findByLeadIdOrderByIdAsc(trial.leadId).map {
            mapOf("content" to it.content, "createdBy" to it.createdBy, "createdAt" to it.createdAt)
        }
        return mapOf(
            "trial" to trial,
            "lead" to mapOf(
                "id" to (lead?.id),
                "studentName" to (lead?.studentName),
                "grade" to (lead?.grade),
                "subject" to (lead?.subject),
                "baseLevel" to (lead?.baseLevel),
                "availableTime" to (lead?.availableTime),
                "parentConcern" to (lead?.parentConcern)
            ),
            "rescheduleCount" to allRecords.size,
            "rescheduleHistory" to history,
            "parentConcerns" to concerns,
            "feedback" to trialFeedbackRepo.findByTrialClassId(trialId)
        )
    }

    /**
     * 转化风险视图（顾问端）：多次改约、释放档位被候补占用、待主管审批的线索，
     * 帮助顾问识别容量变化带来的转化风险。
     */
    @Transactional(readOnly = true)
    fun consultantRisks(consultantId: Long?): List<Map<String, Any?>> {
        val approvals = approvalRepo.findByStatusOrderByCreatedAtAsc(TrialApprovalStatus.PENDING)
        return trialClassRepo.findAll()
            .filter { it.rescheduleCount >= 2 || it.releasedSlotTaken || approvals.any { a -> a.trialClassId == it.id } }
            .mapNotNull { trial ->
                val lead = leadRepo.findById(trial.leadId).orElse(null) ?: return@mapNotNull null
                if (consultantId != null && lead.consultantId != consultantId) return@mapNotNull null
                mapOf(
                    "trialId" to trial.id,
                    "leadId" to lead.id,
                    "studentName" to lead.studentName,
                    "consultantId" to lead.consultantId,
                    "rescheduleCount" to trial.rescheduleCount,
                    "conversionRisk" to trial.conversionRisk,
                    "releasedSlotTaken" to trial.releasedSlotTaken,
                    "pendingApproval" to approvals.firstOrNull { it.trialClassId == trial.id }?.let {
                        mapOf("id" to it.id, "seq" to it.seq, "reason" to it.reason, "nearStart" to it.nearStart, "toStartTime" to it.toStartTime)
                    },
                    "riskReasons" to buildList {
                        if (trial.rescheduleCount >= 2) add("已改约${trial.rescheduleCount}次，转化意向存疑")
                        if (trial.releasedSlotTaken) add("释放的试听位已被候补占用，家长回流需重新排队")
                        if (approvals.any { it.trialClassId == trial.id }) add("第3次改约待主管审批，热门老师档期被长期占用")
                    }
                )
            }
    }

    /**
     * 试听转化统计：试听反馈的转化影响（POSITIVE/NEUTRAL/NEGATIVE）与多次改约
     * 都会影响线索转化，按维度给出转化率，供顾问主管复盘。
     */
    @Transactional(readOnly = true)
    fun conversionStats(): Map<String, Any?> {
        val leads = leadRepo.findAll()
        fun converted(ids: List<Long>) = ids.count { id -> leads.firstOrNull { it.id == id }?.status == LeadStatus.CONVERTED }

        // 每条线索取最新一条试听反馈的转化影响
        val latestImpact = mutableMapOf<Long, Pair<ConversionImpact, LocalDateTime>>()
        trialClassRepo.findAll().forEach { trial ->
            trialFeedbackRepo.findByTrialClassId(trial.id)?.let { fb ->
                val prev = latestImpact[trial.leadId]
                if (prev == null || fb.createdAt.isAfter(prev.second)) latestImpact[trial.leadId] = fb.conversionImpact to fb.createdAt
            }
        }
        val impactStats = ConversionImpact.entries.map { impact ->
            val ids = latestImpact.filterValues { it.first == impact }.keys.toList()
            mapOf(
                "impact" to impact,
                "feedbackLeads" to ids.size,
                "converted" to converted(ids),
                "conversionRate" to rate(ids.size, converted(ids))
            )
        }

        // 按累计改约次数分组（取线索改约次数最多的一堂试听）
        val maxCountByLead = trialClassRepo.findAll().groupBy { it.leadId }.mapValues { e -> e.value.maxOf { it.rescheduleCount } }
        val buckets = listOf("0-1次", "2次", "3次及以上")
        val rescheduleStats = buckets.mapIndexed { idx, label ->
            val ids = maxCountByLead.filter { (_, c) ->
                when (idx) {
                    0 -> c <= 1
                    1 -> c == 2
                    else -> c >= 3
                }
            }.keys.toList()
            mapOf("rescheduleRange" to label, "leads" to ids.size, "converted" to converted(ids), "conversionRate" to rate(ids.size, converted(ids)))
        }

        val requeueLeads = rescheduleRecordRepo.findAll().filter { it.releasedSlotTakenByWaitlist }.map { it.leadId }.distinct()
        val totalConverted = leads.count { it.status == LeadStatus.CONVERTED }
        return mapOf(
            "totalLeads" to leads.size,
            "convertedLeads" to totalConverted,
            "conversionRate" to rate(leads.size, totalConverted),
            "byFeedbackImpact" to impactStats,
            "byRescheduleCount" to rescheduleStats,
            "slotReclaimedLeads" to mapOf(
                "leads" to requeueLeads.size,
                "converted" to converted(requeueLeads),
                "conversionRate" to rate(requeueLeads.size, converted(requeueLeads))
            )
        )
    }

    /** 登记家长关注点（随改约/预约补充，老师课前可见） */
    @Transactional
    fun addConcern(leadId: Long, trialClassId: Long?, content: String, actor: String): ParentConcern {
        val lead = leadRepo.findById(leadId).orElseThrow { BusinessException("NOT_FOUND", "线索不存在: $leadId") }
        if (content.isBlank()) bizFail("INVALID_ARGUMENT", "关注点内容不能为空")
        val rec = concernRepo.save(ParentConcern(leadId = leadId, trialClassId = trialClassId, content = content, createdBy = actor))
        lead.parentConcern = content
        leadRepo.save(lead)
        auditService.record("PARENT_CONCERN_RECORDED", "家长关注点：$content", leadId = leadId, actor = actor)
        return rec
    }

    /** 试听到课；参加活动的线索发放体验课赠品 */
    @Transactional
    fun attend(trialId: Long, actor: String): TrialClass {
        val trial = get(trialId)
        if (trial.status != TrialStatus.SCHEDULED) bizFail("INVALID_STATE", "试听课状态不允许签到: ${trial.status}")
        trial.status = TrialStatus.ATTENDED
        val lead = leadRepo.findById(trial.leadId).get()
        if (lead.joinedActivity && giftRecordRepo.findByLeadId(lead.id).isEmpty()) {
            giftRecordRepo.save(GiftRecord(leadId = lead.id, giftType = "试听大礼包", cost = BigDecimal("50")))
            auditService.record("GIFT_GRANTED", "体验课赠品发放：试听大礼包（成本¥50）", leadId = lead.id, actor = actor)
        }
        auditService.record("TRIAL_ATTENDED", "试听到课", leadId = lead.id, actor = actor)
        return trialClassRepo.save(trial)
    }

    /** 学生缺席试听 */
    @Transactional
    fun absent(trialId: Long, actor: String): TrialClass {
        val trial = get(trialId)
        if (trial.status != TrialStatus.SCHEDULED) bizFail("INVALID_STATE", "试听课状态不允许标记缺席: ${trial.status}")
        trial.status = TrialStatus.ABSENT
        val lead = leadRepo.findById(trial.leadId).get()
        lead.status = LeadStatus.NEW // 回到可重新预约状态
        leadRepo.save(lead)
        closePendingApprovals(trial, "试听已标记缺席，改约申请自动关闭", actor)
        auditService.record("TRIAL_ABSENT", "学生缺席试听，需顾问重新跟进预约", leadId = lead.id, actor = actor)
        return trialClassRepo.save(trial)
    }

    /** 老师临时请假：取消试听课，线索回到可预约状态；释放档位优先提升候补 */
    @Transactional
    fun teacherLeave(trialId: Long, reason: String, actor: String): TrialClass {
        val trial = get(trialId)
        if (trial.status != TrialStatus.SCHEDULED) bizFail("INVALID_STATE", "试听课状态不允许取消: ${trial.status}")
        val freedTeacher = trial.teacherId
        val freedRoom = trial.classroomId
        val freedStart = trial.startTime
        val freedEnd = trial.endTime
        val freedCampus = trial.campusId
        trial.status = TrialStatus.CANCELLED_TEACHER_LEAVE
        val lead = leadRepo.findById(trial.leadId).get()
        lead.status = LeadStatus.NEW
        leadRepo.save(lead)
        closePendingApprovals(trial, "试听因老师请假取消，改约申请自动关闭", actor)
        val saved = trialClassRepo.save(trial)
        auditService.record("TRIAL_TEACHER_LEAVE", "老师临时请假，试听取消：$reason", leadId = lead.id, actor = actor)
        // 取消同样释放容量，候补可顶上
        promoteWaitlistForFreedSlot(freedTeacher, freedRoom, freedCampus, freedStart, freedEnd, actor)?.let { promoted ->
            auditService.record(
                "TRIAL_SLOT_TAKEN_BY_WAITLIST",
                "容量变化：老师请假取消的档位 $freedStart~$freedEnd 被候补学生占用（新试听=#${promoted.id}）",
                leadId = lead.id, actor = actor
            )
        }
        return saved
    }

    /** 老师提交试听反馈：课堂表现、测评结果、适合班型、风险提醒、对转化的影响 */
    @Transactional
    fun submitFeedback(trialId: Long, req: FeedbackReq, actor: String): TrialFeedback {
        val trial = get(trialId)
        if (trial.status != TrialStatus.ATTENDED) bizFail("INVALID_STATE", "仅到课的试听可提交反馈")
        if (trialFeedbackRepo.findByTrialClassId(trialId) != null) bizFail("DUPLICATE", "该试听课已有反馈")
        val impact = req.conversionImpact ?: inferImpact(req.assessmentScore, req.riskWarning)
        val feedback = trialFeedbackRepo.save(
            TrialFeedback(
                trialClassId = trialId,
                teacherId = trial.teacherId,
                performance = req.performance,
                assessmentScore = req.assessmentScore,
                suitableClassType = req.suitableClassType,
                suggestedLevel = req.suggestedLevel,
                riskWarning = req.riskWarning,
                conversionImpact = impact
            )
        )
        val lead = leadRepo.findById(trial.leadId).get()
        lead.status = LeadStatus.TRIAL_DONE
        leadRepo.save(lead)
        auditService.record(
            "FEEDBACK_SUBMITTED",
            "试听反馈：表现=${req.performance}，测评=${req.assessmentScore}分，适合班型=${req.suitableClassType}，建议水平=${req.suggestedLevel}，风险=${req.riskWarning ?: "无"}，转化影响=$impact",
            leadId = lead.id, actor = actor
        )
        if (impact == ConversionImpact.NEGATIVE) {
            trial.conversionRisk = "HIGH_RISK"
            trialClassRepo.save(trial)
            auditService.record(
                "TRIAL_CONVERSION_RISK",
                "试听反馈偏负面（$impact），该线索转化风险升高，顾问需重点跟进",
                leadId = lead.id, actor = actor
            )
        }
        return feedback
    }

    fun get(trialId: Long): TrialClass =
        trialClassRepo.findById(trialId).orElseThrow { BusinessException("NOT_FOUND", "试听课不存在: $trialId") }

    // ---------- 匹配逻辑 ----------

    private fun pickTeacher(lead: Lead, start: LocalDateTime, end: LocalDateTime, teacherId: Long?): Teacher {
        val candidates = if (teacherId != null) {
            listOf(teacherRepo.findById(teacherId).orElseThrow { BusinessException("NOT_FOUND", "老师不存在: $teacherId") })
        } else {
            teacherRepo.findAll()
        }
        val dow = start.dayOfWeek.value // 1..7
        val matched = candidates.filter { t ->
            // 科目匹配
            lead.subject in t.subjectSet() &&
                // 多校区共享：老师在该校区可授课
                lead.campusId in t.campusIdSet() &&
                // 老师空档覆盖整个时段
                teacherAvailabilityRepo.findByTeacherId(t.id).any { av ->
                    av.dayOfWeek == dow && !av.startTime.isAfter(start.toLocalTime()) && !av.endTime.isBefore(end.toLocalTime())
                } &&
                // 时段未被其他试听/正式课占用
                isTeacherFree(t.id, start, end)
        }
        if (matched.isEmpty()) {
            bizFail(
                "NO_TEACHER",
                "无匹配老师：需满足科目=${lead.subject}、校区=${lead.campusId}、空档覆盖 $start~$end 且时段空闲" +
                    if (teacherId != null) "（指定老师=$teacherId），可加入候补排队" else ""
            )
        }
        return matched.first()
    }

    private fun pickClassroom(campusId: Long, start: LocalDateTime, end: LocalDateTime, classroomId: Long?): Classroom {
        val candidates = if (classroomId != null) {
            listOf(classroomRepo.findById(classroomId).orElseThrow { BusinessException("NOT_FOUND", "教室不存在: $classroomId") })
        } else {
            classroomRepo.findByCampusId(campusId)
        }
        val free = candidates.filter { c -> c.campusId == campusId && isClassroomFree(c.id, start, end) }
        if (free.isEmpty()) bizFail("NO_CLASSROOM", "校区 $campusId 在 ${start}~${end} 无空闲教室，可加入候补排队")
        return free.first()
    }

    private fun isTeacherFree(teacherId: Long, start: LocalDateTime, end: LocalDateTime): Boolean {
        val trialConflict = trialClassRepo.findByTeacherIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
            teacherId, TrialStatus.SCHEDULED, end, start
        )
        val lessonConflict = lessonRepo.findByTeacherIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
            teacherId, LessonStatus.SCHEDULED, end, start
        )
        return trialConflict.isEmpty() && lessonConflict.isEmpty()
    }

    private fun isClassroomFree(classroomId: Long, start: LocalDateTime, end: LocalDateTime): Boolean {
        val trialConflict = trialClassRepo.findByClassroomIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
            classroomId, TrialStatus.SCHEDULED, end, start
        )
        val lessonConflict = lessonRepo.findByClassroomIdAndStatusAndStartTimeLessThanAndEndTimeGreaterThan(
            classroomId, LessonStatus.SCHEDULED, end, start
        )
        return trialConflict.isEmpty() && lessonConflict.isEmpty()
    }

    private fun assertTeacherFree(teacherId: Long, start: LocalDateTime, end: LocalDateTime) {
        if (!isTeacherFree(teacherId, start, end)) bizFail("TEACHER_BUSY", "老师在该时段已有安排")
    }

    private fun assertClassroomFree(classroomId: Long, start: LocalDateTime, end: LocalDateTime) {
        if (!isClassroomFree(classroomId, start, end)) bizFail("CLASSROOM_BUSY", "教室在该时段已被占用")
    }

    /** 原家长曾释放、且已被候补占用的同一时段：不允许再次直接选择，必须重新排队 */
    private fun assertNotReclaimedSlot(leadId: Long, start: LocalDateTime, end: LocalDateTime) {
        if (rescheduleRecordRepo.findAll().any {
                it.leadId == leadId && it.releasedSlotTakenByWaitlist && it.fromStartTime == start && it.fromEndTime == end
            }
        ) {
            bizFail("SLOT_TAKEN_REQUEUE", "您此前改约释放的 $start~$end 档位已被候补学生占用，再次选择该时段请重新排队候补")
        }
    }

    /** 试听取消/缺席时关闭其挂起的改约审批，避免脏审批单阻塞后续重新预约 */
    private fun closePendingApprovals(trial: TrialClass, note: String, actor: String) {
        approvalRepo.findByTrialClassIdAndStatus(trial.id, TrialApprovalStatus.PENDING).forEach { a ->
            a.status = TrialApprovalStatus.REJECTED
            a.decidedBy = actor
            a.decisionNote = note
            a.decidedAt = LocalDateTime.now()
            approvalRepo.save(a)
        }
    }

    private fun isNearStart(startTime: LocalDateTime): Boolean {        val now = LocalDateTime.now()
        if (!startTime.isAfter(now)) return false
        return Duration.between(now, startTime).toHours() <= nearStartHours
    }

    /** 反馈转化影响推断：高分正向；有风险提醒或低分负向；其余中性 */
    private fun inferImpact(score: Int, riskWarning: String?): ConversionImpact = when {
        !riskWarning.isNullOrBlank() || score < 60 -> ConversionImpact.NEGATIVE
        score >= 80 -> ConversionImpact.POSITIVE
        else -> ConversionImpact.NEUTRAL
    }

    private fun rate(total: Int, converted: Int): String =
        if (total == 0) "0.00%" else String.format("%.2f%%", converted * 100.0 / total)

    private fun <T> withSlotLocks(teacherId: Long, classroomId: Long, start: LocalDateTime, block: () -> T): T =
        withLocksOrdered(sortedSetOf("slot:teacher:$teacherId:$start", "slot:classroom:$classroomId:$start"), block)

    private fun <T> withLocksOrdered(keys: Set<String>, block: () -> T): T {
        if (keys.isEmpty()) return block()
        val first = keys.first()
        val rest = keys.drop(1).toSet()
        return lockService.withLock(first) { withLocksOrdered(rest, block) }
    }
}

data class TrialBookReq(
    val leadId: Long,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val teacherId: Long? = null,
    val classroomId: Long? = null,
    val textbookVersion: String? = null,
    val classType: ClassType = ClassType.ONE_ON_ONE
)

data class TrialRescheduleReq(
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val teacherId: Long? = null,
    val classroomId: Long? = null,
    /** 改约原因（家长给出） */
    val reason: String? = null,
    /** 顾问是否已确认；默认 true（顾问发起即确认），家长先行发起时可传 false */
    val consultantConfirmed: Boolean = true
)

data class FeedbackReq(
    val performance: String,
    val assessmentScore: Int,
    val suitableClassType: ClassType,
    val suggestedLevel: Level,
    val riskWarning: String? = null,
    /** 不填则按测评分数与风险提醒自动推断 */
    val conversionImpact: ConversionImpact? = null
)

data class WaitlistReq(
    val leadId: Long,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val teacherId: Long? = null,
    val classroomId: Long? = null,
    val classType: ClassType = ClassType.ONE_ON_ONE,
    val note: String? = null
)

data class ApprovalDecisionReq(
    val approved: Boolean,
    val note: String? = null
)

data class ConcernReq(
    val content: String,
    val trialClassId: Long? = null
)
