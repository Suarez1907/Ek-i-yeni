package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ui.theme.ActiveThemeState
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.EksiNeonGreen
import com.example.ui.theme.OledCardSurface
import com.example.ui.theme.OledHairlineDivider
import com.example.ui.theme.OledOnAccentColor
import com.example.ui.theme.OledPillGreenBg
import com.example.ui.theme.OledPitchBlack
import com.example.ui.theme.OledTextPrimary
import com.example.ui.theme.OledTextSecondary

/**
 * Aktif temaya (OLED Siyah, Koyu Gri, Klasik Beyaz) duyarlı Skeleton Shimmer fırçası.
 */
@Composable
fun rememberOledShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "oled_skeleton_shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = -300f,
        targetValue = 1200f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1250, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )

    val shimmerColors = when (ActiveThemeState.currentMode) {
        AppThemeMode.OLED_BLACK -> listOf(
            Color(0xFF0E0E0E),
            Color(0xFF1A221B),
            Color(0xFF243026),
            Color(0xFF1A221B),
            Color(0xFF0E0E0E)
        )
        AppThemeMode.DARK_GRAY -> listOf(
            Color(0xFF1A1A1A),
            Color(0xFF263027),
            Color(0xFF324034),
            Color(0xFF263027),
            Color(0xFF1A1A1A)
        )
        AppThemeMode.CLASSIC_LIGHT -> listOf(
            Color(0xFFECEFEC),
            Color(0xFFDCE7DD),
            Color(0xFFCDE0CF),
            Color(0xFFDCE7DD),
            Color(0xFFECEFEC)
        )
    }

    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(x = translateAnim - 250f, y = 0f),
        end = Offset(x = translateAnim + 250f, y = 180f)
    )
}

/**
 * Sol Frame (Gündem / Bugün / Kanallar / DEBE / Olaylar / Arama) başlık listesi yüklenirken
 * dönen çember (spinner) yerine gerçek başlık ve hap (pill badge) boyutlarında gösterilen
 * Skeleton Shimmer yükleme animasyonu.
 */
@Composable
fun TopicListSkeletonShimmer(
    itemCount: Int = 11,
    modifier: Modifier = Modifier
) {
    val brush = rememberOledShimmerBrush()
    val widthFractions = remember {
        listOf(0.76f, 0.58f, 0.84f, 0.64f, 0.72f, 0.52f, 0.80f, 0.68f, 0.60f, 0.75f, 0.62f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .testTag("topic_skeleton_shimmer")
    ) {
        repeat(itemCount) { idx ->
            val fraction = widthFractions[idx % widthFractions.size]
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(15.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(brush)
                    )
                    if (idx % 3 == 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.38f)
                                .height(10.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(brush)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(42.dp)
                        .height(24.dp)
                        .clip(RoundedCornerShape(50))
                        .background(brush)
                )
            }
            HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)
        }
    }
}

/**
 * Başlık içi entry listesi veya (bkz:) alt penceresi yüklenirken gerçek entry boyutlarında
 * gösterilen Skeleton Shimmer yükleme animasyonu.
 */
