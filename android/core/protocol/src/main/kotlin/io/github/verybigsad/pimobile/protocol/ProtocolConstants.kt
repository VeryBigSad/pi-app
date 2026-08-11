package io.github.verybigsad.pimobile.protocol

object ProtocolConstants {
    const val major = 1
    const val minor = 0
    const val headerBytes = 12
    const val streamPrefixBytes = 28
    const val terminalPrefixBytes = 16
    const val maxFramePayloadBytes = 1_048_576
    const val maxJsonPayloadBytes = 262_144
    const val maxBinaryDataBytes = 65_536
    const val maxEventBatchEvents = 128
    const val maxEventBatchBytes = 262_144
    const val maxInlineRawBytes = 131_072
    const val maxOutboundQueueFrames = 512
    const val maxOutboundQueueBytes = 8_388_608
    const val outboundQueueStallMilliseconds = 10_000
    const val maxPromptImageBytes = 8_388_608
    const val maxTerminalHistoryLines = 5_000
    const val maxTerminalHistoryBytes = 1_048_576
    const val maxFinalizedMessages = 500
    const val maxPiRecordBytes = 16_777_216
    const val maxReplayEventsPerSession = 10_000
    const val maxReplayBytesPerSession = 67_108_864
    const val maxReplayBytesGlobal = 268_435_456
    const val replayRetentionMilliseconds = 86_400_000L
    const val maxRawReferenceStoreBytes = 536_870_912
    const val rawReferenceRetentionMilliseconds = 2_592_000_000L
    const val maxPromptBlobBytesPerDevice = 67_108_864
    const val maxPromptBlobBytesGlobal = 268_435_456
    const val maxPromptBlobConcurrentUploads = 32
    const val promptBlobOrphanMilliseconds = 900_000L
    const val promptBlobDormantMilliseconds = 86_400_000L
    const val promptBlobTerminalMilliseconds = 3_600_000L
    const val journalDormantMilliseconds = 86_400_000L
    const val journalFullRetentionMilliseconds = 2_592_000_000L
    const val journalTombstoneRetentionMilliseconds = 31_536_000_000L
    const val maxJournalTombstoneRows = 100_000
    const val maxAndroidCacheMessages = 50_000
    const val maxAndroidCacheBytes = 536_870_912
    const val backgroundLeaseMilliseconds = 300_000L
    const val maxRouteJsonBytes = 16_384
    const val routeNonceBytes = 32
    const val routeChallengeMilliseconds = 30_000L
    const val routeReplayMilliseconds = 120_000L
    const val routeHeartbeatMilliseconds = 30_000L
    const val routeHeartbeatTimeoutMilliseconds = 90_000L
    const val routeRendezvousMilliseconds = 20_000L
    const val routeKeyOverlapMilliseconds = 86_400_000L
    const val maxPairingInvitationBytes = 2_048
    const val pairingInvitationMilliseconds = 300_000L
    const val maxApprovalQueueEntries = 8
    const val approvalPromotionMilliseconds = 30_000L
    const val approvalDecisionMilliseconds = 120_000L
    const val approvalHookMilliseconds = 150_000L
    const val maxVoiceBodyBytes = 65_536
    const val maxVoiceTextChars = 16_384
    const val maxAgents = 256
    val hardBounds = mapOf(
        "framePayloadBytes" to maxFramePayloadBytes.toLong(),
        "jsonPayloadBytes" to maxJsonPayloadBytes.toLong(),
        "binaryDataBytes" to maxBinaryDataBytes.toLong(),
        "eventBatchEvents" to maxEventBatchEvents.toLong(),
        "eventBatchBytes" to maxEventBatchBytes.toLong(),
        "inlineRawBytes" to maxInlineRawBytes.toLong(),
        "outboundQueueFrames" to maxOutboundQueueFrames.toLong(),
        "outboundQueueBytes" to maxOutboundQueueBytes.toLong(),
        "outboundQueueStallMilliseconds" to outboundQueueStallMilliseconds.toLong(),
        "promptImageBytes" to maxPromptImageBytes.toLong(),
        "terminalHistoryLines" to maxTerminalHistoryLines.toLong(),
        "terminalHistoryBytes" to maxTerminalHistoryBytes.toLong(),
        "finalizedMessages" to maxFinalizedMessages.toLong(),
        "piRecordBytes" to maxPiRecordBytes.toLong(),
        "replayEventsPerSession" to maxReplayEventsPerSession.toLong(),
        "replayBytesPerSession" to maxReplayBytesPerSession.toLong(),
        "replayBytesGlobal" to maxReplayBytesGlobal.toLong(),
        "replayRetentionMilliseconds" to replayRetentionMilliseconds,
        "rawReferenceStoreBytes" to maxRawReferenceStoreBytes.toLong(),
        "rawReferenceRetentionMilliseconds" to rawReferenceRetentionMilliseconds,
        "promptBlobBytesPerDevice" to maxPromptBlobBytesPerDevice.toLong(),
        "promptBlobBytesGlobal" to maxPromptBlobBytesGlobal.toLong(),
        "promptBlobConcurrentUploads" to maxPromptBlobConcurrentUploads.toLong(),
        "promptBlobOrphanMilliseconds" to promptBlobOrphanMilliseconds,
        "promptBlobDormantMilliseconds" to promptBlobDormantMilliseconds,
        "promptBlobTerminalMilliseconds" to promptBlobTerminalMilliseconds,
        "journalDormantMilliseconds" to journalDormantMilliseconds,
        "journalFullRetentionMilliseconds" to journalFullRetentionMilliseconds,
        "journalTombstoneRetentionMilliseconds" to journalTombstoneRetentionMilliseconds,
        "journalTombstoneRows" to maxJournalTombstoneRows.toLong(),
        "androidCacheMessages" to maxAndroidCacheMessages.toLong(),
        "androidCacheBytes" to maxAndroidCacheBytes.toLong(),
        "backgroundLeaseMilliseconds" to backgroundLeaseMilliseconds,
        "routeJsonBytes" to maxRouteJsonBytes.toLong(),
        "routeNonceBytes" to routeNonceBytes.toLong(),
        "routeChallengeMilliseconds" to routeChallengeMilliseconds,
        "routeReplayMilliseconds" to routeReplayMilliseconds,
        "routeHeartbeatMilliseconds" to routeHeartbeatMilliseconds,
        "routeHeartbeatTimeoutMilliseconds" to routeHeartbeatTimeoutMilliseconds,
        "routeRendezvousMilliseconds" to routeRendezvousMilliseconds,
        "routeKeyOverlapMilliseconds" to routeKeyOverlapMilliseconds,
        "pairingInvitationBytes" to maxPairingInvitationBytes.toLong(),
        "pairingInvitationMilliseconds" to pairingInvitationMilliseconds,
        "approvalQueueEntries" to maxApprovalQueueEntries.toLong(),
        "approvalPromotionMilliseconds" to approvalPromotionMilliseconds,
        "approvalDecisionMilliseconds" to approvalDecisionMilliseconds,
        "approvalHookMilliseconds" to approvalHookMilliseconds,
        "voiceBodyBytes" to maxVoiceBodyBytes.toLong(),
        "voiceTextChars" to maxVoiceTextChars.toLong(),
        "maxAgents" to maxAgents.toLong(),
    )
    val magic = byteArrayOf(0x50, 0x49, 0x4d, 0x42)
}

enum class FrameKind(val code: Int) {
    Json(0x01),
    BlobChunk(0x02),
    AudioPcm(0x03),
    TerminalBytes(0x04),
    ;

    companion object {
        fun fromCode(code: Int): FrameKind = entries.firstOrNull { it.code == code }
            ?: protocolViolation("PIMB frame kind is invalid")
    }
}
