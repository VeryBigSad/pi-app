package io.github.verybigsad.pimobile.session

import com.google.common.truth.Truth.assertThat
import kotlin.random.Random
import org.junit.Test

class StructuredDisplaySanitizerTest {

    @Test
    fun sanitizerDropsDelAndAllC1Controls() {
        val c1 = (0x80..0x9F).joinToString("") { it.toChar().toString() }
        val value = "safe\u007F$c1 text"

        assertThat(sanitizeStructuredDisplay(value)).isEqualTo("safe text")
    }

    @Test
    fun sanitizerDropsBidiOverridesAndDirectionalMarks() {
        val value = buildString {
            append("report")
            append('\u202A') // LRE
            append('\u202B') // RLE
            append('\u202C') // PDF
            append('\u202D') // LRO
            append('\u202E') // RLO
            append('\u2066') // LRI
            append('\u2067') // RLI
            append('\u2068') // FSI
            append('\u2069') // PDI
            append('\u200E') // LRM
            append('\u200F') // RLM
            append('\u061C') // ALM
            append("total: 42")
        }

        assertThat(sanitizeStructuredDisplay(value)).isEqualTo("reporttotal: 42")
    }

    @Test
    fun sanitizerDropsZeroWidthAndInvisibleFormatCharacters() {
        val value = "vi\u200Bsi\u200Bble\u200Cte\u200Dxt\u00ADspan\uFEFFboom\uFFF9anchor\uFFFAin\uFFFBfix"

        assertThat(sanitizeStructuredDisplay(value)).isEqualTo("visibletextspanboomanchorinfix")
    }

    @Test
    fun sanitizerDropsTagCharactersAndDeprecatedFormatControls() {
        val value = buildString {
            append("base")
            appendCodePoint(0xE0001) // LANGUAGE TAG
            appendCodePoint(0xE0041) // TAG A
            appendCodePoint(0xE007F) // CANCEL TAG
            append('\u206A') // INHIBIT SYMMETRIC SWAPPING
            append('\u206F') // NOMINAL DIGIT SHAPES
            append("end")
        }

        assertThat(sanitizeStructuredDisplay(value)).isEqualTo("baseend")
    }

    @Test
    fun sanitizerKeepsPrivateUseCharacters() {
        val value = buildString {
            append("icon")
            appendCodePoint(0xE000)
            appendCodePoint(0xF8FF)
            appendCodePoint(0xF0000)
            appendCodePoint(0x10FFFD)
        }

        assertThat(sanitizeStructuredDisplay(value)).isEqualTo(value)
    }

    @Test
    fun sanitizerNormalizesCrlfAndLoneCarriageReturns() {
        assertThat(sanitizeStructuredDisplay("a\r\nb\rc\n\nd")).isEqualTo("a\nb\nc\n\nd")
        assertThat(sanitizeStructuredDisplay("\r\n\r\n")).isEqualTo("\n\n")
    }

    @Test
    fun sanitizerStripsWellFormedAnsiAndOscSequences() {
        val value = "ok\u001B[1;31mred\u001B[0m\u001B]0;window title\u0007done\u001B]8;;http://evil\u001B\\link\u001B]8;;\u001B\\"

        assertThat(sanitizeStructuredDisplay(value)).isEqualTo("okreddonelink")
    }

    @Test
    fun sanitizerNeutralizesMalformedAnsiSequences() {
        // Unterminated CSI (no final byte): ESC dropped as Cc, remainder is inert text.
        assertThat(sanitizeStructuredDisplay("a\u001B[31")).isEqualTo("a[31")
        // Lone ESC and bare C1 CSI introducer vanish entirely.
        assertThat(sanitizeStructuredDisplay("a\u001Bb\u009B31mc")).isEqualTo("ab31mc")
        // ESC mid-word never reaches the UI.
        assertThat(sanitizeStructuredDisplay("x\u001B\u001By")).isEqualTo("xy")
    }

    @Test
    fun sanitizerNeutralizesUnterminatedOscSequences() {
        // No BEL/ST terminator: ESC is dropped, payload degrades to inert text.
        assertThat(sanitizeStructuredDisplay("pre\u001B]0;spoofed titlepost"))
            .isEqualTo("pre]0;spoofed titlepost")
        assertThat(sanitizeStructuredDisplay("\u001B]8;;http://evil")).isEqualTo("]8;;http://evil")
    }

