package com.training.crm.domain

/** 科目 */
enum class Subject { MATH, ENGLISH, CHINESE, PHYSICS, CHEMISTRY }

/** 学生基础水平 / 班级难度等级 */
enum class Level { BEGINNER, BASIC, INTERMEDIATE, ADVANCED }

/** 班型 */
enum class ClassType { REGULAR, INTENSIVE, ONE_ON_ONE, SPRINT }

/** 线索状态：新建 → 已约试听 → 试听完成 → 已转化 / 已流失 */
enum class LeadStatus { NEW, TRIAL_SCHEDULED, TRIAL_DONE, CONVERTED, LOST }

/** 试听课状态 */
enum class TrialStatus { SCHEDULED, ATTENDED, ABSENT, CANCELLED_TEACHER_LEAVE, CANCELLED }

/** 候补状态：排队中 / 已用释放档位转正 / 已转订其他时段 / 已取消 */
enum class WaitlistStatus { WAITING, PROMOTED, BOOKED_ELSEWHERE, CANCELLED }

/** 第三次改约的主管审批状态：待审批 / 通过 / 驳回 */
enum class TrialApprovalStatus { PENDING, APPROVED, REJECTED }

/** 试听反馈对线索转化的影响：正向 / 中性 / 负向（负向反馈计入转化风险统计） */
enum class ConversionImpact { POSITIVE, NEUTRAL, NEGATIVE }

/** 学员状态 */
enum class StudentStatus { ACTIVE, SUSPENDED, REFUNDED, GRADUATED }

/** 合同状态 */
enum class ContractStatus { ACTIVE, SUSPENDED, REFUNDED, COMPLETED }

/** 分期状态 */
enum class InstallmentStatus { PENDING, PAID, WAIVED }

/** 发票状态 */
enum class InvoiceStatus { ISSUED, VOID }

/** 正式课课时状态 */
enum class LessonStatus { SCHEDULED, COMPLETED, CANCELLED_TEACHER_LEAVE, CANCELLED }

/** 课消出勤状态 */
enum class AttendStatus { PENDING, ATTENDED, ABSENT, LEAVE }

/** 请假单状态 */
enum class LeaveStatus { PENDING, APPROVED, REJECTED }

/** 报班记录状态 */
enum class EnrollmentStatus { ACTIVE, TRANSFERRED_OUT, DROPPED }

/** 顾问提成状态：计提 → 冻结（退费中）→ 追回 / 结算 */
enum class CommissionStatus { ACCRUED, FROZEN, CLAWED_BACK, SETTLED }

/** 退费单状态 */
enum class RefundStatus { REQUESTED, APPROVED, REJECTED, PAID }

/** 投诉状态 */
enum class ComplaintStatus { OPEN, RESOLVED }

/** 课消扣课顺序：先扣付费课时 / 先扣赠课 */
enum class ConsumeOrder { PAID_FIRST, GIFT_FIRST }
