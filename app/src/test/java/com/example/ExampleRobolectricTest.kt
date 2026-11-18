package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<MainActivity>()

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("צלילים", appName)
  }

  @Test
  fun `app launches directly into music discovery without google sign in`() {
    composeRule.onNodeWithTag("search_input").assertIsDisplayed()
    composeRule.onNodeWithTag("nav_discover").assertIsDisplayed()
    composeRule.onNodeWithTag("nav_playlists").assertIsDisplayed()
    composeRule.onNodeWithTag("google_sign_in_button").assertDoesNotExist()
  }

  @Test
  fun `youtube stream resolver prioritizes direct mp4 audio without webview`() {
    val sampleJson = """
      {
        "streamingData": {
          "adaptiveFormats": [
            {"mimeType": "video/mp4; codecs=\"avc1\"", "bitrate": 500000, "url": "https://example.com/video.mp4"},
            {"mimeType": "audio/webm; codecs=\"opus\"", "bitrate": 160000, "url": "https://example.com/audio.webm"},
            {"mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "url": "https://example.com/audio.m4a"},
            {"mimeType": "audio/mp4; codecs=\"mp4a.40.2\"", "bitrate": 128000, "signatureCipher": "s=abc"}
          ],
          "formats": [
            {"mimeType": "video/mp4; codecs=\"avc1, mp4a\"", "bitrate": 300000, "url": "https://example.com/muxed.mp4"}
          ]
        }
      }
    """.trimIndent()

    val streams = com.example.player.YouTubeStreamResolver.parseInnerTubePlayerResponse(
      jsonText = sampleJson,
      userAgent = "TestUA",
      label = "InnerTube-iOS"
    )
    assertEquals(3, streams.size)
    assertEquals("https://example.com/audio.m4a", streams[0].url)
    assertEquals("https://example.com/audio.webm", streams[1].url)
    assertEquals("https://example.com/muxed.mp4", streams[2].url)
    assertEquals("TestUA", streams[0].userAgent)
  }
}
