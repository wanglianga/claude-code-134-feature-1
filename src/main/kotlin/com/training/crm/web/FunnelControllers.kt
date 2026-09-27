package com.training.crm.web

import com.training.crm.service.*
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/leads")
class LeadController(private val leadService: LeadService) {

    @PostMapping
    fun create(
        @RequestBody req: LeadCreateReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?,
        @RequestHeader(value = "X-Idempotency-Key", required = false) idemKey: String?
    ): Any {
        val (existed, lead) = leadService.createLead(req, idemKey, resolveActor(role, actor))
        return mapOf("idempotentReplay" to existed, "lead" to lead)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long) = leadService.get(id)

    @PostMapping("/{id}/campus")
    fun changeCampus(
        @PathVariable id: Long,
        @RequestBody req: Map<String, Long>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = leadService.changeCampus(id, req["campusId"]!!, resolveActor(role, actor))

    @PostMapping("/{id}/promises")
    fun addPromise(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = leadService.addPromise(id, req["content"]!!, req["promisedBy"] ?: resolveActor(role, actor), resolveActor(role, actor))

    @PostMapping("/{id}/lost")
    fun markLost(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = leadService.markLost(id, req["reason"] ?: "未说明", resolveActor(role, actor))
}

@RestController
@RequestMapping("/api/trials")
class TrialController(private val trialService: TrialService) {

    @PostMapping("/book")
    fun book(
        @RequestBody req: TrialBookReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.book(req, resolveActor(role, actor))

    @PostMapping("/{id}/reschedule")
    fun reschedule(
        @PathVariable id: Long,
        @RequestBody req: TrialRescheduleReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.reschedule(id, req, resolveActor(role, actor))

    @PostMapping("/{id}/attend")
    fun attend(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.attend(id, resolveActor(role, actor))

    @PostMapping("/{id}/absent")
    fun absent(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.absent(id, resolveActor(role, actor))

    @PostMapping("/{id}/teacher-leave")
    fun teacherLeave(
        @PathVariable id: Long,
        @RequestBody req: Map<String, String>,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.teacherLeave(id, req["reason"] ?: "老师临时请假", resolveActor(role, actor))

    @PostMapping("/{id}/feedback")
    fun feedback(
        @PathVariable id: Long,
        @RequestBody req: FeedbackReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = trialService.submitFeedback(id, req, resolveActor(role, actor))

    @GetMapping("/{id}")
    fun get(@PathVariable id: Long) = trialService.get(id)
}

@RestController
@RequestMapping("/api/conversions")
class ConversionController(private val conversionService: ConversionService) {

    @PostMapping
    fun convert(
        @RequestBody req: ConvertReq,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = conversionService.convert(req, resolveActor(role, actor))

    @PostMapping("/installments/{id}/pay")
    fun payInstallment(
        @PathVariable id: Long,
        @RequestHeader("X-Role") role: String,
        @RequestHeader(value = "X-Actor", required = false) actor: String?
    ) = conversionService.payInstallment(id, resolveActor(role, actor))
}
