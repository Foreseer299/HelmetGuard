package com.helmet.guard.ui.screens

import android.telephony.PhoneNumberUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.helmet.guard.AppGraph
import com.helmet.guard.data.db.EmergencyContactEntity
import com.helmet.guard.ui.components.InfoCard
import com.helmet.guard.ui.components.SectionTitle
import com.helmet.guard.ui.components.StatusPill
import com.helmet.guard.ui.theme.GuardBlue
import com.helmet.guard.ui.theme.GuardGreen
import com.helmet.guard.ui.theme.GuardOrange
import com.helmet.guard.ui.theme.GuardRed
import kotlinx.coroutines.launch

@Composable
fun ContactsScreen(graph: AppGraph, back: () -> Unit) {
    val contactsFlow = remember(graph) { graph.database.contacts().observeAll() }
    val contacts by contactsFlow.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<EmergencyContactEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<EmergencyContactEntity?>(null) }

    LazyColumn(
        Modifier.statusBarsPadding().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.Outlined.ArrowBack, "返回") }
                SectionTitle("紧急联系人", "管理求救短信的接收人")
            }
        }
        item {
            InfoCard {
                Text("事故倒计时结束后，应用会向所有“启用”的联系人发送。请事先征得联系人同意，并定期通过模拟演练检查流程。", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { editing = null; showEditor = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Text(" 添加联系人") }
            }
        }
        if (contacts.isEmpty()) item { InfoCard { Text("至少添加一位联系人", fontWeight = FontWeight.Bold); Text("没有联系人时，自动短信求救无法执行。", color = GuardRed) } }
        items(contacts, key = { it.id }) { contact ->
            InfoCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Person, null, tint = GuardBlue)
                    Spacer(Modifier.padding(5.dp))
                    Column(Modifier.weight(1f)) {
                        Text(contact.name, fontWeight = FontWeight.Bold)
                        Text("${mask(contact.phone)} · ${contact.relationship}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill("优先级 ${contact.priority + 1}", if (contact.enabled) GuardGreen else GuardOrange)
                    Switch(contact.enabled, { enabled -> scope.launch { graph.database.contacts().setEnabled(contact.id, enabled) } })
                }
                Row {
                    TextButton(onClick = { editing = contact; showEditor = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.Edit, null); Text("编辑") }
                    TextButton(onClick = { deleting = contact }, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.DeleteOutline, null, tint = GuardRed); Text("删除", color = GuardRed) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    deleting?.let { contact ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除紧急联系人？") },
            text = { Text("删除后，${contact.name}将不再接收求救短信。") },
            confirmButton = { TextButton(onClick = {
                scope.launch { graph.database.contacts().delete(contact.id) }
                deleting = null
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("保留") } }
        )
    }
    if (showEditor) {
        ContactEditor(
            original = editing,
            defaultPriority = contacts.size,
            dismiss = { showEditor = false },
            save = { entity -> scope.launch { graph.database.contacts().upsert(entity) }; showEditor = false }
        )
    }
}

@Composable
private fun ContactEditor(original: EmergencyContactEntity?, defaultPriority: Int, dismiss: () -> Unit, save: (EmergencyContactEntity) -> Unit) {
    var name by remember { mutableStateOf(original?.name.orEmpty()) }
    var phone by remember { mutableStateOf(original?.phone.orEmpty()) }
    var relationship by remember { mutableStateOf(original?.relationship ?: "家属") }
    var priority by remember { mutableStateOf((original?.priority ?: defaultPriority).toString()) }
    val normalizedPhone = phone.filterNot(Char::isWhitespace)
    val valid = name.isNotBlank() && PhoneNumberUtils.isGlobalPhoneNumber(normalizedPhone)
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(if (original == null) "添加紧急联系人" else "编辑联系人") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(24) }, label = { Text("姓名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(phone, { phone = it.take(30) }, label = { Text("手机号（可含 +86）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), isError = phone.isNotBlank() && !PhoneNumberUtils.isGlobalPhoneNumber(normalizedPhone))
                OutlinedTextField(relationship, { relationship = it.take(16) }, label = { Text("关系") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(priority, { priority = it.filter(Char::isDigit).take(2) }, label = { Text("优先级（数字越小越先发送）") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            Button(onClick = {
                save(EmergencyContactEntity(original?.id ?: 0, name.trim(), normalizedPhone, relationship.trim(), priority.toIntOrNull()?.coerceIn(0, 99) ?: defaultPriority, original?.enabled ?: true))
            }, enabled = valid) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("取消") } }
    )
}

private fun mask(phone: String): String {
    val p = phone.filter { it.isDigit() || it == '+' }
    return if (p.length <= 7) p else "${p.take(3)}****${p.takeLast(4)}"
}
