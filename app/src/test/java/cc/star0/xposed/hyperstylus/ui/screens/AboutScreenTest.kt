package cc.star0.xposed.hyperstylus.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import cc.star0.xposed.hyperstylus.R
import cc.star0.xposed.hyperstylus.config.EnhanceConfig
import cc.star0.xposed.hyperstylus.ui.navigation.SettingsNavigator
import cc.star0.xposed.hyperstylus.ui.theme.FocusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun display() {
        compose.setContent {
            FocusTheme { SettingsNavigator(EnhanceConfig.defaults(), 0) {} }
        }
        compose.onNodeWithTag("main_about_tab").performClick()
        compose.onNodeWithTag("main_about_page").assertIsDisplayed()
    }

    private fun scrollTo(tag: String) {
        if (tag == "about_tip") {
            // Align the final item so the touch target clears the overlaid bottom navigation.
            compose.onNodeWithTag("main_about_page").performScrollToIndex(3)
            val target = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val bar = compose.onNodeWithTag("main_bottom_navigation").fetchSemanticsNode().boundsInRoot
            org.junit.Assert.assertTrue(target.bottom <= bar.top)
        } else compose.onNodeWithTag("main_about_page").performScrollToNode(hasTestTag(tag))
    }

    private fun capture(name: String) {
        val directory = System.getenv("HYPERSTYLUS_PREVIEW_DIR") ?: return
        val file = File(directory, "$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun identityAndLinksUseTheRequestedPublicProfiles() {
        display()
        assertEquals("HyperStylus", compose.activity.getString(R.string.app_name))
        assertEquals("HyperStylus", compose.activity.applicationInfo.loadLabel(compose.activity.packageManager).toString())
        compose.onNodeWithText("HyperStylus", substring = false).assertIsDisplayed()
        compose.onNodeWithText("星澪Star_ZER0").assertIsDisplayed()
        compose.onNodeWithContentDescription("星澪Star_ZER0 的头像").assertIsDisplayed()
        capture("about-light")
        for ((tag, url) in listOf(
            "about_qq_group" to "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3DjGMPw1fEhifz25lfMoA6XtS0UVIlzZwn",
            "about_homepage" to "https://star0.cc",
            "about_project" to "https://github.com/Star-ZER0/HyperStylus",
        )) {
            scrollTo(tag)
            if (tag == "about_qq_group") {
                compose.onNodeWithText("1124571903").assertIsDisplayed()
                capture("about-contacts")
            }
            compose.onNodeWithTag(tag).performClick()
            val intent = shadowOf(compose.activity).nextStartedActivity
            org.junit.Assert.assertNotNull("No external activity from $tag", intent)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals(url, intent.dataString)
        }
    }

    @Test fun appreciationCodeCanBeOpenedAndClosedWithoutStartingPayment() {
        display()
        scrollTo("about_tip")
        compose.onNodeWithTag("about_tip").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        compose.onNodeWithTag("about_tip_dialog").assertIsDisplayed()
        compose.onNodeWithContentDescription("星澪Star_ZER0 的微信赞赏码").assertIsDisplayed()
        capture("about-tip")
        compose.onNodeWithTag("about_tip_save").performScrollTo().assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag("about_tip_close").performScrollTo().performClick()
        compose.onNodeWithTag("about_tip_dialog").assertDoesNotExist()
        compose.onNodeWithTag("main_about_page").assertIsDisplayed()
        assertEquals(null, shadowOf(compose.activity).nextStartedActivity)
    }

    @Test
    @Config(sdk = [35], qualifiers = "w360dp-h480dp-night")
    fun shortDarkWindowKeepsLinksAndDialogActionsReachable() {
        display()
        capture("about-dark-compact")
        scrollTo("about_project")
        compose.onNodeWithTag("about_project").assertIsDisplayed()
        scrollTo("about_tip")
        compose.onNodeWithTag("about_tip").performClick()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        capture("about-tip-dark-compact-top")
        compose.onNodeWithTag("about_tip_save").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("about_tip_close").performScrollTo().assertIsDisplayed()
        capture("about-tip-dark-compact")
        compose.onNodeWithTag("about_tip_close").performClick()
        compose.onNodeWithTag("about_tip_dialog").assertDoesNotExist()
    }
}
