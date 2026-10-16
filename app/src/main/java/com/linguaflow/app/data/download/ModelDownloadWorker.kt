package com.linguaflow.app.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.linguaflow.app.data.model.ModelCatalog
import com.linguaflow.app.data.model.ModelInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelDownloadWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return@withContext Result.failure()
        val model = ModelCatalog.getModelById(modelId) ?: return@withContext Result.failure()

        val modelsDir = File(applicationContext.filesDir, "models").apply {
            if (!exists()) mkdirs()
        }
        val targetFile = File(modelsDir, model.fileName)
        val partFile = File(modelsDir, "${model.fileName}.part")

        val existingBytes = if (partFile.exists()) partFile.length() else 0L

        val requestBuilder = Request.Builder().url(model.url)
        if (existingBytes > 0) {
            requestBuilder.header("Range", "bytes=$existingBytes-")
        }

        try {
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            val isPartial = response.code == 206
            if (!response.isSuccessful && !isPartial) {
                return@withContext Result.retry()
            }

            val body = response.body ?: return@withContext Result.retry()
            val totalBytes = if (isPartial) existingBytes + body.contentLength() else body.contentLength()

            var downloaded = if (isPartial) existingBytes else 0L
            val appendMode = isPartial && existingBytes > 0

            body.byteStream().use { input ->
                FileOutputStream(partFile, appendMode).use { output ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    var lastUpdate = System.currentTimeMillis()

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        if (isStopped) {
                            return@withContext Result.retry()
                        }
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 500) {
                            lastUpdate = now
                            val progress = if (totalBytes > 0) ((downloaded.toFloat() / totalBytes) * 100).toInt() else 0
                            setProgress(workDataOf(KEY_PROGRESS to progress))
                        }
                    }
                    output.flush()
                }
            }

            // SHA-256 verification
            val digest = MessageDigest.getInstance("SHA-256")
            partFile.inputStream().use { input ->
                val buffer = ByteArray(65536)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            val calculatedSha = digest.digest().joinToString("") { "%02x".format(it) }

            if (model.expectedSha256.isNotBlank() && !calculatedSha.equals(model.expectedSha256, ignoreCase = true)) {
                return@withContext Result.failure(workDataOf(KEY_ERROR to "SHA-256 incorreto"))
            }

            if (targetFile.exists()) targetFile.delete()
            if (partFile.renameTo(targetFile)) {
                Result.success(workDataOf(KEY_OUTPUT_PATH to targetFile.absolutePath))
            } else {
                Result.failure(workDataOf(KEY_ERROR to "Erro ao mover arquivo final"))
            }
        } catch (e: Exception) {
            if (isStopped) Result.retry() else Result.failure(workDataOf(KEY_ERROR to (e.message ?: "Erro")))
        }
    }

    companion object {
        const val KEY_MODEL_ID = "key_model_id"
        const val KEY_PROGRESS = "key_progress"
        const val KEY_ERROR = "key_error"
        const val KEY_OUTPUT_PATH = "key_output_path"
    }
}
