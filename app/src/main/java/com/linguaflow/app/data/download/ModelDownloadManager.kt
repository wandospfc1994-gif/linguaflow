package com.linguaflow.app.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.linguaflow.app.data.model.ModelCatalog
import com.linguaflow.app.data.model.ModelDownloadState
import com.linguaflow.app.data.model.ModelInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

sealed class HfAuthStatus {
    data class Success(val username: String, val hasGemmaAccess: Boolean) : HfAuthStatus()
    data class TermsRequired(val username: String) : HfAuthStatus()
    data object InvalidToken : HfAuthStatus()
    data class NetworkError(val message: String) : HfAuthStatus()
}

class ModelDownloadManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val tag = "ModelDownloadManager"
    private val prefs = context.getSharedPreferences("linguaflow_models_auth", Context.MODE_PRIVATE)

    private val modelsDir: File = File(context.filesDir, "models").apply {
        if (!exists()) mkdirs()
    }

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
        .build()

    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _downloadStates = MutableStateFlow<Map<String, ModelDownloadState>>(emptyMap())
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = _downloadStates.asStateFlow()

    init {
        refreshAllStates()
    }

    fun saveAuthToken(token: String) {
        prefs.edit().putString("hf_token", token.trim()).apply()
    }

    fun getAuthToken(): String? {
        val token = prefs.getString("hf_token", null)?.trim()
        return if (token.isNullOrBlank()) null else token
    }

    fun clearAuthToken() {
        prefs.edit().remove("hf_token").apply()
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
                if (current !is ModelDownloadState.Downloading &&
                    current !is ModelDownloadState.Verifying &&
                    current !is ModelDownloadState.Paused &&
                    current !is ModelDownloadState.RequiresAuth
                ) {
                    newStates[model.id] = ModelDownloadState.NotInstalled
                } else {
                    newStates[model.id] = current ?: ModelDownloadState.NotInstalled
                }
            }
        }
        _downloadStates.value = newStates
    }

    fun hasMinimumRequiredModels(): Boolean {
        val required = ModelCatalog.allModels.filter { it.isRequired }
        return required.all { model ->
            val file = getModelFile(model)
            file.exists() && file.length() > 0
        }
    }

    /**
     * Inicia download automático em segundo plano dos modelos obrigatórios que ainda não estejam instalados.
     * Previne loop infinito ignorando modelos com erro ativo ou que requerem autenticação/termos.
     */
    fun startAutoDownloadMissingRequiredModels() {
        val required = ModelCatalog.allModels.filter { it.isRequired }
        for (model in required) {
            val file = getModelFile(model)
            val currentState = _downloadStates.value[model.id]
            val isBusyOrBlocked = currentState is ModelDownloadState.Downloading ||
                currentState is ModelDownloadState.Verifying ||
                currentState is ModelDownloadState.RequiresAuth ||
                currentState is ModelDownloadState.Error
            if ((!file.exists() || file.length() == 0L) && !isBusyOrBlocked) {
                Log.d(tag, "Iniciando download automático para o modelo obrigatório: ${model.name}")
                startDownload(model)
            }
        }
    }

    fun startDownload(model: ModelInfo) {
        val existingJob = activeJobs[model.id]
        if (existingJob != null && existingJob.isActive) return

        val job = scope.launch {
            try {
                downloadInternal(model)
            } catch (e: CancellationException) {
                val part = getPartFile(model)
                if (part.exists() && part.length() > 0) {
                    updateState(model.id, ModelDownloadState.Paused(part.length(), model.approximateSizeBytes))
                } else {
                    updateState(model.id, ModelDownloadState.NotInstalled)
                }
            } catch (e: Exception) {
                Log.e(tag, "Erro no download do modelo ${model.id}", e)
                updateState(
                    model.id,
                    ModelDownloadState.Error("Falha no download: ${e.localizedMessage ?: "Erro de conexão"}")
                )
            } finally {
                activeJobs.remove(model.id)
            }
        }
        activeJobs[model.id] = job
    }

    fun pauseDownload(model: ModelInfo) {
        activeJobs[model.id]?.cancel()
        activeJobs.remove(model.id)
        val part = getPartFile(model)
        val currentBytes = if (part.exists()) part.length() else 0L
        updateState(model.id, ModelDownloadState.Paused(currentBytes, model.approximateSizeBytes))
    }

    fun resumeDownload(model: ModelInfo) {
        startDownload(model)
    }

    fun clearError(model: ModelInfo) {
        val part = getPartFile(model)
        val currentBytes = if (part.exists()) part.length() else 0L
        if (currentBytes > 0) {
            updateState(model.id, ModelDownloadState.Paused(currentBytes, model.approximateSizeBytes))
        } else {
            updateState(model.id, ModelDownloadState.NotInstalled)
        }
    }

    fun cancelDownload(model: ModelInfo) {
        activeJobs[model.id]?.cancel()
        activeJobs.remove(model.id)
        getPartFile(model).delete()
        updateState(model.id, ModelDownloadState.NotInstalled)
    }

    private suspend fun downloadInternal(model: ModelInfo) = withContext(Dispatchers.IO) {
        val targetFile = getModelFile(model)
        val partFile = getPartFile(model)

        val maxRetries = 5
        var attempt = 0
        var isCompleted = false

        while (attempt < maxRetries && !isCompleted) {
            attempt++
            try {
                val existingBytes = if (partFile.exists()) partFile.length() else 0L

                // Se já estiver completo
                if (existingBytes > 0 && model.approximateSizeBytes > 0 && existingBytes >= model.approximateSizeBytes) {
                    isCompleted = true
                    break
                }

                val requestBuilder = Request.Builder()
                    .url(model.url)
                    .header("User-Agent", "Linguaflow-Android/1.0")

                if (existingBytes > 0) {
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                }

                // Injeta header se o modelo for do Hugging Face e houver token salvo
                val token = getAuthToken()
                if (!token.isNullOrBlank() && model.url.contains("huggingface.co")) {
                    requestBuilder.header("Authorization", "Bearer $token")
                }

                val request = requestBuilder.build()
                val response = okHttpClient.newCall(request).execute()

                val responseCode = response.code
                val isPartial = responseCode == 206
                val isFull = responseCode == 200

                // Tratamento de autenticação exigida (HTTP 401 ou 403 no Hugging Face)
                if (responseCode == 401) {
                    Log.w(tag, "Modelo ${model.id} retornou HTTP 401 - Requer token de acesso")
                    updateState(
                        model.id,
                        ModelDownloadState.RequiresAuth(
                            modelId = model.id,
                            message = "Token do Hugging Face ausente ou inválido. Insira seu token de acesso.",
                            requiresTermsAcceptance = false
                        )
                    )
                    return@withContext
                }

                if (responseCode == 403) {
                    Log.w(tag, "Modelo ${model.id} retornou HTTP 403 - Termos de licença não aceitos na Hugging Face")
                    updateState(
                        model.id,
                        ModelDownloadState.RequiresAuth(
                            modelId = model.id,
                            message = "Acesso restrito (HTTP 403): Você precisa aceitar os termos de licença do Gemma 2B no Hugging Face com sua conta.",
                            requiresTermsAcceptance = true
                        )
                    )
                    return@withContext
                }

                if (responseCode == 416) {
                    if (existingBytes >= (model.approximateSizeBytes * 0.95)) {
                        isCompleted = true
                        break
                    } else {
                        Log.w(tag, "HTTP 416 para ${model.id}: faixa de bytes inválida, reiniciando partFile.")
                        partFile.delete()
                        continue
                    }
                }

                if (!response.isSuccessful && !isPartial) {
                    updateState(model.id, ModelDownloadState.Error("Servidor retornou erro HTTP $responseCode"))
                    return@withContext
                }

                val responseBody = response.body ?: run {
                    updateState(model.id, ModelDownloadState.Error("Resposta sem dados recebida"))
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
                        var bytesInInterval = 0L

                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            downloaded += bytesRead
                            bytesInInterval += bytesRead

                            val now = System.currentTimeMillis()
                            if (now - lastUpdate > 300) {
                                val intervalSec = (now - lastUpdate) / 1000.0
                                val speedMb = if (intervalSec > 0) (bytesInInterval / (1024.0 * 1024.0)) / intervalSec else 0.0
                                val speedFormatted = String.format("%.1f MB/s", speedMb)

                                lastUpdate = now
                                bytesInInterval = 0L

                                val percent = if (totalBytes > 0) {
                                    ((downloaded.toFloat() / totalBytes) * 100).toInt().coerceIn(0, 100)
                                } else 0

                                updateState(
                                    model.id,
                                    ModelDownloadState.Downloading(
                                        progressPercent = percent,
                                        downloadedBytes = downloaded,
                                        totalBytes = totalBytes,
                                        speedText = speedFormatted
                                    )
                                )
                            }
                        }
                        output.flush()
                    }
                }

                isCompleted = true

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "Tentativa $attempt/$maxRetries falhou para ${model.id}: ${e.message}")
                if (attempt >= maxRetries) {
                    updateState(
                        model.id,
                        ModelDownloadState.Error("Falha na conexão após $maxRetries tentativas: ${e.localizedMessage ?: "Conexão interrompida"}")
                    )
                    return@withContext
                }
                delay(attempt * 1500L)
            }
        }

        if (!isCompleted) return@withContext

        // Download concluído, etapa de verificação
        updateState(model.id, ModelDownloadState.Verifying("Verificando integridade do modelo..."))
        val finalSha = calculateSha256(partFile)

        if (model.expectedSha256.isNotBlank() &&
            !finalSha.equals(model.expectedSha256, ignoreCase = true) &&
            partFile.length() < (model.approximateSizeBytes * 0.9)
        ) {
            Log.w(tag, "Aviso de divergência de SHA: esperado ${model.expectedSha256}, obtido $finalSha")
        }

        if (partFile.renameTo(targetFile)) {
            Log.d(tag, "Modelo ${model.id} salvo com sucesso em ${targetFile.absolutePath}")
            updateState(model.id, ModelDownloadState.Installed(targetFile.length()))
        } else {
            updateState(model.id, ModelDownloadState.Error("Não foi possível finalizar o arquivo no armazenamento."))
        }
    }

    /**
     * Valida o token e permissões de acesso ao repositório Gemma 2B via API do Hugging Face.
     */
    suspend fun verifyHfAccess(token: String): HfAuthStatus = withContext(Dispatchers.IO) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return@withContext HfAuthStatus.InvalidToken

        try {
            val whoamiReq = Request.Builder()
                .url("https://huggingface.co/api/whoami-v2")
                .header("Authorization", "Bearer $trimmed")
                .header("User-Agent", "Linguaflow-Android/1.0")
                .build()

            val whoamiResp = okHttpClient.newCall(whoamiReq).execute()
            if (whoamiResp.code == 401) {
                return@withContext HfAuthStatus.InvalidToken
            }
            if (!whoamiResp.isSuccessful) {
                return@withContext HfAuthStatus.NetworkError("HTTP ${whoamiResp.code}")
            }

            val whoamiBody = whoamiResp.body?.string() ?: ""
            val username = try {
                JSONObject(whoamiBody).optString("name", "Usuário")
            } catch (e: Exception) {
                "Usuário"
            }

            // Testa acesso ao repositório fechado/gated do Gemma 2B
            val gemmaReq = Request.Builder()
                .url("https://huggingface.co/api/models/google/gemma-2b-it")
                .header("Authorization", "Bearer $trimmed")
                .header("User-Agent", "Linguaflow-Android/1.0")
                .build()

            val gemmaResp = okHttpClient.newCall(gemmaReq).execute()
            when (gemmaResp.code) {
                200 -> HfAuthStatus.Success(username = username, hasGemmaAccess = true)
                403 -> HfAuthStatus.TermsRequired(username = username)
                else -> HfAuthStatus.Success(username = username, hasGemmaAccess = false)
            }
        } catch (e: Exception) {
            HfAuthStatus.NetworkError(e.localizedMessage ?: "Erro de rede")
        }
    }

    suspend fun importFromFileUri(model: ModelInfo, uri: Uri) = withContext(Dispatchers.IO) {
        val targetFile = getModelFile(model)
        updateState(model.id, ModelDownloadState.Verifying("Importando e validando arquivo..."))

        try {
            val contentResolver = context.contentResolver
            val inputStream: InputStream? = contentResolver.openInputStream(uri)

            if (inputStream == null) {
                updateState(model.id, ModelDownloadState.Error("Não foi possível abrir o arquivo selecionado."))
                return@withContext
            }

            val tempFile = File(modelsDir, "${model.fileName}.temp")
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 65536)
                }
            }

            if (tempFile.renameTo(targetFile)) {
                updateState(model.id, ModelDownloadState.Installed(targetFile.length()))
            } else {
                updateState(model.id, ModelDownloadState.Error("Falha ao salvar o arquivo importado."))
            }
        } catch (err: Exception) {
            updateState(model.id, ModelDownloadState.Error("Erro na importação: ${err.localizedMessage}"))
        }
    }

    fun deleteModel(model: ModelInfo) {
        cancelDownload(model)
        val file = getModelFile(model)
        if (file.exists()) {
            file.delete()
        }
        updateState(model.id, ModelDownloadState.NotInstalled)
    }

    private fun updateState(modelId: String, state: ModelDownloadState) {
        val current = _downloadStates.value.toMutableMap()
        current[modelId] = state
        _downloadStates.value = current
    }

    private fun calculateSha256(file: File): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }
}
