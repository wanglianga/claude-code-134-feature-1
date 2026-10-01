package com.training.crm.web

import com.training.crm.service.*
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@RestController
@RequestMapping("/api/courses")
class CourseController(private val courseService: CourseService) {

    @PostMapping("/classes/{clazzId}/enroll")
    fun enroll(
        @PathVariable clazzId: Long,
        @RequestBody req: Map<String, Any>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = courseService.enroll(
        clazzId,
        (req["studentId"] as Number).toLong(),
        (req["contractId"] as Number).toLong(),
        req["force"] as? Boolean ?: false,
        resolveActor(role, actor)
    )

    @PostMapping("/lessons")
    fun scheduleLesson(
        @RequestBody req: LessonScheduleReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = courseService.scheduleLesson(req, resolveActor(role, actor))

    @PostMapping("/lessons/{id}/complete")
    fun completeLesson(
        @PathVariable id: Long,
        @RequestBody(required = false) req: Map<String, List<Long>>?,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = courseService.completeLesson(id, req?.get("absentStudentIds") ?: emptyList(), resolveActor(role, actor))

    @PostMapping("/lessons/{id}/teacher-leave")
    fun teacherLeaveLesson(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = courseService.teacherLeaveLesson(id, req["reason"] ?: "老师临时请假", resolveActor(role, actor))

    @GetMapping("/contracts/{contractId}/consumption")
    fun consumption(@PathVariable contractId: Long): Any {
        val (paid, gift) = courseService.consumptionOf(contractId)
        return mapOf("contractId" to contractId, "consumedPaid" to paid, "consumedGift" to gift)
    }
}

@RestController
@RequestMapping("/api/students")
class StudentOpsController(private val ops: StudentOpsService) {

    @PostMapping("/{id}/leave")
    fun requestLeave(
        @PathVariable id: Long,
        @RequestBody req: Map<String, Any>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.requestLeave(id, (req["lessonId"] as Number).toLong(), req["reason"] as? String ?: "", resolveActor(role, actor))

    @PostMapping("/leave-requests/{id}/approve")
    fun approveLeave(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.approveLeave(id, resolveActor(role, actor))

    @PostMapping("/leave-requests/{id}/reject")
    fun rejectLeave(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.rejectLeave(id, resolveActor(role, actor))

    @PostMapping("/{id}/transfer")
    fun transfer(
        @PathVariable id: Long,
        @RequestBody req: Map<String, Any>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.transfer(id, (req["toClazzId"] as Number).toLong(), req["reason"] as? String ?: "", resolveActor(role, actor))

    @PostMapping("/{id}/suspend")
    fun suspend(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.suspend(
        id,
        req["reason"] ?: "",
        LocalDate.parse(req["startDate"]!!),
        req["endDate"]?.let { LocalDate.parse(it) },
        resolveActor(role, actor)
    )

    @PostMapping("/{id}/resume")
    fun resume(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.resume(id, resolveActor(role, actor))

    @PostMapping("/{id}/complaints")
    fun fileComplaint(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.fileComplaint(id, req["content"]!!, resolveActor(role, actor))

    @PostMapping("/complaints/{id}/resolve")
    fun resolveComplaint(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = ops.resolveComplaint(id, resolveActor(role, actor))
}

@RestController
@RequestMapping("/api/refunds")
class RefundController(private val refundService: RefundService) {

    /** 退费试算 */
    @GetMapping("/preview/{studentId}")
    fun preview(@PathVariable studentId: Long) = refundService.preview(studentId)

    @PostMapping
    fun request(
        @RequestBody req: Map<String, Any>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?,
        @RequestHeader(value = "X-Idempotency-Key", required = false) idemKey: String?
    ) = refundService.request(
        (req["studentId"] as Number).toLong(),
        req["reason"] as? String ?: "",
        idemKey,
        resolveActor(role, actor)
    )

    @PostMapping("/{id}/approve")
    fun approve(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = refundService.approve(id, resolveActor(role, actor))

    @PostMapping("/{id}/reject")
    fun reject(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = refundService.reject(id, resolveActor(role, actor), req["reason"] ?: "")

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long) = refundService.get(id)
}

@RestController
@RequestMapping("/api/principal")
class PrincipalController(private val principalService: PrincipalService) {

    /** 校长视角：学员完整履约轨迹 */
    @GetMapping("/students/{id}/timeline")
    fun timeline(@PathVariable id: Long) = principalService.timeline(id)

    /** 经营概览 */
    @GetMapping("/overview")
    fun overview() = principalService.overview()

    /** 试听反馈影响线索转化的统计：按测评分数段/改约次数/风险提醒/容量回收分组 */
    @GetMapping("/conversion-stats")
    fun conversionStats() = principalService.conversionStats()
}
