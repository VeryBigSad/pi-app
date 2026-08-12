package io.github.verybigsad.pimobile.storage

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.TypeConverter
import java.security.MessageDigest

private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val LEAF_ID_PATTERN = Regex("^[0-9a-f]{8}$")

const val CANONICAL_UINT64_MAX_TEXT = "18446744073709551615"

private val CANONICAL_UINT64_DIGITS = Regex("^[0-9]+$")

/**
 * Canonical decimal text form of a protocol uint64 (docs/protocol-v1.md): digits only,
 * no leading zeros, value at most 2^64 - 1. Canonical form makes (LENGTH, text)
 * lexicographic ordering numeric, which SQLite cursor/index ordering relies on.
 */
internal fun isCanonicalUint64Decimal(value: String): Boolean =
    CANONICAL_UINT64_DIGITS.matches(value) &&
        (value == "0" || !value.startsWith("0")) &&
        (value.length < CANONICAL_UINT64_MAX_TEXT.length ||
            (value.length == CANONICAL_UINT64_MAX_TEXT.length && value <= CANONICAL_UINT64_MAX_TEXT))

internal fun compareCanonicalUint64(left: String, right: String): Int =
    if (left.length != right.length) left.length.compareTo(right.length) else left.compareTo(right)

internal fun sha256Hex(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.encodeToByteArray())
    .joinToString("") { byte -> "%02x".format(byte) }

enum class StoredMessageRole {
    USER,
    ASSISTANT,
    TOOL,
    SYSTEM,
    UNKNOWN,
}

enum class FinalizedMessageState {
    FINALIZED,
    LEGACY_UNKNOWN,
}

enum class FinalMetadataSource {
    AUTHORITATIVE,
    LEGACY_V1,
}

enum class CommandReceiptState {
    RECEIVED,
    ARMED,
    ACKED,
    REJECTED,
    INDETERMINATE,
    UNKNOWN,
}

enum class StoredTrustStatus {
    TRUSTED,
    REVOKED,
}

data class CanonicalAppendCursor(
    @ColumnInfo(name = "stream_epoch") val streamEpoch: String,
    @ColumnInfo(name = "sequence") val sequence: String,
    @ColumnInfo(name = "leaf_id") val leafId: String?,
    @ColumnInfo(name = "last_append_id") val lastAppendId: String?,
) {
    init {
        require(streamEpoch.isNotBlank())
        require(isCanonicalUint64Decimal(sequence)) { "Non-canonical uint64 sequence: $sequence" }
        require(leafId == null || LEAF_ID_PATTERN.matches(leafId))
        require(lastAppendId == null || lastAppendId.isNotBlank())
    }
}

data class AuthoritativeFinalMetadata(
    @ColumnInfo(name = "source") val source: FinalMetadataSource,
    @ColumnInfo(name = "raw_json") val rawJson: String?,
    @ColumnInfo(name = "raw_ref") val rawRef: String?,
    @ColumnInfo(name = "raw_size_bytes") val rawSizeBytes: Long,
    @ColumnInfo(name = "raw_sha256") val rawSha256: String,
    @ColumnInfo(name = "projection_json") val projectionJson: String,
    @ColumnInfo(name = "signature") val signature: String?,
    @ColumnInfo(name = "redacted") val redacted: Boolean,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "finalized_at_epoch_ms") val finalizedAtEpochMs: Long,
) {
    init {
        require((rawJson == null) != (rawRef == null))
        require(rawRef == null || rawRef.isNotBlank())
        require(rawSizeBytes >= 0)
        require(SHA256_PATTERN.matches(rawSha256))
        require(projectionJson.isNotBlank())
        require(signature == null || signature.isNotBlank())
        require(createdAtEpochMs >= 0)
        require(finalizedAtEpochMs >= createdAtEpochMs)
        if (rawJson != null) {
            require(rawJson.encodeToByteArray().size.toLong() == rawSizeBytes)
            require(sha256Hex(rawJson) == rawSha256) { "rawSha256 does not match rawJson bytes" }
        }
    }
}

@Entity(tableName = "sessions", primaryKeys = ["sessionId"])
data class SessionEntity(
    val sessionId: String,
    val cwd: String,
    val displayName: String?,
    val provider: String,
    val modelId: String,
    val thinkingLevel: String,
    @Embedded(prefix = "canonical_") val canonicalCursor: CanonicalAppendCursor?,
    val updatedAtEpochMs: Long,
    val repositoryPath: String = cwd,
    val worktreePath: String = cwd,
    val parentSessionId: String? = null,
) {
    init {
        require(sessionId.isNotBlank())
        require(cwd.isNotBlank())
        require(repositoryPath.isNotBlank())
        require(worktreePath.isNotBlank())
        require(parentSessionId != sessionId)
        require(provider.isNotBlank())
        require(modelId.isNotBlank())
        require(thinkingLevel.isNotBlank())
        require(updatedAtEpochMs >= 0)
    }
}

