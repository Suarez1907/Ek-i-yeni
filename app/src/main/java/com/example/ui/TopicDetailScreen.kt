package com.example.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PersonSearch
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import com.example.ai.GeminiAnalysisMode
import com.example.data.model.EntryItem
import com.example.data.model.TopicFilterMode
import com.example.data.model.TopicItem
import com.example.data.remote.EksiParser
import com.example.ui.components.AuthorMiniAvatar
import com.example.ui.components.EntryListSkeletonShimmer
import com.example.ui.components.FloatingPagerCapsuleBar
import com.example.ui.components.ThemeSelectorDialog
import com.example.ui.components.rememberOledShimmerBrush
import com.example.ui.sheets.BkzPreviewBottomSheet
import com.example.ui.sheets.GeminiSummaryBottomSheet
import com.example.ui.theme.ActiveThemeState
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.EksiDeepGreen
import com.example.ui.theme.EksiNeonGreen
import com.example.ui.theme.OledDivider
import com.example.ui.theme.OledElevatedCard
import com.example.ui.theme.OledHairlineDivider
import com.example.ui.theme.OledOnAccentColor
import com.example.ui.theme.OledPillGreenBg
import com.example.ui.theme.OledSwipeAuthorBlue
import com.example.ui.theme.OledTextMuted
import com.example.ui.theme.OledTextPrimary
import com.example.ui.theme.OledTextSecondary
import com.example.ui.theme.OledTopicUnread
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Başlık İçi Entry Okuma Ekranı (`TopicDetailScreen.kt`):
 * 1. Direct Page State (`currentPage`, `totalPages`, `topicPageUiState`):
 *    Paging 3'ün otomatik `prevKey` / sonsuz geri sarma döngüsü yerine kullanıcının seçtiği
 *    sayfayı (`?p=X`) doğrudan ekranda gösterir ve sadece `X + 1` sayfasını sessizce önbelleğe alır.
 * 2. `LaunchedEffect` Sıfırlama Koruması:
 *    Sadece `topic.id` / `topic.url` ilk kez açıldığında 1 kez çalışır; sayfa değişiminde veya
 *    recomposition sırasında sayfayı 1'e sıfırlamaz.
 * 3. Room `ReadState` Çakışma Koruması:
 *    Kalınan yer bilgisi yalnızca ilk açılışta tek seferlik uygulanır ve temizlenir.
 * 4. Sayfa Değişiminde Liste Konumu:
 *    Yeni sayfa (`goToPage(targetPage)`) verisi yüklendikten sonra `listState.scrollToItem(0)`
 *    çağrılarak liste en başa sarılır.
 */
