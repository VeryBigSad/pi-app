package io.github.verybigsad.pimobile.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PairedProfileStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = PairedProfileStore(context)

    @After
    fun clean() {
        store.delete()
    }

    private fun profile(endpointId: String? = "endpoint-1") = PairedProfile(
        deviceId = "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
        macId = "3fa85f64-5717-4562-b3fc-2c963f66afa6",
        macDisplayName = "Test Mac",
        relayWssUrl = "wss://relay.example.com",
        routeId = "route-1",
        deviceRouteKeyId = "device-route-1",
        directCandidates = listOf(DirectCandidate("192.168.1.10", 8443), DirectCandidate("fe80::1", 8443)),
        caCertificatePem = "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n",
        certificateSerial = "aa".repeat(32),
        certificateNotAfterEpochMillis = 10_000_000L,
        endpointId = endpointId,
    )

    @Test
    fun saveLoadRoundtripPreservesEveryField() {
        val original = profile()
        store.save(original)
        assertThat(store.load()).isEqualTo(original)
    }

    @Test
    fun nullableEndpointIdRoundtrips() {
        val original = profile(endpointId = null)
        store.save(original)
        assertThat(store.load()).isEqualTo(original)
    }

    @Test
    fun loadWithoutSaveReturnsNull() {
        store.delete()
        assertThat(store.load()).isNull()
    }

    @Test
    fun deleteRemovesProfile() {
        store.save(profile())
        store.delete()
        assertThat(store.load()).isNull()
    }

    @Test
    fun corruptedEnvelopeReturnsNullAndSelfHeals() {
        store.save(profile())
        File(context.noBackupFilesDir, "paired-profile.bin").writeBytes(byteArrayOf(1, 2, 3))
        assertThat(store.load()).isNull()
        assertThat(File(context.noBackupFilesDir, "paired-profile.bin").exists()).isFalse()
    }

    @Test
    fun overwriteReplacesPreviousProfile() {
        store.save(profile())
        val updated = profile().copy(macDisplayName = "Other Mac", endpointId = null)
        store.save(updated)
        assertThat(store.load()).isEqualTo(updated)
    }
}
