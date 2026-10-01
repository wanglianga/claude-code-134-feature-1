package com.training.crm.service

import com.training.crm.common.AuditService
import com.training.crm.common.BusinessException
import com.training.crm.common.LockService
import com.training.crm.common.bizFail
import com.training.crm.domain.*
import com.training.crm.repo.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
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

@Service
class TrialService(
    private val trialClassRepo: TrialClassRepo,
    private val trialFeedbackRepo: TrialFeedbackRepo,
    private val rescheduleRecordRepo: TrialRescheduleRecordRepo,
    private val waitlistRepo: TrialWaitlistRepo,
    private val rescheduleApprovalRepo: RescheduleApprovalRepo,
    private val leadRepo: LeadRepo,
    private val teacherRepo: TeacherRepo,
    private val teacherAvailabilityRepo: TeacherAvailabilityRepo,
    private val classroomRepo: ClassroomRepo,
    private val lessonRepo: LessonRepo,
    private val giftRecordRepo: GiftRecordRepo,
    private val lockService: LockService,
    private val auditService: AuditService,
    @Value("\${app.trial.near-start-hours:24}") private val nearStartHours: Double,
    @Value("\${app.trial.supervisor-approval-count:3}") private val supervisorApprovalCount: Int,
    @Value("\${app.trial.hot-teacher-weekly-slots:3}") private val hotTeacherWeeklySlots: Int
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

        val teacher = pickTeacher(lead, req.startTime, req.endTime, req.teacherId)
        val classroom = pickClassroom(lead.campusId, req.startTime, req.endTime, req.classroomId)
        val textbook = req.textbookVersion ?: DEFAULT_TEXTBOOK[lead.subject]!!

        // Redis 锁：老师时段 + 教室时段
        return lockService.withLock("slot:teacher:${teacher.id}:${req.startTime}") {
            lockService.withLock("slot:classroom:${classroom.id}:${req.startTime}") {
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
                auditService.record(
                    "TRIAL_BOOKED",
                    "预约试听：${req.startTime}~${req.endTime}，老师=${teacher.name}，教室=${classroom.name}，教材=$textbook，水平=${lead.baseLevel}",
                    leadId = lead.id, actor = actor
                )
                trial
            }
        }
    }

    /**
     * 改约试听：
     * 1. 每次改约记录原因、是否临近开课、顾问确认情况；
     * 2. 改约立即释放原老师/教室档期，释放出的试听位按候补顺序自动补位；
     * 3. 第 3 次（含）起改约需顾问主管审批：提交时仅挂起审批单，原档期暂不释放，
     *    主管批准后才执行改约与容量回收，驳回则保留原时段——避免热门老师试听位被长期占用。
     */
    @Transactional
    fun reschedule(trialId: Long, req: TrialRescheduleReq, actor: String): Map<String, Any?> {
        val trial = get(trialId)
        if (trial.status != TrialStatus.SCHEDULED) bizFail("INVALID_STATE", "仅待上的试听课可改约")
        if (!req.endTime.isAfter(req.startTime)) bizFail("INVALID_TIME", "结束时间必须晚于开始时间")
        if (req.reason.isBlank()) bizFail("INVALID_ARGUMENT", "请填写改约原因")
        if (req.startTime == trial.startTime && req.endTime == trial.endTime) {
            bizFail("INVALID_TIME", "新时段与原时段相同，无需改约")
        }
        rescheduleApprovalRepo.findByTrialClassIdAndStatus(trialId, RescheduleApprovalStatus.PENDING)?.let {
            bizFail("RESCHEDULE_APPROVAL_PENDING", "该试听已有第${it.seq}次改约审批单(#${it.id})待主管处理，勿重复提交")
        }

        val now = LocalDateTime.now()
        if (!trial.startTime.isAfter(now)) bizFail("INVALID_TIME", "原试听时段已过，不能改约，请重新预约")
        val hoursBefore = Duration.between(now, trial.startTime).seconds / 3600.0
        val nearStart = hoursBefore <= nearStartHours

        val nextSeq = trial.rescheduleCount + 1
        if (nextSeq >= supervisorApprovalCount) {
            // 第 3 次及以上：挂起审批，原老师/教室档期保持占用
            val approval = rescheduleApprovalRepo.save(
                RescheduleApproval(
                    trialClassId = trial.id,
                    leadId = trial.leadId,
                    seq = nextSeq,
                    toTeacherId = req.teacherId,
                    toClassroomId = req.classroomId,
                    toStartTime = req.startTime,
                    toEndTime = req.endTime,
                    reason = req.reason,
                    nearStart = nearStart,
                    consultantConfirmed = req.consultantConfirmed,
                    confirmedBy = req.confirmedBy ?: if (req.consultantConfirmed) actor else null,
                    requestedBy = actor
                )
            )
            auditService.record(
                "TRIAL_RESCHEDULE_APPROVAL_REQUIRED",
                "第$nextSeq 次改约需顾问主管审批：原因=${req.reason}，临近开课=$nearStart（距开课${"%.1f".format(hoursBefore)}小时），" +
                    "顾问已确认=${req.consultantConfirmed}；审批期间保留原档期 ${trial.startTime}~${trial.endTime}",
                leadId = trial.leadId, actor = actor
            )
            return mapOf(
                "rescheduled" to false,
                "approvalRequired" to true,
                "approval" to approval,
                "trial" to trial
            )
        }

        val record = applyReschedule(
            trial = trial,
            newStart = req.startTime,
            newEnd = req.endTime,
            newTeacherId = req.teacherId,
            newClassroomId = req.classroomId,
            reason = req.reason,
            nearStart = nearStart,
            hoursBefore = hoursBefore,
            consultantConfirmed = req.consultantConfirmed,
            confirmedBy = req.confirmedBy ?: if (req.consultantConfirmed) actor else null,
            actor = actor
        )
        return mapOf("rescheduled" to true, "approvalRequired" to false, "trial" to trial, "record" to record)
    }

    /** 主管批准第 3 次及以上改约：执行改约、容量回收与候补补位 */
    @Transactional
    fun approveReschedule(approvalId: Long, note: String?, actor: String): Map<String, Any?> {
        val approval = rescheduleApprovalRepo.findById(approvalId)
            .orElseThrow { BusinessException("NOT_FOUND", "改约审批单不存在: $approvalId") }
        if (approval.status != RescheduleApprovalStatus.PENDING) bizFail("INVALID_STATE", "审批单已处理: ${approval.status}")
        val trial = get(approval.trialClassId)
        if (trial.status != TrialStatus.SCHEDULED) bizFail("INVALID_STATE", "试听课状态不允许改约: ${trial.status}")

        val now = LocalDateTime.now()
        val hoursBefore = Duration.between(now, trial.startTime).seconds / 3600.0
        val record = applyReschedule(
            trial = trial,
            newStart = approval.toStartTime,
            newEnd = approval.toEndTime,
            newTeacherId = approval.toTeacherId,
            newClassroomId = approval.toClassroomId,
            reason = approval.reason,
            nearStart = approval.nearStart,
            hoursBefore = hoursBefore,
            consultantConfirmed = approval.consultantConfirmed,
            confirmedBy = approval.confirmedBy,
            actor = approval.requestedBy,
            supervisorApproved = true,
            approvedBy = actor
        )
        approval.status = RescheduleApprovalStatus.APPROVED
        approval.decidedBy = actor
        approval.decisionNote = note
        approval.decidedAt = now
        rescheduleApprovalRepo.save(approval)
        auditService.record(
            "TRIAL_RESCHEDULE_APPROVED",
            "主管批准第${approval.seq}次改约（审批单#${approval.id}）：$note",
            leadId = trial.leadId, actor = actor
        )
        return mapOf("rescheduled" to true, "approval" to approval, "trial" to trial, "record" to record)
    }

    /** 主管驳回改约：试听保留原老师、原教室、原时段 */
    @Transactional
    fun rejectReschedule(approvalId: Long, note: String?, actor: String): RescheduleApproval {
        val approval = rescheduleApprovalRepo.findById(approvalId)
            .orElseThrow { BusinessException("NOT_FOUND", "改约审批单不存在: $approvalId") }
        if (approval.status != RescheduleApprovalStatus.PENDING) bizFail("INVALID_STATE", "审批单已处理: ${approval.status}")
        approval.status = RescheduleApprovalStatus.REJECTED
        approval.decidedBy = actor
        approval.decisionNote = note
        approval.decidedAt = LocalDateTime.now()
        auditService.record(
            "TRIAL_RESCHEDULE_REJECTED",
            "主管驳回第${approval.seq}次改约（审批单#${approval.id}）：试听保留原时段；原因=${approval.reason}；批复=$note",
            leadId = approval.leadId, actor = actor
        )
        return rescheduleApprovalRepo.save(approval)
    }

    /** 待主管处理的改约审批单 */
    fun pendingApprovals(): List<RescheduleApproval> =
        rescheduleApprovalRepo.findByStatusOrderByCreatedAtAsc(RescheduleApprovalStatus.PENDING)

    /**
     * 候补排队：目标老师时段已满（无空档）时登记，改约释放该老师/教室时段后自动补位。
     */
    @Transactional
    fun joinWaitlist(req: TrialWaitlistReq, actor: String): TrialWaitlist {
        val lead = leadRepo.findById(req.leadId).orElseThrow { BusinessException("NOT_FOUND", "线索不存在: ${req.leadId}") }
        if (lead.status != LeadStatus.NEW && lead.status != LeadStatus.TRIAL_SCHEDULED) {
            bizFail("INVALID_STATE", "当前线索状态(${lead.status})不能候补试听")
        }
        if (!req.endTime.isAfter(req.startTime)) bizFail("INVALID_TIME", "结束时间必须晚于开始时间")
        if (lead.campusId != req.campusId) bizFail("INVALID_ARGUMENT", "候补校区须与线索校区一致")

        val teacher = req.teacherId?.let {
            teacherRepo.findById(it).orElseThrow { BusinessException("NOT_FOUND", "老师不存在: $it") }
        }
        if (teacher != null) {
            if (lead.subject !in teacher.subjectSet()) bizFail("INVALID_ARGUMENT", "老师不教授该科目")
            if (lead.campusId !in teacher.campusIdSet()) bizFail("INVALID_ARGUMENT", "老师不在该校区授课")
        }
        req.classroomId?.let {
            val room = classroomRepo.findById(it).orElseThrow { BusinessException("NOT_FOUND", "教室不存在: $it") }
            if (room.campusId != req.campusId) bizFail("INVALID_ARGUMENT", "教室不属于该校区")
        }
        val duplicate = waitlistRepo.findByLeadIdAndStatus(lead.id, WaitlistStatus.WAITING)
            .any { it.teacherId == req.teacherId && it.startTime == req.startTime }
        if (duplicate) bizFail("DUPLICATE", "该线索已在该老师时段的候补队列中")

        // 目标老师时段当前有空闲试听位时，直接预约即可，不必排队
        if (teacher != null && isTeacherFree(teacher.id, req.startTime, req.endTime)
            && (req.classroomId == null || isClassroomFree(req.classroomId, req.startTime, req.endTime))
        ) {
            bizFail("SLOT_AVAILABLE", "该老师时段当前仍有空余试听位，请直接调用 /api/trials/book 预约")
        }

        val w = waitlistRepo.save(
            TrialWaitlist(
                leadId = lead.id,
                campusId = req.campusId,
                teacherId = req.teacherId,
                classroomId = req.classroomId,
                startTime = req.startTime,
                endTime = req.endTime,
                enqueuedBy = actor
            )
        )
        auditService.record(
            "TRIAL_WAITLIST_JOINED",
            "候补试听：${req.startTime}~${req.endTime}，老师=${teacher?.name ?: "不限"}，排队等待容量释放",
            leadId = lead.id, actor = actor
        )
        return w
    }

    /** 取消候补 */
    @Transactional
    fun cancelWaitlist(waitlistId: Long, actor: String): TrialWaitlist {
        val w = waitlistRepo.findById(waitlistId)
            .orElseThrow { BusinessException("NOT_FOUND", "候补记录不存在: $waitlistId") }
        if (w.status != WaitlistStatus.WAITING) bizFail("INVALID_STATE", "候补记录状态为 ${w.status}，不能取消")
        w.status = WaitlistStatus.CANCELLED
        auditService.record("TRIAL_WAITLIST_CANCELLED", "取消试听候补：${w.startTime}", leadId = w.leadId, actor = actor)
        return waitlistRepo.save(w)
    }

    /**
     * 老师视角的到课简报：学生到课后，老师可查看该线索全部试听的改约历史、
     * 家长关注点（历次改约原因）、临近开课改约、主管审批与容量补位情况，
     * 用于课堂把握与转化跟进。
     */
    @Transactional
    fun teacherBriefing(trialId: Long): Map<String, Any?> {
        val trial = get(trialId)
        if (trial.status != TrialStatus.ATTENDED && trial.status != TrialStatus.SCHEDULED) {
            bizFail("INVALID_STATE", "仅待上/已到课的试听提供老师简报")
        }
        val lead = leadRepo.findById(trial.leadId).orElse(null)
        // 改约可能发生在历史试听课上（如老师请假重约），按线索汇总
        val records = rescheduleRecordRepo.findByLeadIdOrderByCreatedAtAsc(trial.leadId)
        val teacher = teacherRepo.findById(trial.teacherId).orElse(null)
        val feedback = trialFeedbackRepo.findByTrialClassId(trialId)
        return mapOf(
            "trial" to mapOf(
                "id" to trial.id,
                "startTime" to trial.startTime,
                "endTime" to trial.endTime,
                "teacher" to teacher?.name,
                "status" to trial.status,
                "textbookVersion" to trial.textbookVersion,
                "level" to trial.level
            ),
            "student" to mapOf(
                "name" to (lead?.studentName ?: ""),
                "grade" to (lead?.grade ?: ""),
                "subject" to (lead?.subject ?: ""),
                "baseLevel" to (lead?.baseLevel ?: ""),
                "availableTime" to (lead?.availableTime ?: ""),
                "remark" to lead?.remark
            ),
            "rescheduleCount" to records.size,
            "parentFocus" to records.map { "第${it.seq}次改约（试听#${it.trialClassId}）：${it.reason}" },
            "nearStartChangeCount" to records.count { it.nearStart },
            "supervisorApprovedCount" to records.count { it.supervisorApproved },
            "slotReclaimedCount" to records.count { it.slotTakenByWaitlist },
            "rescheduleHistory" to records.map { it.toBrief() },
            "feedback" to feedback
        )
    }

    /**
     * 顾问端容量变化与转化风险视图（按线索聚合该线索全部试听）：
     * 待主管审批的改约、多次改约、临近开课、热门老师试听位变动、
     * 释放后被候补占用（原家长再次选择需重新排队）等风险。
     */
    fun consultantRisks(consultantId: Long?): List<Map<String, Any?>> {
        val leads = if (consultantId != null) leadRepo.findByConsultantId(consultantId) else leadRepo.findAll()
        val now = LocalDateTime.now()
        return leads.mapNotNull { lead ->
            // 进行中的试听（待上/已到课），已取消/缺席的不占容量
            val activeTrials = trialClassRepo.findByLeadId(lead.id)
                .filter { it.status == TrialStatus.SCHEDULED || it.status == TrialStatus.ATTENDED }
            if (activeTrials.isEmpty()) return@mapNotNull null
            val records = rescheduleRecordRepo.findByLeadIdOrderByCreatedAtAsc(lead.id)
            val pending = activeTrials.mapNotNull {
                rescheduleApprovalRepo.findByTrialClassIdAndStatus(it.id, RescheduleApprovalStatus.PENDING)
            }
            val risks = mutableListOf<String>()
            var level = "LOW"

            pending.firstOrNull()?.let {
                risks.add("第${it.seq}次改约待主管审批（审批单#${it.id}），原热门档期暂被保留")
                level = "HIGH"
            }
            if (records.size >= supervisorApprovalCount) {
                risks.add("已改约${records.size}次（第3次起经主管审批），家长转化意向存疑")
                level = "HIGH"
            }
            val reclaimed = records.filter { it.slotTakenByWaitlist }
            if (reclaimed.isNotEmpty()) {
                risks.add("改约释放的试听位已${reclaimed.size}次被候补线索补位（候补#${reclaimed.joinToString { it.promotedWaitlistId.toString() }}），原家长再次选择该时段需重新排队")
                level = "HIGH"
            }
            val upcoming = activeTrials.filter { it.status == TrialStatus.SCHEDULED && !it.startTime.isBefore(now) }
            val nearest = upcoming.minByOrNull { it.startTime }
            val hoursBefore = nearest?.let { Duration.between(now, it.startTime).seconds / 3600.0 }
            val nearStart = hoursBefore != null && hoursBefore in 0.0..nearStartHours
            if (nearStart) {
                risks.add("距开课仅${"%.1f".format(hoursBefore)}小时，临近开课改约/爽约风险高")
                if (level != "HIGH") level = "MEDIUM"
            }
            val hotTeachers = activeTrials.map { it.teacherId }.filter { isHotTeacher(it) }.toSet()
            if (hotTeachers.isNotEmpty()) {
                risks.add("涉及热门老师（近7天试听位≥$hotTeacherWeeklySlots）：老师#${hotTeachers.joinToString()}，档期容量紧张")
                if (level == "LOW") level = "MEDIUM"
            }
            if (records.isEmpty() && level == "LOW") level = "LOW"

            mapOf(
                "riskLevel" to level,
                "leadId" to lead.id,
                "studentName" to lead.studentName,
                "consultantId" to lead.consultantId,
                "trialClassIds" to activeTrials.map { it.id },
                "nearestStartTime" to nearest?.startTime,
                "hotTeacher" to hotTeachers.isNotEmpty(),
                "hotTeacherIds" to hotTeachers,
                "rescheduleCount" to records.size,
                "nearStart" to nearStart,
                "slotReclaimedCount" to reclaimed.size,
                "pendingApproval" to pending.isNotEmpty(),
                "waitingWaitlistCount" to waitlistRepo.findByLeadIdAndStatus(lead.id, WaitlistStatus.WAITING).size,
                "risks" to risks
            )
        }.sortedWith(compareBy({ it["riskLevel"] != "HIGH" }, { it["riskLevel"] != "MEDIUM" }))
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
        auditService.record(
            "TRIAL_ATTENDED",
            "试听到课（此前改约${trial.rescheduleCount}次，老师可查看改约历史与家长关注点）",
            leadId = lead.id, actor = actor
        )
        return trialClassRepo.save(trial)
    }

    /** 学生缺席试听 */
    @Transactional
    fun absent(trialId: Long, actor: String): TrialClass {
        val trial = get(trialId)
        if (trial.status != TrialStatus.SCHEDULED) bizFail("INVALID_STATE", "试听课状态不允许标记缺席: ${trial.status}")
        cancelPendingApprovals(trialId, "学生缺席试听", actor)
        trial.status = TrialStatus.ABSENT
        val lead = leadRepo.findById(trial.leadId).get()
        lead.status = LeadStatus.NEW // 回到可重新预约状态
        leadRepo.save(lead)
        auditService.record("TRIAL_ABSENT", "学生缺席试听，需顾问重新跟进预约", leadId = lead.id, actor = actor)
        return trialClassRepo.save(trial)
    }

    /** 老师临时请假：取消试听课，线索回到可预约状态 */
    @Transactional
    fun teacherLeave(trialId: Long, reason: String, actor: String): TrialClass {
        val trial = get(trialId)
        if (trial.status != TrialStatus.SCHEDULED) bizFail("INVALID_STATE", "试听课状态不允许取消: ${trial.status}")
        cancelPendingApprovals(trialId, "老师临时请假（$reason）", actor)
        trial.status = TrialStatus.CANCELLED_TEACHER_LEAVE
        val lead = leadRepo.findById(trial.leadId).get()
        lead.status = LeadStatus.NEW
        leadRepo.save(lead)
        auditService.record("TRIAL_TEACHER_LEAVE", "老师临时请假，试听取消：$reason", leadId = lead.id, actor = actor)
        return trialClassRepo.save(trial)
    }

    /** 试听失效（缺席/老师请假）时关闭挂起中的改约审批单，原档期随试听一并释放 */
    private fun cancelPendingApprovals(trialId: Long, note: String, actor: String) {
        rescheduleApprovalRepo.findByTrialClassIdAndStatus(trialId, RescheduleApprovalStatus.PENDING)?.let { approval ->
            approval.status = RescheduleApprovalStatus.REJECTED
            approval.decidedBy = actor
            approval.decisionNote = "系统关闭：$note"
            approval.decidedAt = LocalDateTime.now()
            rescheduleApprovalRepo.save(approval)
            auditService.record(
                "TRIAL_RESCHEDULE_REJECTED",
                "试听#$trialId 失效（$note），第${approval.seq}次改约审批单#${approval.id}自动关闭",
                leadId = approval.leadId, actor = actor
            )
        }
    }

    /** 老师提交试听反馈：课堂表现、测评结果、适合班型、风险提醒 */
    @Transactional
    fun submitFeedback(trialId: Long, req: FeedbackReq, actor: String): TrialFeedback {
        val trial = get(trialId)
        if (trial.status != TrialStatus.ATTENDED) bizFail("INVALID_STATE", "仅到课的试听可提交反馈")
        if (trialFeedbackRepo.findByTrialClassId(trialId) != null) bizFail("DUPLICATE", "该试听课已有反馈")
        val feedback = trialFeedbackRepo.save(
            TrialFeedback(
                trialClassId = trialId,
                teacherId = trial.teacherId,
                performance = req.performance,
                assessmentScore = req.assessmentScore,
                suitableClassType = req.suitableClassType,
                suggestedLevel = req.suggestedLevel,
                riskWarning = req.riskWarning
            )
        )
        val lead = leadRepo.findById(trial.leadId).get()
        lead.status = LeadStatus.TRIAL_DONE
        leadRepo.save(lead)
        auditService.record(
            "FEEDBACK_SUBMITTED",
            "试听反馈：表现=${req.performance}，测评=${req.assessmentScore}分，适合班型=${req.suitableClassType}，建议水平=${req.suggestedLevel}，风险=${req.riskWarning ?: "无"}；该反馈计入线索转化统计",
            leadId = lead.id, actor = actor
        )
        return feedback
    }

    fun get(trialId: Long): TrialClass =
        trialClassRepo.findById(trialId).orElseThrow { BusinessException("NOT_FOUND", "试听课不存在: $trialId") }

    // ---------- 改约核心：容量回收 + 候补补位 ----------



    private fun applyReschedule(
        trial: TrialClass,
        newStart: LocalDateTime,
        newEnd: LocalDateTime,
        newTeacherId: Long?,
        newClassroomId: Long?,
        reason: String,
        nearStart: Boolean,
        hoursBefore: Double,
        consultantConfirmed: Boolean,
        confirmedBy: String?,
        actor: String,
        supervisorApproved: Boolean = false,
        approvedBy: String? = null
    ): TrialRescheduleRecord {
        val lead = leadRepo.findById(trial.leadId).get()
        val newTeacher = pickTeacher(lead, newStart, newEnd, newTeacherId)
        val newClassroom = pickClassroom(lead.campusId, newStart, newEnd, newClassroomId)

        val fromTeacherId = trial.teacherId
        val fromClassroomId = trial.classroomId
        val fromStart = trial.startTime
        val fromEnd = trial.endTime

        // 新旧老师/教室四把锁统一排序加锁，防止容量回收与并发预约/候补补位双订
        val lockKeys = sortedSetOf(
            "slot:teacher:$fromTeacherId:$fromStart",
            "slot:classroom:$fromClassroomId:$fromStart",
            "slot:teacher:${newTeacher.id}:$newStart",
            "slot:classroom:${newClassroom.id}:$newStart"
        )
        return lockService.withLocks(lockKeys.toList()) {
            assertTeacherFree(newTeacher.id, newStart, newEnd)
            assertClassroomFree(newClassroom.id, newStart, newEnd)

            // 占用新档期
            trial.teacherId = newTeacher.id
            trial.classroomId = newClassroom.id
            trial.startTime = newStart
            trial.endTime = newEnd
            trial.rescheduleCount += 1
            trial.lastSlotTakenByWaitlist = false
            trial.lastPromotedWaitlistId = null
            trialClassRepo.save(trial)

            // 释放原档期 → 候补自动补位
            val promotion = promoteWaitlistForReleasedSlot(fromTeacherId, fromClassroomId, fromStart, fromEnd, actor)
            if (promotion != null) {
                trial.lastSlotTakenByWaitlist = true
                trial.lastPromotedWaitlistId = promotion.first.id
                trialClassRepo.save(trial)
            }

            val record = rescheduleRecordRepo.save(
                TrialRescheduleRecord(
                    trialClassId = trial.id,
                    leadId = lead.id,
                    seq = trial.rescheduleCount,
                    fromTeacherId = fromTeacherId,
                    toTeacherId = newTeacher.id,
                    fromClassroomId = fromClassroomId,
                    toClassroomId = newClassroom.id,
                    fromStartTime = fromStart,
                    fromEndTime = fromEnd,
                    toStartTime = newStart,
                    toEndTime = newEnd,
                    reason = reason,
                    hoursBeforeStart = hoursBefore,
                    nearStart = nearStart,
                    consultantConfirmed = consultantConfirmed,
                    confirmedBy = confirmedBy,
                    slotTakenByWaitlist = promotion != null,
                    promotedWaitlistId = promotion?.first?.id,
                    promotedLeadId = promotion?.second?.leadId,
                    supervisorApproved = supervisorApproved,
                    approvedBy = approvedBy,
                    actor = actor
                )
            )

            val fromTeacher = teacherRepo.findById(fromTeacherId).get()
            auditService.record(
                "TRIAL_RESCHEDULED",
                "试听改约（第${trial.rescheduleCount}次）：${fromStart} 老师=${fromTeacher.name} → ${newStart} 老师=${newTeacher.name}；" +
                    "原因=$reason；临近开课=$nearStart（距开课${"%.1f".format(hoursBefore)}小时）；顾问已确认=$consultantConfirmed；" +
                    "主管审批=$supervisorApproved；原档期容量已释放" +
                        if (promotion != null) "，并被候补线索#${promotion.second.leadId}补位（候补#${promotion.first.id}）" else "",
                leadId = lead.id, actor = actor
            )
            if (nearStart) {
                auditService.record(
                    "TRIAL_NEAR_START_RESCHEDULE",
                    "临近开课改约：距开课仅${"%.1f".format(hoursBefore)}小时，顾问确认=$consultantConfirmed，需防范爽约",
                    leadId = lead.id, actor = actor
                )
            }
            if (isHotTeacher(fromTeacherId) || isHotTeacher(newTeacher.id)) {
                auditService.record(
                    "HOT_TEACHER_SLOT_RISK",
                    "热门老师试听位变动：原/新老师涉及热门档期，关注容量转化风险",
                    leadId = lead.id, actor = actor
                )
            }
            if (trial.rescheduleCount >= 3) {
                auditService.record(
                    "TRIAL_RESCHEDULE_RISK",
                    "风险：家长已改约${trial.rescheduleCount}次（第3次起经主管审批），转化意向存疑",
                    leadId = lead.id, actor = actor
                )
            }
            if (promotion != null) {
                auditService.record(
                    "TRIAL_SLOT_RECLAIMED",
                    "改约释放的试听位（${fromStart}，老师=${fromTeacher.name}）已被候补线索#${promotion.second.leadId}占用；" +
                        "原家长再次选择该时段需重新排队，容量变化存在转化风险",
                    leadId = lead.id, actor = actor
                )
            }
            record
        }
    }

    /**
     * 容量回收：在释放出的老师/教室时段上，按入队顺序把第一个仍有效的候补补位成新试听课。
     * @return Pair(候补记录, 为候补生成的试听课)；无可补位候补返回 null
     */
    private fun promoteWaitlistForReleasedSlot(
        teacherId: Long,
        classroomId: Long,
        start: LocalDateTime,
        end: LocalDateTime,
        actor: String
    ): Pair<TrialWaitlist, TrialClass>? {
        if (!isTeacherFree(teacherId, start, end) || !isClassroomFree(classroomId, start, end)) return null
        val candidates = waitlistRepo.findByStatusOrderByEnqueuedAtAsc(WaitlistStatus.WAITING).filter { w ->
            w.teacherId == teacherId &&
                w.startTime == start && w.endTime == end &&
                (w.classroomId == null || w.classroomId == classroomId)
        }
        for (w in candidates) {
            val lead = leadRepo.findById(w.leadId).orElse(null) ?: continue
            if (lead.status != LeadStatus.NEW && lead.status != LeadStatus.TRIAL_SCHEDULED) {
                w.status = WaitlistStatus.EXPIRED
                waitlistRepo.save(w)
                continue
            }
            // 已另有待上课且时段冲突的，不再重复补位
            val hasConflict = trialClassRepo.findByLeadId(lead.id).any { t ->
                t.status == TrialStatus.SCHEDULED && t.startTime < end && t.endTime > start
            }
            if (hasConflict) continue

            val promoted = trialClassRepo.save(
                TrialClass(
                    leadId = lead.id,
                    teacherId = teacherId,
                    classroomId = classroomId,
                    campusId = lead.campusId,
                    startTime = start,
                    endTime = end,
                    textbookVersion = DEFAULT_TEXTBOOK[lead.subject] ?: "",
                    level = lead.baseLevel,
                    classType = ClassType.ONE_ON_ONE
                )
            )
            lead.status = LeadStatus.TRIAL_SCHEDULED
            leadRepo.save(lead)
            w.status = WaitlistStatus.PROMOTED
            w.promotedTrialClassId = promoted.id
            w.promotedAt = LocalDateTime.now()
            waitlistRepo.save(w)
            val teacher = teacherRepo.findById(teacherId).get()
            auditService.record(
                "TRIAL_WAITLIST_PROMOTED",
                "候补补位：线索#${lead.id}（${lead.studentName}）自动占用改约释放的试听位 ${start}~${end}，老师=${teacher.name}（候补#${w.id}→试听#${promoted.id}）",
                leadId = lead.id, actor = actor
            )
            return w to promoted
        }
        return null
    }

    /** 热门老师：未来 7 天内待上/已到课试听位达到阈值 */
    private fun isHotTeacher(teacherId: Long): Boolean {
        val now = LocalDateTime.now()
        return trialClassRepo.findAll().count {
            it.teacherId == teacherId &&
                (it.status == TrialStatus.SCHEDULED || it.status == TrialStatus.ATTENDED) &&
                !it.startTime.isBefore(now) && it.startTime.isBefore(now.plusDays(7))
        } >= hotTeacherWeeklySlots
    }

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
            // 原家长再次选择已被候补补位占用的时段：明确提示需重新排队
            candidates.firstOrNull { t ->
                lead.subject in t.subjectSet() &&
                    lead.campusId in t.campusIdSet() &&
                    teacherAvailabilityRepo.findByTeacherId(t.id).any { av ->
                        av.dayOfWeek == dow && !av.startTime.isAfter(start.toLocalTime()) && !av.endTime.isBefore(end.toLocalTime())
                    } &&
                    waitlistRepo.findByTeacherIdAndStartTimeAndStatus(t.id, start, WaitlistStatus.PROMOTED).isNotEmpty()
            }?.let {
                bizFail(
                    "SLOT_TAKEN_REQUEUE",
                    "老师=${it.name} 在 $start 的试听位此前改约释放后已被候补学生占用，原家长如需该时段请重新排队候补",
                    HttpStatus.CONFLICT
                )
            }
            bizFail(
                "NO_TEACHER",
                "无匹配老师：需满足科目=${lead.subject}、校区=${lead.campusId}、空档覆盖 $start~$end 且时段空闲" +
                    if (teacherId != null) "（指定老师=$teacherId）" else ""
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
        if (free.isEmpty()) bizFail("NO_CLASSROOM", "校区 $campusId 在 $start~$end 无空闲教室")
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
}

