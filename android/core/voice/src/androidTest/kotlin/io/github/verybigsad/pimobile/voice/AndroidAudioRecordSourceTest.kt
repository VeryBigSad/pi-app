package io.github.verybigsad.pimobile.voice

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidAudioRecordSourceTest {
    @Test
    fun permissionAndAudioRecordWorkWithPcm16MonoAtSixteenKilohertz() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)

        assertThat(AndroidMicrophonePermissionSource(context).current()).isEqualTo(MicrophonePermissionState.GRANTED)
        val source = AndroidAudioRecordSourceFactory(context).create()
        try {
            source.start()
            source.stop()
        } finally {
            source.release()
        }
    }
}
