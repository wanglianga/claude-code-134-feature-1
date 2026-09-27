package com.training.crm.web

/**
 * 操作人解析。Tomcat 按 ISO-8859-1 解码请求头，而 curl 等客户端直接发送 UTF-8 字节，
 * 这里统一还原，保证中文操作人姓名在履约轨迹中可读。纯 ASCII 输入不受影响。
 */
fun resolveActor(role: String?, actor: String?): String {
    val raw = actor ?: role ?: "system"
    return String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
}