@Entity(
    tableName = "messages",
    primaryKeys = ["sessionId", "messageId"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "appendOrder"], unique = true),
        Index(value = ["sessionId", "appendId"], unique = true),
    ],
)
data class MessageEntity(
    val sessionId: String,
    val messageId: String,
    val parentId: String?,
    /** Canonical decimal text of the protocol uint64 append order. */
    val appendOrder: String,
    val appendId: String?,
    val role: StoredMessageRole,
    val state: FinalizedMessageState,
    val contentJson: String,
    @Embedded(prefix = "final_") val authoritativeFinal: AuthoritativeFinalMetadata,
) {
    init {
        require(sessionId.isNotBlank())
        require(messageId.isNotBlank())
        require(parentId == null || parentId.isNotBlank())
        require(isCanonicalUint64Decimal(appendOrder)) { "Non-canonical uint64 appendOrder: $appendOrder" }
        require(appendId == null || appendId.isNotBlank())
        require(contentJson.isNotBlank())
        if (authoritativeFinal.source == FinalMetadataSource.AUTHORITATIVE) {
            require(state == FinalizedMessageState.FINALIZED)
            require(appendId != null)
        }
    }
}

@Entity(tableName = "command_receipts", primaryKeys = ["commandId"], indices = [Index("sessionId")])
data class CommandReceiptEntity(
    val commandId: String,
    val sessionId: String,
    val state: CommandReceiptState,
    val resultDigest: String?,
    val updatedAtEpochMs: Long,
) {
    init {
        require(commandId.isNotBlank())
        require(sessionId.isNotBlank())
        require(resultDigest == null || SHA256_PATTERN.matches(resultDigest))
        require(updatedAtEpochMs >= 0)
    }
}

/**
 * Composer drafts survive sync resets, cache eviction, and process death. Deliberately no
 * foreign key: drafts are retained even when the session row is wiped and later resynced.
 */
@Entity(tableName = "drafts", primaryKeys = ["sessionId"])
data class DraftEntity(
    val sessionId: String,
    val typedText: String,
    val transcriptionText: String?,
    val revision: Long,
    val updatedAtEpochMs: Long,
) {
    init {
        require(sessionId.isNotBlank())
        require(revision >= 0)
        require(updatedAtEpochMs >= 0)
    }
}

/**
 * Pairing trust state per Mac. Absence of a row means unpaired. Trust rows are never
 * LRU-evicted with message cache data; revocation rows are retained for audit.
 */
@Entity(tableName = "trust_states", primaryKeys = ["macId"])
data class TrustStateEntity(
    val macId: String,
    val status: StoredTrustStatus,
    val displayName: String?,
    val certificateSerial: String?,
    val certificateNotAfterEpochMs: Long?,
    val revokedAtEpochMs: Long?,
    val revocationReasonCode: String?,
    val updatedAtEpochMs: Long,
) {
    init {
        require(macId.isNotBlank())
        require(updatedAtEpochMs >= 0)
        when (status) {
            StoredTrustStatus.TRUSTED -> {
                require(!displayName.isNullOrBlank())
                require(!certificateSerial.isNullOrBlank())
                require(certificateNotAfterEpochMs != null && certificateNotAfterEpochMs >= 0)
            }

            StoredTrustStatus.REVOKED -> {
                require(revokedAtEpochMs != null && revokedAtEpochMs >= 0)
                require(!revocationReasonCode.isNullOrBlank())
            }
        }
    }
}

internal class StorageTypeConverters {
    @TypeConverter
    fun storedMessageRole(value: String): StoredMessageRole = enumValueOf(value)

    @TypeConverter
    fun storedMessageRole(value: StoredMessageRole): String = value.name

    @TypeConverter
    fun finalizedMessageState(value: String): FinalizedMessageState = enumValueOf(value)

    @TypeConverter
    fun finalizedMessageState(value: FinalizedMessageState): String = value.name

    @TypeConverter
    fun finalMetadataSource(value: String): FinalMetadataSource = enumValueOf(value)

    @TypeConverter
    fun finalMetadataSource(value: FinalMetadataSource): String = value.name

    @TypeConverter
    fun commandReceiptState(value: String): CommandReceiptState = enumValueOf(value)

    @TypeConverter
    fun commandReceiptState(value: CommandReceiptState): String = value.name

    @TypeConverter
    fun storedTrustStatus(value: String): StoredTrustStatus = enumValueOf(value)

    @TypeConverter
    fun storedTrustStatus(value: StoredTrustStatus): String = value.name
}
