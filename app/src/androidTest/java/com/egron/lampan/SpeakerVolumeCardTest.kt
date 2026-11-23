package com.egron.lampan

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.egron.lampan.ui.theme.LampanTheme
import android.graphics.Bitmap
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SpeakerVolumeCardTest {
    @get:Rule val compose = createComposeRule()
    private val percent = mutableStateOf(50)
    private val revision = mutableStateOf(0)
    private val acknowledgement = mutableStateOf(0L)
    private val requests = mutableListOf<Int>()

    private fun singleSpeaker() {
        compose.setContent {
            MaterialTheme {
                SpeakerVolumeCard(
                    groupPercent = percent.value,
                    levels = mapOf("kitchen" to percent.value),
                    names = mapOf("kitchen" to "Kitchen"),
                    revision = revision.value,
                    acknowledgement = acknowledgement.value,
                    onChange = { _, value -> requests.add(value); requests.size.toLong() },
                )
            }
        }
    }

    @Test fun rejectedCommandRestoresTheAcknowledgedValue() {
        singleSpeaker()
        compose.onNodeWithContentDescription("Increase Kitchen volume").performClick()
        compose.onNodeWithText("52%").assertIsDisplayed()
        compose.runOnIdle { acknowledgement.value = 1; revision.value++ }
        compose.onNodeWithText("50%").assertIsDisplayed()
    }

    @Test fun olderAcknowledgementDoesNotUndoRapidPresses() {
        singleSpeaker()
        val plus = compose.onNodeWithContentDescription("Increase Kitchen volume")
        plus.performClick()
        plus.performClick()
        compose.runOnIdle {
            percent.value = 52
            acknowledgement.value = 1
            revision.value++
        }
        plus.performClick()
        compose.runOnIdle { assertEquals(listOf(52, 54, 56), requests) }
    }

    @Test fun distantTrackTapDoesNotJumpToMaximum() {
        singleSpeaker()
        compose.onNodeWithContentDescription("Kitchen volume").performTouchInput {
            // Stay within the track, outside the neighboring button's touch target.
            click(Offset(width * 0.85f, height / 2f))
        }
        compose.runOnIdle { assertEquals(emptyList<Int>(), requests) }
        compose.onNodeWithText("50%").assertIsDisplayed()
    }

    @Test fun accessibilityCanStillAdjustVolume() {
        singleSpeaker()
        compose.onNodeWithContentDescription("Kitchen volume")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(62f) }
        compose.runOnIdle { assertEquals(listOf(62), requests) }
    }

    @Test fun verticalSwipeAcrossTrackStillScrolls() {
        lateinit var scroll: ScrollState
        compose.setContent {
            MaterialTheme {
                scroll = rememberScrollState()
                Column(Modifier.height(240.dp).verticalScroll(scroll)) {
                    SpeakerVolumeCard(
                        groupPercent = 50,
                        levels = mapOf("a" to 50, "b" to 30),
                        names = mapOf("a" to "Kitchen", "b" to "Bedroom"),
                        revision = 0, acknowledgement = 0,
                        onChange = { _, value -> requests.add(value); requests.size.toLong() },
                    )
                }
            }
        }
        compose.onNodeWithText("Individual speakers").performClick()
        compose.onNodeWithContentDescription("All speakers volume").performTouchInput {
            swipe(Offset(width * 0.15f, height / 2f), Offset(width * 0.15f, -200f))
        }
        compose.runOnIdle {
            assertTrue(scroll.value > 0)
            assertEquals(emptyList<Int>(), requests)
        }
    }

    @Test fun thumbDragCommitsASteppedVolume() {
        singleSpeaker()
        compose.onNodeWithContentDescription("Kitchen volume").performTouchInput {
            swipe(Offset(width / 2f, height / 2f), Offset(width * 0.8f, height / 2f))
        }
        compose.runOnIdle {
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.last() > 50)
            assertEquals(0, requests.last() % 2)
        }
    }

    @Test fun individualControlsRemainReachableAtDoubleTextSize() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, 2f)) {
                LampanTheme(darkTheme = true) {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SpeakerVolumeCard(
                            groupPercent = 60,
                            levels = mapOf("a" to 60, "b" to 30),
                            names = mapOf("a" to "Kitchen and dining room speakers", "b" to "Bedroom"),
                            revision = 0,
                            acknowledgement = 0,
                            onChange = { _, value -> requests.add(value); requests.size.toLong() },
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("Individual speakers").performClick()
        saveScreenshot("volume-group-large.png")
        compose.onNodeWithContentDescription("Increase Bedroom volume")
            .performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf(32), requests) }
    }

    @Test fun groupControlsExpandWithoutClutteringTheInitialCard() {
        compose.setContent {
            LampanTheme(darkTheme = true) {
                SpeakerVolumeCard(
                    groupPercent = 60,
                    levels = mapOf("a" to 60, "b" to 30),
                    names = mapOf("a" to "Kitchen", "b" to "Bedroom"),
                    revision = 0,
                    acknowledgement = 0,
                    onChange = { _, value -> requests.add(value); requests.size.toLong() },
                )
            }
        }
        compose.onNodeWithText("Individual speakers").performClick()
        compose.onNodeWithContentDescription("Kitchen volume").assertIsDisplayed()
        compose.onNodeWithContentDescription("Bedroom volume").assertIsDisplayed()
        saveScreenshot("volume-group.png")
        compose.onNodeWithText("Hide individual speakers").performClick()
        compose.onNodeWithContentDescription("Kitchen volume").assertDoesNotExist()
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
