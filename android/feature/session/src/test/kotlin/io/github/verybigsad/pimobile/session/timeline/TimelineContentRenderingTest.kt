package io.github.verybigsad.pimobile.session

import com.google.common.truth.Truth.assertThat
import io.github.verybigsad.pimobile.model.MessageContent
import io.github.verybigsad.pimobile.model.MessageContentKind
import org.junit.Test

class TimelineContentRenderingTest {
    @Test
    fun textPartRendersItsTextNotTheJsonEnvelope() {
        val content = MessageContent(
            stableId = "content-0",
            kind = MessageContentKind.TEXT,
            contentVersion = 0,
            projection = "{\"text\":\"hello pi\",\"type\":\"text\"}",
        )
        assertThat(displayMessageText(content)).isEqualTo("hello pi")
    }

    @Test
    fun thinkingPartRendersItsThinkingText() {
        val content = MessageContent(
            stableId = "content-0",
            kind = MessageContentKind.THINKING,
            contentVersion = 0,
            projection = "{\"thinking\":\"working it out\",\"type\":\"thinking\"}",
        )
        assertThat(displayMessageText(content)).isEqualTo("working it out")
    }

    @Test
    fun unknownOrMalformedPartFallsBackToTheRawProjection() {
        val raw = "{\"type\":\"tool_call\",\"name\":\"bash\"}"
        val content = MessageContent(
            stableId = "content-0",
            kind = MessageContentKind.TOOL_CALL,
            contentVersion = 0,
            projection = raw,
        )
        assertThat(displayMessageText(content)).isEqualTo(raw)

        val malformed = MessageContent(
            stableId = "content-1",
            kind = MessageContentKind.TEXT,
            contentVersion = 0,
            projection = "not json",
        )
        assertThat(displayMessageText(malformed)).isEqualTo("not json")
    }

    @Test
    fun textPartWithoutTextFieldFallsBackToTheRawProjection() {
        val raw = "{\"type\":\"text\"}"
        val content = MessageContent(
            stableId = "content-0",
            kind = MessageContentKind.TEXT,
            contentVersion = 0,
            projection = raw,
        )
        assertThat(displayMessageText(content)).isEqualTo(raw)
    }
}