@Composable
fun EntryListSkeletonShimmer(
    cardCount: Int = 4,
    modifier: Modifier = Modifier
) {
    val brush = rememberOledShimmerBrush()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("entry_skeleton_shimmer"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(cardCount) { idx ->
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, OledHairlineDivider, RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.95f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(brush)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.88f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(brush)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(if (idx % 2 == 0) 0.72f else 0.54f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(brush)
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(64.dp)
                                .height(26.dp)
                                .clip(RoundedCornerShape(50))
                                .background(brush)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(brush)
                            )
                            Column(
                                horizontalAlignment = Alignment.End,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(84.dp)
                                        .height(12.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(brush)
                                )
                                Box(
                                    modifier = Modifier
                                        .width(60.dp)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(brush)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Yazarın baş harfinden oluşan mini dairesel avatar (veya `avatarUrl` varsa görsel).
 */
@Composable
fun AuthorMiniAvatar(
    authorName: String,
    avatarUrl: String? = null,
    size: Dp = 26.dp,
    modifier: Modifier = Modifier
) {
    val cleanInitial = remember(authorName) {
        authorName.trim().removePrefix("@").firstOrNull()?.uppercaseChar()?.toString() ?: "E"
    }

    if (!avatarUrl.isNullOrBlank()) {
        AsyncImage(
            model = avatarUrl,
            contentDescription = authorName,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .border(0.75.dp, EksiNeonGreen.copy(alpha = 0.45f), CircleShape)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(OledPillGreenBg)
                .border(0.75.dp, EksiNeonGreen.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = cleanInitial,
                color = EksiNeonGreen,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

/**
 * Başparmak Hizasında Yüzen Sayfalama Çubuğu (Floating Pager Bar).
 */
@Composable
fun FloatingPagerCapsuleBar(
    currentPage: Int,
    totalPages: Int,
    onSelectPage: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val safeTotal = totalPages.coerceAtLeast(1)
    val safeCurrent = currentPage.coerceIn(1, safeTotal)
    var showPagePickerDialog by remember { mutableStateOf(false) }

    if (showPagePickerDialog) {
        PagePickerDialog(
            currentPage = safeCurrent,
            totalPages = safeTotal,
            onDismiss = { showPagePickerDialog = false },
            onConfirmPage = { chosenPage ->
                showPagePickerDialog = false
                onSelectPage(chosenPage.coerceIn(1, safeTotal))
            }
        )
    }

    val isLight = ActiveThemeState.currentMode == AppThemeMode.CLASSIC_LIGHT
    val frostedGlassBrush = if (isLight) {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xF5FFFFFF),
                Color(0xF5F2F6F2)
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                Color(0xEB131915),
                Color(0xF20A0E0B)
            )
        )
    }

    Box(
        modifier = modifier
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(50),
                ambientColor = Color.Black.copy(alpha = if (isLight) 0.15f else 0.9f),
                spotColor = EksiNeonGreen.copy(alpha = 0.25f)
            )
            .clip(RoundedCornerShape(50))
            .background(brush = frostedGlassBrush)
            .border(
                width = 0.8.dp,
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        OledHairlineDivider,
                        EksiNeonGreen.copy(alpha = 0.45f),
                        OledHairlineDivider
                    )
                ),
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .testTag("floating_pager_bar")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            PagerCapsuleButton(
                label = "İlk (1)",
                enabled = safeCurrent > 1,
                onClick = { onSelectPage(1) },
                testTag = "pager_first_button"
            )

            PagerCapsuleButton(
                label = "< Önceki",
                enabled = safeCurrent > 1,
                onClick = { onSelectPage((safeCurrent - 1).coerceAtLeast(1)) },
                testTag = "pager_prev_button"
            )

            Surface(
                color = OledPillGreenBg,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { showPagePickerDialog = true }
                    .border(0.6.dp, EksiNeonGreen.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .testTag("pager_center_selector")
            ) {
                Text(
                    text = "Sayfa $safeCurrent / $safeTotal",
                    color = EksiNeonGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                )
            }

            PagerCapsuleButton(
                label = "Sonraki >",
                enabled = safeCurrent < safeTotal,
                onClick = { onSelectPage((safeCurrent + 1).coerceAtMost(safeTotal)) },
                testTag = "pager_next_button"
            )

            PagerCapsuleButton(
                label = "Son ($safeTotal)",
                enabled = safeCurrent < safeTotal,
                onClick = { onSelectPage(safeTotal) },
                testTag = "pager_last_button"
            )
        }
    }
}

@Composable
private fun PagerCapsuleButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val textColor = if (enabled) OledTextPrimary else OledTextSecondary.copy(alpha = 0.4f)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 7.dp)
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = if (enabled) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PagePickerDialog(
    currentPage: Int,
    totalPages: Int,
    onDismiss: () -> Unit,
    onConfirmPage: (Int) -> Unit
) {
    var pageInput by remember(currentPage) { mutableStateOf(currentPage.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Text(
                text = "Sayfaya Git (1 - $totalPages)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = pageInput,
                    onValueChange = { value ->
                        pageInput = value.filter { it.isDigit() }
                    },
                    label = { Text("Sayfa Numarası") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Go
                    ),
                    keyboardActions = KeyboardActions(
                        onGo = {
                            val parsed = pageInput.toIntOrNull() ?: currentPage
                            onConfirmPage(parsed.coerceIn(1, totalPages))
                        }
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EksiNeonGreen,
                        unfocusedBorderColor = OledHairlineDivider,
                        focusedContainerColor = MaterialTheme.colorScheme.background,
                        unfocusedContainerColor = MaterialTheme.colorScheme.background,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Hızlı Seçim:",
                    style = MaterialTheme.typography.labelSmall,
                    color = OledTextSecondary
                )

                val quickPages = remember(totalPages, currentPage) {
                    buildSet {
                        add(1)
                        if (currentPage > 2) add(currentPage - 1)
                        add(currentPage)
                        if (currentPage < totalPages) add(currentPage + 1)
                        if (totalPages >= 5) add((totalPages / 2).coerceAtLeast(2))
                        add(totalPages)
                    }.sorted()
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    quickPages.forEach { p ->
                        val isCurrent = p == currentPage
                        Surface(
                            color = if (isCurrent) EksiNeonGreen else OledPillGreenBg,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable { onConfirmPage(p) }
                        ) {
                            Text(
                                text = "S. $p",
                                color = if (isCurrent) OledOnAccentColor else EksiNeonGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = pageInput.toIntOrNull() ?: currentPage
                    onConfirmPage(parsed.coerceIn(1, totalPages))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = EksiNeonGreen,
                    contentColor = OledOnAccentColor
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("Git", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal", color = OledTextSecondary)
            }
        }
    )
}

/**
 * 4. ARAYÜZ RENK / TEMA SEÇENEĞİ (BEYAZ, KOYU GRİ, OLED SİYAH) Diyalog Penceresi.
 */
@Composable
fun ThemeSelectorDialog(
    currentTheme: AppThemeMode,
    onSelectTheme: (AppThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Palette,
                    contentDescription = null,
                    tint = EksiNeonGreen
                )
                Text(
                    text = "Arayüz Teması Seçin",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AppThemeMode.entries.forEach { mode ->
                    val isSelected = currentTheme == mode
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onSelectTheme(mode) }
                            .border(
                                width = if (isSelected) 1.5.dp else 0.6.dp,
                                color = if (isSelected) EksiNeonGreen else OledHairlineDivider,
                                shape = RoundedCornerShape(14.dp)
                            )
                            .testTag("theme_option_${mode.storageKey}")
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                // Canlı renk paleti önizleme kutusu
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(mode.previewBgHex)
                                        .border(1.dp, mode.previewAccentHex, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(mode.previewAccentHex)
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = mode.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onBackground
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = mode.subtitle,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        color = OledTextSecondary
                                    )
                                }
                            }

                            Icon(
                                imageVector = if (isSelected) {
                                    Icons.Default.CheckCircle
                                } else {
                                    Icons.Default.RadioButtonUnchecked
                                },
                                contentDescription = null,
                                tint = if (isSelected) EksiNeonGreen else OledTextSecondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Kapat",
                    color = EksiNeonGreen,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    )
}
