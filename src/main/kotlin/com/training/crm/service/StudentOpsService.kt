package com.training.crm.service

import com.training.crm.common.AuditService
import com.training.crm.common.BusinessException
import com.training.crm.common.LockService
import com.training.crm.common.bizFail
import com.training.crm.domain.*
import com.training.crm.repo.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime

@Service
class StudentOpsService(
    private val studentRepo: StudentRepo,
    private val contractRepo: ContractRepo,
    private val leaveRequestRepo: LeaveRequestRepo,
    private val attendanceRepo: AttendanceRepo,
    private val lessonRepo: LessonRepo,
    private val enrollmentRepo: EnrollmentRepo,
    private val clazzRepo: ClazzRepo,
    private val transferRecordRepo: TransferRecordRepo,
    private val suspensionRepo: SuspensionRepo,
    private val complaintRepo: ComplaintRepo,
    private val coursePackageRepo: CoursePackageRepo,
    private val lockService: LockService,
    private val auditService: AuditService
) {

    // ---------- 请假 ----------

    @Transactional
    fun requestLeave(studentId: Long, lessonId: Long, reason: String, actor: String): LeaveRequest {
        val att = attendanceRepo.findByLessonIdAndStudentId(lessonId, studentId)
            ?: bizFail("NOT_FOUND", "该学员没有这节课的出勤记录")
        if (att.status == AttendStatus.LEAVE) bizFail("INVALID_STATE", "该课时已是请假状态")
        if (leaveRequestRepo.findByStudentIdAndLessonIdAndStatus(studentId, lessonId, LeaveStatus.PENDING).isNotEmpty()) {
            bizFail("DUPLICATE", "已有待审批的请假单")
        }
        val req = leaveRequestRepo.save(LeaveRequest(studentId = studentId, lessonId = lessonId, reason = reason))
        auditService.record("LEAVE_REQUESTED", "课程请假：课时#$lessonId，原因=$reason", studentId = studentId, actor = actor)
        return req
    }

    /**
     * 批准请假：出勤记录改为 LEAVE 不扣课时；
     * 若课时已完成且已扣课时，则回退已扣课时（返还到合同余额）。
     */
    @Transactional
    fun approveLeave(leaveId: Long, handler: String): LeaveRequest {
        val req = leaveRequestRepo.findById(leaveId).orElseThrow { BusinessException("NOT_FOUND", "请假单不存在: $leaveId") }
        if (req.status != LeaveStatus.PENDING) bizFail("INVALID_STATE", "请假单已处理")
        req.status = LeaveStatus.APPROVED
        req.handledBy = handler
        req.handledAt = LocalDateTime.now()
        val att = attendanceRepo.findByLessonIdAndStudentId(req.lessonId, req.studentId)
        if (att != null && att.status != AttendStatus.LEAVE) {
            val refundedPaid = att.consumedPaid
            val refundedGift = att.consumedGift
            att.status = AttendStatus.LEAVE
            att.consumedPaid = 0
            att.consumedGift = 0
            attendanceRepo.save(att)
            if (refundedPaid + refundedGift > 0) {
                auditService.record(
                    "CONSUMPTION_REVERSED",
                    "请假批准，返还已扣课时：付费=$refundedPaid 赠课=$refundedGift（课时#${req.lessonId}）",
                    studentId = req.studentId, actor = handler
                )
            }
        }
        auditService.record("LEAVE_APPROVED", "请假批准：课时#${req.lessonId}", studentId = req.studentId, actor = handler)
        return leaveRequestRepo.save(req)
    }

    @Transactional
    fun rejectLeave(leaveId: Long, handler: String): LeaveRequest {
        val req = leaveRequestRepo.findById(leaveId).orElseThrow { BusinessException("NOT_FOUND", "请假单不存在: $leaveId") }
        if (req.status != LeaveStatus.PENDING) bizFail("INVALID_STATE", "请假单已处理")
        req.status = LeaveStatus.REJECTED
        req.handledBy = handler
        req.handledAt = LocalDateTime.now()
        auditService.record("LEAVE_REJECTED", "请假驳回：课时#${req.lessonId}", studentId = req.studentId, actor = handler)
        return leaveRequestRepo.save(req)
    }

    // ---------- 转班 ----------

    /**
     * 转班：原班级退出，进入新班级（可跨校区）。
     * 校验新班容量与科目一致；原班未上课时不再扣减，新班未上课时补出勤记录。
     */
    @Transactional
    fun transfer(studentId: Long, toClazzId: Long, reason: String, actor: String): TransferRecord {
        val from = enrollmentRepo.findByStudentIdAndStatus(studentId, EnrollmentStatus.ACTIVE).firstOrNull()
            ?: bizFail("NOT_FOUND", "学员没有在读班级")
        val to = clazzRepo.findById(toClazzId).orElseThrow { BusinessException("NOT_FOUND", "目标班级不存在: $toClazzId") }
        if (to.status != "OPEN") bizFail("INVALID_STATE", "目标班级已关闭")
        if (from.clazzId == toClazzId) bizFail("INVALID_STATE", "学员已在目标班级")
        val contract = contractRepo.findById(from.contractId).get()
        val pkg = coursePackageRepo.findById(contract.coursePackageId).get()
        if (pkg.subject != to.subject) bizFail("SUBJECT_MISMATCH", "合同科目(${pkg.subject})与目标班级科目(${to.subject})不一致，需先办理课程变更")

        return lockService.withLock("clazz:$toClazzId") {
            val activeCount = enrollmentRepo.findByClazzIdAndStatus(toClazzId, EnrollmentStatus.ACTIVE).size
            if (activeCount >= to.capacity) bizFail("CLASS_FULL", "目标班级容量已满(${to.capacity})")

            from.status = EnrollmentStatus.TRANSFERRED_OUT
            enrollmentRepo.save(from)
            // 原班未上课时出勤记录清理
            val fromClazz = clazzRepo.findById(from.clazzId).get()
            lessonRepo.findByClazzIdAndStatus(from.clazzId, LessonStatus.SCHEDULED).forEach { lesson ->
                attendanceRepo.findByLessonIdAndStudentId(lesson.id, studentId)?.let { att ->
                    if (att.status == AttendStatus.PENDING) attendanceRepo.delete(att)
                }
            }
            // 新班报班记录 + 未上课时补出勤
            val midTerm = lessonRepo.findByClazzIdAndStatus(toClazzId, LessonStatus.COMPLETED).isNotEmpty()
            enrollmentRepo.save(
                Enrollment(studentId = studentId, clazzId = toClazzId, contractId = from.contractId, midTermInsertion = midTerm)
            )
            lessonRepo.findByClazzIdAndStatus(toClazzId, LessonStatus.SCHEDULED).forEach { lesson ->
                attendanceRepo.save(Attendance(lessonId = lesson.id, studentId = studentId, contractId = from.contractId))
            }
            val rec = transferRecordRepo.save(
                TransferRecord(studentId = studentId, fromClazzId = fromClazz.id, toClazzId = toClazzId, reason = reason)
            )
            auditService.record(
                "TRANSFERRED",
                "转班：${fromClazz.name}(#${fromClazz.id}) → ${to.name}(#$toClazzId)，原因=$reason",
                studentId = studentId, actor = actor
            )
            rec
        }
    }

    // ---------- 停课 / 复课 ----------

    /** 停课：学员与合同挂起，停课期间未上课时按请假处理不扣课时 */
    @Transactional
    fun suspend(studentId: Long, reason: String, startDate: LocalDate, endDate: LocalDate?, actor: String): Suspension {
        val student = studentRepo.findById(studentId).orElseThrow { BusinessException("NOT_FOUND", "学员不存在: $studentId") }
        if (student.status != StudentStatus.ACTIVE) bizFail("INVALID_STATE", "学员状态(${student.status})不能停课")
        student.status = StudentStatus.SUSPENDED
        studentRepo.save(student)
        contractRepo.findByStudentId(studentId).filter { it.status == ContractStatus.ACTIVE }.forEach {
            it.status = ContractStatus.SUSPENDED
            contractRepo.save(it)
        }
        // 停课期间未上课时 → 请假不扣课时
        val end = endDate ?: startDate.plusYears(1)
        enrollmentRepo.findByStudentIdAndStatus(studentId, EnrollmentStatus.ACTIVE).forEach { en ->
            lessonRepo.findByClazzIdAndStatus(en.clazzId, LessonStatus.SCHEDULED)
                .filter { !it.startTime.toLocalDate().isBefore(startDate) && !it.startTime.toLocalDate().isAfter(end) }
                .forEach { lesson ->
                    attendanceRepo.findByLessonIdAndStudentId(lesson.id, studentId)?.let { att ->
                        if (att.status == AttendStatus.PENDING) {
                            att.status = AttendStatus.LEAVE
                            attendanceRepo.save(att)
                        }
                    }
                }
        }
        val sus = suspensionRepo.save(Suspension(studentId = studentId, reason = reason, startDate = startDate, endDate = endDate))
        auditService.record("SUSPENDED", "停课：$startDate ~ ${endDate ?: "待定"}，原因=$reason", studentId = studentId, actor = actor)
        return sus
    }

    @Transactional
    fun resume(studentId: Long, actor: String): Student {
        val student = studentRepo.findById(studentId).orElseThrow { BusinessException("NOT_FOUND", "学员不存在: $studentId") }
        if (student.status != StudentStatus.SUSPENDED) bizFail("INVALID_STATE", "学员未处于停课状态")
        student.status = StudentStatus.ACTIVE
        studentRepo.save(student)
        contractRepo.findByStudentId(studentId).filter { it.status == ContractStatus.SUSPENDED }.forEach {
            it.status = ContractStatus.ACTIVE
            contractRepo.save(it)
        }
        suspensionRepo.findByStudentIdAndStatus(studentId, "ACTIVE").forEach {
            it.status = "CLOSED"
            it.endDate = LocalDate.now()
            suspensionRepo.save(it)
        }
        auditService.record("RESUMED", "复课", studentId = studentId, actor = actor)
        return student
    }

    // ---------- 投诉 ----------

    @Transactional
    fun fileComplaint(studentId: Long, content: String, actor: String): Complaint {
        studentRepo.findById(studentId).orElseThrow { BusinessException("NOT_FOUND", "学员不存在: $studentId") }
        val c = complaintRepo.save(Complaint(studentId = studentId, content = content))
        auditService.record("COMPLAINT_FILED", "投诉登记：$content", studentId = studentId, actor = actor)
        return c
    }

    @Transactional
    fun resolveComplaint(complaintId: Long, actor: String): Complaint {
        val c = complaintRepo.findById(complaintId).orElseThrow { BusinessException("NOT_FOUND", "投诉不存在: $complaintId") }
        c.status = ComplaintStatus.RESOLVED
        auditService.record("COMPLAINT_RESOLVED", "投诉结案：${c.content}", studentId = c.studentId, actor = actor)
        return complaintRepo.save(c)
    }
}
