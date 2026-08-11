package io.github.verybigsad.pimobile.security

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PasskeyProviderApi29Test {
    @Test
    @Suppress("DEPRECATION")
    fun api29PlayServicesAvailabilityFailsClosedAtCredentialManagerMinimum() {
        assumeTrue(Build.VERSION.SDK_INT == 29)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo("com.google.android.gms", 0)
        val expected = if (packageInfo.longVersionCode >= PasskeyProviderProbe.MinimumPlayServicesVersion) {
            PasskeyAvailability.Available(PasskeyProviderKind.PLAY_SERVICES, 1, false)
        } else {
            PasskeyAvailability.Locked(PasskeyLockReason.PLAY_SERVICES_PROVIDER_REQUIRED)
        }
        assertThat(PasskeyProviderProbe.availability(context)).isEqualTo(expected)
    }
}
