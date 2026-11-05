package cc.star0.xposed.hyperstylus.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Decorative miuix glyph; the adjacent preference supplies the accessible label. */
@Composable
internal fun SettingsIcon(icon: ImageVector, modifier: Modifier = Modifier) {
    SettingsIcon(rememberVectorPainter(icon), modifier)
}

@Composable
internal fun SettingsIcon(icon: Painter, modifier: Modifier = Modifier) {
    Card(modifier = modifier, cornerRadius = 12.dp, insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primaryContainer)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp),
                tint = MiuixTheme.colorScheme.onPrimaryContainer)
        }
    }
}
