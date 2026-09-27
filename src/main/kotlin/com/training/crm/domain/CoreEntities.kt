package com.training.crm.domain

import jakarta.persistence.*
import java.time.LocalTime

/** 校区 */
@Entity
class Campus(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var name: String = "",
    var city: String = ""
)

/** 教室 */
@Entity
class Classroom(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var campusId: Long = 0,
    var name: String = "",
    var capacity: Int = 1
)

/** 课程顾问 */
@Entity
class Consultant(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var name: String = "",
    var campusId: Long = 0,
    var phone: String = ""
)

/**
 * 老师。多校区共享：campusIds 以逗号分隔保存可授课校区；
 * subjects 以逗号分隔保存可授科目。
 */
@Entity
class Teacher(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var name: String = "",
    var subjects: String = "",
    var campusIds: String = "",
    var phone: String = ""
) {
    fun subjectSet(): Set<Subject> = subjects.split(',').filter { it.isNotBlank() }.map { Subject.valueOf(it.trim()) }.toSet()
    fun campusIdSet(): Set<Long> = campusIds.split(',').filter { it.isNotBlank() }.map { it.trim().toLong() }.toSet()
}

/** 老师空档（每周重复的可用时间段） */
@Entity
class TeacherAvailability(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    var teacherId: Long = 0,
    /** 1=周一 … 7=周日 */
    var dayOfWeek: Int = 1,
    var startTime: LocalTime = LocalTime.MIDNIGHT,
    var endTime: LocalTime = LocalTime.MIDNIGHT
)
