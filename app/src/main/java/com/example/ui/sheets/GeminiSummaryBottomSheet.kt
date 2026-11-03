package com.example.ui.sheets

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.GeminiAnalysisMode
import com.example.ai.GeminiStreamState
import com.example.ui.theme.EksiDeepGreen
import com.example.ui.theme.EksiNeonGreen
import com.example.ui.theme.OledCardSurface
import com.example.ui.theme.OledDivider
import com.example.ui.theme.OledPitchBlack
import com.example.ui.theme.OledTextPrimary
import com.example.ui.theme.OledTextSecondary

/**
 * `✨ Gemini Özet` çipine basıldığında açılan ve başlığın:
 * a) "Başlığı 5 Maddede Özetle"
 * b) "Fikir Ayrılıklarını ve Baskın Görüşü Çıkar"
 * analizlerini canlı akan metin (`generateContentStream` streaming) olarak gösteren alt pencere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GeminiSummaryBottomSheet(
    topicTitle: String,
    streamState: GeminiStreamState,
    onSelectMode: (GeminiAnalysisMode) -> Unit,
    onDismiss: () -> Unit
) {
    if (streamState is GeminiStreamState.Idle) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activeMode = when (streamState) {
        is GeminiStreamState.Loading -> streamState.mode
        is GeminiStreamState.Streaming -> streamState.mode
        is GeminiStreamState.Completed -> streamState.mode
        is GeminiStreamState.Error -> streamState.mode
        GeminiStreamState.Idle -> GeminiAnalysisMode.FIVE_BULLET_SUMMARY
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = OledPitchBlack,
        contentColor = OledTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 640.dp)
                .background(OledPitchBlack)
                .padding(horizontal = 18.dp)
        ) {
            // Başlık Satırı
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
                            text = "✨ Gemini 2.5 Flash Canlı Özet",
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

            Spacer(modifier = Modifier.height(10.dp))

            // Analiz Modu Geçiş Çipleri (5 Madde Özet / Fikir Ayrılıkları & Baskın Görüş)
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
                        selectedLabelColor = Color.Black,
                        containerColor = OledCardSurface,
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
                        selectedLabelColor = Color.Black,
                        containerColor = OledCardSurface,
                        labelColor = OledTextPrimary
                    ),
                    modifier = Modifier.testTag("gemini_mode_debate_consensus")
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = OledDivider, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = OledCardSurface),
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
                                    text = "Entry'ler taranıyor, canlı metin akışı başlatılıyor...",
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
                                        text = "Canlı Akış (Streaming...)",
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

                        GeminiStreamState.Idle -> Unit
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
