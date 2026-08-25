package com.example.batteryfloat.ui.family

import android.content.SharedPreferences
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.batteryfloat.BuildConfig
import com.example.batteryfloat.R
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.family.FamilyStore
import com.example.batteryfloat.p2p.SignalClient
import com.example.batteryfloat.service.FamilyLocationService
import com.example.batteryfloat.ui.PrimaryActionButton
import com.example.batteryfloat.ui.SectionTitle
import com.example.batteryfloat.ui.theme.DesignSystem

/**
 * 创建 / 加入家庭
 *
 * 输入 6 位家庭码 + 我的备注名（展示给家人）→ 保存并启动服务。
 * 家庭码规则：6 位数字；两台设备输入相同码即配对（信令按房间隔离）。
 */
@Composable
fun AddFamilyScreen(
    onDone: () -> Unit,
    onBeforeExternalIntent: () -> Unit = {}
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val store = remember { FamilyStore.get(context) }

    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(prefs.getString(PrefsKeys.FAMILY_MY_NAME, "") ?: "") }
    var allowLoc by remember { mutableStateOf(store.allowLocReq()) }
    var error by remember { mutableStateOf<String?>(null) }


    // 提交：保存家庭信息并启动服务（仅权限已就绪时调用）
    val doSubmit: () -> Unit = {
        prefs.edit()
            .putString(PrefsKeys.FAMILY_CODE, code)
            .putString(PrefsKeys.FAMILY_MY_NAME, name.trim())
            .apply()
        store.setFamilyCode(code)
        store.setMyName(name)
        store.setAllowLocReq(allowLoc)
        // 加入/更换家庭：清空旧家庭成员，避免残留旧房间成员
        store.clearMembers()
        // 单一 start：服务 setup() 幂等重建连接（家庭码变化自动生效）
        FamilyLocationService.start(context)
        onDone()
    }

    // 提交前查询家庭码占用情况（仅提示用途，不影响流程；失败按已占用处理走加入流程）
    val joinWithCheck: () -> Unit = {
        SignalClient(BuildConfig.SIGNAL_URL).checkRoom(code) { exists, ownerName ->
            if (!exists) {
                Toast.makeText(context, "家庭码可用，将创建新家庭", Toast.LENGTH_SHORT).show()
            } else if (ownerName != null) {
                Toast.makeText(context, "已加入" + ownerName + "的家庭", Toast.LENGTH_SHORT).show()
            }
            doSubmit()
        }
    }

    // 提交前主动请求定位权限：授权成功自动继续提交，拒绝则提示并停留
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            joinWithCheck()
        } else {
            Toast.makeText(context, "需要定位权限才能使用家人位置共享", Toast.LENGTH_LONG).show()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(DesignSystem.PagePadding),
        verticalArrangement = Arrangement.spacedBy(DesignSystem.SpacingM)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.family_back))
            }
            SectionTitle(stringResource(R.string.family_add_title))
        }

        Card(
            shape = RoundedCornerShape(DesignSystem.CornerL),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(DesignSystem.CardPaddingLarge),
                verticalArrangement = Arrangement.spacedBy(DesignSystem.SpacingM)
            ) {
                Text(
                    text = stringResource(R.string.family_pair_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = code,
                    onValueChange = { input ->
                        code = input.filter { it.isDigit() }.take(6)
                        error = null
                    },
                    label = { Text(stringResource(R.string.family_code_input_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    isError = error != null,
                    supportingText = error?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(16) },
                    label = { Text(stringResource(R.string.family_name_input_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.family_allow_loc_req),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.family_allow_loc_req_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = allowLoc,
                        onCheckedChange = { allowLoc = it }
                    )
                }

                PrimaryActionButton(
                    text = stringResource(R.string.family_join_submit),
                    onClick = {
                        if (code.length != 6) {
                            error = context.getString(R.string.family_code_invalid)
                            return@PrimaryActionButton
                        }
                        if (!hasLocationPermission(context)) {
                            // 权限弹窗是独立 Activity，标记外部跳转防 onUserLeaveHint finish
                            onBeforeExternalIntent()
                            permissionLauncher.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION
                                )
                            )
                            return@PrimaryActionButton
                        }
                        joinWithCheck()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(DesignSystem.SpacingL))
    }
}

/**
 * 检查是否已持有定位权限（FINE 或 COARSE）
 *
 * @param context 上下文
 * @return true 已持有
 */
private fun hasLocationPermission(context: Context): Boolean {
    return androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED ||
        androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
}
