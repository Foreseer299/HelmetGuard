package com.helmet.guard.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Used only on the redesigned screens; home and telemetry keep their existing components. */
@Composable
fun RefinedHeader(title: String, subtitle: String) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RefinedSubpageHeader(title: String, subtitle: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun RefinedGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun RefinedRow(title: String, detail: String, icon: ImageVector? = null, onClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    val action = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(Modifier.fillMaxWidth().then(action).heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (icon != null) Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = .08f), shape = RoundedCornerShape(12.dp)) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailing != null) trailing()
        else if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RefinedSection(title: String, detail: String? = null) {
    Column(Modifier.padding(top = 8.dp, start = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun RefinedNote(text: String) {
    Text(text, modifier = Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
