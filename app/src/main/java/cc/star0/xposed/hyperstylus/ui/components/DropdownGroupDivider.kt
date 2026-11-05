package cc.star0.xposed.hyperstylus.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.DividerDefaults

/** Paints a group boundary in a native dropdown's icon slot without taking row space. */
@Composable
internal fun DropdownGroupDivider() {
    val color = DividerDefaults.DividerColor
    val inset = with(LocalDensity.current) { 20.dp.toPx() }
    var line by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
    Box(Modifier.size(0.dp).onGloballyPositioned { marker ->
        val row = marker.parentLayoutCoordinates?.parentLayoutCoordinates?.parentLayoutCoordinates
        if (row != null && row.isAttached) {
            line = marker.localPositionOf(row, Offset(inset, 0f)) to
                marker.localPositionOf(row, Offset((row.size.width - inset).coerceAtLeast(inset), 0f))
        }
    }.drawBehind {
        line?.let { (start, end) -> drawLine(color, start, end, strokeWidth = 1.5.dp.toPx()) }
    })
}
