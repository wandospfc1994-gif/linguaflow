package com.linguaflow.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguaflow.app.data.local.entity.MessageEntity
import com.linguaflow.app.data.model.TutorFeedback
import com.linguaflow.app.data.repository.LinguaflowRepository
import androidx.lifecycle.viewmodel.compose.viewModel
import com.linguaflow.app.engine.SpeechToTextEngine
import com.linguaflow.app.engine.SttState
import com.linguaflow.app.engine.TextToSpeechEngine
import com.linguaflow.app.engine.TtsPlaybackState
import com.linguaflow.app.engine.TutorLlmEngine
import com.linguaflow.app.engine.llm.TutorResponseParser
import com.linguaflow.app.ui.state.ChatUiState
import com.linguaflow.app.ui.viewmodel.TutorChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    repository: LinguaflowRepository,
    ttsEngine: TextToSpeechEngine,
    tutorLlm: TutorLlmEngine,
    sttEngine: SpeechToTextEngine,
    onBack: () -> Unit,
    viewModel: TutorChatViewModel = viewModel(
        factory = TutorChatViewModel.provideFactory(repository, tutorLlm, ttsEngine, sttEngine)
    )
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()

    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val chatUiState by viewModel.uiState.collectAsStateWithLifecycle()
    val inputText by viewModel.inputText.collectAsStateWithLifecycle()
    val streamingRawText by viewModel.streamingRawText.collectAsStateWithLifecycle()
    val speechRate by viewModel.speechRate.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    var showPermissionDeniedDialog by remember { mutableStateOf(false) }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { err ->
            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
            viewModel.clearErrorMessage()
        }
    }

    androidx.activity.compose.BackHandler {
        viewModel.stopTts()
        viewModel.stopListening()
        onBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopTts()
            viewModel.stopListening()
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startListening(
                onError = { err ->
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            showPermissionDeniedDialog = true
        }
    }

    val suggestions = listOf(
        "Hello, nice to meet you!",
        "How do I say 'saudades' in English?",
        "Can you correct my sentences please?",
        "What is the difference between make and do?"
    )

    // Auto-scroll
    LaunchedEffect(messages.size, streamingRawText) {
        val totalItems = messages.size + if (chatUiState == ChatUiState.THINKING) 1 else 0
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            // TopBar
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Tutor de Inglês",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = if (tutorLlm.isModelLoaded) "Gemma 2B" else "Tutor IA",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Text(
                            text = "STT Vosk + Fala fluida nativa",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("chat_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            viewModel.clearChat()
                        },
                        modifier = Modifier.testTag("clear_chat_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = "Limpar conversa"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            // State Machine Status Bar
            StateStatusBar(state = chatUiState)

            // Speed Control Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Voz do Tutor:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val rates = listOf(0.8f to "0.8x Lento", 1.0f to "1.0x Normal", 1.2f to "1.2x Rápido")
                    rates.forEach { (rate, label) ->
                        val isSelected = speechRate == rate
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                viewModel.setSpeechRate(rate)
                            },
                            label = { Text(label, fontSize = 11.sp) },
                            modifier = Modifier.height(26.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        )
                    }
                }
            }

            // Message List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                state = listState,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Spacer(Modifier.height(4.dp))
                }
                items(messages, key = { it.id }) { msg ->
                    ChatBubble(
                        message = msg,
                        playbackState = playbackState,
                        onPlayTts = {
                            viewModel.playTtsForMessage(msg)
                        }
                    )
                }

                if (chatUiState == ChatUiState.THINKING) {
                    item(key = "streaming_bubble") {
                        val parsed = TutorResponseParser.parse(streamingRawText)
                        StreamingChatBubble(
                            feedback = parsed,
                            rawText = streamingRawText
                        )
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                }
            }

            // Suggestions Chips
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(suggestions) { suggestion ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable(enabled = chatUiState == ChatUiState.IDLE) {
                                viewModel.onInputTextChanged(suggestion)
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = suggestion,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Bottom Input Bar with Mic & Echo Prevention
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Voice capture button (Mic with State Machine animation)
                MicrophoneButton(
                    chatState = chatUiState,
                    onClick = {
                        when (chatUiState) {
                            ChatUiState.SPEAKING -> {
                                Toast.makeText(
                                    context,
                                    "O microfone está pausado enquanto o tutor fala para evitar eco.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            ChatUiState.LISTENING -> {
                                viewModel.stopListening()
                            }
                            ChatUiState.IDLE -> {
                                val hasPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED

                                if (hasPermission) {
                                    viewModel.startListening(
                                        onError = { err ->
                                            Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                                        }
                                    )
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            else -> {}
                        }
                    }
                )

                Spacer(Modifier.width(8.dp))

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { viewModel.onInputTextChanged(it) },
                    placeholder = {
                        Text(
                            when (chatUiState) {
                                ChatUiState.LISTENING -> "Escutando sua fala..."
                                ChatUiState.TRANSCRIBING -> "Transcrevendo voz..."
                                ChatUiState.THINKING -> "Tutor pensando..."
                                ChatUiState.SPEAKING -> "Tutor falando..."
                                ChatUiState.IDLE -> "Digite ou toque no mic..."
                            }
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("chat_input_field"),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface
                    ),
                    maxLines = 3,
                    enabled = chatUiState != ChatUiState.THINKING
                )

                Spacer(Modifier.width(8.dp))

                // Send Button
                val canSend = inputText.isNotBlank() && chatUiState != ChatUiState.THINKING
                IconButton(
                    onClick = {
                        val textToSend = inputText.trim()
                        if (canSend) {
                            viewModel.sendMessage(textToSend)
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSend) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                        .testTag("chat_send_button"),
                    enabled = canSend
                ) {
                    if (chatUiState == ChatUiState.THINKING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Enviar",
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Permission Denied Dialog with Settings Redirect
    if (showPermissionDeniedDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDeniedDialog = false },
            title = { Text("Permissão de Microfone Necessária") },
            text = {
                Text("Para praticar conversação por voz com o tutor de inglês e ter suas métricas de pronúncia analisadas, o Linguaflow AI precisa do acesso ao microfone.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionDeniedDialog = false
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}")
                        ).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    }
                ) {
                    Text("Abrir Configurações")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPermissionDeniedDialog = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

@Composable
private fun StateStatusBar(state: ChatUiState) {
    val backgroundColor by animateColorAsState(
        targetValue = when (state) {
            ChatUiState.IDLE -> MaterialTheme.colorScheme.surface
            ChatUiState.LISTENING -> MaterialTheme.colorScheme.errorContainer
            ChatUiState.TRANSCRIBING -> MaterialTheme.colorScheme.tertiaryContainer
            ChatUiState.THINKING -> MaterialTheme.colorScheme.secondaryContainer
            ChatUiState.SPEAKING -> MaterialTheme.colorScheme.primaryContainer
        },
        label = "status_bar_bg"
    )

    AnimatedVisibility(visible = state != ChatUiState.IDLE) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(backgroundColor)
                .padding(vertical = 4.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state == ChatUiState.LISTENING) {
                    val transition = rememberInfiniteTransition(label = "pulse")
                    val scale by transition.animateFloat(
                        initialValue = 0.8f,
                        targetValue = 1.3f,
                        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                        label = "scale"
                    )
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .scale(scale)
                            .clip(CircleShape)
                            .background(Color.Red)
                    )
                    Spacer(Modifier.width(8.dp))
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    text = state.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun MicrophoneButton(
    chatState: ChatUiState,
    onClick: () -> Unit
) {
    val isSpeaking = chatState == ChatUiState.SPEAKING
    val isListening = chatState == ChatUiState.LISTENING

    val transition = rememberInfiniteTransition(label = "mic_pulse")
    val pulseScale by transition.animateFloat(
        initialValue = 1.0f,
        targetValue = if (isListening) 1.25f else 1.0f,
        animationSpec = infiniteRepeatable(tween(500), RepeatMode.Reverse),
        label = "mic_scale"
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .scale(if (isListening) pulseScale else 1.0f)
            .clip(CircleShape)
            .background(
                when {
                    isListening -> MaterialTheme.colorScheme.error
                    isSpeaking -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
            )
            .clickable(onClick = onClick)
            .testTag("chat_mic_button"),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when {
                isListening -> Icons.Default.Mic
                isSpeaking -> Icons.Default.MicOff
                else -> Icons.Default.Mic
            },
            contentDescription = if (isListening) "Ouvindo" else if (isSpeaking) "Microfone pausado (IA falando)" else "Falar",
            tint = when {
                isListening -> Color.White
                isSpeaking -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun ChatBubble(
    message: MessageEntity,
    playbackState: TtsPlaybackState,
    onPlayTts: () -> Unit
) {
    val isUser = message.sender == "user"
    val isSpeakingThis = playbackState is TtsPlaybackState.Speaking &&
        playbackState.utteranceId == message.id.toString()

    val parsedFeedback = remember(message.translation) {
        if (!isUser && message.translation != null) {
            TutorResponseParser.parse(message.translation)
        } else null
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(
                    if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .padding(12.dp)
        ) {
            // Block 1: <reply>
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                IconButton(
                    onClick = onPlayTts,
                    modifier = Modifier
                        .size(32.dp)
                        .testTag("tts_button_${message.id}")
                ) {
                    Icon(
                        imageVector = if (isSpeakingThis) Icons.Default.Stop else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = if (isSpeakingThis) "Parar áudio" else "Ouvir pronúncia",
                        tint = if (isUser) {
                            if (isSpeakingThis) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.onPrimary
                        } else {
                            if (isSpeakingThis) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Block 2: <fix> (Corrections badge)
            if (!isUser && parsedFeedback?.hasCorrections == true) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f))
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "Dica de Gramática / Correção",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = parsedFeedback.corrections ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }
                }
            }

            // Block 3: <pron> (Pronunciation badge)
            if (!isUser && parsedFeedback?.hasPronunciationTip == true) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "Dica de Pronúncia",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = parsedFeedback.pronunciationTip ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingChatBubble(
    feedback: TutorFeedback,
    rawText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.88f)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 16.dp
                    )
                )
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (feedback.reply.isNotBlank()) feedback.reply else "Pensando na resposta...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (feedback.hasCorrections) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f))
                        .padding(8.dp)
                ) {
                    Text(
                        text = "Correção: ${feedback.corrections}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp
                    )
                }
            }

            if (feedback.hasPronunciationTip) {
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f))
                        .padding(8.dp)
                ) {
                    Text(
                        text = "Pronúncia: ${feedback.pronunciationTip}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}