    @Test
    fun sanitizerKeepsBenignVisibleTextUnchanged() {
        val snapshot = "Pi session “alpha” — запуск теста: 42/42 ✓\n" +
            "\tstatus: مرحبا שלום こんにちは 안녕\n" +
            "emoji 🚀 ✅ and symbols ∑ ∫ → ✓"

        assertThat(sanitizeStructuredDisplay(snapshot)).isEqualTo(snapshot)
    }

    @Test
    fun sanitizerMixedContentSnapshotKeepsOnlyVisibleText() {
        val value = "\u001B[32mPASS\u001B[0m\r\n" +
            "\tmodule: feature/session\u007F\n" +
            "\u202Eevo\u202Clve\u001B[4munderlined\u001B[0m � ok"

        assertThat(sanitizeStructuredDisplay(value)).isEqualTo(
            "PASS\n\tmodule: feature/session\nevolveunderlined � ok",
        )
    }

    @Test
    fun sanitizerFuzzNeverEmitsControlOrFormatCharacters() {
        val attackAlphabet = buildList {
            addAll((0x00..0x1F).map { it.toChar() })
            addAll((0x7F..0x9F).map { it.toChar() })
            addAll(
                listOf(
                    '\u00AD', '\u061C', '\u202A', '\u202B', '\u202C', '\u202D', '\u202E',
                    '\u2066', '\u2067', '\u2068', '\u2069', '\u200E', '\u200F', '\u200B', '\u200C', '\u200D', '\uFEFF',
                    '\u206A', '\u206B', '\u206C', '\u206D', '\u206E', '\u206F', '\uFFF9', '\uFFFA', '\uFFFB',
                ),
            )
        }
        val benignAlphabet = "abcXYZ 019é中🚀.-_=+{}()\t\n".toList()
        val random = Random(0x5EED)

        repeat(2_000) {
            val length = random.nextInt(0, 96)
            val input = buildString {
                repeat(length) {
                    when (random.nextInt(10)) {
                        0, 1 -> append(attackAlphabet[random.nextInt(attackAlphabet.size)])
                        2 -> append("\r\n")
                        3 -> append("\u001B[${random.nextInt(99)}m")
                        4 -> append("\u001B]${random.nextInt(9)};title\u0007")
                        5 -> append('\u001B')
                        else -> append(benignAlphabet[random.nextInt(benignAlphabet.size)])
                    }
                }
            }

            val output = sanitizeStructuredDisplay(input)

            var index = 0
            while (index < output.length) {
                val codePoint = output.codePointAt(index)
                if (codePoint != '\t'.code && codePoint != '\n'.code) {
                    assertThat(Character.getType(codePoint)).isNotEqualTo(Character.CONTROL.toInt())
                }
                assertThat(Character.getType(codePoint)).isNotEqualTo(Character.FORMAT.toInt())
                index += Character.charCount(codePoint)
            }
            assertThat(output).doesNotContain("\u001B")
            assertThat(output).doesNotContain("\r")

            // Idempotence.
            assertThat(sanitizeStructuredDisplay(output)).isEqualTo(output)
        }
    }

    @Test
    fun sanitizerFuzzPreservesBenignTextContent() {
        // Interleave attack chars into benign text; benign visible text must
        // survive contiguously.
        val random = Random(0xC0FFEE)
        val benignWords = listOf("hello", "world", "session", "42", "✓ok", "текст")

        repeat(1_000) {
            val word = benignWords[random.nextInt(benignWords.size)]
            val poisoned = buildString {
                word.forEach { character ->
                    append(character)
                    if (random.nextBoolean()) {
                        append((0x80..0x9F).random(random).toChar())
                    }
                    if (random.nextBoolean()) {
                        append('\u202E')
                    }
                }
            }

            assertThat(sanitizeStructuredDisplay(poisoned)).contains(word)
        }
    }

    @Test
    fun sanitizerFuzzBenignOnlyInputIsByteIdentical() {
        val benignPool = "Hello world 123 !@#$%^&*()_+-={}|;:',.<>/?`~ é中🚀\t\n"
        val random = Random(0xBEEF)

        repeat(1_000) {
            val length = random.nextInt(0, 128)
            val input = buildString {
                repeat(length) { append(benignPool[random.nextInt(benignPool.length)]) }
            }

            assertThat(sanitizeStructuredDisplay(input)).isEqualTo(input)
        }
    }
}
