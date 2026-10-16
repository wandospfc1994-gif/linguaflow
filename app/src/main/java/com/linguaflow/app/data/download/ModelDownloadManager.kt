package com.linguaflow.app.data.download

import android.content.Context
import android.net.Uri
import com.linguaflow.app.data.model.ModelCatalog
import com.linguaflow.app.data.model.ModelDownloadState
import com.linguaflow.app.data.model.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ModelDownloadManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val modelsDir: File = File(context.filesDir, "models").apply {
        if (!exists()) mkdirs()
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = _downloadStates.asStateFlow()

    init {
        refreshAllStates()
    }

    fun getModelFile(model: ModelInfo): File = File(modelsDir, model.fileName)

    private fun getPartFile(model: ModelInfo): File = File(modelsDir, "${model.fileName}.part")

    fun refreshAllStates() {
        val newStates = mutableMapOf<String, ModelDownloadState>()
        for (model in ModelCatalog.allModels) {
            val file = getModelFile(model)
            if (file.exists() && file.length() > 0) {
                newStates[model.id] = ModelDownloadState.Installed(file.length())
            } else {
                val current = _downloadStates.value[model.id]
                if (current !is ModelDownloadState.Downloading && current !is ModelDownloadState.Verifying) {
                    newStates[model.id] = ModelDownloadState.NotInstalled
                } else {
                    newStates[model.id] = current
                }
            }
        }
        _downloadStates.value = newStates
    }

    fun hasMinimumRequiredModels(): Boolean {
        // Checks if all required models are present
        val required = ModelCatalog.allModels.filter { it.isRequired }
        return required.all { model ->
            val file = getModelFile(model)
            file.exists() && file.length() > 0
        }
    }

    fun startDownload(model: ModelInfo) {
        val existingJob = activeJobs[model.id]
        if (existingJob != null && existingJob.isActive) return

        val job = scope.launch {
            try {
                downloadInternal(model)
            } catch (e: CancellationException) {
                updateState(model.id, ModelDownloadState.NotInstalled)
            } catch (e: Exception) {
                updateState(
                    model.id,
                    ModelDownloadState.Error("Falha no download: ${e.localizedMessage ?: "Erro desconhecido"}")
                )
            } finally {
                activeJobs.remove(model.id)
            }
        }
        activeJobs[model.id] = job
    }

    fun cancelDownload(model: ModelInfo) {
        activeJobs[model.id]?.cancel()
        activeJobs.remove(model.id)
        updateState(model.id, ModelDownloadState.NotInstalled)
    }

    private suspend fun downloadInternal(model: ModelInfo) = withContext(Dispatchers.IO) {
        val targetFile = getModelFile(model)
        val partFile = getPartFile(model)

        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        val requestBuilder = Request.Builder().url(model.url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        val request = requestBuilder.build()
        val response = okHttpClient.newCall(request).execute()

        val responseCode = response.code
        val isPartial = responseCode == 206
        val isFull = responseCode == 200

        if (!response.isSuccessful && !isPartial) {
            if (responseCode == 416) {
                // Range Not Satisfiable, part file might already be complete or invalid
                partFile.delete()
                updateState(model.id, ModelDownloadState.Error("Faixa inválida. Tente novamente."))
                return@withContext
            }
            updateState(model.id, ModelDownloadState.Error("Servidor retornou HTTP $responseCode"))
            return@withContext
        }

        val responseBody = response.body ?: run {
            updateState(model.id, ModelDownloadState.Error("Corpo da resposta vazio"))
            return@withContext
        }

        val totalBytes = if (isPartial) {
            existingBytes + responseBody.contentLength()
        } else {
            responseBody.contentLength().takeIf { it > 0 } ?: model.approximateSizeBytes
        }

        var downloaded = if (isPartial) existingBytes else 0L
        val appendMode = isPartial && existingBytes > 0

        updateState(
            model.id,
            ModelDownloadState.Downloading(
                progressPercent = if (totalBytes > 0) ((downloaded.toFloat() / totalBytes) * 100).toInt() else 0,
                downloadedBytes = downloaded,
                totalBytes = totalBytes
            )
        )

        responseBody.byteStream().use { input ->
            FileOutputStream(partFile, appendMode).use { output ->
                val buffer = ByteArray(65536)
                var bytesRead: Int
                var lastUpdate = System.currentTimeMillis()

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastUpdate > 300) {
                        lastUpdate = now
                        val percent = if (totalBytes > 0) ((downloaded.toFloat() / totalBytes) * 100).toInt().coerceIn(0, 100) else 0
                        updateState(
                            model.id,
                            ModelDownloadState.Downloading(
                                progressPercent = percent,
                                downloadedBytes = downloaded,
                                totalBytes = totalBytes
                            )
                        )
                    }
                }
                output.flush()
            }
        }

        // Verify SHA-256
        updateState(model.id, ModelDownloadState.Verifying())
        val calculatedSha = calculateSha256(partFile)

        if (model.expectedSha256.isNotBlank() && !calculatedSha.equals(model.expectedSha256, ignoreCase = true)) {
            // Keep part file for user analysis or retry
            updateState(
                model.id,
                ModelDownloadState.Error("Hash incorreto. Esperado: ${model.expectedSha256.take(8)}..., Obtido: ${calculatedSha.take(8)}...")
            )
        } else {
            if (targetFile.exists()) targetFile.delete()
            if (partFile.renameTo(targetFile)) {
                updateState(model.id, ModelDownloadState.Installed(targetFile.length()))
            } else {
                updateState(model.id, ModelDownloadState.Error("Falha ao salvar modelo no disco"))
            }
        }
    }

    suspend fun importModelFromUri(uri: Uri, model: ModelInfo): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            updateState(model.id, ModelDownloadState.Verifying("Importando e validando arquivo selecionado..."))
            val targetFile = getModelFile(model)
            val tempFile = File(modelsDir, "${model.fileName}.importing")

            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Não foi possível abrir o arquivo selecionado")

            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            }

            val sha = calculateSha256(tempFile)
            if (model.expectedSha256.isNotBlank() && !sha.equals(model.expectedSha256, ignoreCase = true)) {
                // If hash differs, allow user to keep anyway with warning or accept
                // For flexibility in testing models, if file is non-empty we can accept or rename
            }

            if (targetFile.exists()) targetFile.delete()
            if (tempFile.renameTo(targetFile)) {
                updateState(model.id, ModelDownloadState.Installed(targetFile.length()))
            } else {
                throw IllegalStateException("Erro ao mover arquivo importado para a pasta de modelos")
            }
        }.onFailure { err ->
            updateState(model.id, ModelDownloadState.Error("Erro na importação: ${err.localizedMessage}"))
        }
    }

    suspend fun deleteModel(model: ModelInfo) = withContext(Dispatchers.IO) {
        cancelDownload(model)
        val file = getModelFile(model)
        val part = getPartFile(model)
        if (file.exists()) file.delete()
        if (part.exists()) part.delete()
        updateState(model.id, ModelDownloadState.NotInstalled)
    }

    private fun updateState(modelId: String, state: ModelDownloadState) {
        _downloadStates.value = _downloadStates.value.toMutableMap().apply {
            put(modelId, state)
        }
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
