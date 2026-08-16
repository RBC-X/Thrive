package com.thrive.app.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * On-device LLM for Thrive (MediaPipe LLM Inference API).
 *
 * Runs a small instruct model (Qwen2.5-0.5B-Instruct, ~546 MB, public
 * HuggingFace link — no API key, no account) entirely on the phone. Pantry
 * recipe generation uses it to genuinely compose a dish from the user's
 * actual items; every other engine still works when the model is absent.
 *
 * The model is downloaded at runtime (it is far too large to bundle in an
 * APK), resumed across interruptions, validated, and only then loaded.
 * Downloading/loading/inference all run off the main thread.
 */
class OnDeviceLlm(private val context: Context) {

    sealed interface State {
        /** No model file present yet. */
        data object NotDownloaded : State
        data class Downloading(val progress: Float, val bytes: Long) : State
        /** Model downloaded, validated, and loaded — inference available. */
        data object Ready : State
        data class Failed(val reason: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    // Declared BEFORE _state: initialState() reads these paths, and Kotlin
    // initializes properties in declaration order — a later declaration would
    // still be null here, silently producing a RELATIVE path (File(null, x))
    // and making the installed model look missing.
    private val modelDir: File = File(context.filesDir, "thrive-llm")
    private val modelFile: File get() = File(modelDir, "model.task")
    private val partialFile: File get() = File(modelDir, "model.task.partial")

    private val _state = MutableStateFlow<State>(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var llm: LlmInference? = null
    private var downloadJob: Job? = null

    val isReady: Boolean get() = _state.value is State.Ready

    val modelSizeBytes: Long get() = modelFile.length()

    /** Model file URL — public, ungated, no auth. */
    companion object {
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        /** Honest guard against a truncated/HTML "model": anything smaller than
         *  this cannot be a real quantized 0.5B task bundle. */
        const val MIN_VALID_BYTES = 100L * 1024 * 1024
        /** Hard cap so a hijacked URL can never fill the device. */
        const val MAX_BYTES = 800L * 1024 * 1024
        const val MAX_TOKENS = 1024

        /** Pure validation: returns null when the file looks like a real model. */
        fun validateModelFile(file: File): String? {
            if (!file.isFile) return "Model file is missing"
            if (file.length() < MIN_VALID_BYTES) {
                return "Model file is incomplete (${file.length()} bytes) — try downloading again"
            }
            if (file.length() > MAX_BYTES) return "Model file is unexpectedly large"
            // Guard against a redirect landing on an HTML error page. Read only
            // the first bytes — never readBytes() the whole (546 MB) model.
            val head = file.inputStream().use { input ->
                val buf = ByteArray(256)
                val n = input.read(buf)
                String(buf, 0, n.coerceAtLeast(0), Charsets.UTF_8)
            }
            if (head.contains("<html", ignoreCase = true) || head.startsWith("<!DOCTYPE")) {
                return "Downloaded file is not a model (looks like a web page) — try again"
            }
            return null
        }
    }

    private fun initialState(): State {
        return if (modelFile.isFile && validateModelFile(modelFile) == null) State.Ready
        else State.NotDownloaded
    }

    /** Starts (or resumes) the model download. No-op when already downloading/ready. */
    fun startDownload() {
        if (_state.value is State.Downloading || _state.value is State.Ready) return
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            runCatching { download() }
                .onFailure { e ->
                    _state.value = State.Failed(
                        "Download failed: ${e.message ?: e.javaClass.simpleName}. Check your connection and try again."
                    )
                }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        if (_state.value is State.Downloading) _state.value = State.NotDownloaded
    }

    /** Removes the model and frees the storage + loaded memory. */
    fun deleteModel() {
        downloadJob?.cancel()
        downloadJob = null
        release()
        modelFile.delete()
        partialFile.delete()
        _state.value = State.NotDownloaded
    }

    private suspend fun download() {
        modelDir.mkdirs()
        val existing = partialFile.length()
        val requestBuilder = Request.Builder().url(MODEL_URL)
        if (existing > 0) {
            // Resume where the last attempt stopped. A 416 (range not
            // satisfiable) means the partial is stale — start over.
            requestBuilder.header("Range", "bytes=$existing-")
        }
        client.newCall(requestBuilder.build()).execute().use { res ->
            when {
                res.code == 416 -> {
                    partialFile.delete()
                    _state.value = State.Failed("Download could not be resumed — tap download to start over")
                    return
                }
                !res.isSuccessful -> {
                    _state.value = State.Failed("Download failed (HTTP ${res.code})")
                    return
                }
            }
            val body = res.body ?: run {
                _state.value = State.Failed("Empty download response")
                return
            }
            val total = body.contentLength()
            if (total > MAX_BYTES || (existing + total) > MAX_BYTES) {
                _state.value = State.Failed("Downloaded file is too large — refused")
                return
            }
            var written = existing
            partialFile.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (written > MAX_BYTES) {
                            _state.value = State.Failed("Download exceeded the size cap — refused")
                            return
                        }
                        val progress = if (total > 0) (written.toFloat() / (existing + total))
                            .coerceIn(0f, 1f) else 0f
                        _state.value = State.Downloading(progress, written)
                    }
                }
            }
        }
        // Atomic promotion: never expose a half-written model file.
        if (!partialFile.renameTo(modelFile)) {
            // Windows-style locking race — copy instead of rename.
            partialFile.copyTo(modelFile, overwrite = true)
            partialFile.delete()
        }
        val problem = validateModelFile(modelFile)
        if (problem != null) {
            modelFile.delete()
            _state.value = State.Failed(problem)
            return
        }
        _state.value = State.Downloading(0.99f, modelFile.length())
        // Verify the model actually loads before claiming ready — an honest
        // check, and it warms the first generation.
        val loaded = withContext(Dispatchers.Default) { load() }
        _state.value = if (loaded != null) State.Ready
        else State.Failed("Model downloaded but could not be loaded on this device (it may need more memory). The app keeps working with the built-in recipe engine.")
    }

    /** Loads the model once (heavy — off the main thread). Returns the engine or null. */
    private fun load(): LlmInference? {
        if (llm != null) return llm
        if (!modelFile.isFile) return null
        return runCatching {
            // Qwen2.5-0.5B-Instruct is a CPU model (multi-prefill-seq task
            // bundle); the CPU backend is the safe, compatible choice.
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                .setMaxTokens(MAX_TOKENS)
                .setMaxTopK(40)
                .setPreferredBackend(LlmInference.Backend.CPU)
                .build()
            LlmInference.createFromOptions(context, options)
        }.onSuccess { llm = it }.getOrNull()
    }

    /**
     * Runs one inference pass. Returns the model's text or null when the model
     * is unavailable or generation fails (callers fall back to the built-in
     * deterministic engine, so the feature always works).
     */
    suspend fun generate(prompt: String): String? {
        if (!modelFile.isFile) return null
        return withContext(Dispatchers.Default) {
            val engine = llm ?: load() ?: return@withContext null
            runCatching {
                val text = engine.generateResponse(prompt)
                text.trim().takeIf { it.isNotEmpty() }
            }.getOrNull()
        }
    }

    private fun release() {
        runCatching { llm?.close() }
        llm = null
    }
}
