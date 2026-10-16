package com.linguaflow.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.linguaflow.app.data.model.CourseTrack
import com.linguaflow.app.data.model.Exercise
import com.linguaflow.app.data.repository.LinguaflowRepository
import com.linguaflow.app.engine.SpeechToTextEngine
import com.linguaflow.app.engine.TextToSpeechEngine
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DailyChallengeScreen(
    repository: LinguaflowRepository,
    ttsEngine: TextToSpeechEngine? = null,
    sttEngine: SpeechToTextEngine? = null,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val progress by repository.getProgress().collectAsStateWithLifecycle(initialValue = null)

    val challengeSets = CourseTrack.challengeSets
    var currentSetIndex by remember { mutableIntStateOf(0) }
    val currentSet = challengeSets.getOrElse(currentSetIndex % challengeSets.size) { challengeSets.first() }

    var currentExerciseIndex by remember { mutableIntStateOf(0) }
    var setCompleted by remember { mutableStateOf(false) }

    // Exercise 1: Multiple choice state
    var selectedOptionIndex by remember { mutableStateOf<Int?>(null) }
    var isMultipleChoiceSubmitted by remember { mutableStateOf(false) }

    // Exercise 2: Sentence builder state
    val availableWords = remember(currentSetIndex, currentExerciseIndex) {
        mutableStateListOf<String>()
    }
    val selectedWords = remember(currentSetIndex, currentExerciseIndex) {
        mutableStateListOf<String>()
    }
    var isSentenceBuilderSubmitted by remember { mutableStateOf(false) }
    var isSentenceCorrect by remember { mutableStateOf(false) }

    // Exercise 3: Speech/Listening state
    var isListeningPracticed by remember { mutableStateOf(false) }
    var userSpokenText by remember { mutableStateOf("") }
    var isSttListening by remember { mutableStateOf(false) }

    // Initialize sentence builder word banks
    val currentExercise = currentSet.exercises.getOrNull(currentExerciseIndex)
    if (currentExercise is Exercise.SentenceBuilder && availableWords.isEmpty() && selectedWords.isEmpty() && !isSentenceBuilderSubmitted) {
        availableWords.addAll(currentExercise.scrambledWords)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && sttEngine != null) {
            isSttListening = true
            sttEngine.startListening(
                onPartial = { userSpokenText = it },
                onFinal = { text, _ ->
                    userSpokenText = text
                    isSttListening = false
                    isListeningPracticed = true
                },
                onError = { err ->
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                    isSttListening = false
                }
            )
        } else {
            Toast.makeText(context, "Permissão necessária para prática de fala.", Toast.LENGTH_SHORT).show()
        }
    }

    androidx.activity.compose.BackHandler {
        ttsEngine?.stop()
        sttEngine?.stopListening()
        onBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            ttsEngine?.stop()
            sttEngine?.stopListening()
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
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Desafio Diário",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${currentSet.category} • Prática Ilimitada",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("challenge_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                },
                actions = {
                    Box(
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AllInclusive,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = "Sem Limites",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                // Streak & Stats Header Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.LocalFireDepartment,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Column {
                                Text(
                                    text = "${progress?.streakDays ?: 1} Dias",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Ofensiva Ativa",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.size(8.dp))
                            Column {
                                Text(
                                    text = "+${currentSet.xpReward} XP",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Recompensa",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                if (setCompleted) {
                    // Celebration & Completed State
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.EmojiEvents,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            Spacer(Modifier.height(16.dp))

                            Text(
                                text = "Desafio Concluído com Sucesso!",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )

                            Spacer(Modifier.height(6.dp))

                            Text(
                                text = "Você ganhou +${currentSet.xpReward} XP e registrou seu progresso no Room Database.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                            )

                            Spacer(Modifier.height(24.dp))

                            Button(
                                onClick = {
                                    // Switch to next unlimited challenge set
                                    currentSetIndex++
                                    currentExerciseIndex = 0
                                    setCompleted = false
                                    selectedOptionIndex = null
                                    isMultipleChoiceSubmitted = false
                                    selectedWords.clear()
                                    availableWords.clear()
                                    isSentenceBuilderSubmitted = false
                                    isListeningPracticed = false
                                    userSpokenText = ""
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                                    .testTag("next_challenge_button"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(imageVector = Icons.Default.Replay, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Fazer Outro Desafio (Prática Ilimitada)")
                            }

                            Spacer(Modifier.height(10.dp))

                            OutlinedButton(
                                onClick = onBack,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Voltar à Tela Inicial")
                            }
                        }
                    }
                } else {
                    // Progress Indicator across exercises
                    val stepFraction = (currentExerciseIndex.toFloat() / currentSet.exercises.size.toFloat()).coerceIn(0f, 1f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Exercício ${currentExerciseIndex + 1} de ${currentSet.exercises.size}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = currentSet.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    LinearProgressIndicator(
                        progress = { stepFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                    )

                    Spacer(Modifier.height(20.dp))

                    // Exercise Body
                    when (currentExercise) {
                        is Exercise.MultipleChoice -> {
                            MultipleChoiceCard(
                                exercise = currentExercise,
                                selectedIndex = selectedOptionIndex,
                                isSubmitted = isMultipleChoiceSubmitted,
                                onSelectOption = { idx ->
                                    if (!isMultipleChoiceSubmitted) {
                                        selectedOptionIndex = idx
                                    }
                                },
                                onSubmit = {
                                    isMultipleChoiceSubmitted = true
                                }
                            )
                        }

                        is Exercise.SentenceBuilder -> {
                            SentenceBuilderCard(
                                exercise = currentExercise,
                                availableWords = availableWords,
                                selectedWords = selectedWords,
                                isSubmitted = isSentenceBuilderSubmitted,
                                isCorrect = isSentenceCorrect,
                                onSelectWord = { word ->
                                    if (!isSentenceBuilderSubmitted) {
                                        availableWords.remove(word)
                                        selectedWords.add(word)
                                    }
                                },
                                onUnselectWord = { word ->
                                    if (!isSentenceBuilderSubmitted) {
                                        selectedWords.remove(word)
                                        availableWords.add(word)
                                    }
                                },
                                onCheck = {
                                    val formed = selectedWords.joinToString(" ").trim()
                                    val targetNormalized = currentExercise.targetSentence.replace("[?.!]", "").trim().lowercase()
                                    val formedNormalized = formed.replace("[?.!]", "").trim().lowercase()
                                    isSentenceCorrect = targetNormalized == formedNormalized
                                    isSentenceBuilderSubmitted = true
                                }
                            )
                        }

                        is Exercise.ListeningSpeech -> {
                            ListeningSpeechCard(
                                exercise = currentExercise,
                                ttsEngine = ttsEngine,
                                isListeningPracticed = isListeningPracticed,
                                isSttListening = isSttListening,
                                userSpokenText = userSpokenText,
                                onPlayAudio = {
                                    ttsEngine?.speak(currentExercise.englishSentence, utteranceId = "challenge_speech")
                                    isListeningPracticed = true
                                },
                                onStartSpeaking = {
                                    val hasPermission = ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (hasPermission) {
                                        isSttListening = true
                                        sttEngine?.startListening(
                                            onPartial = { userSpokenText = it },
                                            onFinal = { text, _ ->
                                                userSpokenText = text
                                                isSttListening = false
                                                isListeningPracticed = true
                                            },
                                            onError = {
                                                isSttListening = false
                                                isListeningPracticed = true
                                            }
                                        )
                                    } else {
                                        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    }
                                }
                            )
                        }

                        null -> {}
                    }

                    Spacer(Modifier.height(24.dp))

                    // Next / Finish Exercise Button
                    val canProceed = when (currentExercise) {
                        is Exercise.MultipleChoice -> isMultipleChoiceSubmitted
                        is Exercise.SentenceBuilder -> isSentenceBuilderSubmitted
                        is Exercise.ListeningSpeech -> isListeningPracticed
                        null -> false
                    }

                    val isLastExercise = currentExerciseIndex >= currentSet.exercises.size - 1

                    Button(
                        onClick = {
                            if (isLastExercise) {
                                scope.launch {
                                    repository.completeDailyChallenge(currentSet.xpReward, 15)
                                    setCompleted = true
                                }
                            } else {
                                currentExerciseIndex++
                                selectedOptionIndex = null
                                isMultipleChoiceSubmitted = false
                                selectedWords.clear()
                                availableWords.clear()
                                isSentenceBuilderSubmitted = false
                                isSentenceCorrect = false
                                isListeningPracticed = false
                                userSpokenText = ""
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("complete_challenge_button"),
                        enabled = canProceed
                    ) {
                        Icon(
                            imageVector = if (isLastExercise) Icons.Default.CheckCircle else Icons.Default.NavigateNext,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = if (isLastExercise) "Concluir Desafio (+${currentSet.xpReward} XP)" else "Próximo Exercício"
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MultipleChoiceCard(
    exercise: Exercise.MultipleChoice,
    selectedIndex: Int?,
    isSubmitted: Boolean,
    onSelectOption: (Int) -> Unit,
    onSubmit: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = exercise.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = exercise.question,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))

            exercise.options.forEachIndexed { index, option ->
                val isSelected = selectedIndex == index
                val isCorrect = index == exercise.correctIndex

                val borderColor = when {
                    isSubmitted && isCorrect -> Color(0xFF4CAF50)
                    isSubmitted && isSelected && !isCorrect -> MaterialTheme.colorScheme.error
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outlineVariant
                }

                val containerColor = when {
                    isSubmitted && isCorrect -> Color(0xFFE8F5E9)
                    isSubmitted && isSelected && !isCorrect -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    else -> MaterialTheme.colorScheme.surface
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(containerColor)
                        .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
                        .clickable(enabled = !isSubmitted) { onSelectOption(index) }
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = ('A' + index).toString(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (!isSubmitted && selectedIndex != null) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = onSubmit,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Verificar Resposta")
                }
            }

            if (isSubmitted) {
                Spacer(Modifier.height(12.dp))
                val wasCorrect = selectedIndex == exercise.correctIndex
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (wasCorrect) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        Icon(
                            imageVector = if (wasCorrect) Icons.Default.CheckCircle else Icons.Default.HelpOutline,
                            contentDescription = null,
                            tint = if (wasCorrect) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (wasCorrect) "Excelente! Resposta correta." else "Atenção:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (wasCorrect) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = exercise.explanation,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SentenceBuilderCard(
    exercise: Exercise.SentenceBuilder,
    availableWords: List<String>,
    selectedWords: List<String>,
    isSubmitted: Boolean,
    isCorrect: Boolean,
    onSelectWord: (String) -> Unit,
    onUnselectWord: (String) -> Unit,
    onCheck: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = exercise.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(6.dp))

            Text(
                text = exercise.promptPt,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(14.dp))

            Text(
                text = "Sua frase montada:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(6.dp))

            // Drop zone / formed sentence area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                if (selectedWords.isEmpty()) {
                    Text(
                        text = "Toque nas palavras abaixo para ordenar a frase...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedWords.forEach { word ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .clickable(enabled = !isSubmitted) { onUnselectWord(word) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = word,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "Banco de Palavras:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(6.dp))

            // Available word chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableWords.forEach { word ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp))
                            .clickable(enabled = !isSubmitted) { onSelectWord(word) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = word,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (!isSubmitted && selectedWords.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onCheck,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Verificar Frase")
                }
            }

            if (isSubmitted) {
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isCorrect) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isCorrect) Icons.Default.CheckCircle else Icons.Default.Close,
                                contentDescription = null,
                                tint = if (isCorrect) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = if (isCorrect) "Correto! Muito bem." else "Frase esperada:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (isCorrect) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                            )
                        }
                        if (!isCorrect) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "\"${exercise.targetSentence}\"",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = exercise.explanation,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ListeningSpeechCard(
    exercise: Exercise.ListeningSpeech,
    ttsEngine: TextToSpeechEngine?,
    isListeningPracticed: Boolean,
    isSttListening: Boolean,
    userSpokenText: String,
    onPlayAudio: () -> Unit,
    onStartSpeaking: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = exercise.title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "Ouça com atenção a frase em inglês e repita em voz alta:",
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(Modifier.height(14.dp))

            // Audio Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "\"${exercise.englishSentence}\"",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = exercise.phoneticGuide,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = exercise.translationPt,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(
                        onClick = onPlayAudio,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Ouvir áudio",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Pronunciation practice button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onStartSpeaking,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSttListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(imageVector = Icons.Default.Mic, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isSttListening) "Escutando..." else "Falar no Microfone")
                }
            }

            if (userSpokenText.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp)
                ) {
                    Text(
                        text = "Voz detectada: \"$userSpokenText\"",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Tip Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(10.dp)
            ) {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(
                        imageVector = Icons.Default.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Dica: ${exercise.tip}",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
