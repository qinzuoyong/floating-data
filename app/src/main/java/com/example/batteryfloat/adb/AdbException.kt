/*
 * 移植自 Shizuku (https://github.com/RikkaApps/Shizuku) manager/src/main/java/moe/shizuku/manager/adb/AdbException.kt
 * Copyright (C) 2021 RikkaApps
 * Licensed under the Apache License, Version 2.0
 */
package com.example.batteryfloat.adb

@Suppress("NOTHING_TO_INLINE")
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

open class AdbException : Exception {

    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException()

class AdbKeyException(cause: Throwable) : AdbException(cause)
