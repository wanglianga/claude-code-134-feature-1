package com.training.crm.common

import org.springframework.http.HttpStatus

/** 业务异常：code 用于前端/调用方识别，message 面向用户 */
class BusinessException(
    val code: String,
    override val message: String,
    val status: HttpStatus = HttpStatus.BAD_REQUEST
) : RuntimeException(message)

fun bizFail(code: String, message: String, status: HttpStatus = HttpStatus.BAD_REQUEST): Nothing =
    throw BusinessException(code, message, status)
