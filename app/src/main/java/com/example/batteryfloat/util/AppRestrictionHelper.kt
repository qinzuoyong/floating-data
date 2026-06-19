package com.example.batteryfloat.util

import android.app.AppOpsManager
import android.content.Context
import android.util.Log
import com.example.batteryfloat.shizuku.ShizukuHelper

/**
 * 应用限制解除助手
 *
 * 多策略解除 Android 13+ 的 ACCESS_RESTRICTED_SETTINGS 限制：
 * 0. 预检：检测限制是否实际存在，避免无谓操作
 * 1. 策略A：反射 AppOpsManager.setMode / setUidMode（无需任何外部工具）
 *    - 原理：AppOpsService.verifyIncomingUid() 中 uid == callingUid 时直接通过权限检查
 *    - 风险：setMode 标注 @hide，可能被非 SDK 接口限制阻止
 * 2. 策略B：Shizuku 执行 appops set 命令
 * 3. 策略C：返回需手动引导（由 UI 层引导用户操作）
 */
object AppRestrictionHelper {

    private const val TAG = "AppRestrictionHelper"

    /** OPSTR_ACCESS_RESTRICTED_SETTINGS */
    private const val OPSTR = "android:access_restricted_settings"

    /** 解除限制结果 */
    sealed class Result {
        /** 解除成功，method 标识使用的方法 */
        data class Success(val method: String) : Result()
        /** 需要手动引导 */
        object NeedsManualAction : Result()
        /** 发生异常 */
        data class Error(val message: String) : Result()
    }