private fun TrialRescheduleRecord.toBrief(): Map<String, Any?> = mapOf(
    "seq" to seq,
    "from" to mapOf("teacherId" to fromTeacherId, "classroomId" to fromClassroomId, "startTime" to fromStartTime, "endTime" to fromEndTime),
    "to" to mapOf("teacherId" to toTeacherId, "classroomId" to toClassroomId, "startTime" to toStartTime, "endTime" to toEndTime),
    "reason" to reason,
    "hoursBeforeStart" to hoursBeforeStart,
    "nearStart" to nearStart,
    "consultantConfirmed" to consultantConfirmed,
    "confirmedBy" to confirmedBy,
    "slotTakenByWaitlist" to slotTakenByWaitlist,
    "promotedWaitlistId" to promotedWaitlistId,
    "promotedLeadId" to promotedLeadId,
    "supervisorApproved" to supervisorApproved,
    "approvedBy" to approvedBy,
    "createdAt" to createdAt
)

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
    /** 改约原因（必填） */
    val reason: String = "",
    /** 顾问是否已与家长确认 */
    val consultantConfirmed: Boolean = false,
    val confirmedBy: String? = null
)

data class FeedbackReq(
    val performance: String,
    val assessmentScore: Int,
    val suitableClassType: ClassType,
    val suggestedLevel: Level,
    val riskWarning: String? = null
)

data class TrialWaitlistReq(
    val leadId: Long,
    val campusId: Long,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val teacherId: Long? = null,
    val classroomId: Long? = null
)
