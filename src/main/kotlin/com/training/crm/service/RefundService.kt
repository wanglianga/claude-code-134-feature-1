package com.training.crm.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.training.crm.common.AuditService
import com.training.crm.common.BusinessException
import com.training.crm.common.LockService
import com.training.crm.common.bizFail
import com.training.crm.domain.*
import com.training.crm.repo.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/**
 * 退费计算规则（与 README 一致）：
 * - 课时单价 = 实付金额 / 付费课时数
 * - 基础退费 = 剩余付费课时 × 单价（赠课不退现）
 * - 赠课抵扣：consumeOrder=GIFT_FIRST 时，已消耗赠课按单价从应退中抵扣
 * - 教材费：教材已发放则全额扣除
 * - 优惠回收：已上课时占比低于课包 discountKeepThreshold 时，全额回收优惠
 * - 历史投诉：每条未结投诉增加固定赔付（app.complaint-compensation）
 * - 顾问提成：退费申请即冻结，退款完成后追回（不计入退给家长的金额，仅展示）
 * - 退费金额以已收款为上限；未收分期全部减免
 */
@Service
class RefundService(
    private val studentRepo: StudentRepo,
    private val contractRepo: ContractRepo,
    private val coursePackageRepo: CoursePackageRepo,
    private val attendanceRepo: AttendanceRepo,
    private val installmentRepo: InstallmentRepo,
    private val commissionRepo: CommissionRepo,
    private val complaintRepo: ComplaintRepo,
    private val refundOrderRepo: RefundOrderRepo,
    private val enrollmentRepo: EnrollmentRepo,
    private val lessonRepo: LessonRepo,
    private val lockService: LockService,
    private val auditService: AuditService,
    @Value("\${app.complaint-compensation}") private val complaintCompensation: BigDecimal
) {
    private val mapper = jacksonObjectMapper()

    data class RefundBreakdown(
        val contractId: Long,
        val paidLessons: Int,
        val giftLessons: Int,
        val consumedPaidLessons: Int,
        val consumedGiftLessons: Int,
        val remainingPaidLessons: Int,
        val unitPrice: BigDecimal,
        val baseRefund: BigDecimal,
        val materialFeeDeducted: BigDecimal,
        val discountRecovered: BigDecimal,
        val giftValueOffset: BigDecimal,
        val openComplaints: Int,
        val complaintCompensation: BigDecimal,
        val commissionFrozen: BigDecimal,
        val totalReceived: BigDecimal,
        val unpaidInstallmentsWaived: BigDecimal,
        val refundAmount: BigDecimal
    )

    /** 退费试算（不落库） */
    fun preview(studentId: Long): RefundBreakdown {
        val contract = activeContractOf(studentId)
        return compute(contract)
    }

    /** 退费申请：落库并冻结顾问提成。幂等键防重复申请。 */
    @Transactional
    fun request(studentId: Long, reason: String, idemKey: String?, actor: String): RefundOrder {
        val contract = activeContractOf(studentId)

        fun doCreate(): RefundOrder {
            val existing = refundOrderRepo.findByContractIdAndStatusIn(
                contract.id, listOf(RefundStatus.REQUESTED, RefundStatus.APPROVED)
            )
            if (existing.isNotEmpty()) bizFail("DUPLICATE", "已有进行中的退费单: #${existing.first().id}")
            val breakdown = compute(contract)
            val order = refundOrderRepo.save(
                RefundOrder(
                    studentId = studentId,
                    contractId = contract.id,
                    reason = reason,
                    breakdownJson = mapper.writeValueAsString(breakdown),
                    refundAmount = breakdown.refundAmount
                )
            )
            // 冻结顾问提成
            commissionRepo.findByContractId(contract.id).filter { it.status == CommissionStatus.ACCRUED }.forEach {
                it.status = CommissionStatus.FROZEN
                it.updatedAt = LocalDateTime.now()
                commissionRepo.save(it)
                auditService.record("COMMISSION_FROZEN", "顾问提成冻结：¥${it.amount}（合同#${contract.id}）", studentId = studentId, actor = actor)
            }
            auditService.record(
                "REFUND_REQUESTED",
                "退费申请：应退 ¥${breakdown.refundAmount}（已上付费课时=${breakdown.consumedPaidLessons}，剩余=${breakdown.remainingPaidLessons}，原因=$reason）",
                studentId = studentId, actor = actor
            )
            return order
        }

        if (idemKey.isNullOrBlank()) return doCreate()
        val (existed, id) = lockService.idempotent("refund:$idemKey") { doCreate().id }
        return refundOrderRepo.findById(id).get()
    }

    /** 审批并打款：合同/学员/报班/分期/提成联动变更 */
    @Transactional
    fun approve(refundId: Long, approver: String): RefundOrder {
        val order = refundOrderRepo.findById(refundId).orElseThrow { BusinessException("NOT_FOUND", "退费单不存在: $refundId") }
        if (order.status != RefundStatus.REQUESTED) bizFail("INVALID_STATE", "退费单状态(${order.status})不能审批")
        order.status = RefundStatus.PAID
        order.decidedBy = approver
        order.decidedAt = LocalDateTime.now()
        refundOrderRepo.save(order)

        val contract = contractRepo.findById(order.contractId).get()
        contract.status = ContractStatus.REFUNDED
        contractRepo.save(contract)
        val student = studentRepo.findById(order.studentId).get()
        student.status = StudentStatus.REFUNDED
        studentRepo.save(student)
        // 报班记录退出 + 未上课时出勤清理
        enrollmentRepo.findByStudentIdAndStatus(order.studentId, EnrollmentStatus.ACTIVE).forEach { en ->
            en.status = EnrollmentStatus.DROPPED
            enrollmentRepo.save(en)
            lessonRepo.findByClazzIdAndStatus(en.clazzId, LessonStatus.SCHEDULED).forEach { lesson ->
                attendanceRepo.findByLessonIdAndStudentId(lesson.id, order.studentId)?.let { att ->
                    if (att.status == AttendStatus.PENDING) attendanceRepo.delete(att)
                }
            }
        }
        // 未收分期减免
        installmentRepo.findByContractId(contract.id).filter { it.status == InstallmentStatus.PENDING }.forEach {
            it.status = InstallmentStatus.WAIVED
            installmentRepo.save(it)
        }
        // 提成追回
        commissionRepo.findByContractId(contract.id).filter { it.status == CommissionStatus.FROZEN }.forEach {
            it.status = CommissionStatus.CLAWED_BACK
            it.updatedAt = LocalDateTime.now()
            commissionRepo.save(it)
        }
        auditService.record("REFUND_PAID", "退费完成：¥${order.refundAmount}（退费单#$refundId，审批人=$approver）", studentId = order.studentId, actor = approver)
        auditService.record("COMMISSION_CLAWED_BACK", "顾问提成追回（合同#${contract.id}）", studentId = order.studentId, actor = approver)
        return order
    }

    @Transactional
    fun reject(refundId: Long, approver: String, reason: String): RefundOrder {
        val order = refundOrderRepo.findById(refundId).orElseThrow { BusinessException("NOT_FOUND", "退费单不存在: $refundId") }
        if (order.status != RefundStatus.REQUESTED) bizFail("INVALID_STATE", "退费单状态(${order.status})不能驳回")
        order.status = RefundStatus.REJECTED
        order.decidedBy = approver
        order.decidedAt = LocalDateTime.now()
        refundOrderRepo.save(order)
        // 解冻提成
        commissionRepo.findByContractId(order.contractId).filter { it.status == CommissionStatus.FROZEN }.forEach {
            it.status = CommissionStatus.ACCRUED
            it.updatedAt = LocalDateTime.now()
            commissionRepo.save(it)
        }
        auditService.record("REFUND_REJECTED", "退费驳回：$reason", studentId = order.studentId, actor = approver)
        return order
    }

    fun get(refundId: Long): RefundOrder =
        refundOrderRepo.findById(refundId).orElseThrow { BusinessException("NOT_FOUND", "退费单不存在: $refundId") }

    private fun activeContractOf(studentId: Long): Contract {
        studentRepo.findById(studentId).orElseThrow { BusinessException("NOT_FOUND", "学员不存在: $studentId") }
        return contractRepo.findByStudentId(studentId).firstOrNull { it.status == ContractStatus.ACTIVE || it.status == ContractStatus.SUSPENDED }
            ?: bizFail("INVALID_STATE", "学员没有可退费的合同")
    }

    private fun compute(contract: Contract): RefundBreakdown {
        val pkg = coursePackageRepo.findById(contract.coursePackageId).get()
        val atts = attendanceRepo.findByContractId(contract.id)
        val consumedPaid = atts.sumOf { it.consumedPaid }
        val consumedGift = atts.sumOf { it.consumedGift }
        val remainingPaid = contract.paidLessons - consumedPaid
        val unitPrice = contract.finalAmount.divide(BigDecimal(contract.paidLessons), 2, RoundingMode.HALF_UP)
        val baseRefund = unitPrice.multiply(BigDecimal(remainingPaid))

        val materialFeeDeducted = if (contract.materialsDelivered) contract.materialFee else BigDecimal.ZERO
        val consumedRatio = BigDecimal(consumedPaid).divide(BigDecimal(contract.paidLessons), 4, RoundingMode.HALF_UP)
        val discountRecovered =
            if (consumedRatio < pkg.discountKeepThreshold) contract.discountAmount else BigDecimal.ZERO
        val giftValueOffset =
            if (contract.consumeOrder == ConsumeOrder.GIFT_FIRST) unitPrice.multiply(BigDecimal(consumedGift)) else BigDecimal.ZERO

        val openComplaints = complaintRepo.findByStudentIdAndStatus(contract.studentId, ComplaintStatus.OPEN).size
        val complaintComp = complaintCompensation.multiply(BigDecimal(openComplaints))
        val commissionFrozen = commissionRepo.findByContractId(contract.id)
            .filter { it.status == CommissionStatus.ACCRUED || it.status == CommissionStatus.FROZEN }
            .fold(BigDecimal.ZERO) { acc, c -> acc.add(c.amount) }

        val installments = installmentRepo.findByContractId(contract.id)
        val totalReceived = installments.filter { it.status == InstallmentStatus.PAID }.fold(BigDecimal.ZERO) { acc, i -> acc.add(i.amount) }
        val unpaidWaived = installments.filter { it.status == InstallmentStatus.PENDING }.fold(BigDecimal.ZERO) { acc, i -> acc.add(i.amount) }

        var refund = baseRefund.subtract(materialFeeDeducted).subtract(discountRecovered).subtract(giftValueOffset).add(complaintComp)
        if (refund < BigDecimal.ZERO) refund = BigDecimal.ZERO
        if (refund > totalReceived) refund = totalReceived // 已收款为上限

        return RefundBreakdown(
            contractId = contract.id,
            paidLessons = contract.paidLessons,
            giftLessons = contract.giftLessons,
            consumedPaidLessons = consumedPaid,
            consumedGiftLessons = consumedGift,
            remainingPaidLessons = remainingPaid,
            unitPrice = unitPrice,
            baseRefund = baseRefund,
            materialFeeDeducted = materialFeeDeducted,
            discountRecovered = discountRecovered,
            giftValueOffset = giftValueOffset,
            openComplaints = openComplaints,
            complaintCompensation = complaintComp,
            commissionFrozen = commissionFrozen,
            totalReceived = totalReceived,
            unpaidInstallmentsWaived = unpaidWaived,
            refundAmount = refund
        )
    }
}
