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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.batteryfloat.R
import com.example.batteryfloat.PrefsKeys
import com.example.batteryfloat.family.FamilyStore
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
fun AddFamilyScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(PrefsKeys.PREFS_NAME, android.content.Context.MODE_PRIVATE)
    val store = remember { FamilyStore.get(context) }

    var code by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(prefs.getString(PrefsKeys.FAMILY_MY_NAME, "") ?: "") }
    var allowLoc by remember { mutableStateOf(store.allowLocReq()) }
    var error by remember { mutableStateOf<String?>(null) }

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
                        prefs.edit()
                            .putString(PrefsKeys.FAMILY_CODE, code)
                            .putString(PrefsKeys.FAMILY_MY_NAME, name.trim())
                            .apply()
                        store.setFamilyCode(code)
                        store.setMyName(name)
                        store.setAllowLocReq(allowLoc)
                        // 重启服务以新家庭码注册
                        FamilyLocationService.stop(context)
                        FamilyLocationService.start(context)
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(DesignSystem.SpacingL))
    }
}