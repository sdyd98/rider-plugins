package com.example.xlsx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.component.CheckboxRow
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.TextField
import javax.swing.JComponent

/**
 * Compose (Jewel) version of the Excel-style per-column value filter popup. The distinct values are
 * still computed off the EDT in [ColumnFilterController]; this is the interactive part — a live
 * search, checkbox list (virtualized via [LazyColumn]), All/None, and OK/Cancel. State is a
 * [mutableStateMapOf] of value→checked; toggling a row just mutates the map and the UI recomposes.
 */
fun createColumnFilterPanel(
    title: String,
    distinct: List<String>,
    truncated: Boolean,
    maxDistinct: Int,
    initiallyChecked: Set<String>?, // null = all checked
    onApply: (Set<String>) -> Unit,
    onCancel: () -> Unit,
): JComponent = JewelComposePanel {
    val checked = remember {
        mutableStateMapOf<String, Boolean>().apply {
            distinct.forEach { put(it, initiallyChecked == null || it in initiallyChecked) }
        }
    }
    val query = rememberTextFieldState()

    Column(Modifier.width(300.dp).height(390.dp).padding(10.dp)) {
        Text("필터: $title", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(query, Modifier.weight(1f), placeholder = { Text("검색…") })
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { distinct.forEach { checked[it] = true } }) { Text("전체") }
            Spacer(Modifier.width(4.dp))
            OutlinedButton(onClick = { distinct.forEach { checked[it] = false } }) { Text("해제") }
        }
        if (truncated) {
            Spacer(Modifier.height(4.dp))
            Text("처음 ${maxDistinct}개 값만 표시")
        }
        Spacer(Modifier.height(8.dp))

        val q = query.text.toString().trim()
        val visible = distinct.filter { q.isEmpty() || it.contains(q, ignoreCase = true) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(visible) { value ->
                CheckboxRow(
                    text = value.ifEmpty { "(빈 값)" },
                    checked = checked[value] == true,
                    onCheckedChange = { checked[value] = it },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onCancel) { Text("취소") }
            Spacer(Modifier.width(6.dp))
            DefaultButton(onClick = { onApply(checked.filterValues { it }.keys.toSet()) }) { Text("확인") }
        }
    }
}
