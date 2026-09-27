package com.training.crm.service

import com.training.crm.common.AuditService
import com.training.crm.common.BusinessException
import com.training.crm.common.LockService
import com.training.crm.common.bizFail
import com.training.crm.domain.*
import com.training.crm.repo.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
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
    private val leadRepo: LeadRepo,
    private val teacherRepo: TeacherRepo,
    private val teacherAvailabilityRepo: TeacherAvailabilityRepo,
    private val classroomRepo: ClassroomRepo,
    private val lessonRepo: LessonRepo,
    private val giftRecordRepo: GiftRecordRepo,
    private val lockService: LockService,
    private val auditService: AuditService
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

    /** 改约试听；多次改约（>=3 次）记录风险事件 */
    @Transactional
    fun reschedule(trialId: Long, req: TrialRescheduleReq, actor: String): TrialClass {
        val trial = get(trialId)
        if (trial.status != TrialStatus.SCHEDULED) bizFail("INVALID_STATE", "仅待上的试听课可改约")
        val lead = leadRepo.findById(trial.leadId).get()
        val teacher = pickTeacher(lead, req.startTime, req.endTime, req.teacherId)
        val classroom = pickClassroom(lead.campusId, req.startTime, req.endTime, req.classroomId)

        return lockService.withLock("slot:teacher:${teacher.id}:${req.startTime}") {
            lockService.withLock("slot:classroom:${classroom.id}:${req.startTime}") {
                assertTeacherFree(teacher.id, req.startTime, req.endTime)
                assertClassroomFree(classroom.id, req.startTime, req.endTime)
                trial.teacherId = teacher.id
                trial.classroomId = classroom.id
                trial.startTime = req.startTime
                trial.endTime = req.endTime
                trial.rescheduleCount += 1
                trialClassRepo.save(trial)
                auditService.record(
                    "TRIAL_RESCHEDULED",
                    "试听改约（第${trial.rescheduleCount}次）：新时段 ${req.startTime}~${req.endTime}，老师=${teacher.name}",
                    leadId = lead.id, actor = actor
                )
                if (trial.rescheduleCount >= 3) {
                    auditService.record(
                        "TRIAL_RESCHEDULE_RISK",
                        "风险：家长已改约${trial.rescheduleCount}次，转化意向存疑",
                        leadId = lead.id, actor = actor
                    )
                }
                trial
            }
        }
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
        auditService.record("TRIAL_ABSENT", "学生缺席试听，需顾问重新跟进预约", leadId = lead.id, actor = actor)
        return trialClassRepo.save(trial)
    }

    /** 老师临时请假：取消试听课，线索回到可预约状态 */
    @Transactional
    fun teacherLeave(trialId: Long, reason: String, actor: String): TrialClass {
        val trial = get(trialId)
        if (trial.status != TrialStatus.SCHEDULED) bizFail("INVALID_STATE", "试听课状态不允许取消: ${trial.status}")
        trial.status = TrialStatus.CANCELLED_TEACHER_LEAVE
        val lead = leadRepo.findById(trial.leadId).get()
        lead.status = LeadStatus.NEW
        leadRepo.save(lead)
        auditService.record("TRIAL_TEACHER_LEAVE", "老师临时请假，试听取消：$reason", leadId = lead.id, actor = actor)
        return trialClassRepo.save(trial)
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
            "试听反馈：表现=${req.performance}，测评=${req.assessmentScore}分，适合班型=${req.suitableClassType}，建议水平=${req.suggestedLevel}，风险=${req.riskWarning ?: "无"}",
            leadId = lead.id, actor = actor
        )
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
                "无匹配老师：需满足科目=${lead.subject}、校区=${lead.campusId}、空档覆盖 ${start}~${end} 且时段空闲" +
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
        if (free.isEmpty()) bizFail("NO_CLASSROOM", "校区 $campusId 在 ${start}~${end} 无空闲教室")
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
    val classroomId: Long? = null
)

data class FeedbackReq(
    val performance: String,
    val assessmentScore: Int,
    val suitableClassType: ClassType,
    val suggestedLevel: Level,
    val riskWarning: String? = null
)
