package com.helmet.guard.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.helmet.guard.AppGraph
import com.helmet.guard.data.db.EmergencyContactEntity
import com.helmet.guard.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(graph: AppGraph, back: () -> Unit) {
    val contacts by remember(graph) { graph.database.contacts().observeAll() }.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<EmergencyContactEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<EmergencyContactEntity?>(null) }
    var busyIds by remember { mutableStateOf(emptySet<Long>()) }
    var deleteBusy by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("紧急联系人") }, navigationIcon = { IconButton(back) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } })
    }) { insets ->
        LazyColumn(Modifier.padding(insets).fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
            item {
                RefinedHeader("让求救有回应", "已启用 ${contacts.count { it.enabled }} 位联系人")
                RefinedNote("事故倒计时结束后，向所有启用的联系人发送求救短信。请先与对方约定收到后的处理方式。")
                Spacer(Modifier.height(14.dp))
                Button(onClick = { editing = null; showEditor = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Outlined.Add, null); Spacer(Modifier.width(8.dp)); Text("添加联系人") }
            }
            if (contacts.isEmpty()) item {
                RefinedGroup {
                    Text("还没有接收求救的人", style = MaterialTheme.typography.titleMedium)
                    RefinedNote("添加家人或朋友的完整号码并启用，求救短信才能发送。这里的联系人与手机通讯录互相独立。")
                }
            }
            itemsIndexed(contacts.sortedWith(compareBy<EmergencyContactEntity> { it.priority }.thenBy { it.id }), key = { _, contact -> contact.id }) { _, contact ->
                RefinedGroup {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(contact.name, style = MaterialTheme.typography.titleMedium)
                            Text(listOf(maskPhone(contact.phone), contact.relationship).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (contact.enabled) "已启用 · 发送顺序 ${contact.priority + 1}" else "已暂停接收求救短信", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(contact.enabled, { value ->
                            busyIds = busyIds + contact.id
                            scope.launch {
                                try { graph.database.contacts().setEnabled(contact.id, value) }
                                catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { graph.runtime.postMessage("未能更新联系人，请重试") }
                                finally { busyIds = busyIds - contact.id }
                            }
                        }, enabled = contact.id !in busyIds)
                    }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { editing = contact; showEditor = true }) { Text("编辑资料") }
                        TextButton(onClick = { deleting = contact }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            if (contacts.isNotEmpty()) item { RefinedNote("发送顺序只影响先后，所有启用的人都会收到。暂停开关会保留资料；删除会移除这位联系人。实测会使用顺序最靠前的可用联系人。") }
        }
    }
    deleting?.let { contact ->
        AlertDialog(onDismissRequest = { if (!deleteBusy) deleting = null }, title = { Text("删除 ${contact.name}？") }, text = {
            Text(if (contact.enabled && contacts.count { it.enabled } == 1) "这是最后一位启用的联系人。删除后，求救短信将没有接收人。" else "删除后，这位联系人不再接收求救短信。")
        }, confirmButton = {
            TextButton(enabled = !deleteBusy, onClick = {
                deleteBusy = true
                scope.launch {
                    try { graph.database.contacts().delete(contact.id); deleting = null }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { graph.runtime.postMessage("删除失败，请重试") }
                    finally { deleteBusy = false }
                }
            }) { Text(if (deleteBusy) "正在删除…" else "删除联系人", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(enabled = !deleteBusy, onClick = { deleting = null }) { Text("保留") } })
    }
    if (showEditor) ContactEditor(editing, contacts, dismiss = { showEditor = false }, save = { entity -> graph.database.contacts().upsert(entity) })
}

@Composable
private fun ContactEditor(original: EmergencyContactEntity?, contacts: List<EmergencyContactEntity>, dismiss: () -> Unit, save: suspend (EmergencyContactEntity) -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember(original?.id) { mutableStateOf(original?.name.orEmpty()) }
    var phone by remember(original?.id) { mutableStateOf(original?.phone.orEmpty()) }
    var relationship by remember(original?.id) { mutableStateOf(original?.relationship.orEmpty()) }
    val nextPriority = ((contacts.maxOfOrNull { it.priority } ?: -1) + 1).coerceAtMost(99)
    var priority by remember(original?.id) { mutableStateOf(((original?.priority ?: nextPriority) + 1).toString()) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val normalized = normalizedEmergencyPhone(phone)
    val duplicate = normalized != null && contacts.any { it.id != original?.id && contactPhoneKey(it.phone) == contactPhoneKey(normalized) }
    val order = priority.toIntOrNull()
    val valid = name.isNotBlank() && normalized != null && !duplicate && order != null && order in 1..100
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text(if (original == null) "添加紧急联系人" else "编辑联系人") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name, { name = it.take(24) }, enabled = !saving, label = { Text("姓名 / 称呼") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(phone, { phone = it.take(40) }, enabled = !saving, label = { Text("接收短信的号码") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), isError = phone.isNotBlank() && (normalized == null || duplicate), supportingText = {
                Text(when { duplicate -> "这个号码已经添加，请编辑已有联系人。"; phone.isNotBlank() && normalized == null -> "填写完整号码，可含国家区号，如 +86。"; else -> "可输入空格或短横线，保存时会自动整理。" })
            })
            OutlinedTextField(relationship, { relationship = it.take(16) }, enabled = !saving, label = { Text("关系（选填）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(priority, { priority = it.filter(Char::isDigit).take(3) }, enabled = !saving, label = { Text("发送顺序") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(), isError = order == null || order !in 1..100, supportingText = { Text("1–100，数字越小越先发送；所有启用的人都会收到。") })
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = {
        Button(enabled = valid && !saving, onClick = {
            saving = true; saveError = null
            scope.launch {
                try {
                    save(EmergencyContactEntity(original?.id ?: 0, name.trim(), requireNotNull(normalized), relationship.trim(), requireNotNull(order) - 1, original?.enabled ?: true))
                    dismiss()
                } catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { saveError = "未能保存，内容已保留，请重试。" }
                finally { saving = false }
            }
        }) { Text(if (saving) "正在保存…" else "保存联系人") }
    }, dismissButton = { TextButton(enabled = !saving, onClick = dismiss) { Text("取消") } })
}

private fun maskPhone(phone: String): String = if (phone.length <= 7) phone else "${phone.take(3)}••••${phone.takeLast(4)}"
