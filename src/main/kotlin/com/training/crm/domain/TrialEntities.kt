package com.training.crm.domain

import jakarta.persistence.*
import java.time.LocalDateTime

/** 试听课排班：匹配老师空档、教室、教材版本与学生水平 */
@Entity
class TrialClass(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var leadId: Long = 0,
    var teacherId: Long = 0,
    var classroomId: Long = 0,
    var campusId: Long = 0,
    var startTime: LocalDateTime = LocalDateTime.now(),
    var endTime: LocalDateTime = LocalDateTime.now(),
    var textbookVersion: String = "",
    @Enumerated(EnumType.STRING)
    var level: Level = Level.BASIC,
    @Enumerated(EnumType.STRING)
    var classType: ClassType = ClassType.ONE_ON_ONE,
    @Enumerated(EnumType.STRING)
    var status: TrialStatus = TrialStatus.SCHEDULED,
    var rescheduleCount: Int = 0,
    /** 最近一次容量回收：释放的试听位是否被候补学生补位 */
    var lastSlotTakenByWaitlist: Boolean = false,
    /** 最近一次补位占用该试听位的候补记录ID（无则 null） */
    var lastPromotedWaitlistId: Long? = null,
    var createdAt: LocalDateTime = LocalDateTime.now()
)

/** 试听反馈：老师到课后提交课堂表现、测评结果、适合班型、风险提醒 */
@Entity
class TrialFeedback(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var trialClassId: Long = 0,
    var teacherId: Long = 0,
    /** 课堂表现 */
    var performance: String = "",
    /** 测评结果（0-100） */
    var assessmentScore: Int = 0,
    @Enumerated(EnumType.STRING)
    var suitableClassType: ClassType = ClassType.REGULAR,
    @Enumerated(EnumType.STRING)
    var suggestedLevel: Level = Level.BASIC,
    /** 风险提醒，如 "基础薄弱，不建议保分承诺" */
    var riskWarning: String? = null,
    var createdAt: LocalDateTime = LocalDateTime.now()
)

/**
 * 试听改约记录：每一次改约都完整留痕。
 * 记录原/新老师、教室、时段（即"释放了谁、占了谁"）、改约原因、
 * 是否临近开课、顾问确认情况、第几次改约、是否主管审批、释放容量是否被候补补位。
 */
@Entity
class TrialRescheduleRecord(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var trialClassId: Long = 0,
    var leadId: Long = 0,
    /** 第几次改约（首次改约=1） */
    var seq: Int = 0,
    var fromTeacherId: Long = 0,
    var toTeacherId: Long = 0,
    var fromClassroomId: Long = 0,
    var toClassroomId: Long = 0,
    var fromStartTime: LocalDateTime = LocalDateTime.now(),
    var fromEndTime: LocalDateTime = LocalDateTime.now(),
    var toStartTime: LocalDateTime = LocalDateTime.now(),
    var toEndTime: LocalDateTime = LocalDateTime.now(),
    /** 改约原因（家长填写/顾问代填） */
    var reason: String = "",
    /** 改约时距开课小时数 */
    var hoursBeforeStart: Double = 0.0,
    /** 是否临近开课（按 app.trial.near-start-hours 判定） */
    var nearStart: Boolean = false,
    /** 顾问是否已与家长电话/当面确认 */
    var consultantConfirmed: Boolean = false,
    var confirmedBy: String? = null,
    /** 释放的原试听位是否被候补学生占用 */
    var slotTakenByWaitlist: Boolean = false,
    /** 占用原时段的候补记录ID */
    var promotedWaitlistId: Long? = null,
    var promotedLeadId: Long? = null,
    /** 是否经过顾问主管审批（第 3 次及以上） */
    var supervisorApproved: Boolean = false,
    var approvedBy: String? = null,
    var actor: String = "",
    var createdAt: LocalDateTime = LocalDateTime.now()
)

/**
 * 试听候补：目标时段（老师）已满时，其他线索可排队。
 * 有人改约释放出该老师/教室时段后，按 enqueuedAt 顺序自动补位。
 */
@Entity
class TrialWaitlist(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var leadId: Long = 0,
    var campusId: Long = 0,
    /** 期望的老师（可空=不限老师，仅排队教室时段；一般热门老师试听位候补会指定） */
    var teacherId: Long? = null,
    var classroomId: Long? = null,
    var startTime: LocalDateTime = LocalDateTime.now(),
    var endTime: LocalDateTime = LocalDateTime.now(),
    @Enumerated(EnumType.STRING)
    var status: WaitlistStatus = WaitlistStatus.WAITING,
    /** 补位后生成的新试听课ID */
    var promotedTrialClassId: Long? = null,
    var enqueuedBy: String = "",
    var enqueuedAt: LocalDateTime = LocalDateTime.now(),
    var promotedAt: LocalDateTime? = null
)

/**
 * 第 3 次及以上试听改约的主管审批单。
 * 提交第 3 次改约时仅暂存改约请求（原老师/教室档期保持占用不释放），
 * 主管批准后才执行容量回收与补位；驳回则试听保留原时段。
 */
@Entity
class RescheduleApproval(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var trialClassId: Long = 0,
    var leadId: Long = 0,
    /** 这将是第几次改约 */
    var seq: Int = 0,
    /** 申请改到的目标老师/教室（null=自动匹配） */
    var toTeacherId: Long? = null,
    var toClassroomId: Long? = null,
    var toStartTime: LocalDateTime = LocalDateTime.now(),
    var toEndTime: LocalDateTime = LocalDateTime.now(),
    var reason: String = "",
    var nearStart: Boolean = false,
    var consultantConfirmed: Boolean = false,
    var confirmedBy: String? = null,
    var requestedBy: String = "",
    @Enumerated(EnumType.STRING)
    var status: RescheduleApprovalStatus = RescheduleApprovalStatus.PENDING,
    var decidedBy: String? = null,
    var decisionNote: String? = null,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var decidedAt: LocalDateTime? = null
)
