package com.thrive.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thrive.app.ai.AiRecipeMaker
import com.thrive.app.ai.AiService
import com.thrive.app.ai.OnDeviceLlm
import com.thrive.app.data.local.SettingsStore
import com.thrive.app.data.model.PantryItem
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Guards the on-device LLM: the model-file validator rejects missing, tiny,
 * oversized, and HTML-error-page files; the AI maker falls back cleanly to
 * null when neither cloud AI nor the on-device model is available, so the
 * deterministic engine always takes over.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class OnDeviceLlmTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `missing model file is rejected`() {
        val f = File(ctx().cacheDir, "missing-model-${System.nanoTime()}.task")
        assertNotNull(OnDeviceLlm.validateModelFile(f))
        assertTrue(OnDeviceLlm.validateModelFile(f)!!.contains("missing"))
    }

    @Test
    fun `tiny file is rejected as incomplete`() {
        val f = File(ctx().cacheDir, "tiny-${System.nanoTime()}.task")
        f.writeBytes(ByteArray(4096) { 0x41 })
        try {
            val err = OnDeviceLlm.validateModelFile(f)
            assertNotNull(err)
            assertTrue(err!!.contains("incomplete"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `oversized file is rejected`() {
        val f = File(ctx().cacheDir, "huge-${System.nanoTime()}.task")
        f.writeBytes(ByteArray(4096) { 0x41 })
        java.io.RandomAccessFile(f, "rw").use { raf -> raf.setLength(OnDeviceLlm.MAX_BYTES + 1) }
        try {
            val err = OnDeviceLlm.validateModelFile(f)
            assertNotNull(err)
            assertTrue(err!!.contains("large"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `html error page is rejected even at model size`() {
        val f = File(ctx().cacheDir, "html-${System.nanoTime()}.task")
        java.io.FileOutputStream(f).use { out ->
            out.write("<!DOCTYPE html><html><body>Access Denied</body></html>".toByteArray())
            val zeros = ByteArray(64 * 1024)
            var written = 118L
            while (written < OnDeviceLlm.MIN_VALID_BYTES) {
                out.write(zeros)
                written += zeros.size
            }
        }
        try {
            val err = OnDeviceLlm.validateModelFile(f)
            assertNotNull(err)
            assertTrue(err!!.contains("not a model"))
        } finally {
            f.delete()
        }
    }

    @Test
    fun `model url is https and public host`() {
        assertTrue(OnDeviceLlm.MODEL_URL.startsWith("https://huggingface.co/"))
        assertTrue(OnDeviceLlm.MODEL_URL.contains(".task"))
    }

    @Test
    fun `recipe maker falls back to null when no ai and no model`() {
        val ctx = ctx()
        val settings = SettingsStore(
            ctx.getSharedPreferences("llm_fallback_${System.nanoTime()}", Context.MODE_PRIVATE)
        )
        val maker = AiRecipeMaker(AiService(settings), OnDeviceLlm(ctx))
        val pantry = listOf(
            PantryItem(id = "1", name = "Chicken breast", category = "Meat", location = "Fridge", quantity = 2),
            PantryItem(id = "2", name = "Rice", category = "Pantry", location = "Pantry", quantity = 1),
        )
        // No cloud key + no downloaded model => no AI recipe; the caller then
        // falls back to RecipeMakerEngine. The important contract: no crash
        // and a clean null even with a valid pantry.
        runBlocking { assertNull(maker.generate(pantry, variant = 0)) }
    }
}
