package com.example.batteryfloat.adb

import javax.net.ssl.SSLSocket

/**
 * TLS keying material 导出(隐藏 API com.android.org.conscrypt.Conscrypt#exportKeyingMaterial)
 *
 * 该 API 属于系统隐藏接口,依赖 App 启动时 HiddenApiBypass.setHiddenApiExemptions("")
 * 建立的全量豁免后以反射调用;豁免未生效时将抛异常由上层降级处理。
 * (替代 Shizuku 原实现的 hidden-stub 编译期依赖)
 */
internal object TlsExporter {

    private const val CONSCRYPT_CLASS = "com.android.org.conscrypt.Conscrypt"

    fun exportKeyingMaterial(socket: SSLSocket, label: String, context: ByteArray?, length: Int): ByteArray {
        val clazz = Class.forName(CONSCRYPT_CLASS)
        val method = clazz.getMethod(
            "exportKeyingMaterial",
            SSLSocket::class.java, String::class.java, ByteArray::class.java, Int::class.javaPrimitiveType
        )
        return method.invoke(null, socket, label, context, length) as ByteArray
    }
}
