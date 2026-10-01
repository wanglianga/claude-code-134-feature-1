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
    /** 多次改约标记的转化风险（NONE/RISK/HIGH_RISK），供顾问端风险视图使用 */
    var conversionRisk: String = "NONE",
    /** 改约释放的档位曾被候补学生占用（原家长再选同一时段需重新排队） */
    var releasedSlotTaken: Boolean = false,
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
    /** 该反馈对线索转化的影响（默认按分数/风险推断，老师可显式给出） */
    @Enumerated(EnumType.STRING)
    var conversionImpact: ConversionImpact = ConversionImpact.NEUTRAL,
    var createdAt: LocalDateTime = LocalDateTime.now()
)

/**
 * 试听改约记录：每次改约一条，保存改约前后的老师/教室/时段快照。
 * 记录改约原因、是否临近开课、顾问确认情况、审批情况，
 * 以及释放出的原档位是否被候补学生占用——学生到课后老师可看到完整改约历史。
 */
@Entity
class TrialRescheduleRecord(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var trialClassId: Long = 0,
    var leadId: Long = 0,
    /** 第几次改约（从 1 开始） */
    var seq: Int = 0,
    // 原档期快照
    var fromTeacherId: Long = 0,
    var fromClassroomId: Long = 0,
    var fromStartTime: LocalDateTime = LocalDateTime.now(),
    var fromEndTime: LocalDateTime = LocalDateTime.now(),
    // 新档期
    var toTeacherId: Long = 0,
    var toClassroomId: Long = 0,
    var toStartTime: LocalDateTime = LocalDateTime.now(),
    var toEndTime: LocalDateTime = LocalDateTime.now(),
    /** 改约原因（家长给出） */
    var reason: String = "",
    /** 是否临近开课（距开课 <= app.trial-near-start-hours 小时） */
    var nearStart: Boolean = false,
    /** 顾问是否已确认该次改约 */
    var consultantConfirmed: Boolean = false,
    /** 确认/操作的顾问 */
    var confirmedBy: String? = null,
    /** 第三次起的主管审批单ID（前两次为空） */
    var approvalId: Long? = null,
    /** 本次释放的原档位是否被候补学生占用 */
    var releasedSlotTakenByWaitlist: Boolean = false,
    /** 占用该档位的候补试听ID（如有） */
    var takenByTrialId: Long? = null,
    var createdAt: LocalDateTime = LocalDateTime.now()
)

/**
 * 试听候补：目标老师/教室的目标时段已满（或被改约释放前就想占），学生排队等位。
 * 有人改约/取消释放档位时，按入队顺序提升队首并自动占用释放档期。
 */
@Entity
class TrialWaitlistEntry(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var leadId: Long = 0,
    var teacherId: Long? = null,
    var classroomId: Long? = null,
    var campusId: Long = 0,
    var wantedStartTime: LocalDateTime = LocalDateTime.now(),
    var wantedEndTime: LocalDateTime = LocalDateTime.now(),
    @Enumerated(EnumType.STRING)
    var classType: ClassType = ClassType.ONE_ON_ONE,
    @Enumerated(EnumType.STRING)
    var status: WaitlistStatus = WaitlistStatus.WAITING,
    /** 提升转正后生成的试听ID */
    var promotedTrialId: Long? = null,
    var note: String? = null,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var updatedAt: LocalDateTime = LocalDateTime.now()
)

/**
 * 第三次（含）以后改约的主管审批单：避免热门老师试听位被长期占用。
 * 审批通过后改约才真正执行并释放/重分配容量；驳回则维持原档期。
 */
@Entity
class TrialScheduleApproval(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var trialClassId: Long = 0,
    var leadId: Long = 0,
    /** 第几次改约 */
    var seq: Int = 0,
    // 期望改到的新档期（提交时锁定快照）
    var toTeacherId: Long = 0,
    var toClassroomId: Long = 0,
    var toStartTime: LocalDateTime = LocalDateTime.now(),
    var toEndTime: LocalDateTime = LocalDateTime.now(),
    var reason: String = "",
    var nearStart: Boolean = false,
    var consultantConfirmed: Boolean = false,
    @Enumerated(EnumType.STRING)
    var status: TrialApprovalStatus = TrialApprovalStatus.PENDING,
    var submittedBy: String = "",
    var decidedBy: String? = null,
    var decisionNote: String? = null,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var decidedAt: LocalDateTime? = null
)

/**
 * 家长关注点：顾问随改约/预约登记（如“时间敏感、只接受张老师”），
 * 学生到课后随改约历史一起呈现给老师，帮助老师把握课堂沟通重点。
 */
@Entity
class ParentConcern(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var leadId: Long = 0,
    var trialClassId: Long? = null,
    var content: String = "",
    var createdBy: String = "",
    var createdAt: LocalDateTime = LocalDateTime.now()
)
