package com.training.crm.service

import com.training.crm.common.BusinessException
import com.training.crm.domain.*
import com.training.crm.repo.*
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class PrincipalService(
    private val studentRepo: StudentRepo,
    private val leadRepo: LeadRepo,
    private val auditEventRepo: AuditEventRepo,
    private val contractRepo: ContractRepo,
    private val coursePackageRepo: CoursePackageRepo,
    private val installmentRepo: InstallmentRepo,
    private val invoiceRepo: InvoiceRepo,
    private val commissionRepo: CommissionRepo,
    private val attendanceRepo: AttendanceRepo,
    private val refundOrderRepo: RefundOrderRepo,
    private val complaintRepo: ComplaintRepo,
    private val channelFeeRepo: ChannelFeeRepo,
    private val giftRecordRepo: GiftRecordRepo,
    private val promiseRecordRepo: PromiseRecordRepo,
    private val enrollmentRepo: EnrollmentRepo,
    private val clazzRepo: ClazzRepo,
    private val campusRepo: CampusRepo,
    private val consultantRepo: ConsultantRepo,
    private val trialClassRepo: TrialClassRepo,
    private val trialFeedbackRepo: TrialFeedbackRepo,
    private val rescheduleRecordRepo: TrialRescheduleRecordRepo,
    private val waitlistRepo: TrialWaitlistRepo
) {

    /**
     * 校长视角：学员从线索进入、试听体验、报名承诺、实际上课到退费结束的完整履约轨迹。
     */
    fun timeline(studentId: Long): Map<String, Any?> {
        val student = studentRepo.findById(studentId).orElseThrow { BusinessException("NOT_FOUND", "学员不存在: $studentId") }
        val lead = leadRepo.findById(student.leadId).orElse(null)
        val events = auditEventRepo.findByStudentIdOrLeadIdOrderByCreatedAtAsc(studentId, student.leadId)

        val contracts = contractRepo.findByStudentId(studentId).map { c ->
            val pkg = coursePackageRepo.findById(c.coursePackageId).get()
            val atts = attendanceRepo.findByContractId(c.id)
            val installments = installmentRepo.findByContractId(c.id)
            mapOf(
                "contractId" to c.id,
                "status" to c.status,
                "package" to pkg.name,
                "finalAmount" to c.finalAmount,
                "paidLessons" to c.paidLessons,
                "giftLessons" to c.giftLessons,
                "consumedPaid" to atts.sumOf { it.consumedPaid },
                "consumedGift" to atts.sumOf { it.consumedGift },
                "received" to installments.filter { it.status == InstallmentStatus.PAID }.fold(BigDecimal.ZERO) { a, i -> a.add(i.amount) },
                "installments" to installments.map { mapOf("seq" to it.seq, "amount" to it.amount, "status" to it.status, "dueDate" to it.dueDate) },
                "invoices" to invoiceRepo.findByContractId(c.id).map { mapOf("amount" to it.amount, "title" to it.title, "status" to it.status) },
                "commissions" to commissionRepo.findByContractId(c.id).map { mapOf("amount" to it.amount, "status" to it.status) },
                "consistencyWarnings" to c.consistencyWarnings
            )
        }

        return mapOf(
            "student" to student,
            "lead" to lead,
            "promises" to promiseRecordRepo.findByStudentId(studentId),
            "channelFees" to (lead?.let { channelFeeRepo.findByLeadId(it.id) } ?: emptyList<ChannelFee>()),
            "gifts" to (lead?.let { giftRecordRepo.findByLeadId(it.id) } ?: emptyList<GiftRecord>()),
            "enrollments" to enrollmentRepo.findByStudentId(studentId).map { en ->
                mapOf("clazz" to clazzRepo.findById(en.clazzId).get().name, "status" to en.status, "midTermInsertion" to en.midTermInsertion, "joinedAt" to en.joinedAt)
            },
            "contracts" to contracts,
            "refunds" to refundOrderRepo.findByStudentId(studentId),
            "complaints" to complaintRepo.findByStudentId(studentId),
            "events" to events.map { mapOf("time" to it.createdAt, "type" to it.eventType, "detail" to it.detail, "actor" to it.actor) }
        )
    }

    /** 经营概览：各校区线索/试听/转化/回款/退费/提成/渠道成本 */
    fun overview(): Map<String, Any?> {
        val leads = leadRepo.findAll()
        val contracts = contractRepo.findAll()
        val perCampus = campusRepo.findAll().map { campus ->
            val campusLeads = leads.filter { it.campusId == campus.id }
            val campusContracts = contracts.filter { it.campusId == campus.id }
            val received = campusContracts.flatMap { installmentRepo.findByContractId(it.id) }
                .filter { it.status == InstallmentStatus.PAID }.fold(BigDecimal.ZERO) { a, i -> a.add(i.amount) }
            mapOf(
                "campus" to campus.name,
                "leads" to campusLeads.size,
                "converted" to campusLeads.count { it.status == LeadStatus.CONVERTED },
                "contracts" to campusContracts.size,
                "received" to received,
                "refunded" to refundOrderRepo.findAll().filter { it.status == RefundStatus.PAID }
                    .filter { r -> campusContracts.any { it.id == r.contractId } }
                    .fold(BigDecimal.ZERO) { a, r -> a.add(r.refundAmount) }
            )
        }
        val perConsultant = consultantRepo.findAll().map { c ->
            val commissions = commissionRepo.findByConsultantId(c.id)
            mapOf(
                "consultant" to c.name,
                "leads" to leads.count { it.consultantId == c.id },
                "converted" to leads.count { it.consultantId == c.id && it.status == LeadStatus.CONVERTED },
                "commissionAccrued" to commissions.filter { it.status == CommissionStatus.ACCRUED }.fold(BigDecimal.ZERO) { a, x -> a.add(x.amount) },
                "commissionFrozen" to commissions.filter { it.status == CommissionStatus.FROZEN }.fold(BigDecimal.ZERO) { a, x -> a.add(x.amount) },
                "commissionClawedBack" to commissions.filter { it.status == CommissionStatus.CLAWED_BACK }.fold(BigDecimal.ZERO) { a, x -> a.add(x.amount) }
            )
        }
        return mapOf(
            "campuses" to perCampus,
            "consultants" to perConsultant,
            "channelCosts" to leads.flatMap { channelFeeRepo.findByLeadId(it.id) }
                .groupBy { it.channel }
                .mapValues { entry -> entry.value.fold(BigDecimal.ZERO) { a, f -> a.add(f.amount) } }
        )
    }

    /**
     * 试听反馈 → 线索转化统计。
     * - 按测评分数段（<60 / 60-74 / 75-89 / >=90）统计试听完成数、转化数、流失数、转化率；
     * - 按改约次数（0 / 1-2 / >=3 次含主管审批）统计转化情况；
     * - 风险提醒对转化的影响、临近开课改约、容量被候补占用的转化情况。
     */
    fun conversionStats(): Map<String, Any?> {
        val leads = leadRepo.findAll()
        // 每条线索取其最近一次有反馈的试听；改约统计按线索下所有试听课汇总
        data class Row(
            val lead: Lead,
            val trial: TrialClass,
            val feedback: TrialFeedback,
            val rescheduleTotal: Int,
            val slotReclaimed: Boolean,
            val nearStart: Boolean
        )
        val allRecords = rescheduleRecordRepo.findAll()
        val rows = leads.mapNotNull { lead ->
            val trials = trialClassRepo.findByLeadId(lead.id)
            val withFeedback = trials.mapNotNull { t ->
                trialFeedbackRepo.findByTrialClassId(t.id)?.let { t to it }
            }
            val leadRecords = allRecords.filter { it.leadId == lead.id }
            withFeedback.maxByOrNull { it.second.createdAt }?.let { (t, f) ->
                Row(
                    lead = lead,
                    trial = t,
                    feedback = f,
                    rescheduleTotal = leadRecords.size,
                    slotReclaimed = leadRecords.any { it.slotTakenByWaitlist },
                    nearStart = leadRecords.any { it.nearStart }
                )
            }
        }

        fun bucket(score: Int): String = when {
            score < 60 -> "score_lt_60"
            score < 75 -> "score_60_74"
            score < 90 -> "score_75_89"
            else -> "score_ge_90"
        }

        fun summarize(group: List<Row>): Map<String, Any?> {
            val converted = group.count { it.lead.status == LeadStatus.CONVERTED }
            val lost = group.count { it.lead.status == LeadStatus.LOST }
            val done = group.size
            return mapOf(
                "trialDone" to done,
                "converted" to converted,
                "lost" to lost,
                "pending" to (done - converted - lost),
                "conversionRate" to if (done == 0) 0.0 else Math.round(converted * 10000.0 / done) / 100.0
            )
        }

        val byScore = rows.groupBy { bucket(it.feedback.assessmentScore) }
            .mapValues { summarize(it.value) }
        val byReschedule = rows.groupBy { r ->
            when {
                r.rescheduleTotal >= 3 -> "reschedule_ge_3_supervisor"
                r.rescheduleTotal >= 1 -> "reschedule_1_2"
                else -> "reschedule_0"
            }
        }.mapValues { summarize(it.value) }

        val withRisk = rows.filter { !it.feedback.riskWarning.isNullOrBlank() }

        return mapOf(
            "totalTrialsScheduled" to trialClassRepo.findAll().size,
            "totalTrialsAttended" to trialClassRepo.findAll().count {
                it.status == TrialStatus.ATTENDED || trialFeedbackRepo.findByTrialClassId(it.id) != null
            },
            "waitlist" to mapOf(
                "waiting" to waitlistRepo.findAll().count { it.status == WaitlistStatus.WAITING },
                "promoted" to waitlistRepo.findAll().count { it.status == WaitlistStatus.PROMOTED }
            ),
            "byAssessmentScore" to byScore,
            "byRescheduleCount" to byReschedule,
            "riskWarningImpact" to summarize(withRisk),
            "nearStartRescheduleImpact" to summarize(rows.filter { it.nearStart }),
            "slotReclaimedRequeueImpact" to summarize(rows.filter { it.slotReclaimed }),
            "overall" to summarize(rows)
        )
    }
}
