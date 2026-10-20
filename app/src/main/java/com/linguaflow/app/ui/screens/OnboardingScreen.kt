package com.linguaflow.app.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguaflow.app.data.download.ModelDownloadManager
import com.linguaflow.app.data.model.ModelCatalog
import com.linguaflow.app.data.model.ModelDownloadState
import com.linguaflow.app.data.model.ModelInfo
import com.linguaflow.app.data.repository.LinguaflowRepository
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    repository: LinguaflowRepository,
    modelManager: ModelDownloadManager,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val downloadStates by modelManager.downloadStates.collectAsStateWithLifecycle()

    val languages = listOf("Inglês", "Espanhol", "Francês", "Alemão")
    val levels = listOf(
        "A1" to "Iniciante absoluto",
        "A2" to "Básico (frases simples)",
        "B1" to "Intermediário (conversas cotidianas)",
        "B2" to "Avançado (fluência e negócios)"
    )
    val goals = listOf("5 min / dia", "10 min / dia", "15 min / dia")

    var selectedLanguage by remember { mutableStateOf("Inglês") }
    var selectedLevel by remember { mutableStateOf("A1") }
    var selectedGoal by remember { mutableStateOf("10 min / dia") }

    // Dialog de Autenticação / Token do Hugging Face nativo
    var showAuthDialog by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf(modelManager.getAuthToken() ?: "") }
    var authDialogModel by remember { mutableStateOf<ModelInfo?>(null) }
    var isTokenPasswordVisible by remember { mutableStateOf(false) }

    // Rastreia modelo recebendo arquivo importado manualmente
    var targetModelForImport by remember { mutableStateOf<ModelInfo?>(null) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val target = targetModelForImport
        if (uri != null && target != null) {
            scope.launch {
                modelManager.importFromFileUri(target, uri)
                Toast.makeText(context, "Arquivo importado para ${target.name}!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // DISPARO AUTOMÁTICO DE DOWNLOAD: Na primeira abertura, se não tiver os modelos mínimos, inicia sozinho!
    LaunchedEffect(Unit) {
        if (!modelManager.hasMinimumRequiredModels()) {
            modelManager.startAutoDownloadMissingRequiredModels()
        }
    }

    // Monitora se algum modelo solicitou autenticação para exibir o dialog nativo automaticamente
    LaunchedEffect(downloadStates) {
        val requiresAuthEntry = downloadStates.entries.firstOrNull { it.value is ModelDownloadState.RequiresAuth }
        if (requiresAuthEntry != null && !showAuthDialog) {
            authDialogModel = ModelCatalog.getModelById(requiresAuthEntry.key)
            showAuthDialog = true
        }
    }

    val requiredModelsReady = ModelCatalog.allModels
        .filter { it.isRequired }
        .all { downloadStates[it.id] is ModelDownloadState.Installed }

    androidx.activity.compose.BackHandler {
        onFinish()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            // Header Hero
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = "Linguaflow Icon",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Configuração do Tutor",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "IA Offline & Personalização de Ensino",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Seção 1: Idioma Alvo
            Text(
                text = "1. Idioma que deseja praticar",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                languages.forEach { lang ->
                    val selected = lang == selectedLanguage
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedLanguage = lang }
                            .testTag("onboarding_lang_${lang.lowercase()}"),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = lang,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Seção 2: Nível Atual
            Text(
                text = "2. Seu nível de proficiência estimado",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                levels.forEach { (code, desc) ->
                    val selected = code == selectedLevel
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedLevel = code }
                            .testTag("onboarding_level_$code"),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = code,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = desc,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Seção 3: Meta Diária
            Text(
                text = "3. Meta diária de conversação",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                goals.forEach { goal ->
                    val selected = goal == selectedGoal
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .clickable { selectedGoal = goal }
                            .testTag("onboarding_goal_${goal.take(2)}"),
                        colors = CardDefaults.cardColors(
                            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = goal,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // Seção 4: Download Automático de Modelos de IA
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "4. Motores de IA Offline",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Download automático via HTTP em segundo plano",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(
                    onClick = { showAuthDialog = true },
                    modifier = Modifier.testTag("btn_configure_hf_token")
                ) {
                    Icon(imageVector = Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Token HF", style = MaterialTheme.typography.labelSmall)
                }
            }

            Spacer(Modifier.height(10.dp))

            ModelCatalog.allModels.forEach { model ->
                val state = downloadStates[model.id] ?: ModelDownloadState.NotInstalled
                ModelDownloadItemCard(
                    model = model,
                    state = state,
                    onStartDownload = { modelManager.startDownload(model) },
                    onPauseDownload = { modelManager.pauseDownload(model) },
                    onResumeDownload = { modelManager.resumeDownload(model) },
                    onCancelDownload = { modelManager.cancelDownload(model) },
                    onAuthenticate = {
                        authDialogModel = model
                        showAuthDialog = true
                    },
                    onSelectManualFile = {
                        targetModelForImport = model
                        filePickerLauncher.launch(arrayOf("*/*"))
                    }
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(24.dp))

            // Botão Principal de Conclusão do Onboarding
            Button(
                onClick = {
                    scope.launch {
                        repository.savePreferences(
                            targetLang = selectedLanguage,
                            level = selectedLevel,
                            dailyMinutes = selectedGoal.filter { it.isDigit() }.toIntOrNull() ?: 10
                        )
                        onFinish()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("onboarding_save_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (requiredModelsReady) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary
                )
            ) {
                Text(
                    text = if (requiredModelsReady) "Tudo Pronto! Iniciar Prática"
                    else "Continuar para o App (Download em Background)",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }

    // Modal Nativo de Autenticação / Token Hugging Face
    if (showAuthDialog) {
        AlertDialog(
            onDismissRequest = { showAuthDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Autenticação Hugging Face",
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = "Para baixar os pesos otimizados do modelo ${authDialogModel?.name ?: "Gemma"}, insira seu User Access Token (Read) do Hugging Face.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "O download é 100% gratuito e direto para a pasta interna do app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))

                    OutlinedTextField(
                        value = tokenInput,
                        onValueChange = { tokenInput = it },
                        label = { Text("Token de Acesso (hf_...)") },
                        placeholder = { Text("Cole aqui seu token") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (isTokenPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { isTokenPasswordVisible = !isTokenPasswordVisible }) {
                                Icon(
                                    imageVector = if (isTokenPasswordVisible) Icons.Default.Close else Icons.Default.Key,
                                    contentDescription = "Alternar visibilidade"
                                )
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_hf_token")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (tokenInput.isNotBlank()) {
                            modelManager.saveAuthToken(tokenInput)
                            showAuthDialog = false
                            val target = authDialogModel ?: ModelCatalog.LLM_GEMMA_2B
                            modelManager.startDownload(target)
                            Toast.makeText(context, "Token salvo! Iniciando download...", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Por favor, insira o token.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.testTag("btn_confirm_token")
                ) {
                    Text("Salvar e Baixar")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showAuthDialog = false }
                ) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
fun ModelDownloadItemCard(
    model: ModelInfo,
    state: ModelDownloadState,
    onStartDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onAuthenticate: () -> Unit,
    onSelectManualFile: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model_card_${model.id}"),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                is ModelDownloadState.Installed -> MaterialTheme.colorScheme.surfaceVariant
                is ModelDownloadState.Downloading -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                is ModelDownloadState.RequiresAuth -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            }
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = model.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        if (model.isRequired) {
                            Spacer(Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Essencial",
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = "${model.type.label} • aprox. ${model.formattedSize}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Status Badge / Action
                when (state) {
                    is ModelDownloadState.Installed -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Instalado",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Pronto",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    is ModelDownloadState.Downloading -> {
                        Row {
                            IconButton(onClick = onPauseDownload, modifier = Modifier.size(32.dp)) {
                                Icon(imageVector = Icons.Default.Pause, contentDescription = "Pausar")
                            }
                            IconButton(onClick = onCancelDownload, modifier = Modifier.size(32.dp)) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Cancelar")
                            }
                        }
                    }
                    is ModelDownloadState.Paused -> {
                        Row {
                            IconButton(onClick = onResumeDownload, modifier = Modifier.size(32.dp)) {
                                Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Continuar")
                            }
                            IconButton(onClick = onCancelDownload, modifier = Modifier.size(32.dp)) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Cancelar")
                            }
                        }
                    }
                    is ModelDownloadState.RequiresAuth -> {
                        Button(
                            onClick = onAuthenticate,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Autenticar", fontSize = 12.sp)
                        }
                    }
                    is ModelDownloadState.Verifying -> {
                        Text(
                            text = "Validando...",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    is ModelDownloadState.Error -> {
                        IconButton(onClick = onStartDownload, modifier = Modifier.size(32.dp)) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Tentar novamente")
                        }
                    }
                    is ModelDownloadState.NotInstalled -> {
                        Button(
                            onClick = onStartDownload,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("btn_download_${model.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Baixar", fontSize = 12.sp)
                        }
                    }
                }
            }

            // Descrição curta do modelo
            Text(
                text = model.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            // Barra de Progresso Real quando em download
            if (state is ModelDownloadState.Downloading) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${state.progressPercent}% (${formatBytes(state.downloadedBytes)} / ${formatBytes(state.totalBytes)})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.speedText.isNotBlank()) {
                        Text(
                            text = state.speedText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            if (state is ModelDownloadState.Paused) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Download pausado em ${formatBytes(state.downloadedBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (state is ModelDownloadState.RequiresAuth) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }

            if (state is ModelDownloadState.Error) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val mb = bytes.toDouble() / (1024.0 * 1024.0)
    return if (mb >= 1000) {
        String.format("%.2f GB", mb / 1024.0)
    } else {
        String.format("%.1f MB", mb)
    }
}
