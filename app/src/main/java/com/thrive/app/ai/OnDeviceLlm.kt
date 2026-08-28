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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
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
    private val verifiedFile: File get() = File(modelDir, "model.task.sha256")

    private val _state = MutableStateFlow<State>(initialState())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var llm: LlmInference? = null
    private var downloadJob: Job? = null
    private val downloadMutex = Mutex()

    val isReady: Boolean get() = _state.value is State.Ready

    val modelSizeBytes: Long get() = modelFile.length()

    /** Model file URL — public, ungated, no auth. */
    companion object {
        // Pin the immutable repository commit as well as the content hash. A
        // mutable `main` URL could otherwise change without an app review.
        const val MODEL_URL =
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/6c237a59eedeb06a821b21f0a59b03d346ac8bc3/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
        const val EXPECTED_BYTES = 546_660_344L
        const val EXPECTED_SHA256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2"
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
        // A marker is only evidence from the previous process. Re-hash and
        // reload in ensureDownloaded() before this process claims Ready.
        return if (isVerifiedModel()) State.Downloading(0.99f, modelFile.length())
        else State.NotDownloaded
    }

    private fun isVerifiedModel(): Boolean =
        modelFile.isFile &&
            modelFile.length() == EXPECTED_BYTES &&
            validateModelFile(modelFile) == null &&
            verifiedFile.readTextOrNull()?.trim()?.equals(EXPECTED_SHA256, ignoreCase = true) == true

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    /** Starts (or resumes) the model download. No-op when already downloading/ready. */
    fun startDownload() {
        if (_state.value is State.Downloading || _state.value is State.Ready) return
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            runCatching { ensureDownloaded() }
                .onFailure { e ->
                    _state.value = State.Failed(
                        "Download failed: ${e.message ?: e.javaClass.simpleName}. Check your connection and try again."
                    )
                }
        }
    }

    /** Durable workers call this same guarded path after process restarts. */
    suspend fun ensureDownloaded(): Boolean = downloadMutex.withLock {
        if (isVerifiedModel()) {
            _state.value = State.Downloading(0.99f, modelFile.length())
            val digestMatches = withContext(Dispatchers.IO) {
                sha256(modelFile).equals(EXPECTED_SHA256, ignoreCase = true)
            }
            if (!digestMatches) {
                release()
                modelFile.delete()
                verifiedFile.delete()
            } else {
                val loaded = withContext(Dispatchers.Default) { load() }
                if (loaded != null) {
                    _state.value = State.Ready
                    return@withLock true
                }
                verifiedFile.delete()
                _state.value = State.Failed(
                    "Offline AI is verified but could not be loaded on this device. " +
                        "Thrive will keep using its built-in recipe engine."
                )
                return@withLock false
            }
        }
        download()
        _state.value is State.Ready
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
        verifiedFile.delete()
        _state.value = State.NotDownloaded
    }

    private suspend fun download() {
        modelDir.mkdirs()
        verifiedFile.delete()
        // A previous run may have finished the verified download but been
        // killed before the model was loaded and the readiness marker was
        // committed. Re-verify and activate that file without downloading
        // another 546 MB.
        if (
            modelFile.isFile &&
            modelFile.length() == EXPECTED_BYTES &&
            validateModelFile(modelFile) == null &&
            sha256(modelFile).equals(EXPECTED_SHA256, ignoreCase = true)
        ) {
            _state.value = State.Downloading(0.99f, modelFile.length())
            val loaded = withContext(Dispatchers.Default) { load() }
            if (loaded != null) {
                verifiedFile.writeText(EXPECTED_SHA256)
                _state.value = State.Ready
            } else {
                _state.value = State.Failed(
                    "Offline AI is verified but could not be loaded on this device (it may need more memory). " +
                        "The app keeps working with the built-in recipe engine."
                )
            }
            return
        }
        if (modelDir.usableSpace < EXPECTED_BYTES + 256L * 1024 * 1024) {
            _state.value = State.Failed("Not enough free storage to prepare offline AI (about 800 MB is needed).")
            return
        }

        // One clean retry handles stale or unsupported Range responses without
        // looping forever. A 206 is appended only when Content-Range proves it
        // starts at the exact byte we already have; a 200 always restarts.
        var attempt = 0
        while (attempt < 2) {
            var existing = partialFile.length()
            if (existing > EXPECTED_BYTES) { partialFile.delete(); existing = 0L }
            val requestBuilder = Request.Builder().url(MODEL_URL)
            if (existing > 0) requestBuilder.header("Range", "bytes=$existing-")
            val completed = client.newCall(requestBuilder.build()).execute().use { res ->
                if (res.code == 416) {
                    if (existing == EXPECTED_BYTES && sha256(partialFile) == EXPECTED_SHA256) return@use true
                    partialFile.delete()
                    return@use false
                }
                if (!res.isSuccessful) {
                    _state.value = State.Failed("Download failed (HTTP ${res.code})")
                    return
                }
                val body = res.body ?: run { _state.value = State.Failed("Empty download response"); return }
                val append = res.code == 206 && existing > 0 &&
                    res.header("Content-Range")?.startsWith("bytes $existing-") == true
                if (!append) {
                    partialFile.delete()
                    existing = 0L
                }
                val responseBytes = body.contentLength()
                if (responseBytes > MAX_BYTES || existing + responseBytes > MAX_BYTES) {
                    _state.value = State.Failed("Downloaded file is too large — refused")
                    return
                }
                var written = existing
                FileOutputStream(partialFile, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            written += n
                            if (written > EXPECTED_BYTES) {
                                partialFile.delete()
                                _state.value = State.Failed("Offline AI download exceeded its verified size.")
                                return
                            }
                            _state.value = State.Downloading((written.toFloat() / EXPECTED_BYTES).coerceIn(0f, 1f), written)
                        }
                    }
                }
                written == EXPECTED_BYTES
            }
            if (completed) break
            partialFile.delete()
            attempt++
        }
        if (partialFile.length() != EXPECTED_BYTES) {
            _state.value = State.Failed("Offline AI download was incomplete and will retry automatically.")
            return
        }
        _state.value = State.Downloading(0.98f, partialFile.length())
        val digest = sha256(partialFile)
        if (!digest.equals(EXPECTED_SHA256, ignoreCase = true)) {
            partialFile.delete()
            _state.value = State.Failed("Offline AI verification failed. The file was removed for safety.")
            return
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
            verifiedFile.delete()
            _state.value = State.Failed(problem)
            return
        }
        _state.value = State.Downloading(0.99f, modelFile.length())
        // Verify the model actually loads before claiming ready — an honest
        // check, and it warms the first generation. Persist the readiness
        // marker only after this device successfully opens the model.
        val loaded = withContext(Dispatchers.Default) { load() }
        if (loaded != null) {
            verifiedFile.writeText(EXPECTED_SHA256)
            _state.value = State.Ready
        } else {
            verifiedFile.delete()
            _state.value = State.Failed("Model downloaded but could not be loaded on this device (it may need more memory). The app keeps working with the built-in recipe engine.")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
            val engine = llm ?: load()
            if (engine == null) {
                _state.value = State.Failed(
                    "Offline AI could not start on this device. Thrive is using its built-in recipe engine."
                )
                return@withContext null
            }
            runCatching {
                val text = engine.generateResponse(prompt)
                text.trim().takeIf { it.isNotEmpty() }
            }.onFailure {
                _state.value = State.Failed(
                    "Offline AI stopped unexpectedly. Thrive is using its built-in recipe engine."
                )
            }.getOrNull()
        }
    }

    private fun release() {
        runCatching { llm?.close() }
        llm = null
    }
}
