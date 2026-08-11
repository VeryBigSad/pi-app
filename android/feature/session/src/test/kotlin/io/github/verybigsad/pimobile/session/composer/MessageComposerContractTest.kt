package io.github.verybigsad.pimobile.session.composer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageComposerContractTest {
    @Test
    fun attachmentValidationAcceptsBoundedDistinctImages() {
        val attachments = List(MAX_COMPOSER_ATTACHMENTS) { index ->
            ComposerAttachment(id = "image-$index", name = "image-$index.png", byteCount = 1)
        }

        assertThat(validateAttachments(attachments)).isTrue()
    }

    @Test
    fun attachmentValidationRejectsDuplicateIds() {
        val attachment = ComposerAttachment(id = "image", name = "image.png", byteCount = 1)

        assertThat(validateAttachments(listOf(attachment, attachment))).isFalse()
    }

    @Test
    fun slashCommandsRequireProtocolSyntax() {
        val command = SlashCommand(name = "/compact", description = "Compact the conversation")

        assertThat(command.name).startsWith("/")
    }

    @Test(expected = IllegalArgumentException::class)
    fun slashCommandsRejectNonProtocolSyntax() {
        SlashCommand(name = "compact", description = "Compact the conversation")
    }
}
