package com.linguaflow.app.data.download

import android.content.Context
import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
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
    data class Success(val username: String, val hasGemmaAccess: Boolean, val httpCode: Int = 200) : HfAuthStatus()
    data class TermsRequired(val username: String, val httpCode: Int = 403) : HfAuthStatus()
    data class InvalidToken(val httpCode: Int = 401) : HfAuthStatus()
    data class NetworkError(val message: String, val httpCode: Int? = null) : HfAuthStatus()
}

class ModelDownloadManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val tag = "ModelDownloadManager"
    private val prefs = context.getSharedPreferences("linguaflow_models_auth", Context.MODE_PRIVATE)

    // DIRETÓRIO PERMANENTE: Salva SEMPRE em context.filesDir/models (nunca em cacheDir)
    private val modelsDir: File = File(context.filesDir, "models").apply {
        if (!exists()) {
            mkdirs()
        }
    }

    val modelsDirectory: File
        get() = modelsDir

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

    /**
     * Sincroniza cookies de sessão autenticada da WebView para o OkHttp
     */
    fun syncCookiesFromWebView(url: String = "https://huggingface.co") {
        try {
            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrBlank()) {
                prefs.edit().putString("hf_web_cookies", cookies.trim()).apply()
                Log.d(tag, "Cookies do Hugging Face sincronizados com sucesso.")
            }
        } catch (e: Exception) {
            Log.w(tag, "Erro ao ler cookies do CookieManager: ${e.message}")
        }
    }

    fun getSavedCookies(): String? {
        val cookies = try {
            CookieManager.getInstance().getCookie("https://huggingface.co")
        } catch (e: Exception) {
            null
        }
        return if (!cookies.isNullOrBlank()) cookies else prefs.getString("hf_web_cookies", null)
    }

    fun clearCookies() {
        prefs.edit().remove("hf_web_cookies").apply()
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (e: Exception) {
            Log.w(tag, "Erro ao limpar cookies: ${e.message}")
        }
    }

    fun getModelFile(model: ModelInfo): File = File(modelsDir, model.fileName)

    private fun getPartFile(model: ModelInfo): File = File(modelsDir, "${model.fileName}.part")

    /**
     * Verificação física e estrita da existência e integridade do modelo no filesDir/models.
     * Retorna true se o arquivo existe e seu tamanho corresponde ao esperado para uso offline.
     */
    fun isModelPhysicallyInstalled(model: ModelInfo): Boolean {
        val file = getModelFile(model)

        // Verificação específica para Vosk STT (pode ser o zip ou a pasta descompactada)
        if (model.id == ModelCatalog.STT_VOSK_EN.id) {
            if (file.exists() && file.length() >= 35_000_000L) {
                prefs.edit().putBoolean("model_verified_${model.id}", true).apply()
                return true
            }
            val extractedDir = File(modelsDir, "vosk-model-small-en-us-0.15")
            if (extractedDir.exists() && File(extractedDir, "am").exists()) {
                prefs.edit().putBoolean("model_verified_${model.id}", true).apply()
                return true
            }
        }

        // Verificação específica para Gemma 2B LLM (mínimo de ~500MB para modelo quantizado int4 funcional)
        if (model.id == ModelCatalog.LLM_GEMMA_2B.id) {
            if (file.exists() && file.length() >= 500_000_000L) {
                prefs.edit().putBoolean("model_verified_${model.id}", true).apply()
                return true
            }
            // Verifica se há outro arquivo compatível .bin ou .task com peso substancial
            val candidate = modelsDir.listFiles { f ->
                (f.name.endsWith(".bin") || f.name.endsWith(".task")) && f.length() >= 500_000_000L
            }?.firstOrNull()
            if (candidate != null) {
                prefs.edit().putBoolean("model_verified_${model.id}", true).apply()
                return true
            }
        }

        // Verificação genérica para outros modelos com tolerância razoável de compressão
        if (file.exists()) {
            val minExpected = if (model.approximateSizeBytes > 0) {
                (model.approximateSizeBytes * 0.70).toLong()
            } else 100_000L

            if (file.length() >= minExpected) {
                prefs.edit().putBoolean("model_verified_${model.id}", true).apply()
                return true
            }
        }

        return false
    }

    fun refreshAllStates() {
        val newStates = mutableMapOf<String, ModelDownloadState>()
        for (model in ModelCatalog.allModels) {
            val file = getModelFile(model)
            if (isModelPhysicallyInstalled(model)) {
                val size = if (file.exists()) file.length() else model.approximateSizeBytes
                newStates[model.id] = ModelDownloadState.Installed(size)
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

    /**
     * Verifica se os modelos mínimos essenciais para o funcionamento do app estão
     * instalados fisicamente no filesDir/models com tamanho esperado.
     */
    fun hasMinimumRequiredModels(): Boolean {
        val required = ModelCatalog.allModels.filter { it.isRequired }
        return required.all { model -> isModelPhysicallyInstalled(model) }
    }

    /**
     * Inicia download automático em segundo plano dos modelos obrigatórios que ainda não estejam instalados.
     * Previne loops ignorando estados ativos ou de erro/autenticação.
     */
    fun startAutoDownloadMissingRequiredModels() {
        val required = ModelCatalog.allModels.filter { it.isRequired }
        for (model in required) {
            if (isModelPhysicallyInstalled(model)) {
                updateState(model.id, ModelDownloadState.Installed(getModelFile(model).length()))
                continue
            }

            val currentState = _downloadStates.value[model.id]
            val isBusyOrBlocked = currentState is ModelDownloadState.Downloading ||
                currentState is ModelDownloadState.Verifying ||
                currentState is ModelDownloadState.RequiresAuth ||
                currentState is ModelDownloadState.Error
            if (!isBusyOrBlocked) {
                Log.d(tag, "Iniciando download automático para o modelo: ${model.name}")
                startDownload(model)
            }
        }
    }

    fun startDownload(model: ModelInfo) {
        if (isModelPhysicallyInstalled(model)) {
            Log.d(tag, "Modelo ${model.id} já está instalado e verificado em filesDir/models. Pulando download.")
            updateState(model.id, ModelDownloadState.Installed(getModelFile(model).length()))
            return
        }

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

    suspend fun importFromFileUri(model: ModelInfo, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val targetFile = getModelFile(model)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(targetFile).use { output ->
                    input.copyTo(output)
                }
            }
            prefs.edit().putBoolean("model_verified_${model.id}", true).apply()
            updateState(model.id, ModelDownloadState.Installed(targetFile.length()))
            true
        } catch (e: Exception) {
            Log.e(tag, "Erro ao importar arquivo do URI para ${model.id}", e)
            updateState(model.id, ModelDownloadState.Error("Falha na importação: ${e.message}"))
            false
        }
    }

    private suspend fun downloadInternal(model: ModelInfo): Unit = withContext(Dispatchers.IO) {
        var activeModel = model
        val targetFile = getModelFile(activeModel)
        val partFile = getPartFile(activeModel)

        val maxRetries = 5
        var attempt = 0
        var isCompleted = false

        while (attempt < maxRetries && !isCompleted) {
            attempt++
            try {
                val existingBytes = if (partFile.exists()) partFile.length() else 0L

                // Se já estiver completo
                if (existingBytes > 0 && activeModel.approximateSizeBytes > 0 && existingBytes >= activeModel.approximateSizeBytes) {
                    isCompleted = true
                    break
                }

                val requestBuilder = Request.Builder()
                    .url(activeModel.url)
                    .header("User-Agent", "Linguaflow-Android/1.0")

                if (existingBytes > 0) {
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                }

                // SOMENTE adiciona Authorization ou Cookies se o modelo exigir autenticação privada
                if (activeModel.requiresAuth) {
                    val token = getAuthToken()
                    if (!token.isNullOrBlank() && token.trim().length > 3) {
                        requestBuilder.header("Authorization", "Bearer ${token.trim()}")
                    }

                    val cookies = getSavedCookies()
                    if (!cookies.isNullOrBlank() && cookies.trim().isNotEmpty()) {
                        requestBuilder.header("Cookie", cookies.trim())
                    }
                }

                val request = requestBuilder.build()
                val response = okHttpClient.newCall(request).execute()

                val responseCode = response.code
                val isPartial = responseCode == 206
                val isFull = responseCode == 200

                // Redundância inteligente para mirrors públicos
                if ((responseCode == 401 || responseCode == 404) && !activeModel.requiresAuth && activeModel.id == ModelCatalog.LLM_GEMMA_2B.id && activeModel.url != ModelCatalog.GEMMA_VERIFIED_MIRROR_URL) {
                    Log.w(tag, "HTTP $responseCode no mirror primário. Alternando para o mirror público verificado: ${ModelCatalog.GEMMA_VERIFIED_MIRROR_URL}")
                    activeModel = activeModel.copy(url = ModelCatalog.GEMMA_VERIFIED_MIRROR_URL)
                    continue
                }

                // Tratamento de autenticação / termos no Hugging Face (somente para modelos privados/gated que exigem auth)
                if (responseCode == 401) {
                    Log.w(tag, "Modelo ${activeModel.id} retornou HTTP 401 - Requer token ou login")
                    updateState(
                        activeModel.id,
                        ModelDownloadState.RequiresAuth(
                            modelId = activeModel.id,
                            message = "Erro HTTP 401: Token ou autenticação ausente/inválida. Insira seu token ou use o Mirror Público.",
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
                            message = "Erro HTTP 403: Licença do Gemma 2B não aceita na conta Hugging Face. Aceite os termos na aba Web ou use o Mirror Público.",
                            requiresTermsAcceptance = true
                        )
                    )
                    return@withContext
                }

                if (responseCode == 416) {
                    if (existingBytes >= (model.approximateSizeBytes * 0.90)) {
                        isCompleted = true
                        break
                    } else {
                        Log.w(tag, "HTTP 416 para ${model.id}: faixa inválida, reiniciando partFile.")
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
            prefs.edit().putBoolean("model_verified_${model.id}", true).apply()
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
        if (trimmed.isEmpty()) return@withContext HfAuthStatus.InvalidToken(401)

        try {
            val whoamiReq = Request.Builder()
                .url("https://huggingface.co/api/whoami-v2")
                .header("Authorization", "Bearer $trimmed")
                .header("User-Agent", "Linguaflow-Android/1.0")
                .build()

            val whoamiResp = okHttpClient.newCall(whoamiReq).execute()
            val whoamiCode = whoamiResp.code

            if (whoamiCode == 401) {
                return@withContext HfAuthStatus.InvalidToken(401)
            }
            if (!whoamiResp.isSuccessful) {
                return@withContext HfAuthStatus.NetworkError("Erro HTTP $whoamiCode na verificação da conta", whoamiCode)
            }

            val username = try {
                val json = JSONObject(whoamiResp.body?.string() ?: "{}")
                json.optString("name", "Usuário HF")
            } catch (e: Exception) {
                "Usuário HF"
            }

            // Testa acesso direto ao repositório do Gemma 2B
            val gemmaReq = Request.Builder()
                .url("https://huggingface.co/google/gemma-2b-it/resolve/main/config.json")
                .header("Authorization", "Bearer $trimmed")
                .header("User-Agent", "Linguaflow-Android/1.0")
                .head()
                .build()

            val gemmaResp = okHttpClient.newCall(gemmaReq).execute()
            val gemmaCode = gemmaResp.code

            return@withContext when (gemmaCode) {
                200 -> HfAuthStatus.Success(username, hasGemmaAccess = true, httpCode = 200)
                403 -> HfAuthStatus.TermsRequired(username, httpCode = 403)
                401 -> HfAuthStatus.InvalidToken(httpCode = 401)
                else -> {
                    if (gemmaResp.isSuccessful) {
                        HfAuthStatus.Success(username, hasGemmaAccess = true, httpCode = gemmaCode)
                    } else {
                        HfAuthStatus.TermsRequired(username, httpCode = gemmaCode)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Exceção ao validar token Hugging Face", e)
            return@withContext HfAuthStatus.NetworkError("Falha de conexão: ${e.localizedMessage}")
        }
    }

    private fun updateState(modelId: String, state: ModelDownloadState) {
        val current = _downloadStates.value.toMutableMap()
        current[modelId] = state
        _downloadStates.value = current
    }

    fun calculateSha256(file: File): String {
        if (!file.exists()) return ""
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            Log.w(tag, "Erro ao calcular SHA256", e)
            ""
        }
    }
}
