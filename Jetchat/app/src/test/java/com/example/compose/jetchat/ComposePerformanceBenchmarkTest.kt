/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:OptIn(
    InternalComposeApi::class,
    ExperimentalComposeRuntimeApi::class,
    InternalComposeTracingApi::class,
)

package com.example.compose.jetchat

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.ExperimentalComposeRuntimeApi
import androidx.compose.runtime.InternalComposeApi
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.example.compose.jetchat.conversation.ConversationContent
import com.example.compose.jetchat.conversation.ConversationUiState
import com.example.compose.jetchat.conversation.Message
import com.example.compose.jetchat.data.colleagueProfile
import com.example.compose.jetchat.data.exampleUiState
import com.example.compose.jetchat.profile.ProfileScreen
import com.example.compose.jetchat.theme.JetchatTheme
import kotlin.math.roundToInt
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ComposePerformanceBenchmarkTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private class RecompositionTracker : CompositionTracer {
        var totalTraceEvents = 0
        val countsByPrefix = mutableMapOf<String, Int>()
        var isRecording = false

        override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
            if (!isRecording) return
            totalTraceEvents++
            val cleanName = info.substringBefore(" (").trim()
            countsByPrefix[cleanName] = (countsByPrefix[cleanName] ?: 0) + 1
        }

        override fun traceEventEnd() {}

        override fun isTraceInProgress(): Boolean = isRecording

        fun reset() {
            totalTraceEvents = 0
            countsByPrefix.clear()
        }

        fun countContaining(substring: String): Int = countsByPrefix.entries.filter { it.key.contains(substring) }.sumOf { it.value }
    }

    private fun percentile(sortedValues: List<Double>, p: Double): Double {
        if (sortedValues.isEmpty()) return 0.0
        val index = ((p / 100.0) * (sortedValues.size - 1)).roundToInt().coerceIn(0, sortedValues.lastIndex)
        return sortedValues[index]
    }

    @Test
    fun runAllPerformanceBenchmarks() {
        val tracker = RecompositionTracker()
        Composer.setTracer(tracker)

        var mode by mutableIntStateOf(0)
        val uiState = ConversationUiState(
            channelName = "#composers",
            channelMembers = 42,
            initialMessages = exampleUiState.messages.toList(),
        )
        val noOpNestedScroll = object : NestedScrollConnection {}

        // 1. Render ProfileScreen first (mode == 0) and measure 20 vertical scroll gestures
        composeTestRule.setContent {
            JetchatTheme {
                if (mode == 0) {
                    ProfileScreen(
                        userData = colleagueProfile,
                        nestedScrollInteropConnection = noOpNestedScroll,
                    )
                } else {
                    ConversationContent(
                        uiState = uiState,
                        navigateToProfile = {},
                    )
                }
            }
        }
        composeTestRule.waitForIdle()

        val profileScrollGestureDurationsMs = mutableListOf<Double>()
        tracker.reset()
        tracker.isRecording = true

        val scrollableProfile = composeTestRule.onAllNodes(hasScrollAction())[0]
        for (i in 1..10) {
            val startNs = System.nanoTime()
            scrollableProfile.performTouchInput {
                swipeUp(startY = bottom * 0.8f, endY = top + 50f, durationMillis = 150)
            }
            composeTestRule.waitForIdle()
            val upMs = (System.nanoTime() - startNs) / 1_000_000.0
            profileScrollGestureDurationsMs.add(upMs)

            val downStartNs = System.nanoTime()
            scrollableProfile.performTouchInput {
                swipeDown(startY = top + 50f, endY = bottom * 0.8f, durationMillis = 150)
            }
            composeTestRule.waitForIdle()
            val downMs = (System.nanoTime() - downStartNs) / 1_000_000.0
            profileScrollGestureDurationsMs.add(downMs)
        }
        tracker.isRecording = false

        val profileScreenRecompositions = tracker.countContaining("com.example.compose.jetchat.profile.ProfileScreen")
        val profileHeaderRecompositions = tracker.countContaining("com.example.compose.jetchat.profile.ProfileHeader")
        val profileFabRecompositions = tracker.countContaining("com.example.compose.jetchat.profile.ProfileFab")
        val totalProfileScrollTraceEvents = tracker.totalTraceEvents
        val sortedProfileScrollFrames = profileScrollGestureDurationsMs.sorted()

        // 2. Switch to ConversationContent (mode == 1) and measure Cold Start + 30 message insertions + LazyColumn scroll
        val coldStartNs = System.nanoTime()
        composeTestRule.runOnIdle {
            mode = 1
        }
        composeTestRule.waitForIdle()
        val coldStartMs = (System.nanoTime() - coldStartNs) / 1_000_000.0

        val messageInsertFrameDurationsMs = mutableListOf<Double>()
        tracker.reset()
        tracker.isRecording = true

        for (i in 1..30) {
            val frameStartNs = System.nanoTime()
            composeTestRule.runOnIdle {
                uiState.addMessage(
                    Message(
                        author = "Author $i",
                        content = "Performance benchmark message #$i checking LazyColumn key skipping and @Immutable Message stability",
                        timestamp = "8:10 PM",
                    ),
                )
            }
            composeTestRule.waitForIdle()
            val frameMs = (System.nanoTime() - frameStartNs) / 1_000_000.0
            messageInsertFrameDurationsMs.add(frameMs)
        }
        tracker.isRecording = false

        val messageComposableInvocations = tracker.countContaining("com.example.compose.jetchat.conversation.Message")
        val authorAndTextInvocations = tracker.countContaining("com.example.compose.jetchat.conversation.AuthorAndTextMessage")
        val chatBubbleInvocations = tracker.countContaining("com.example.compose.jetchat.conversation.ChatItemBubble")
        val clickableMessageInvocations = tracker.countContaining("com.example.compose.jetchat.conversation.ClickableMessage")
        val totalInsertTraceEvents = tracker.totalTraceEvents
        val sortedInsertFrames = messageInsertFrameDurationsMs.sorted()

        // 3. Conversation LazyColumn Scroll Fling Benchmark (10 scroll gestures on conversation list)
        val conversationScrollDurationsMs = mutableListOf<Double>()
        tracker.reset()
        tracker.isRecording = true

        val scrollableConversation = composeTestRule.onAllNodes(hasScrollAction())[0]
        for (i in 1..5) {
            val startNs = System.nanoTime()
            scrollableConversation.performTouchInput {
                swipeDown(startY = top + 100f, endY = bottom * 0.8f, durationMillis = 120)
            }
            composeTestRule.waitForIdle()
            conversationScrollDurationsMs.add((System.nanoTime() - startNs) / 1_000_000.0)

            val upStartNs = System.nanoTime()
            scrollableConversation.performTouchInput {
                swipeUp(startY = bottom * 0.8f, endY = top + 100f, durationMillis = 120)
            }
            composeTestRule.waitForIdle()
            conversationScrollDurationsMs.add((System.nanoTime() - upStartNs) / 1_000_000.0)
        }
        tracker.isRecording = false
        val sortedConvScrollFrames = conversationScrollDurationsMs.sorted()
        val convScrollTotalEvents = tracker.totalTraceEvents

        println("=== JETCHAT_BENCHMARK_RESULTS_START ===")
        println("coldStartMs=${"%.2f".format(coldStartMs)}")
        println("messageInsert_frames=30")
        println("messageInsert_P50_ms=${"%.2f".format(percentile(sortedInsertFrames, 50.0))}")
        println("messageInsert_P90_ms=${"%.2f".format(percentile(sortedInsertFrames, 90.0))}")
        println("messageInsert_P95_ms=${"%.2f".format(percentile(sortedInsertFrames, 95.0))}")
        println("messageInsert_P99_ms=${"%.2f".format(percentile(sortedInsertFrames, 99.0))}")
        println("messageInsert_sum_ms=${"%.2f".format(messageInsertFrameDurationsMs.sum())}")
        println("messageInsert_Message_invocations=$messageComposableInvocations")
        println("messageInsert_AuthorAndTextMessage_invocations=$authorAndTextInvocations")
        println("messageInsert_ChatItemBubble_invocations=$chatBubbleInvocations")
        println("messageInsert_ClickableMessage_invocations=$clickableMessageInvocations")
        println("messageInsert_totalComposerEvents=$totalInsertTraceEvents")
        println("conversationScroll_gestures=10")
        println("conversationScroll_P50_ms=${"%.2f".format(percentile(sortedConvScrollFrames, 50.0))}")
        println("conversationScroll_P95_ms=${"%.2f".format(percentile(sortedConvScrollFrames, 95.0))}")
        println("conversationScroll_P99_ms=${"%.2f".format(percentile(sortedConvScrollFrames, 99.0))}")
        println("conversationScroll_sum_ms=${"%.2f".format(conversationScrollDurationsMs.sum())}")
        println("conversationScroll_totalComposerEvents=$convScrollTotalEvents")
        println("profileScroll_gestures=20")
        println("profileScroll_P50_ms=${"%.2f".format(percentile(sortedProfileScrollFrames, 50.0))}")
        println("profileScroll_P95_ms=${"%.2f".format(percentile(sortedProfileScrollFrames, 95.0))}")
        println("profileScroll_P99_ms=${"%.2f".format(percentile(sortedProfileScrollFrames, 99.0))}")
        println("profileScroll_sum_ms=${"%.2f".format(profileScrollGestureDurationsMs.sum())}")
        println("profileScroll_ProfileScreen_recompositions=$profileScreenRecompositions")
        println("profileScroll_ProfileHeader_recompositions=$profileHeaderRecompositions")
        println("profileScroll_ProfileFab_recompositions=$profileFabRecompositions")
        println("profileScroll_totalComposerEvents=$totalProfileScrollTraceEvents")
        println("=== JETCHAT_BENCHMARK_RESULTS_END ===")
    }
}
