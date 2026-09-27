package com.training.crm.domain

import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalDateTime

/** 班级 */
@Entity
class Clazz(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var name: String = "",
    var campusId: Long = 0,
    @Enumerated(EnumType.STRING)
    var subject: Subject = Subject.MATH,
    @Enumerated(EnumType.STRING)
    var level: Level = Level.BASIC,
    @Enumerated(EnumType.STRING)
    var classType: ClassType = ClassType.REGULAR,
    var teacherId: Long = 0,
    var classroomId: Long = 0,
    var capacity: Int = 1,
    var textbookVersion: String = "",
    var scheduleDesc: String = "",
    /** OPEN / CLOSED */
    var status: String = "OPEN"
)

/** 报班记录（学员 ↔ 班级 ↔ 合同） */
@Entity
class Enrollment(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var studentId: Long = 0,
    var clazzId: Long = 0,
    var contractId: Long = 0,
    /** 是否插班（开班后中途加入） */
    var midTermInsertion: Boolean = false,
    var joinedAt: LocalDateTime = LocalDateTime.now(),
    @Enumerated(EnumType.STRING)
    var status: EnrollmentStatus = EnrollmentStatus.ACTIVE
)

/** 正式课课时 */
@Entity
class Lesson(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var clazzId: Long = 0,
    var teacherId: Long = 0,
    var classroomId: Long = 0,
    var startTime: LocalDateTime = LocalDateTime.now(),
    var endTime: LocalDateTime = LocalDateTime.now(),
    var topic: String = "",
    @Enumerated(EnumType.STRING)
    var status: LessonStatus = LessonStatus.SCHEDULED
)

/** 课消记录：每个学员每节课一条 */
@Entity
class Attendance(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var lessonId: Long = 0,
    var studentId: Long = 0,
    var contractId: Long = 0,
    @Enumerated(EnumType.STRING)
    var status: AttendStatus = AttendStatus.PENDING,
    /** 本节课消耗的付费课时 */
    var consumedPaid: Int = 0,
    /** 本节课消耗的赠课课时 */
    var consumedGift: Int = 0,
    var recordedAt: LocalDateTime? = null
)

/** 课程请假单 */
@Entity
class LeaveRequest(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var studentId: Long = 0,
    var lessonId: Long = 0,
    var reason: String = "",
    @Enumerated(EnumType.STRING)
    var status: LeaveStatus = LeaveStatus.PENDING,
    var createdAt: LocalDateTime = LocalDateTime.now(),
    var handledBy: String? = null,
    var handledAt: LocalDateTime? = null
)

/** 转班记录 */
@Entity
class TransferRecord(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var studentId: Long = 0,
    var fromClazzId: Long = 0,
    var toClazzId: Long = 0,
    var reason: String = "",
    var createdAt: LocalDateTime = LocalDateTime.now()
)

/** 停课记录 */
@Entity
class Suspension(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var studentId: Long = 0,
    var reason: String = "",
    var startDate: LocalDate = LocalDate.now(),
    var endDate: LocalDate? = null,
    /** ACTIVE / CLOSED */
    var status: String = "ACTIVE",
    var createdAt: LocalDateTime = LocalDateTime.now()
)
