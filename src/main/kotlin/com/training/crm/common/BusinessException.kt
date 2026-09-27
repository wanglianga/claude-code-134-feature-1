package com.training.crm.common

/** 业务异常：code 用于前端/调用方识别，message 面向用户 */
class BusinessException(val code: String, override val message: String) : RuntimeException(message)

fun bizFail(code: String, message: String): Nothing = throw BusinessException(code, message)
