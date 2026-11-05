package cc.star0.xposed.hyperstylus.ui.screens

import android.widget.Toast
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import cc.star0.xposed.hyperstylus.BuildConfig
import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.ui.components.SettingsGroup
import cc.star0.xposed.hyperstylus.ui.components.SettingsPage
import cc.star0.xposed.hyperstylus.ui.state.AboutActions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun AboutScreen() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val iconSize = with(LocalDensity.current) { 80.dp.roundToPx() }
    val configuration = LocalConfiguration.current
    val icon = remember(resources, iconSize, configuration) {
        requireNotNull(resources.getDrawable(R.mipmap.ic_launcher, context.theme)).toBitmap(iconSize, iconSize).asImageBitmap()
    }
    val author = stringResource(R.string.author_name)
    var showTipCode by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    SettingsPage(stringResource(R.string.main_about), "main_about_page", reserveNavigationBarSpace = true) {
        item(key = "identity") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Image(icon, contentDescription = null, modifier = Modifier.size(80.dp))
                Text(stringResource(R.string.app_name), fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Text(stringResource(R.string.about_tagline), fontSize = 16.sp, textAlign = TextAlign.Center)
                Text(stringResource(R.string.about_description), fontSize = 13.sp, lineHeight = 21.sp,
                    textAlign = TextAlign.Center, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
        item(key = "author") {
            Card {
                BasicComponent(title = author, summary = stringResource(R.string.about_developer),
                    startAction = {
                        Image(painterResource(R.drawable.star_zer0),
                            contentDescription = stringResource(R.string.about_avatar, author),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.padding(end = 16.dp).size(52.dp).clip(CircleShape))
                    })
            }
        }
        item(key = "links") {
            SettingsGroup(stringResource(R.string.about_links)) {
                ArrowPreference(title = stringResource(R.string.about_qq_group),
                    summary = stringResource(R.string.qq_group_number),
                    modifier = Modifier.testTag("about_qq_group"), onClick = {
                        if (!AboutActions.joinQQGroup(context)) {
                            Toast.makeText(context, R.string.about_qq_unavailable, Toast.LENGTH_SHORT).show()
                        }
                    })
                AboutLink(R.string.about_homepage, R.string.author_homepage, "about_homepage")
                AboutLink(R.string.about_project, R.string.project_github, "about_project",
                    summary = stringResource(R.string.about_project_name))
            }
        }
        item(key = "support") {
            SettingsGroup(stringResource(R.string.about_support)) {
                ArrowPreference(title = stringResource(R.string.about_tip),
                    summary = stringResource(R.string.about_tip_summary),
                    onClick = { showTipCode = true }, modifier = Modifier.testTag("about_tip"))
            }
        }
    }

    OverlayDialog(show = showTipCode, title = stringResource(R.string.about_tip),
        onDismissRequest = { showTipCode = false }) {
        val tipCode = remember(resources) {
            resources.openRawResource(R.raw.tipcode).use {
                requireNotNull(BitmapFactory.decodeStream(it)).asImageBitmap()
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("about_tip_dialog"),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Image(tipCode,
                contentDescription = stringResource(R.string.about_tip_code, author),
                contentScale = ContentScale.Fit,
                modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).clip(CircleShape))
            Text(stringResource(R.string.about_tip_thanks), textAlign = TextAlign.Center)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(text = stringResource(if (saving) R.string.about_tip_saving else R.string.about_tip_save),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    enabled = !saving, modifier = Modifier.weight(1f).testTag("about_tip_save"), onClick = {
                        if (!saving) {
                            saving = true
                            scope.launch {
                                try {
                                    withContext(Dispatchers.IO) { AboutActions.saveTipCode(context) }
                                    Toast.makeText(context, R.string.about_tip_saved, Toast.LENGTH_SHORT).show()
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    Toast.makeText(context, R.string.about_tip_save_failed, Toast.LENGTH_SHORT).show()
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    })
                TextButton(text = stringResource(R.string.about_close), onClick = { showTipCode = false },
                    modifier = Modifier.weight(1f).testTag("about_tip_close"))
            }
        }
    }
}

@Composable
private fun AboutLink(title: Int, urlResource: Int, tag: String, summary: String? = null) {
    val context = LocalContext.current
    val url = stringResource(urlResource)
    ArrowPreference(title = stringResource(title), summary = summary ?: url.removePrefix("https://"),
        modifier = Modifier.testTag(tag), onClick = {
            if (!AboutActions.openLink(context, url)) {
                Toast.makeText(context, R.string.about_link_failed, Toast.LENGTH_SHORT).show()
            }
        })
}
