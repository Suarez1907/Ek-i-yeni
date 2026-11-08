package com.example.ui.sheets

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.GeminiAnalysisMode
import com.example.ai.GeminiStreamState
import com.example.ui.theme.EksiDeepGreen
import com.example.ui.theme.EksiNeonGreen
import com.example.ui.theme.OledDivider
import com.example.ui.theme.OledOnAccentColor
import com.example.ui.theme.OledTextMuted
import com.example.ui.theme.OledTextPrimary
import com.example.ui.theme.OledTextSecondary

/**
 * `✨ AI Özet` çipine basıldığında açılan ve başlığın:
 * a) "Başlığı 5 Maddede Özetle"
 * b) "Fikir Ayrılıklarını ve Baskın Görüşü Çıkar"
 * analizlerini doğrudan OkHttp SSE (`:streamGenerateContent?alt=sse`) ile `gemini-3.5-flash-lite`
 * (ve otomatik fallback zinciri) üzerinden gösteren alt pencere.
 *
 * API anahtarı cihazda Android Keystore (`AES/GCM/NoPadding`) ile şifrelenerek saklanır ve
 * yalnızca `x-goog-api-key` HTTP header'ı üzerinden gönderilir.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiSummaryBottomSheet(
    topicTitle: String,
    streamState: GeminiStreamState,
    hasSavedApiKey: Boolean = true,
    savedApiKey: String = "",
    keyDecryptionWarning: String? = null,
    onSelectMode: (GeminiAnalysisMode) -> Unit,
    onSaveApiKeyAndSummarize: (String, GeminiAnalysisMode) -> Unit = { _, _ -> },
    onDismiss: () -> Unit
) {
    if (streamState is GeminiStreamState.Idle) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusManager = LocalFocusManager.current

    val activeMode = when (streamState) {
        is GeminiStreamState.AwaitingApiKey -> streamState.mode
        is GeminiStreamState.Loading -> streamState.mode
        is GeminiStreamState.Streaming -> streamState.mode
        is GeminiStreamState.Completed -> streamState.mode
        is GeminiStreamState.Error -> streamState.mode
        GeminiStreamState.Idle -> GeminiAnalysisMode.FIVE_BULLET_SUMMARY
    }

    var apiKeyInput by rememberSaveable(savedApiKey) { mutableStateOf(savedApiKey) }
    var showApiKeyPassword by remember { mutableStateOf(false) }
    var manualShowKeyEditor by remember { mutableStateOf(false) }

    val isAwaitingApiKey = streamState is GeminiStreamState.AwaitingApiKey ||
        !hasSavedApiKey ||
        savedApiKey.isBlank()

    val shouldShowKeyInputSection = isAwaitingApiKey || manualShowKeyEditor

    LaunchedEffect(savedApiKey) {
        if (savedApiKey.isNotBlank() && apiKeyInput.isBlank()) {
            apiKeyInput = savedApiKey
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 260.dp, max = 680.dp)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoAwesome,
                        contentDescription = null,
                        tint = EksiNeonGreen,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Gemini 3.5 Flash-Lite Canlı Özet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = EksiNeonGreen
                        )
                        Text(
                            text = topicTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = OledTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasSavedApiKey && savedApiKey.isNotBlank()) {
                        IconButton(
                            onClick = { manualShowKeyEditor = !manualShowKeyEditor },
                            modifier = Modifier.testTag("gemini_sheet_toggle_key_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Key,
                                contentDescription = "API Anahtarı Ayarları",
                                tint = EksiNeonGreen
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("gemini_sheet_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Kapat",
                            tint = OledTextSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = activeMode == GeminiAnalysisMode.FIVE_BULLET_SUMMARY,
                    onClick = { onSelectMode(GeminiAnalysisMode.FIVE_BULLET_SUMMARY) },
                    label = { Text("5 Maddede Özetle", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = EksiNeonGreen,
                        selectedLabelColor = OledOnAccentColor,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = OledTextPrimary
                    ),
                    modifier = Modifier.testTag("gemini_mode_five_bullets")
                )

                FilterChip(
                    selected = activeMode == GeminiAnalysisMode.DEBATE_AND_CONSENSUS,
                    onClick = { onSelectMode(GeminiAnalysisMode.DEBATE_AND_CONSENSUS) },
                    label = { Text("Fikir Ayrılıkları & Baskın Görüş", fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = EksiNeonGreen,
                        selectedLabelColor = OledOnAccentColor,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = OledTextPrimary
                    ),
                    modifier = Modifier.testTag("gemini_mode_debate_consensus")
                )
            }

            // Doğrudan BottomSheet içinde Google AI Studio API Anahtarı girişi ve "Kaydet ve Özetle" butonu
            AnimatedVisibility(visible = shouldShowKeyInputSection) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .border(1.dp, EksiNeonGreen.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!keyDecryptionWarning.isNullOrBlank()) {
                            Text(
                                text = keyDecryptionWarning,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Text(
                            text = "Ücretsiz Google AI Studio API anahtarınızı girerek Gemini 3.5 Flash-Lite ile canlı SSE özetlemeyi başlatın. Anahtarınız cihazınızda Android Keystore (AES-GCM) ile şifrelenerek saklanır.",
                            style = MaterialTheme.typography.labelSmall,
                            color = OledTextSecondary
                        )

                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it },
                            label = { Text("Google AI Studio API Anahtarınızı Girin") },
                            placeholder = {
                                Text(
                                    text = "AIzaSy...",
                                    color = OledTextMuted
                                )
                            },
                            singleLine = true,
                            visualTransformation = if (showApiKeyPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { showApiKeyPassword = !showApiKeyPassword }) {
                                    Icon(
                                        imageVector = if (showApiKeyPassword) {
                                            Icons.Default.VisibilityOff
                                        } else {
                                            Icons.Default.Visibility
                                        },
                                        contentDescription = if (showApiKeyPassword) {
                                            "Anahtarı Gizle"
                                        } else {
                                            "Anahtarı Göster"
                                        },
                                        tint = OledTextSecondary
                                    )
                                }
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = EksiNeonGreen,
                                unfocusedBorderColor = OledDivider,
                                focusedLabelColor = EksiNeonGreen,
                                unfocusedLabelColor = OledTextSecondary,
                                focusedTextColor = OledTextPrimary,
                                unfocusedTextColor = OledTextPrimary,
                                cursorColor = EksiNeonGreen
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("gemini_sheet_api_key_input")
                        )

                        Button(
                            onClick = {
                                val trimmedKey = apiKeyInput.trim()
                                if (trimmedKey.isNotEmpty()) {
                                    focusManager.clearFocus()
                                    manualShowKeyEditor = false
                                    onSaveApiKeyAndSummarize(trimmedKey, activeMode)
                                }
                            },
                            enabled = apiKeyInput.trim().isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EksiNeonGreen,
                                contentColor = OledOnAccentColor
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("gemini_sheet_save_and_summarize_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Kaydet ve Özetle",
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            if (streamState !is GeminiStreamState.AwaitingApiKey) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = OledDivider, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .border(0.5.dp, OledDivider, RoundedCornerShape(14.dp))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        when (streamState) {
                            is GeminiStreamState.Loading -> {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.padding(vertical = 24.dp)
                                ) {
                                    CircularProgressIndicator(
                                        color = EksiNeonGreen,
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.5.dp
                                    )
                                    Text(
                                        text = "Entry'ler taranıyor, Gemini 3.5 Flash-Lite SSE akışı başlatılıyor...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = OledTextSecondary
                                    )
                                }
                            }

                            is GeminiStreamState.Streaming -> {
                                Surface(
                                    color = EksiDeepGreen,
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier.padding(bottom = 10.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            color = EksiNeonGreen,
                                            modifier = Modifier.size(12.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "Gemini 3.5 Flash-Lite Canlı SSE Akışı...",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = EksiNeonGreen,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    text = streamState.partialText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OledTextPrimary,
                                    lineHeight = 23.sp
                                )
                            }

                            is GeminiStreamState.Completed -> {
                                Text(
                                    text = streamState.fullText,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = OledTextPrimary,
                                    lineHeight = 23.sp
                                )
                            }

                            is GeminiStreamState.Error -> {
                                Text(
                                    text = streamState.message,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }

                            is GeminiStreamState.AwaitingApiKey,
                            GeminiStreamState.Idle -> Unit
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
