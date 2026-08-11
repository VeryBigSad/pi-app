package io.github.verybigsad.pimobile.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableEndpointQueueTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val queue = DurableEndpointQueue(context)

    @After
    fun clean() {
        File(context.noBackupFilesDir, "push-endpoint-ops.json").delete()
        File(context.noBackupFilesDir, "push-endpoint-ops.json.bak").delete()
    }

    private fun operation(endpointId: String, revoke: Boolean = false, wakePublicKey: String? = "wake-key") =
        DurableEndpointQueue.Operation(
            endpointId = endpointId,
            distributor = "distributor",
            endpoint = if (revoke) "" else "https://push.example.com/$endpointId",
            wakePublicKey = wakePublicKey,
            revoke = revoke,
        )

    @Test
    fun enqueuePersistsAcrossQueueInstances() = runBlocking {
        queue.enqueue(operation("ep-1"))
        queue.enqueue(operation("ep-2", wakePublicKey = null))
        val reloaded = DurableEndpointQueue(context)
        val all = reloaded.all()
        assertThat(all.map { it.endpointId }).containsExactly("ep-1", "ep-2").inOrder()
        assertThat(all[1].wakePublicKey).isNull()
    }

    @Test
    fun enqueueDeduplicatesByEndpointId() = runBlocking {
        queue.enqueue(operation("ep-1"))
        queue.enqueue(operation("ep-1", revoke = true, wakePublicKey = null))
        val all = queue.all()
        assertThat(all).hasSize(1)
        assertThat(all[0].revoke).isTrue()
        assertThat(all[0].wakePublicKey).isNull()
    }

    @Test
    fun removeDeletesOnlyMatchingOperation() = runBlocking {
        queue.enqueue(operation("ep-1"))
        queue.enqueue(operation("ep-2"))
        queue.remove("ep-1")
        assertThat(queue.all().map { it.endpointId }).containsExactly("ep-2")
        queue.remove("ep-2")
        assertThat(queue.all()).isEmpty()
    }

    @Test
    fun corruptedFileReadsAsEmpty() = runBlocking {
        queue.enqueue(operation("ep-1"))
        File(context.noBackupFilesDir, "push-endpoint-ops.json").writeBytes(byteArrayOf(0xFF.toByte(), 0x00))
        assertThat(DurableEndpointQueue(context).all()).isEmpty()
    }
}