@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun TopicDetailScreen(
    topic: TopicItem,
    viewModel: EksinViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val pageUiState by viewModel.topicPageUiState.collectAsStateWithLifecycle()
    val currentPage by viewModel.currentPage.collectAsStateWithLifecycle()
    val totalPages by viewModel.totalPages.collectAsStateWithLifecycle()
    val metadata by viewModel.activeTopicMetadata.collectAsStateWithLifecycle()
    val restoredReadState by viewModel.restoredReadState.collectAsStateWithLifecycle()
    val activeFilterMode by viewModel.topicFilterMode.collectAsStateWithLifecycle()
    val inTopicQuery by viewModel.inTopicSearchQuery.collectAsStateWithLifecycle()
    val authorFilter by viewModel.authorFilterInTopic.collectAsStateWithLifecycle()
    val favoritedIds by viewModel.favoritedEntryIds.collectAsStateWithLifecycle()
    val bkzPreviewState by viewModel.bkzPreviewState.collectAsStateWithLifecycle()
    val geminiStreamState by viewModel.geminiStreamState.collectAsStateWithLifecycle()
    val geminiApiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val isGeminiKeyConfigured by viewModel.isGeminiKeyConfigured.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val showThemeDialog by viewModel.showThemeDialog.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val listState = rememberLazyListState()
    val isHeaderVisible by rememberDetailHeaderVisibility(listState)

    val activeTitle = metadata?.topicTitle?.takeIf { it.isNotBlank() } ?: topic.title
    val safeCurrentPage = currentPage.coerceAtLeast(1)
    val safeTotalPages = totalPages.coerceAtLeast(safeCurrentPage)
    val isSingleEntryOrFiltered = activeFilterMode != TopicFilterMode.ALL ||
        metadata?.isSingleEntryView == true ||
        topic.url.contains("/entry/", ignoreCase = true)

    val currentEntries: List<EntryItem> = remember(pageUiState, metadata, authorFilter) {
        val rawEntries = when (val state = pageUiState) {
            is TopicPageUiState.Success -> state.entries
            else -> metadata?.entries.orEmpty()
        }
        if (authorFilter.isNullOrBlank()) {
            rawEntries
        } else {
            rawEntries.filter { it.author.equals(authorFilter, ignoreCase = true) }
        }
    }

    if (showThemeDialog) {
        ThemeSelectorDialog(
            currentTheme = themeMode,
            onSelectTheme = { viewModel.selectThemeMode(it) },
            onDismiss = { viewModel.dismissThemeDialog() }
        )
    }

    // 2. KURAL: Sadece topic ilk açıldığında 1 kez kontrol et; recomposition veya sayfa değişiminde
    // sayfayı asla 1'e sıfırlama.
    LaunchedEffect(topic.id, topic.url) {
        viewModel.ensureTopicInitializedOnce(topic)
    }

    // 4. KURAL: Yeni sayfa verisi yüklendikten (`loadSequence` değiştiğinde) sonra:
    // - İlk açılışta kaydedilmiş okuma konumu varsa bir kez o entry'ye kaydır ve temizle.
    // - Kullanıcı sayfa değiştirdiyse (`goToPage`) listeyi en üste (`scrollToItem(0)`) sar.
    val latestLoadSequence = (pageUiState as? TopicPageUiState.Success)?.loadSequence ?: 0
    LaunchedEffect(latestLoadSequence) {
        if (latestLoadSequence <= 0) return@LaunchedEffect
        val saved = restoredReadState
        if (saved != null && currentEntries.isNotEmpty()) {
            val headerOffset = if (isSingleEntryOrFiltered) 1 else 0
            val matchedEntryIdx = currentEntries.indexOfFirst { it.id == saved.entryId }
            val targetLazyIndex = if (matchedEntryIdx >= 0) {
                matchedEntryIdx + headerOffset
            } else {
                saved.scrollIndex.coerceIn(0, (currentEntries.size - 1 + headerOffset).coerceAtLeast(0))
            }
            listState.scrollToItem(
                index = targetLazyIndex,
                scrollOffset = saved.scrollOffset.coerceAtLeast(0)
            )
            viewModel.markScrollPositionRestored()
        } else {
            listState.scrollToItem(0)
        }
    }

    // 3. KURAL: Kullanıcı okurken kalınan yeri Room'a sessizce kaydet (Room Flow dinlenmez, state ezilmez).
    LaunchedEffect(listState, currentEntries, isSingleEntryOrFiltered) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .debounce(400L)
            .collect { (firstIndex, offset) ->
                if (currentEntries.isEmpty()) return@collect
                val headerOffset = if (isSingleEntryOrFiltered) 1 else 0
                val entryIdx = (firstIndex - headerOffset).coerceIn(0, currentEntries.lastIndex)
                val visibleEntry = currentEntries.getOrNull(entryIdx)
                if (visibleEntry != null) {
                    viewModel.saveCurrentScrollPosition(
                        entry = visibleEntry,
                        scrollIndex = entryIdx,
                        scrollOffset = offset
                    )
                }
            }
    }

    BkzPreviewBottomSheet(
        state = bkzPreviewState,
        onDismiss = { viewModel.dismissBkzPreview() },
        onOpenFullTopic = { targetTopic ->
            viewModel.dismissBkzPreview()
            viewModel.openTopic(targetTopic, preferTodayMode = false)
        }
    )

    GeminiSummaryBottomSheet(
        topicTitle = activeTitle,
        streamState = geminiStreamState,
        hasSavedApiKey = isGeminiKeyConfigured,
        savedApiKey = geminiApiKey,
        onSelectMode = { mode ->
            viewModel.runGeminiAnalysisStream(
                mode = mode,
                topicTitle = activeTitle,
                entries = currentEntries.take(40)
            )
        },
        onSaveApiKeyAndSummarize = { newApiKey, mode ->
            viewModel.saveGeminiApiKey(newApiKey)
            viewModel.runGeminiAnalysisStream(
                mode = mode,
                topicTitle = activeTitle,
                entries = currentEntries.take(40)
            )
        },
        onDismiss = { viewModel.clearGeminiStream() }
    )

    val shareEntry: (EntryItem) -> Unit = { entry ->
        val plainText = entry.annotatedContent.text
        val shareBody = "$activeTitle - @${entry.author} (#${entry.id}):\n\n$plainText\n\nhttps://eksisozluk.com/entry/${entry.id}"
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, activeTitle)
                putExtra(Intent.EXTRA_TEXT, shareBody)
            }
            context.startActivity(Intent.createChooser(sendIntent, "Entry Paylaş"))
        } catch (_: Exception) {
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                AnimatedVisibility(
                    visible = isHeaderVisible,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        navigationIcon = {
                            IconButton(
                                onClick = onBack,
                                modifier = Modifier.testTag("topic_detail_back_button")
                            ) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Geri",
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        },
                        title = {
                            Column {
                                Text(
                                    text = activeTitle,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = OledTopicUnread,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                val subtitle = buildString {
                                    append("Sayfa $safeCurrentPage / $safeTotalPages")
                                    val meta = metadata
                                    if (meta != null) {
                                        if (activeFilterMode == TopicFilterMode.TODAY_POPULAR && !meta.usedTodayFallback) {
                                            append(" • Bugün")
                                        } else if (meta.usedTodayFallback) {
                                            append(" • Tüm Başlık (Bugün Entry Yok)")
                                        } else if (meta.usedTrackedFallback) {
                                            append(" • Takipten")
                                        }
                                    } else if (pageUiState is TopicPageUiState.Loading) {
                                        append(" • Yükleniyor...")
                                    }
                                }
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EksiNeonGreen,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = { viewModel.openThemeDialog() },
                                modifier = Modifier.testTag("topic_detail_theme_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = "Tema Seçici",
                                    tint = EksiNeonGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            IconButton(onClick = { viewModel.refreshCurrentTopicPage() }) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Yenile",
                                    tint = OledTextSecondary
                                )
                            }
                        }
                    )
                }

                // Üstte yatay kaydırılabilir modern filtre çipleri:
                // "Bugün" | "Tümü" | "🔥 Şükela Bugün" | "👑 Şükela Tümü" | "🔗 Linkler" | "🔍 Ara" | "✨ AI Özet"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val chipItems = listOf(
                        TopicFilterMode.TODAY_POPULAR to "Bugün",
                        TopicFilterMode.ALL to "Tümü",
                        TopicFilterMode.SUKELA_TODAY to "🔥 Şükela Bugün",
                        TopicFilterMode.SUKELA_ALL to "👑 Şükela Tümü",
                        TopicFilterMode.LINKS_ONLY to "🔗 Linkler",
                        TopicFilterMode.SEARCH_IN_TOPIC to "🔍 Ara"
                    )

                    chipItems.forEach { (mode, label) ->
                        val isSelected = activeFilterMode == mode
                        FilterChip(
                            selected = isSelected,
                            onClick = { viewModel.selectTopicFilterMode(mode) },
                            shape = RoundedCornerShape(50),
                            label = {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = EksiNeonGreen,
                                selectedLabelColor = OledOnAccentColor,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                labelColor = OledTextPrimary
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = isSelected,
                                borderColor = OledHairlineDivider,
                                selectedBorderColor = EksiNeonGreen
                            ),
                            modifier = Modifier.testTag("topic_filter_${mode.name.lowercase()}")
                        )
                    }

                    // ✨ AI Özet Çipi
                    AssistChip(
                        onClick = {
                            viewModel.runGeminiAnalysisStream(
                                mode = GeminiAnalysisMode.FIVE_BULLET_SUMMARY,
                                topicTitle = activeTitle,
                                entries = currentEntries.take(40)
                            )
                        },
                        shape = RoundedCornerShape(50),
                        label = {
                            Text(
                                text = "✨ AI Özet",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = EksiNeonGreen
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = OledPillGreenBg,
                            labelColor = EksiNeonGreen
                        ),
                        border = AssistChipDefaults.assistChipBorder(
                            enabled = true,
                            borderColor = EksiNeonGreen.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.testTag("chip_gemini_summary")
                    )
                }

                // Sola kaydırma ile aktifleşen Yazar Filtresi bilgi bandı
                AnimatedVisibility(visible = !authorFilter.isNullOrBlank()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .border(0.5.dp, EksiNeonGreen.copy(alpha = 0.4f), RoundedCornerShape(10.dp)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FilterAlt,
                                    contentDescription = null,
                                    tint = EksiNeonGreen,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = "Sadece @$authorFilter entry'leri gösteriliyor",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = EksiNeonGreen,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = { viewModel.clearAuthorFilterInTopic() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Filtreyi Kaldır",
                                    tint = OledTextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // 🔍 Başlıkta Ara metin kutusu
                AnimatedVisibility(visible = activeFilterMode == TopicFilterMode.SEARCH_IN_TOPIC) {
                    OutlinedTextField(
                        value = inTopicQuery,
                        onValueChange = { viewModel.updateInTopicSearchQuery(it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .testTag("in_topic_search_input"),
                        placeholder = {
                            Text(
                                text = "Başlık içinde kelime veya @yazar ara...",
                                color = OledTextMuted
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = EksiNeonGreen
                            )
                        },
                        trailingIcon = {
                            if (inTopicQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.clearAuthorFilterInTopic() }) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Temizle",
                                        tint = OledTextSecondary
                                    )
                                }
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(
                            onSearch = {
                                focusManager.clearFocus()
                                viewModel.submitInTopicSearch()
                            }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EksiNeonGreen,
                            unfocusedBorderColor = OledDivider,
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                            focusedTextColor = OledTextPrimary,
                            unfocusedTextColor = OledTextPrimary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                if (pageUiState is TopicPageUiState.Loading && currentEntries.isNotEmpty()) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = EksiNeonGreen,
                        trackColor = Color.Transparent
                    )
                } else {
                    HorizontalDivider(color = OledHairlineDivider, thickness = 1.dp)
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                (pageUiState is TopicPageUiState.Loading || pageUiState is TopicPageUiState.Idle) &&
                    currentEntries.isEmpty() -> {
                    EntryListSkeletonShimmer(
                        cardCount = 5,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                pageUiState is TopicPageUiState.Error && currentEntries.isEmpty() -> {
                    val errorState = pageUiState as TopicPageUiState.Error
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(20.dp)
                            .fillMaxWidth()
                            .border(1.dp, OledDivider, RoundedCornerShape(16.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Security,
                                contentDescription = null,
                                tint = EksiNeonGreen,
                                modifier = Modifier.size(36.dp)
                            )
                            Text(
                                text = "Sayfa ${errorState.targetPage} Yüklenemedi",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = OledTextPrimary
                            )
                            Text(
                                text = errorState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = OledTextSecondary
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                AssistChip(
                                    onClick = {
                                        viewModel.goToPage(
                                            targetPage = errorState.targetPage,
                                            forceRefresh = true
                                        )
                                    },
                                    label = { Text("Tekrar Dene", color = OledTextPrimary) }
                                )
                                Button(
                                    onClick = { viewModel.openCloudflareVerification(topic.url) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = EksiNeonGreen,
                                        contentColor = OledOnAccentColor
                                    )
                                ) {
                                    Text("Doğrula", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("entry_paging_list"),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 10.dp,
                            bottom = 96.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // 2. ÖZELLİK: Başlık ekranının hemen üst kısmında (entry listesinin en başında)
                        // Ekşin tarzı belirgin ve şık "Tümünü Göster (İlk Entry'den İtibaren Tüm Başlığı Yükle)" butonu
                        if (isSingleEntryOrFiltered) {
                            item(key = "show_all_entries_header_button") {
                                Surface(
                                    color = OledPillGreenBg,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .clickable { viewModel.showAllTopicEntriesFromFirstPage() }
                                        .border(
                                            width = 1.dp,
                                            color = EksiNeonGreen.copy(alpha = 0.6f),
                                            shape = RoundedCornerShape(14.dp)
                                        )
                                        .testTag("show_all_entries_button")
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
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                                contentDescription = null,
                                                tint = EksiNeonGreen,
                                                modifier = Modifier.size(20.dp)
                                            )
                                            Column {
                                                Text(
                                                    text = "Tümünü Göster (İlk Entry'den İtibaren Tüm Başlığı Yükle)",
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = EksiNeonGreen,
                                                    fontSize = 13.sp
                                                )
                                                val modeHint = when {
                                                    metadata?.usedTodayFallback == true ->
                                                        "Bugün yeni entry girilmediği için tüm başlık gösteriliyor • 1. sayfadan açmak için dokunun"
                                                    metadata?.isSingleEntryView == true ->
                                                        "Tekil DEBE / Entry görünümü • Başlığın 1. sayfasından itibaren tümünü yükle"
                                                    activeFilterMode == TopicFilterMode.TODAY_POPULAR ->
                                                        "Şu an son 24 saat (Bugün) filtresi aktif • 1. sayfadan (?p=1) itibaren tümünü yükle"
                                                    else ->
                                                        "${activeFilterMode.displayTitle} filtresini kaldır ve 1. sayfadan itibaren tümünü yükle"
                                                }
                                                Text(
                                                    text = modeHint,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = OledTextSecondary,
                                                    fontSize = 11.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        itemsIndexed(
                            items = currentEntries,
                            key = { index, entry -> "${safeCurrentPage}_${index}_${entry.id}" }
                        ) { _, entry ->
                            val isFavorited = favoritedIds.contains(entry.id) || entry.isFavorited

                            SwipeableEntryOledCard(
                                entry = entry,
                                isFavorited = isFavorited,
                                onSwipeRightSukelaFav = {
                                    viewModel.toggleSukelaOrFavorite(entry)
                                    coroutineScope.launch {
                                        snackbarHostState.showSnackbar(
                                            message = if (isFavorited) {
                                                "Entry #${entry.id} favorilerden çıkarıldı"
                                            } else {
                                                "Entry #${entry.id} Şükela / Favorilere eklendi ★"
                                            },
                                            duration = SnackbarDuration.Short
                                        )
                                    }
                                },
                                onSwipeLeftAuthorOrShare = {
                                    viewModel.filterByAuthorInTopic(entry.author)
                                    coroutineScope.launch {
                                        val result = snackbarHostState.showSnackbar(
                                            message = "Sadece @${entry.author} entry'leri filtrelendi",
                                            actionLabel = "Paylaş",
                                            duration = SnackbarDuration.Short
                                        )
                                        if (result == SnackbarResult.ActionPerformed) {
                                            shareEntry(entry)
                                        }
                                    }
                                },
                                onAuthorClick = { authorName ->
                                    viewModel.openBkzPreview(authorName)
                                },
                                onShareEntry = {
                                    shareEntry(entry)
                                },
                                onBkzClick = { bkzQuery ->
                                    viewModel.openBkzPreview(bkzQuery)
                                },
                                onExternalUrlClick = { externalUrl ->
                                    try {
                                        context.startActivity(
                                            Intent(Intent.ACTION_VIEW, Uri.parse(externalUrl))
                                        )
                                    } catch (_: Exception) {
                                    }
                                },
                                onSummarizeSingle = {
                                    viewModel.runGeminiAnalysisStream(
                                        mode = GeminiAnalysisMode.FIVE_BULLET_SUMMARY,
                                        topicTitle = "${entry.topicTitle} (#${entry.id})",
                                        entries = listOf(entry)
                                    )
                                }
                            )
                        }

                        // Tekil entry (`/entry/{gerçek_entry_id}` veya DEBE) görünümünde entry'nin hemen altında
                        // "Başlığın Tüm Entry'lerini Gör" butonu
                        if (metadata?.isSingleEntryView == true ||
                            topic.url.contains("/entry/", ignoreCase = true) ||
                            topic.url.contains("a=debe", ignoreCase = true)
                        ) {
                            item(key = "see_all_topic_entries_bottom_button") {
                                Button(
                                    onClick = { viewModel.showAllTopicEntriesFromFirstPage() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = EksiNeonGreen,
                                        contentColor = OledOnAccentColor
                                    ),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp, bottom = 12.dp)
                                        .heightIn(min = 50.dp)
                                        .testTag("see_all_topic_entries_bottom_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.MenuBook,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Başlığın Tüm Entry'lerini Gör",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 1. & 4. KURAL: Direct Page State kapsül barı
            // Kullanıcı hangi sayfayı seçerse (İlk, Önceki, Sayfa X, Sonraki, Son)
            // doğrudan `viewModel.goToPage(targetPage)` çağrılır ve veri yüklendiğinde
            // `LaunchedEffect(latestLoadSequence)` ile liste en üste (`scrollToItem(0)`) sarılır.
            if (metadata?.isSingleEntryView != true && !topic.url.contains("/entry/", ignoreCase = true)) {
                FloatingPagerCapsuleBar(
                    currentPage = safeCurrentPage,
                    totalPages = safeTotalPages,
                    onSelectPage = { targetPage ->
                        viewModel.goToPage(targetPage = targetPage, forceRefresh = false)
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun rememberDetailHeaderVisibility(listState: LazyListState): State<Boolean> {
    var previousIndex by remember(listState) { mutableIntStateOf(0) }
    var previousScrollOffset by remember(listState) { mutableIntStateOf(0) }

    return remember(listState) {
        derivedStateOf {
            val currentIndex = listState.firstVisibleItemIndex
            val currentOffset = listState.firstVisibleItemScrollOffset
            val isScrollingUp = when {
                currentIndex < previousIndex -> true
                currentIndex > previousIndex -> false
                else -> currentOffset <= previousScrollOffset
            }
            previousIndex = currentIndex
            previousScrollOffset = currentOffset
            currentIndex == 0 || isScrollingUp
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("DEPRECATION")
@Composable
fun SwipeableEntryOledCard(
    entry: EntryItem,
    isFavorited: Boolean,
    onSwipeRightSukelaFav: () -> Unit,
    onSwipeLeftAuthorOrShare: () -> Unit,
    onAuthorClick: (String) -> Unit,
    onShareEntry: () -> Unit,
    onBkzClick: (String) -> Unit,
    onExternalUrlClick: (String) -> Unit,
    onSummarizeSingle: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { targetValue ->
            when (targetValue) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    onSwipeRightSukelaFav()
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    onSwipeLeftAuthorOrShare()
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    val inlineImageUrls = remember(entry.id, entry.contentHtml) {
        extractInlineImageUrls(entry)
    }

    val isLight = ActiveThemeState.currentMode == AppThemeMode.CLASSIC_LIGHT

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = {
            val direction = dismissState.dismissDirection
            val bgColor by animateColorAsState(
                targetValue = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> EksiDeepGreen
                    SwipeToDismissBoxValue.EndToStart -> if (isLight) Color(0xFFE3F2FD) else Color(0xFF102436)
                    SwipeToDismissBoxValue.Settled -> MaterialTheme.colorScheme.surfaceVariant
                },
                label = "swipe_bg_color"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(bgColor)
                    .padding(horizontal = 20.dp),
                contentAlignment = when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                    SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                    SwipeToDismissBoxValue.Settled -> Alignment.Center
                }
            ) {
                when (direction) {
                    SwipeToDismissBoxValue.StartToEnd -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Şükela / Fav",
                                tint = EksiNeonGreen
                            )
                            Text(
                                text = "Favori / Şükela",
                                color = EksiNeonGreen,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    SwipeToDismissBoxValue.EndToStart -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Sadece @${entry.author} Filtrele • Paylaş",
                                color = OledSwipeAuthorBlue,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Icon(
                                imageVector = Icons.Default.PersonSearch,
                                contentDescription = "Yazarın Entry'lerini Filtrele",
                                tint = OledSwipeAuthorBlue
                            )
                        }
                    }
                    SwipeToDismissBoxValue.Settled -> Unit
                }
            }
        }
    ) {
        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 0.5.dp,
                    color = if (isFavorited) EksiNeonGreen.copy(alpha = 0.65f) else OledHairlineDivider,
                    shape = RoundedCornerShape(14.dp)
                )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = OledElevatedCard,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Sayfa ${entry.pageNumber} • #${entry.id}",
                            style = MaterialTheme.typography.labelSmall,
                            color = OledTextSecondary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        IconButton(
                            onClick = onSwipeLeftAuthorOrShare,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.PersonSearch,
                                contentDescription = "Sadece bu yazarın entry'lerini filtrele",
                                tint = OledTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = onSummarizeSingle,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = "Entry AI Özetle",
                                tint = EksiNeonGreen,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        IconButton(
                            onClick = onShareEntry,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Share,
                                contentDescription = "Paylaş",
                                tint = OledTextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                ClickableText(
                    text = entry.annotatedContent,
                    style = TextStyle(
                        color = OledTopicUnread,
                        fontSize = 15.sp,
                        lineHeight = 24.sp
                    ),
                    onClick = { offset ->
                        entry.annotatedContent
                            .getStringAnnotations(tag = EksiParser.TAG_BKZ, start = offset, end = offset)
                            .firstOrNull()?.let { annotation ->
                                onBkzClick(annotation.item)
                                return@ClickableText
                            }

                        entry.annotatedContent
                            .getStringAnnotations(tag = EksiParser.TAG_HIDDEN_BKZ, start = offset, end = offset)
                            .firstOrNull()?.let { annotation ->
                                onBkzClick(annotation.item)
                                return@ClickableText
                            }

                        entry.annotatedContent
                            .getStringAnnotations(
                                tag = EksiParser.TAG_EXTERNAL_URL,
                                start = offset,
                                end = offset
                            )
                            .firstOrNull()?.let { annotation ->
                                onExternalUrlClick(annotation.item)
                            }
                    }
                )

                if (inlineImageUrls.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        inlineImageUrls.forEach { imageUrl ->
                            InlineEntryImagePreview(
                                imageUrl = imageUrl,
                                onOpenExternal = { onExternalUrlClick(imageUrl) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                HorizontalDivider(color = OledHairlineDivider, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val displayFavCount = entry.favoriteCount + if (isFavorited && !entry.isFavorited) 1 else 0
                    Surface(
                        color = if (isFavorited) OledPillGreenBg else OledElevatedCard,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { onSwipeRightSukelaFav() }
                            .border(
                                width = 0.5.dp,
                                color = if (isFavorited) EksiNeonGreen else OledHairlineDivider,
                                shape = RoundedCornerShape(50)
                            )
                            .testTag("entry_fav_button_${entry.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Icon(
                                imageVector = if (isFavorited) {
                                    Icons.Default.Favorite
                                } else {
                                    Icons.Default.FavoriteBorder
                                },
                                contentDescription = "Şükela / Favori",
                                tint = if (isFavorited) EksiNeonGreen else OledTextSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = if (displayFavCount > 0) "$displayFavCount Şükela" else "Şükela",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isFavorited) EksiNeonGreen else OledTextSecondary,
                                fontWeight = if (isFavorited) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onAuthorClick(entry.author) }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = entry.author,
                                style = MaterialTheme.typography.labelLarge,
                                color = EksiNeonGreen,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val dateLabel = if (!entry.editDate.isNullOrBlank()) {
                                "${entry.date} ~ ${entry.editDate}"
                            } else {
                                entry.date
                            }
                            Text(
                                text = dateLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = OledTextMuted,
                                fontSize = 11.sp
                            )
                        }

                        if (!entry.avatarUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = entry.avatarUrl,
                                contentDescription = entry.author,
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .border(0.5.dp, EksiNeonGreen.copy(alpha = 0.5f), CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            AuthorMiniAvatar(
                                authorName = entry.author,
                                size = 28.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InlineEntryImagePreview(
    imageUrl: String,
    onOpenExternal: () -> Unit
) {
    val shimmerBrush = rememberOledShimmerBrush()
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, OledHairlineDivider, RoundedCornerShape(12.dp))
    ) {
        Column {
            SubcomposeAsyncImage(
                model = imageUrl,
                contentDescription = "Entry içi görsel önizleme",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 140.dp, max = 320.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
                loading = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .background(shimmerBrush)
                    )
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(OledElevatedCard)
                    .clickable { onOpenExternal() }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = imageUrl,
                    style = MaterialTheme.typography.labelSmall,
                    color = OledTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.OpenInNew,
                    contentDescription = "Bağlantıyı Aç",
                    tint = EksiNeonGreen,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}

fun extractInlineImageUrls(entry: EntryItem): List<String> {
    val annotations = entry.annotatedContent.getStringAnnotations(
        tag = EksiParser.TAG_EXTERNAL_URL,
        start = 0,
        end = entry.annotatedContent.length
    )

    return annotations.mapNotNull { ann ->
        val rawUrl = ann.item.trim()
        val lower = rawUrl.lowercase()
        val cleanWithoutQuery = lower.substringBefore('?')

        when {
            cleanWithoutQuery.endsWith(".jpg") ||
                cleanWithoutQuery.endsWith(".jpeg") ||
                cleanWithoutQuery.endsWith(".png") ||
                cleanWithoutQuery.endsWith(".webp") ||
                cleanWithoutQuery.endsWith(".gif") -> rawUrl

            lower.contains("picsum.photos/") -> rawUrl

            lower.contains("i.imgur.com/") -> rawUrl

            lower.contains("imgur.com/") && !lower.contains("/a/") && !lower.contains("/gallery/") -> {
                val slug = rawUrl.substringAfterLast("/").substringBefore("?")
                if (slug.isNotBlank()) "https://i.imgur.com/$slug.jpg" else null
            }

            lower.contains("eksiseyler.com") &&
                (lower.contains("/img/") || lower.contains("seyler.ekstat.com")) -> rawUrl

            lower.contains("seyler.ekstat.com") ||
                lower.contains("pbs.twimg.com/media/") ||
                lower.contains("i.redd.it/") ||
                lower.contains("i.ibb.co/") -> rawUrl

            else -> null
        }
    }.distinct()
}