    /**
     * 检测当前应用是否被 ACCESS_RESTRICTED_SETTINGS 限制
     *
     * 通过反射 AppOpsManager.unsafeCheckOpNoThrow 检查 op 状态
     *
     * @return true=已被限制, false=未受限或检测失败
     */
    fun isRestricted(context: Context): Boolean {
        return try {
            val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val packageName = context.packageName
            val uid = context.applicationInfo.uid

            // 尝试通过 unsafeCheckOpNoThrow 检测
            val checkMethod = AppOpsManager::class.java.getMethod(
                "unsafeCheckOpNoThrow",
                String::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            val result = checkMethod.invoke(appOpsManager, OPSTR, uid, packageName) as Int
            // MODE_ERRORED = 4, MODE_IGNORED = 1
            val isRestricted = result != AppOpsManager.MODE_ALLOWED
            Log.i(TAG, "限制检测: mode=$result, restricted=$isRestricted")
            isRestricted
        } catch (e: Exception) {
            Log.w(TAG, "限制检测失败（假设受限）: ${e.message}")
            true  // 检测失败时假设受限，安全起见
        }
    }

    /**
     * 尝试解除 ACCESS_RESTRICTED_SETTINGS 限制
     * 按预检 → 策略A → B → C 依次降级
     *
     * @param context 上下文
     * @return 解除结果
     */
    fun liftRestriction(context: Context): Result {
        // 预检：若未受限，直接返回成功
        if (!isRestricted(context)) {
            Log.i(TAG, "应用未受限，无需解除")
            return Result.Success("无需解除")
        }

        // 策略A：反射 setMode / setUidMode
        val reflectionResult = tryViaReflection(context)
        if (reflectionResult is Result.Success) return reflectionResult

        Log.w(TAG, "反射解除失败($reflectionResult), 尝试 Shizuku")

        // 策略B：Shizuku
        val shizukuResult = tryViaShizuku(context)
        if (shizukuResult is Result.Success) return shizukuResult

        Log.w(TAG, "Shizuku 解除失败($shizukuResult), 需手动引导")

        // 策略C：手动引导
        return Result.NeedsManualAction
    }

    /**
     * 策略A：通过反射调用 AppOpsManager.setMode 或 setUidMode
     *
     * verifyIncomingUid() 源码逻辑：
     *   if (uid == Binder.getCallingUid()) return;  // 设置自己的 op，直接通过
     *
     * 尝试顺序：setMode → setUidMode
     *
     * @return Success 或 Error（含失败原因）
     */
    private fun tryViaReflection(context: Context): Result {
        val appOpsManager = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val packageName = context.packageName
        val uid = context.applicationInfo.uid

        // 通过反射获取 OP_ACCESS_RESTRICTED_SETTINGS 常量值
        val opCode = getOpCode(appOpsManager) ?: run {
            return Result.Error("无法获取限制操作码")
        }

        // 尝试1：setMode(int code, int uid, String pkg, int mode)
        try {
            val setModeMethod = AppOpsManager::class.java.getMethod(
                "setMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
                Int::class.javaPrimitiveType
            )
            setModeMethod.invoke(appOpsManager, opCode, uid, packageName, AppOpsManager.MODE_ALLOWED)
            Log.i(TAG, "反射 setMode 成功: op=$opCode, uid=$uid, pkg=$packageName")
            return Result.Success("反射")
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "setMode 方法被隐藏 API 限制: ${e.message}")
        } catch (e: SecurityException) {
            Log.w(TAG, "setMode 安全异常: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "setMode 异常: ${e.message}")
        }

        // 尝试2：setUidMode(int code, int uid, int mode)
        try {
            val setUidModeMethod = AppOpsManager::class.java.getMethod(
                "setUidMode",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            setUidModeMethod.invoke(appOpsManager, opCode, uid, AppOpsManager.MODE_ALLOWED)
            Log.i(TAG, "反射 setUidMode 成功: op=$opCode, uid=$uid")
            return Result.Success("反射(setUidMode)")
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "setUidMode 方法被隐藏 API 限制: ${e.message}")
        } catch (e: SecurityException) {
            Log.w(TAG, "setUidMode 安全异常: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "setUidMode 异常: ${e.message}")
        }

        return Result.Error("所有反射方法均被阻止")
    }

    /**
     * 获取 OP_ACCESS_RESTRICTED_SETTINGS 操作码
     */
    private fun getOpCode(appOpsManager: AppOpsManager): Int? {
        // 尝试1：直接读取常量字段
        try {
            return AppOpsManager::class.java.getDeclaredField("OP_ACCESS_RESTRICTED_SETTINGS")
                .getInt(null)
        } catch (e: NoSuchFieldException) {
            Log.w(TAG, "OP_ACCESS_RESTRICTED_SETTINGS 字段不存在")
        }

        // 尝试2：通过 strOpToOp 转换
        try {
            val method = AppOpsManager::class.java.getMethod("strOpToOp", String::class.java)
            return method.invoke(appOpsManager, OPSTR) as Int
        } catch (e: Exception) {
            Log.w(TAG, "strOpToOp 失败: ${e.message}")
        }

        return null
    }

    /**
     * 策略B：通过 Shizuku 执行 appops set 命令
     */
    private fun tryViaShizuku(context: Context): Result {
        return try {
            if (!ShizukuHelper.isRunning()) {
                return Result.Error("Shizuku 未运行")
            }
            if (!ShizukuHelper.hasPermission()) {
                return Result.Error("Shizuku 未授权")
            }

            val packageName = context.packageName

            // 执行 appops set 命令
            val setResult = ShizukuHelper.executeCommand(
                "appops set $packageName ACCESS_RESTRICTED_SETTINGS allow"
            )

            if (setResult == null) {
                return Result.Error("Shizuku 命令执行失败")
            }

            // 验证：查询当前状态
            val getResult = ShizukuHelper.executeCommand(
                "appops get $packageName ACCESS_RESTRICTED_SETTINGS"
            )

            if (getResult != null && getResult.contains("allow", ignoreCase = true)) {
                Log.i(TAG, "Shizuku 解除限制成功")
                Result.Success("Shizuku")
            } else {
                Log.w(TAG, "Shizuku 命令已执行但验证失败: $getResult")
                Result.Error("验证失败: $getResult")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku 解除异常", e)
            Result.Error(e.message ?: "Shizuku 异常")
        }
    }
}
