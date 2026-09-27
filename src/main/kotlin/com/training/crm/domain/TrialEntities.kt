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
