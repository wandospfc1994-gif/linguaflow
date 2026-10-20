package com.linguaflow.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguaflow.app.data.download.HfAuthStatus
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

    // Dialog de Autenticação / Termos Hugging Face (com WebView e Verificação de Token)
    var showAuthDialog by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf(modelManager.getAuthToken() ?: "") }
    var authDialogModel by remember { mutableStateOf<ModelInfo?>(null) }
    var dismissedAuthModelIds by remember { mutableStateOf(setOf<String>()) }

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

    // DISPARO AUTOMÁTICO DE DOWNLOAD: Inicia uma única vez em background se não houver modelos prontos
    LaunchedEffect(Unit) {
        if (!modelManager.hasMinimumRequiredModels()) {
            modelManager.startAutoDownloadMissingRequiredModels()
        }
    }

    // Monitora se algum modelo solicitou autenticação para exibir o dialog (com proteção contra loop infinito)
    LaunchedEffect(downloadStates) {
        val requiresAuthEntry = downloadStates.entries.firstOrNull {
            it.value is ModelDownloadState.RequiresAuth && !dismissedAuthModelIds.contains(it.key)
        }
        if (requiresAuthEntry != null && !showAuthDialog) {
            authDialogModel = ModelCatalog.getModelById(requiresAuthEntry.key)
            showAuthDialog = true
        }
    }

    val requiredModelsReady = ModelCatalog.allModels
        .filter { it.isRequired }
        .all { downloadStates[it.id] is ModelDownloadState.Installed }

    BackHandler {
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
                        text = "Download resiliente com retomada automática",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                TextButton(
                    onClick = {
                        authDialogModel = ModelCatalog.LLM_GEMMA_2B
                        showAuthDialog = true
                    },
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
                    onStartDownload = {
                        modelManager.clearError(model)
                        modelManager.startDownload(model)
                    },
                    onPauseDownload = { modelManager.pauseDownload(model) },
                    onResumeDownload = { modelManager.resumeDownload(model) },
                    onCancelDownload = { modelManager.cancelDownload(model) },
                    onAuthenticate = {
                        authDialogModel = model
                        dismissedAuthModelIds = dismissedAuthModelIds - model.id
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

    // Modal Nativo de Autenticação / Termos Hugging Face com WebView Embutida
    if (showAuthDialog) {
        HuggingFaceAuthDialog(
            targetModel = authDialogModel ?: ModelCatalog.LLM_GEMMA_2B,
            initialToken = tokenInput,
            modelManager = modelManager,
            onDismiss = {
                authDialogModel?.let { m ->
                    dismissedAuthModelIds = dismissedAuthModelIds + m.id
                }
                showAuthDialog = false
            },
            onTokenSaved = { savedToken ->
                tokenInput = savedToken
                modelManager.saveAuthToken(savedToken)
                authDialogModel?.let { m ->
                    dismissedAuthModelIds = dismissedAuthModelIds - m.id
                    modelManager.clearError(m)
                    modelManager.startDownload(m)
                }
                showAuthDialog = false
            }
        )
    }
}

@Composable
fun HuggingFaceAuthDialog(
    targetModel: ModelInfo,
    initialToken: String,
    modelManager: ModelDownloadManager,
    onDismiss: () -> Unit,
    onTokenSaved: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedTab by remember { mutableIntStateOf(0) } // 0: Chave / Token, 1: WebView
    var tokenText by remember { mutableStateOf(initialToken) }
    var isTokenPasswordVisible by remember { mutableStateOf(false) }

    var isVerifying by remember { mutableStateOf(false) }
    var authVerificationStatus by remember { mutableStateOf<HfAuthStatus?>(null) }

    var webViewUrl by remember { mutableStateOf("https://huggingface.co/google/gemma-2b-it") }
    var isWebLoading by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .padding(vertical = 16.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Acesso Hugging Face",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Fechar")
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Abas de navegação
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Token & Chave") },
                        icon = { Icon(imageVector = Icons.Default.Key, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Web / Termos") },
                        icon = { Icon(imageVector = Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    )
                }

                Spacer(Modifier.height(14.dp))

                if (selectedTab == 0) {
                    // Aba 0: Inserção e validação do token
                    Text(
                        text = "O modelo ${targetModel.name} requer autorização da Hugging Face para download dos pesos quantizados.",
                        style = MaterialTheme.typography.bodyMedium
                    )

                    Spacer(Modifier.height(12.dp))

                    OutlinedTextField(
                        value = tokenText,
                        onValueChange = {
                            tokenText = it
                            authVerificationStatus = null
                        },
                        label = { Text("User Access Token (hf_...)") },
                        placeholder = { Text("Cole aqui seu token de leitura") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        visualTransformation = if (isTokenPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            Row {
                                IconButton(onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                                    if (!clip.isNullOrBlank()) {
                                        tokenText = clip.trim()
                                        Toast.makeText(context, "Token colado!", Toast.LENGTH_SHORT).show()
                                    }
                                }) {
                                    Icon(imageVector = Icons.Default.ContentPaste, contentDescription = "Colar")
                                }
                                IconButton(onClick = { isTokenPasswordVisible = !isTokenPasswordVisible }) {
                                    Icon(
                                        imageVector = if (isTokenPasswordVisible) Icons.Default.Close else Icons.Default.Key,
                                        contentDescription = "Alternar visibilidade"
                                    )
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("input_hf_token")
                    )

                    Spacer(Modifier.height(8.dp))

                    // Botão de testar conexão
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (tokenText.isNotBlank()) {
                                    scope.launch {
                                        isVerifying = true
                                        val status = modelManager.verifyHfAccess(tokenText)
                                        authVerificationStatus = status
                                        isVerifying = false
                                    }
                                } else {
                                    Toast.makeText(context, "Insira um token antes de verificar.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = !isVerifying && tokenText.isNotBlank(),
                            modifier = Modifier.testTag("btn_verify_hf_token")
                        ) {
                            if (isVerifying) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(6.dp))
                                Text("Verificando...")
                            } else {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Testar Acesso")
                            }
                        }

                        TextButton(onClick = { selectedTab = 1 }) {
                            Text("Abrir Termos Web", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Exibição do feedback de status de verificação
                    authVerificationStatus?.let { status ->
                        Spacer(Modifier.height(10.dp))
                        when (status) {
                            is HfAuthStatus.Success -> {
                                Surface(
                                    color = if (status.hasGemmaAccess) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.tertiaryContainer,
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = "Conectado como @${status.username}",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                            Text(
                                                text = if (status.hasGemmaAccess) "Acesso ao modelo Gemma liberado e verificado!"
                                                else "Token válido! Se necessário, certifique-se de aceitar os termos na página do modelo.",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                    }
                                }
                            }
                            is HfAuthStatus.TermsRequired -> {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Default.Warning,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            Text(
                                                text = "Termos de Licença Pendentes",
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = "Seu token para @${status.username} é válido, mas os termos de licença do Gemma 2B ainda não foram aceitos na Hugging Face.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                        Spacer(Modifier.height(6.dp))
                                        Button(
                                            onClick = {
                                                webViewUrl = "https://huggingface.co/google/gemma-2b-it"
                                                selectedTab = 1
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                        ) {
                                            Text("Aceitar Termos na Web Agora", fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                            is HfAuthStatus.InvalidToken -> {
                                Surface(
                                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = "Token inválido ou não reconhecido pela API do Hugging Face.",
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                            is HfAuthStatus.NetworkError -> {
                                Text(
                                    text = "Falha ao validar com a Hugging Face: ${status.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                } else {
                    // Aba 1: WebView Embutida
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    webViewUrl = "https://huggingface.co/google/gemma-2b-it"
                                    webViewInstance?.loadUrl(webViewUrl)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Gemma 2B", fontSize = 11.sp, maxLines = 1)
                            }
                            OutlinedButton(
                                onClick = {
                                    webViewUrl = "https://huggingface.co/settings/tokens"
                                    webViewInstance?.loadUrl(webViewUrl)
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Meus Tokens", fontSize = 11.sp, maxLines = 1)
                            }
                            IconButton(onClick = { webViewInstance?.reload() }) {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = "Recarregar")
                            }
                        }

                        if (isWebLoading) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            AndroidView(
                                factory = { ctx ->
                                    WebView(ctx).apply {
                                        settings.javaScriptEnabled = true
                                        settings.domStorageEnabled = true
                                        settings.loadWithOverviewMode = true
                                        settings.useWideViewPort = true
                                        settings.setSupportZoom(true)
                                        webViewClient = object : WebViewClient() {
                                            override fun onPageFinished(view: WebView?, url: String?) {
                                                isWebLoading = false
                                            }
                                        }
                                        webChromeClient = object : WebChromeClient() {
                                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                                isWebLoading = newProgress < 100
                                            }
                                        }
                                        loadUrl(webViewUrl)
                                        webViewInstance = this
                                    }
                                },
                                update = { view ->
                                    webViewInstance = view
                                },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Faça login com sua conta, clique em 'Acknowledge license' e depois copie seu token em 'Meus Tokens'.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (tokenText.isNotBlank()) {
                        onTokenSaved(tokenText.trim())
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
            OutlinedButton(onClick = onDismiss) {
                Text("Fechar")
            }
        }
    )
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
                is ModelDownloadState.Error -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
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
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("btn_auth_${model.id}")
                        ) {
                            Text(if (state.requiresTermsAcceptance) "Termos" else "Autenticar", fontSize = 12.sp)
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
                        IconButton(
                            onClick = onStartDownload,
                            modifier = Modifier
                                .size(40.dp)
                                .testTag("btn_retry_${model.id}")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Tentar novamente download de ${model.name}",
                                tint = MaterialTheme.colorScheme.error
                            )
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
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
