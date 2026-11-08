package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.data.local.BookmarkedEntryEntity
import com.example.data.local.TopicReadStateEntity
import com.example.data.model.DebeAndStatsCategory
import com.example.data.model.DebeAndStatsUiState
import com.example.data.model.EksiChannel
import com.example.data.model.LeftFrameSection
import com.example.data.model.PeriodicRankingTab
import com.example.data.model.PeriodicTimeRange
import com.example.data.model.RankedTopicOrEntryItem
import com.example.data.model.SearchSortOrder
import com.example.data.model.TopicItem
import com.example.ui.auth.AuthWebViewScreen
import com.example.ui.components.ThemeSelectorDialog
import com.example.ui.components.TopicListSkeletonShimmer
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.EksiNeonGreen
import com.example.ui.theme.OledCardSurface
import com.example.ui.theme.OledHairlineDivider
import com.example.ui.theme.OledOnAccentColor
import com.example.ui.theme.OledPillGreenBg
import com.example.ui.theme.OledTextMuted
import com.example.ui.theme.OledTextPrimary
import com.example.ui.theme.OledTextSecondary
import com.example.ui.theme.OledTopicRead
import com.example.ui.theme.OledTopicUnread
import kotlinx.coroutines.launch

/**
 * Tek elle kullanıma uygun Alt Navigasyon Barı sekmeleri:
 * Gündem, Bugün, Kanallar, DEBE & Enler, Arama, Profil
 */
enum class MainNavDestination(
    val title: String,
    val icon: ImageVector,
    val testTag: String
) {
    GUNDEM("Gündem", Icons.Default.Whatshot, "nav_gundem"),
    BUGUN("Bugün", Icons.Default.Today, "nav_bugun"),
    KANALLAR("Kanallar", Icons.Default.Tag, "nav_kanallar"),
    DEBE_ENLER("DEBE", Icons.Default.EmojiEvents, "nav_debe_enler"),
    ARAMA("Arama", Icons.Default.Search, "nav_arama"),
    PROFIL("Profil", Icons.Default.Person, "nav_profil")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(
    viewModel: EksinViewModel
) {
    val activeAuthSheet by viewModel.activeAuthSheet.collectAsStateWithLifecycle()
    val pendingCfChallenge by viewModel.pendingCloudflareChallenge.collectAsStateWithLifecycle()
    val selectedTopic by viewModel.selectedTopic.collectAsStateWithLifecycle()
    val selectedSection by viewModel.selectedSection.collectAsStateWithLifecycle()
    val hasCfClearance by viewModel.hasCfClearance.collectAsStateWithLifecycle()
    val hasUserSession by viewModel.hasUserSession.collectAsStateWithLifecycle()
    val allReadStates by viewModel.allReadStates.collectAsStateWithLifecycle()
    val lastSeenEntryCounts by viewModel.lastSeenEntryCounts.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val showThemeDialog by viewModel.showThemeDialog.collectAsStateWithLifecycle()

    val readStateByUrl = remember(allReadStates) {
        allReadStates.associateBy { it.topicKey }
    }

    val coroutineScope = rememberCoroutineScope()
    var currentDestination by rememberSaveable { mutableStateOf(MainNavDestination.GUNDEM) }

    // 2. KURAL: Her sekme için ayrı LazyListState ve ayrı LazyPagingItems en üst seviyede (TopicDetailScreen
    // veya AuthWebViewScreen açıldığında bile silinmeyecek şekilde) tutulur. Böylece bir başlığa tıklayıp
    // geri dönüldüğünde kaydırma konumu (firstVisibleItemIndex ve scrollOffset) %100 korunur.
    val gundemListState = rememberLazyListState()
    val bugunListState = rememberLazyListState()
    val olaylarListState = rememberLazyListState()
    val kanallarListState = rememberLazyListState()
    val debeListState = rememberLazyListState()
    val searchListState = rememberLazyListState()

    val gundemPagingItems = viewModel.gundemTopicsPagingFlow.collectAsLazyPagingItems()
    val bugunPagingItems = viewModel.bugunTopicsPagingFlow.collectAsLazyPagingItems()
    val olaylarPagingItems = viewModel.olaylarTopicsPagingFlow.collectAsLazyPagingItems()
    val channelPagingItems = viewModel.channelTopicsPagingFlow.collectAsLazyPagingItems()
    val searchPagingItems = viewModel.searchTopicsPagingFlow.collectAsLazyPagingItems()

    LaunchedEffect(gundemPagingItems.itemCount, bugunPagingItems.itemCount) {
        if (gundemPagingItems.itemCount > 0 || bugunPagingItems.itemCount > 0) {
            viewModel.refreshCookieState()
        }
    }

    val activeScrollListState = when (currentDestination) {
        MainNavDestination.GUNDEM -> when (selectedSection) {
            LeftFrameSection.OLAYLAR -> olaylarListState
            LeftFrameSection.BUGUN -> bugunListState
            else -> gundemListState
        }
        MainNavDestination.BUGUN -> bugunListState
        MainNavDestination.KANALLAR -> kanallarListState
        MainNavDestination.DEBE_ENLER -> debeListState
        MainNavDestination.ARAMA -> searchListState
        MainNavDestination.PROFIL -> gundemListState
    }
    val isHeaderVisible = rememberDirectionalHeaderVisibility(activeScrollListState)
    val isDebeActive = currentDestination == MainNavDestination.DEBE_ENLER

    // 1. KURAL: Alt veya üst bardaki "Gündem" butonuna tıklandığında:
    // - Başka sekmedeyse Gündem sekmesine geçer.
    // - Zaten Gündem sekmesindeyse (re-click) Gündem önbelleğini temizler, baştan çeker ve en üste kaydırır.
    val handleGundemClick: () -> Unit = {
        val alreadyOnGundem = currentDestination == MainNavDestination.GUNDEM &&
            selectedSection == LeftFrameSection.GUNDEM
        if (alreadyOnGundem) {
            viewModel.forceRefreshGundem()
            gundemPagingItems.refresh()
            coroutineScope.launch {
                gundemListState.animateScrollToItem(0)
            }
        } else {
            currentDestination = MainNavDestination.GUNDEM
            viewModel.selectSection(LeftFrameSection.GUNDEM)
        }
    }

    // 4. KURAL: Üst bardaki "DEBE", üst çipteki "🏆 DEBE & Enler" ve alt bardaki "DEBE" butonlarının
    // tamamı aynı DEBE ekranını ve "Dünün En Beğenilenleri (DEBE)" sekmesini açar.
    val handleDebeClick: () -> Unit = {
        val alreadyOnDebe = currentDestination == MainNavDestination.DEBE_ENLER
        currentDestination = MainNavDestination.DEBE_ENLER
        viewModel.openDebeSection(forceReload = alreadyOnDebe)
        if (alreadyOnDebe) {
            coroutineScope.launch {
                debeListState.animateScrollToItem(0)
            }
        }
    }

    if (showThemeDialog) {
        ThemeSelectorDialog(
            currentTheme = themeMode,
            onSelectTheme = { viewModel.selectThemeMode(it) },
            onDismiss = { viewModel.dismissThemeDialog() }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                AnimatedVisibility(
                    visible = isHeaderVisible ||
                        currentDestination == MainNavDestination.ARAMA ||
                        currentDestination == MainNavDestination.PROFIL ||
                        currentDestination == MainNavDestination.DEBE_ENLER,
                    enter = slideInVertically(
                        initialOffsetY = { -it },
                        animationSpec = tween(durationMillis = 220)
                    ) + fadeIn(animationSpec = tween(durationMillis = 180)),
                    exit = slideOutVertically(
                        targetOffsetY = { -it },
                        animationSpec = tween(durationMillis = 220)
                    ) + fadeOut(animationSpec = tween(durationMillis = 160))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                titleContentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            title = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .clip(CircleShape)
                                            .background(EksiNeonGreen)
                                    )
                                    Text(
                                        text = "Ekşi+",
                                        fontWeight = FontWeight.ExtraBold,
                                        fontSize = 19.sp,
                                        letterSpacing = (-0.3).sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            },
                            actions = {
                                // Modern Kapsül "DEBE" Butonu -> Doğrudan Dünün En Beğenilenleri (DEBE) ekranını açar
                                Surface(
                                    color = if (isDebeActive) EksiNeonGreen else OledPillGreenBg,
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier
                                        .padding(end = 4.dp)
                                        .clip(RoundedCornerShape(50))
                                        .clickable { handleDebeClick() }
                                        .border(
                                            width = 0.8.dp,
                                            color = EksiNeonGreen.copy(alpha = if (isDebeActive) 1f else 0.45f),
                                            shape = RoundedCornerShape(50)
                                        )
                                        .testTag("debe_capsule_chip")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Whatshot,
                                            contentDescription = null,
                                            tint = if (isDebeActive) OledOnAccentColor else EksiNeonGreen,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "DEBE",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isDebeActive) OledOnAccentColor else EksiNeonGreen
                                        )
                                    }
                                }

                                // 4. Tema / Renk Seçici (🎨) İkonu
                                IconButton(
                                    onClick = { viewModel.openThemeDialog() },
                                    modifier = Modifier.testTag("top_theme_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Palette,
                                        contentDescription = "Tema Seçici",
                                        tint = EksiNeonGreen,
                                        modifier = Modifier.size(21.dp)
                                    )
                                }

                                // Kalkan ikonu + sadece küçük yeşil durum noktası
                                IconButton(
                                    onClick = { viewModel.openCloudflareVerification() },
                                    modifier = Modifier.testTag("top_cloudflare_button")
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            imageVector = Icons.Default.Security,
                                            contentDescription = "Cloudflare Durumu",
                                            tint = OledTextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopEnd)
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(
                                                    if (hasCfClearance) EksiNeonGreen else Color(0xFFFFB74D)
                                                )
                                                .border(1.2.dp, MaterialTheme.colorScheme.surface, CircleShape)
                                                .testTag("cf_status_badge")
                                        )
                                    }
                                }

                                IconButton(
                                    onClick = {
                                        if (currentDestination == MainNavDestination.GUNDEM &&
                                            selectedSection == LeftFrameSection.GUNDEM
                                        ) {
                                            handleGundemClick()
                                        } else {
                                            viewModel.triggerRefresh()
                                        }
                                    },
                                    modifier = Modifier.testTag("top_refresh_button")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Yenile",
                                        tint = OledTextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        )

                        // Ana ekran üst sekme çubuğu: Gündem | Bugün | Kanallar | DEBE & Enler | Olaylar
                        if (currentDestination == MainNavDestination.GUNDEM ||
                            currentDestination == MainNavDestination.BUGUN ||
                            currentDestination == MainNavDestination.KANALLAR ||
                            currentDestination == MainNavDestination.DEBE_ENLER
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState())
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TopQuickSectionChip(
                                    label = "🔥 Gündem",
                                    selected = currentDestination == MainNavDestination.GUNDEM &&
                                        selectedSection == LeftFrameSection.GUNDEM,
                                    onClick = { handleGundemClick() }
                                )
                                TopQuickSectionChip(
                                    label = "📅 Bugün",
                                    selected = currentDestination == MainNavDestination.BUGUN,
                                    onClick = {
                                        val alreadyOnBugun = currentDestination == MainNavDestination.BUGUN
                                        currentDestination = MainNavDestination.BUGUN
                                        viewModel.selectSection(LeftFrameSection.BUGUN)
                                        if (alreadyOnBugun) {
                                            bugunPagingItems.refresh()
                                            coroutineScope.launch {
                                                bugunListState.animateScrollToItem(0)
                                            }
                                        }
                                    }
                                )
                                TopQuickSectionChip(
                                    label = "# Kanallar",
                                    selected = currentDestination == MainNavDestination.KANALLAR,
                                    onClick = {
                                        currentDestination = MainNavDestination.KANALLAR
                                    }
                                )
                                TopQuickSectionChip(
                                    label = "🏆 DEBE & Enler",
                                    selected = currentDestination == MainNavDestination.DEBE_ENLER,
                                    onClick = { handleDebeClick() }
                                )
                                TopQuickSectionChip(
                                    label = "🔔 Olaylar",
                                    selected = currentDestination == MainNavDestination.GUNDEM &&
                                        selectedSection == LeftFrameSection.OLAYLAR,
                                    onClick = {
                                        currentDestination = MainNavDestination.GUNDEM
                                        viewModel.selectSection(LeftFrameSection.OLAYLAR)
                                    }
                                )
                            }
                        }

                        HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)
                    }
                }
            },
            bottomBar = {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    tonalElevation = 0.dp,
                    modifier = Modifier.border(0.5.dp, OledHairlineDivider)
                ) {
                    val navColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = OledOnAccentColor,
                        selectedTextColor = EksiNeonGreen,
                        indicatorColor = EksiNeonGreen,
                        unselectedIconColor = OledTextSecondary,
                        unselectedTextColor = OledTextSecondary
                    )

                    MainNavDestination.entries.forEach { destination ->
                        val isSelected = currentDestination == destination
                        NavigationBarItem(
                            selected = isSelected,
                            onClick = {
                                when (destination) {
                                    MainNavDestination.GUNDEM -> handleGundemClick()
                                    MainNavDestination.BUGUN -> {
                                        val alreadyOnBugun = currentDestination == MainNavDestination.BUGUN
                                        currentDestination = MainNavDestination.BUGUN
                                        viewModel.selectSection(LeftFrameSection.BUGUN)
                                        if (alreadyOnBugun) {
                                            bugunPagingItems.refresh()
                                            coroutineScope.launch {
                                                bugunListState.animateScrollToItem(0)
                                            }
                                        }
                                    }
                                    MainNavDestination.KANALLAR -> {
                                        val alreadyOnKanallar = currentDestination == MainNavDestination.KANALLAR
                                        currentDestination = MainNavDestination.KANALLAR
                                        if (alreadyOnKanallar) {
                                            channelPagingItems.refresh()
                                            coroutineScope.launch {
                                                kanallarListState.animateScrollToItem(0)
                                            }
                                        }
                                    }
                                    MainNavDestination.DEBE_ENLER -> handleDebeClick()
                                    MainNavDestination.ARAMA -> {
                                        currentDestination = MainNavDestination.ARAMA
                                    }
                                    MainNavDestination.PROFIL -> {
                                        currentDestination = MainNavDestination.PROFIL
                                    }
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.title
                                )
                            },
                            label = {
                                Text(
                                    text = destination.title,
                                    fontSize = 10.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1
                                )
                            },
                            colors = navColors,
                            modifier = Modifier.testTag(destination.testTag)
                        )
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                if (!hasCfClearance && pendingCfChallenge != null) {
                    val challenge = pendingCfChallenge
                    if (challenge != null) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = challenge.reason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OledTextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                AssistChip(
                                    onClick = {
                                        viewModel.openCloudflareVerification(challenge.targetUrl)
                                    },
                                    label = {
                                        Text(
                                            text = "Doğrula",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = EksiNeonGreen
                                        )
                                    }
                                )
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    when (currentDestination) {
                        MainNavDestination.GUNDEM -> {
                            val activePaging = if (selectedSection == LeftFrameSection.OLAYLAR) {
                                olaylarPagingItems
                            } else {
                                gundemPagingItems
                            }
                            val activeState = if (selectedSection == LeftFrameSection.OLAYLAR) {
                                olaylarListState
                            } else {
                                gundemListState
                            }
                            val emptyTitle = if (selectedSection == LeftFrameSection.OLAYLAR) {
                                "Olaylar"
                            } else {
                                "Gündem"
                            }

                            TopicPagingList(
                                pagingItems = activePaging,
                                listState = activeState,
                                isDebeMode = false,
                                readStateByUrl = readStateByUrl,
                                lastSeenEntryCounts = lastSeenEntryCounts,
                                onTopicClick = { topic ->
                                    viewModel.openTopic(
                                        topic = topic,
                                        preferTodayMode = selectedSection != LeftFrameSection.OLAYLAR
                                    )
                                },
                                onOpenCloudflare = { viewModel.openCloudflareVerification() },
                                emptyMessage = "$emptyTitle sekmesinde başlık bulunamadı."
                            )
                        }

                        MainNavDestination.BUGUN -> {
                            TopicPagingList(
                                pagingItems = bugunPagingItems,
                                listState = bugunListState,
                                isDebeMode = false,
                                readStateByUrl = readStateByUrl,
                                lastSeenEntryCounts = lastSeenEntryCounts,
                                onTopicClick = { topic ->
                                    viewModel.openTopic(
                                        topic = topic,
                                        preferTodayMode = true
                                    )
                                },
                                onOpenCloudflare = { viewModel.openCloudflareVerification() },
                                emptyMessage = "Bugün sekmesinde başlık bulunamadı."
                            )
                        }

                        MainNavDestination.KANALLAR -> {
                            ChannelsScreenContent(
                                viewModel = viewModel,
                                channelPagingItems = channelPagingItems,
                                listState = kanallarListState,
                                readStateByUrl = readStateByUrl,
                                lastSeenEntryCounts = lastSeenEntryCounts,
                                onTopicClick = { topic ->
                                    viewModel.openTopic(topic = topic, preferTodayMode = true)
                                },
                                onOpenCloudflare = { viewModel.openCloudflareVerification() }
                            )
                        }

                        MainNavDestination.DEBE_ENLER -> {
                            DebeAndPeriodicBestsScreenContent(
                                viewModel = viewModel,
                                listState = debeListState,
                                readStateByUrl = readStateByUrl,
                                lastSeenEntryCounts = lastSeenEntryCounts
                            )
                        }

                        MainNavDestination.ARAMA -> {
                            SearchScreenContent(
                                viewModel = viewModel,
                                searchPagingItems = searchPagingItems,
                                searchListState = searchListState,
                                readStateByUrl = readStateByUrl,
                                lastSeenEntryCounts = lastSeenEntryCounts,
                                onTopicClick = { viewModel.openTopic(it, preferTodayMode = false) },
                                onOpenCloudflare = { viewModel.openCloudflareVerification() }
                            )
                        }

                        MainNavDestination.PROFIL -> {
                            ProfileAndStudioScreenContent(
                                viewModel = viewModel,
                                hasUserSession = hasUserSession,
                                onOpenTopicUrl = { title, url ->
                                    viewModel.openTopic(
                                        TopicItem(
                                            id = url,
                                            title = title,
                                            slug = title.replace(" ", "-"),
                                            url = url
                                        ),
                                        preferTodayMode = false
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }

        // Başlık Detay Ekranı (Alt katmandaki Scaffold ve LazyListState'leri yok etmeden üstte açılır)
        selectedTopic?.let { topic ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                TopicDetailScreen(
                    topic = topic,
                    viewModel = viewModel,
                    onBack = { viewModel.closeTopicDetail() }
                )
            }
        }

        // Cloudflare Doğrulama veya Giriş WebView Ekranı
        activeAuthSheet?.let { sheet ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                AuthWebViewScreen(
                    initialUrl = sheet.url,
                    mode = sheet.mode,
                    cookieJar = viewModel.cookieJar,
                    userAgentProvider = viewModel.userAgentProvider,
                    webViewFetchBridge = viewModel.webViewFetchBridge,
                    onVerificationSuccess = { viewModel.onAuthVerificationCompleted() },
                    onDismiss = { viewModel.onAuthVerificationDismissed() }
                )
            }
        }
    }
}

@Composable
private fun TopQuickSectionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        color = if (selected) EksiNeonGreen else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .border(
                width = 0.6.dp,
                color = if (selected) EksiNeonGreen else OledHairlineDivider,
                shape = RoundedCornerShape(50)
            )
    ) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) OledOnAccentColor else OledTextPrimary,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/**
 * 1. ÖZELLİK: KANALLAR SEKMESİ (`https://eksisozluk.com/basliklar/kanal/{kanal_slug}?p={page}`).
 * Üst kısımda yatay kaydırılabilir 14 kanal çipi (`#spor`, `#teknoloji`, `#siyaset`, `#ekonomi`,
 * `#sinema`, `#oyun`, `#bilim`, `#müzik`, `#edebiyat`, `#tarih`, `#otomotiv`, `#havacılık`,
 * `#ilişkiler`, `#sağlık`) ve altında o kanalın güncel başlıkları yer alır.
 */
@Composable
private fun ChannelsScreenContent(
    viewModel: EksinViewModel,
    channelPagingItems: LazyPagingItems<TopicItem>,
    listState: LazyListState,
    readStateByUrl: Map<String, TopicReadStateEntity>,
    lastSeenEntryCounts: Map<String, Int>,
    onTopicClick: (TopicItem) -> Unit,
    onOpenCloudflare: () -> Unit
) {
    val selectedChannel by viewModel.selectedChannel.collectAsStateWithLifecycle()
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Yatay kaydırılabilir Kanal Çipleri (FilterChip)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .testTag("channels_filter_row"),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EksiChannel.entries.forEach { channel ->
                val isSelected = selectedChannel == channel
                FilterChip(
                    selected = isSelected,
                    onClick = {
                        if (isSelected) {
                            channelPagingItems.refresh()
                            coroutineScope.launch { listState.animateScrollToItem(0) }
                        } else {
                            viewModel.selectChannel(channel)
                            coroutineScope.launch { listState.scrollToItem(0) }
                        }
                    },
                    shape = RoundedCornerShape(50),
                    label = {
                        Text(
                            text = channel.hashtag,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
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
                    modifier = Modifier.testTag("channel_chip_${channel.slug}")
                )
            }
        }

        // Seçili kanal bilgi bandı
        Surface(
            color = OledPillGreenBg,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${selectedChannel.hashtag} kanalı güncel başlıkları",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = EksiNeonGreen
                )
                Text(
                    text = "/basliklar/kanal/${selectedChannel.slug}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = OledTextSecondary
                )
            }
        }

        HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)

        TopicPagingList(
            pagingItems = channelPagingItems,
            listState = listState,
            isDebeMode = false,
            readStateByUrl = readStateByUrl,
            lastSeenEntryCounts = lastSeenEntryCounts,
            onTopicClick = onTopicClick,
            onOpenCloudflare = onOpenCloudflare,
            emptyMessage = "${selectedChannel.hashtag} kanalında güncel başlık bulunamadı."
        )
    }
}

/**
 * 3. ÖZELLİK: GELİŞMİŞ DEBE VE DÖNEMSEL ENLER (GÜN / HAFTA / AY) SEKMESİ.
 * - Kategori 1: Dünün En Beğenilen Entry'leri (DEBE 50 - `/debe`)
 * - Kategori 2: Dönemsel Enler (`Önceki Gün`, `Önceki Hafta`, `Önceki Ay`)
 *   ve alt sekmeler (`En Beğenilen Entry'ler`, `En Popüler Konular`).
 */
@Composable
private fun DebeAndPeriodicBestsScreenContent(
    viewModel: EksinViewModel,
    listState: LazyListState,
    readStateByUrl: Map<String, TopicReadStateEntity>,
    lastSeenEntryCounts: Map<String, Int>
) {
    val activeCategory by viewModel.debeAndStatsCategory.collectAsStateWithLifecycle()
    val activePeriod by viewModel.periodicTimeRange.collectAsStateWithLifecycle()
    val activeRankingTab by viewModel.periodicRankingTab.collectAsStateWithLifecycle()
    val uiState by viewModel.debeAndStatsUiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Ana Kategori Seçici: 1) Dünün En Beğenilenleri (DEBE) | 2) Dönemsel Enler (Gün/Hafta/Ay)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DebeAndStatsCategory.entries.forEach { category ->
                    val isSelected = activeCategory == category
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.selectDebeAndStatsCategory(category) },
                        shape = RoundedCornerShape(50),
                        label = {
                            Text(
                                text = category.title,
                                fontSize = 12.sp,
                                fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
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
                        modifier = Modifier.testTag("debe_cat_${category.name.lowercase()}")
                    )
                }
            }

            // Dönemsel Enler aktifse: Dönem Çipleri (Önceki Gün / Önceki Hafta / Önceki Ay) +
            // Alt Sekmeler (En Beğenilen Entry'ler / En Popüler Konular)
            AnimatedVisibility(visible = activeCategory == DebeAndStatsCategory.PERIODIC_BESTS) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PeriodicTimeRange.entries.forEach { period ->
                            val isSelected = activePeriod == period
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.selectPeriodicTimeRange(period) },
                                shape = RoundedCornerShape(50),
                                label = {
                                    Text(
                                        text = period.label,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = OledPillGreenBg,
                                    selectedLabelColor = EksiNeonGreen,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    labelColor = OledTextPrimary
                                ),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true,
                                    selected = isSelected,
                                    borderColor = OledHairlineDivider,
                                    selectedBorderColor = EksiNeonGreen
                                ),
                                modifier = Modifier.testTag("period_chip_${period.name.lowercase()}")
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        PeriodicRankingTab.entries.forEach { tab ->
                            val isSelected = activeRankingTab == tab
                            Surface(
                                color = if (isSelected) EksiNeonGreen else MaterialTheme.colorScheme.surfaceVariant,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { viewModel.selectPeriodicRankingTab(tab) }
                                    .border(
                                        width = 0.8.dp,
                                        color = if (isSelected) EksiNeonGreen else OledHairlineDivider,
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .testTag("periodic_tab_${tab.name.lowercase()}")
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = tab.title,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                                        color = if (isSelected) OledOnAccentColor else OledTextPrimary
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)

        when (val state = uiState) {
            is DebeAndStatsUiState.Loading -> {
                TopicListSkeletonShimmer(itemCount = 10)
            }

            is DebeAndStatsUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = OledTextSecondary
                        )
                        Button(
                            onClick = { viewModel.loadDebeAndStats() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EksiNeonGreen,
                                contentColor = OledOnAccentColor
                            ),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text("Tekrar Dene", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            is DebeAndStatsUiState.Success -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        color = OledPillGreenBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = state.subtitle,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = EksiNeonGreen
                            )
                            Text(
                                text = "Yenile ↻",
                                style = MaterialTheme.typography.labelSmall,
                                color = OledTextSecondary,
                                modifier = Modifier.clickable { viewModel.loadDebeAndStats() }
                            )
                        }
                    }

                    HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)

                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                            .testTag("debe_stats_list"),
                        contentPadding = PaddingValues(vertical = 2.dp)
                    ) {
                        itemsIndexed(
                            items = state.items,
                            key = { idx, item -> "${idx}_${item.url}" }
                        ) { index, item ->
                            val cleanUrl = item.url.substringBefore('?').trim().lowercase()
                            val titleKey = "title:${item.title.trim().lowercase()}"
                            val lastSeenCount = lastSeenEntryCounts[cleanUrl]
                                ?: lastSeenEntryCounts[titleKey]
                                ?: if (readStateByUrl.containsKey(item.url.substringBefore('?'))) 0 else null
                            val currentCount = item.badgeText.filter { it.isDigit() }.toIntOrNull() ?: 0
                            val hasNewEntriesAfterRead = lastSeenCount != null &&
                                lastSeenCount > 0 &&
                                currentCount > lastSeenCount
                            val isRead = lastSeenCount != null && !hasNewEntriesAfterRead

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.background)
                                    .clickable { viewModel.openRankedItem(item) }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Surface(
                                        color = OledPillGreenBg,
                                        shape = CircleShape,
                                        modifier = Modifier
                                            .size(30.dp)
                                            .border(0.6.dp, EksiNeonGreen.copy(alpha = 0.45f), CircleShape)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = "${index + 1}",
                                                color = EksiNeonGreen,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.ExtraBold
                                            )
                                        }
                                    }

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            fontSize = 15.sp,
                                            lineHeight = 21.sp,
                                            color = if (isRead) OledTopicRead else OledTopicUnread,
                                            fontWeight = if (isRead) FontWeight.Normal else FontWeight.Medium
                                        )
                                        if (!item.author.isNullOrBlank() || !item.entryId.isNullOrBlank()) {
                                            Spacer(modifier = Modifier.height(3.dp))
                                            val metaText = buildString {
                                                if (!item.author.isNullOrBlank()) {
                                                    append("@${item.author}")
                                                }
                                                if (!item.entryId.isNullOrBlank()) {
                                                    if (isNotEmpty()) append(" • ")
                                                    append("Entry #${item.entryId}")
                                                }
                                            }
                                            Text(
                                                text = metaText,
                                                style = MaterialTheme.typography.labelSmall,
                                                fontSize = 11.sp,
                                                color = EksiNeonGreen.copy(alpha = 0.8f)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                val pillText = when {
                                    (activeCategory == DebeAndStatsCategory.DEBE || activeRankingTab == PeriodicRankingTab.TOP_ENTRIES) &&
                                        item.favoriteCount > 0 -> "♥ ${item.favoriteCount}"
                                    item.badgeText.isNotBlank() -> item.badgeText
                                    else -> "♥ ..."
                                }

                                Surface(
                                    color = OledPillGreenBg,
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier
                                        .border(
                                            width = 0.6.dp,
                                            color = EksiNeonGreen.copy(alpha = 0.45f),
                                            shape = RoundedCornerShape(50)
                                        )
                                        .testTag("debe_fav_pill_${index + 1}")
                                ) {
                                    Text(
                                        text = pillText,
                                        color = EksiNeonGreen,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }

                            HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun rememberDirectionalHeaderVisibility(listState: LazyListState): Boolean {
    var previousIndex by remember(listState) { mutableIntStateOf(listState.firstVisibleItemIndex) }
    var previousScrollOffset by remember(listState) { mutableIntStateOf(listState.firstVisibleItemScrollOffset) }
    var headerVisible by remember { mutableStateOf(true) }

    return remember(listState) {
        derivedStateOf {
            val currentIndex = listState.firstVisibleItemIndex
            val currentOffset = listState.firstVisibleItemScrollOffset

            if (currentIndex == 0 && currentOffset < 24) {
                headerVisible = true
            } else if (currentIndex > previousIndex || (currentIndex == previousIndex && currentOffset > previousScrollOffset + 18)) {
                headerVisible = false
            } else if (currentIndex < previousIndex || (currentIndex == previousIndex && currentOffset < previousScrollOffset - 12)) {
                headerVisible = true
            }

            previousIndex = currentIndex
            previousScrollOffset = currentOffset
            headerVisible
        }
    }.value
}

/**
 * 3. ÖZELLİK: ANLIK ARAMA ÖNERİLERİ (AUTOCOMPLETE - BAŞLIKLAR VE @YAZARLAR) + SAYFALAMALI ARAMA.
 */
@Composable
private fun SearchScreenContent(
    viewModel: EksinViewModel,
    searchPagingItems: LazyPagingItems<TopicItem>,
    searchListState: LazyListState,
    readStateByUrl: Map<String, TopicReadStateEntity>,
    lastSeenEntryCounts: Map<String, Int>,
    onTopicClick: (TopicItem) -> Unit,
    onOpenCloudflare: () -> Unit
) {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by viewModel.searchSortOrder.collectAsStateWithLifecycle()
    val submittedFilter by viewModel.submittedSearchFilter.collectAsStateWithLifecycle()
    val autocompleteState by viewModel.autocompleteState.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.updateSearchQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("search_input_field"),
            placeholder = {
                Text(
                    text = "Başlık veya @yazar ara (anlık öneri + p=1,2..)",
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (autocompleteState.isLoading) {
                        CircularProgressIndicator(
                            color = EksiNeonGreen,
                            strokeWidth = 2.dp,
                            modifier = Modifier
                                .size(18.dp)
                                .padding(end = 4.dp)
                        )
                    }
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Temizle",
                                tint = OledTextSecondary
                            )
                        }
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    viewModel.submitSearch()
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = EksiNeonGreen,
                unfocusedBorderColor = OledHairlineDivider,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedTextColor = OledTextPrimary,
                unfocusedTextColor = OledTextPrimary
            ),
            shape = RoundedCornerShape(14.dp)
        )

        // Anlık Arama Önerileri (Autocomplete) Açılır Kartı / Listesi:
        // Kullanıcı en az 2 harf yazdığında ve öneri varsa arama kutusunun hemen altında belirir.
        AnimatedVisibility(
            visible = query.trim().length >= 2 && autocompleteState.hasAnySuggestion
        ) {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .border(
                        width = 0.8.dp,
                        color = EksiNeonGreen.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .testTag("autocomplete_suggestions_card")
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(OledPillGreenBg)
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Anlık Öneriler (\"${autocompleteState.query}\")",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = EksiNeonGreen
                        )
                        Text(
                            text = "Kapat ✕",
                            style = MaterialTheme.typography.labelSmall,
                            color = OledTextSecondary,
                            modifier = Modifier.clickable { viewModel.dismissAutocomplete() }
                        )
                    }

                    HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 300.dp)
                            .testTag("autocomplete_suggestions_list")
                    ) {
                        // 1) Başlık Önerileri (Titles)
                        itemsIndexed(
                            items = autocompleteState.titleSuggestions,
                            key = { idx, item -> "ac_title_${idx}_${item.title}" }
                        ) { _, suggestion ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        focusManager.clearFocus()
                                        viewModel.dismissAutocomplete()
                                        onTopicClick(suggestion)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Search,
                                        contentDescription = null,
                                        tint = EksiNeonGreen,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Text(
                                        text = suggestion.title,
                                        fontSize = 14.sp,
                                        color = OledTextPrimary,
                                        fontWeight = FontWeight.Medium
                                    )
                                }

                                if (suggestion.entryCountText.isNotBlank() || suggestion.entryCount > 0) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Surface(
                                        color = OledPillGreenBg,
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text(
                                            text = suggestion.entryCountText.ifBlank {
                                                suggestion.entryCount.toString()
                                            },
                                            color = EksiNeonGreen,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)
                        }

                        // 2) Yazar Önerileri (Nicks)
                        itemsIndexed(
                            items = autocompleteState.nickSuggestions,
                            key = { idx, nick -> "ac_nick_${idx}_$nick" }
                        ) { _, nick ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        focusManager.clearFocus()
                                        viewModel.openAuthorSuggestion(nick)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Person,
                                        contentDescription = null,
                                        tint = EksiNeonGreen,
                                        modifier = Modifier.size(17.dp)
                                    )
                                    Text(
                                        text = "@$nick",
                                        fontSize = 14.sp,
                                        color = EksiNeonGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Surface(
                                    color = OledPillGreenBg,
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.border(
                                        width = 0.6.dp,
                                        color = EksiNeonGreen.copy(alpha = 0.45f),
                                        shape = RoundedCornerShape(50)
                                    )
                                ) {
                                    Text(
                                        text = "@yazar",
                                        color = EksiNeonGreen,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)
                        }
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SearchSortOrder.entries.forEach { order ->
                FilterChip(
                    selected = sortOrder == order,
                    onClick = { viewModel.selectSearchSortOrder(order) },
                    label = { Text(order.label, fontSize = 12.sp) },
                    shape = RoundedCornerShape(50),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = EksiNeonGreen,
                        selectedLabelColor = OledOnAccentColor,
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        labelColor = OledTextPrimary
                    )
                )
            }
        }

        HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)

        if (submittedFilter.keywords.isBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Aramak istediğiniz başlığı veya @yazarı yazın; anlık önerilerden seçin ya da klavyeden Ara tuşuna basın.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OledTextSecondary
                )
            }
        } else {
            TopicPagingList(
                pagingItems = searchPagingItems,
                listState = searchListState,
                isDebeMode = false,
                readStateByUrl = readStateByUrl,
                lastSeenEntryCounts = lastSeenEntryCounts,
                onTopicClick = onTopicClick,
                onOpenCloudflare = onOpenCloudflare,
                emptyMessage = "\"${submittedFilter.keywords}\" için başlık sonucu bulunamadı."
            )
        }
    }
}

/**
 * 1. ÖZELLİK: AKILLI OKUNDU TAKİBİ (OKUNAN BAŞLIĞA YENİ ENTRY GİRİLDİĞİNDE SOLUKLUĞUN KALKMASI).
 * - Hiç tıklanmadıysa (`lastSeenCount == null`): Parlak (Okunmamış - `OledTopicUnread`)
 * - Daha önce tıklandıysa ve `güncelEntrySayısı <= lastSeenEntryCount` ise: Soluk (Okundu - `OledTopicRead`)
 * - Daha önce tıklandıysa AMA `güncelEntrySayısı > lastSeenEntryCount` ise (yeni entry girildiyse):
 *   Solukluk kalkar ve yeniden Parlak (Okunmamış - `OledTopicUnread`) gösterilir!
 */
@Composable
private fun TopicPagingList(
    pagingItems: LazyPagingItems<TopicItem>,
    listState: LazyListState,
    isDebeMode: Boolean,
    readStateByUrl: Map<String, TopicReadStateEntity>,
    lastSeenEntryCounts: Map<String, Int>,
    onTopicClick: (TopicItem) -> Unit,
    onOpenCloudflare: () -> Unit,
    emptyMessage: String
) {
    val refreshState = pagingItems.loadState.refresh

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (refreshState is LoadState.Loading && pagingItems.itemCount == 0) {
            TopicListSkeletonShimmer()
            return@Box
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (refreshState is LoadState.Loading && pagingItems.itemCount > 0) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = EksiNeonGreen,
                    trackColor = Color.Transparent
                )
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .testTag("topic_paging_list"),
                contentPadding = PaddingValues(vertical = 2.dp)
            ) {
                items(
                    count = pagingItems.itemCount,
                    key = { index -> "${index}_${pagingItems.peek(index)?.url ?: "topic"}" }
                ) { index ->
                    val item = pagingItems[index] ?: return@items
                    val savedRead = readStateByUrl[item.cleanUrl]
                    val normalizedUrlKey = item.cleanUrl.trim().lowercase()
                    val titleKey = "title:${item.title.trim().lowercase()}"

                    val lastSeenCount: Int? = lastSeenEntryCounts[normalizedUrlKey]
                        ?: lastSeenEntryCounts[titleKey]
                        ?: if (savedRead != null) 0 else null

                    val hasNewEntriesAfterRead = lastSeenCount != null &&
                        lastSeenCount > 0 &&
                        item.entryCount > lastSeenCount

                    val isRead = lastSeenCount != null && !hasNewEntriesAfterRead

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.background)
                            .clickable { onTopicClick(item) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (isDebeMode) {
                                Surface(
                                    color = OledPillGreenBg,
                                    shape = CircleShape,
                                    modifier = Modifier
                                        .size(28.dp)
                                        .border(0.6.dp, EksiNeonGreen.copy(alpha = 0.4f), CircleShape)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "${index + 1}",
                                            color = EksiNeonGreen,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }

                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = item.title,
                                    fontSize = 15.sp,
                                    lineHeight = 21.sp,
                                    color = if (isRead) OledTopicRead else OledTopicUnread,
                                    fontWeight = when {
                                        hasNewEntriesAfterRead -> FontWeight.SemiBold
                                        isRead -> FontWeight.Normal
                                        else -> FontWeight.Medium
                                    }
                                )
                                if (hasNewEntriesAfterRead && lastSeenCount != null) {
                                    val addedCount = (item.entryCount - lastSeenCount).coerceAtLeast(1)
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "+$addedCount yeni entry" + (savedRead?.let { " • Kalınan: Sayfa ${it.pageNumber}" } ?: ""),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EksiNeonGreen
                                    )
                                } else if (savedRead != null) {
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "Kalınan: Sayfa ${savedRead.pageNumber} • #${savedRead.entryId}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontSize = 11.sp,
                                        color = EksiNeonGreen.copy(alpha = 0.75f)
                                    )
                                }
                            }
                        }

                        val badgeText = when {
                            isDebeMode && item.favoriteCount > 0 -> "♥ ${item.favoriteCount}"
                            isDebeMode && item.entryCountText.isNotBlank() -> item.entryCountText
                            isDebeMode -> "♥ ..."
                            item.entryCountText.isNotBlank() -> item.entryCountText
                            item.entryCount > 0 -> item.entryCount.toString()
                            else -> ""
                        }

                        if (badgeText.isNotBlank()) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Surface(
                                color = if (hasNewEntriesAfterRead) EksiNeonGreen else OledPillGreenBg,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = badgeText,
                                    color = if (hasNewEntriesAfterRead) OledOnAccentColor else EksiNeonGreen,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)
                }

                if (pagingItems.loadState.append is LoadState.Loading) {
                    item {
                        TopicListSkeletonShimmer(itemCount = 3)
                    }
                }
            }
        }

        if (refreshState is LoadState.Error && pagingItems.itemCount == 0) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(20.dp)
                    .fillMaxWidth()
                    .border(1.dp, OledHairlineDivider, RoundedCornerShape(16.dp))
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
                        text = "Bağlantı Kontrolü",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = OledTextPrimary
                    )
                    Text(
                        text = refreshState.error.localizedMessage ?: "Bağlantı hatası",
                        style = MaterialTheme.typography.bodySmall,
                        color = OledTextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AssistChip(
                            onClick = { pagingItems.retry() },
                            label = { Text("Tekrar Dene") },
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = OledTextPrimary
                            )
                        )
                        Button(
                            onClick = onOpenCloudflare,
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
        } else if (refreshState is LoadState.NotLoading && pagingItems.itemCount == 0) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = emptyMessage,
                    color = OledTextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfileAndStudioScreenContent(
    viewModel: EksinViewModel,
    hasUserSession: Boolean,
    onOpenTopicUrl: (String, String) -> Unit
) {
    val readStates by viewModel.allReadStates.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarkedEntries.collectAsStateWithLifecycle()
    val hasCfClearance by viewModel.hasCfClearance.collectAsStateWithLifecycle()
    val currentTheme by viewModel.themeMode.collectAsStateWithLifecycle()
    val savedGeminiKey by viewModel.geminiApiKey.collectAsStateWithLifecycle()
    val isGeminiKeyConfigured by viewModel.isGeminiKeyConfigured.collectAsStateWithLifecycle()

    val selectedModel by viewModel.selectedImageModel.collectAsStateWithLifecycle()
    val selectedSize by viewModel.selectedImageSize.collectAsStateWithLifecycle()
    val selectedRatio by viewModel.selectedAspectRatio.collectAsStateWithLifecycle()
    val aiImageState by viewModel.aiImageState.collectAsStateWithLifecycle()

    var apiKeyInput by rememberSaveable(savedGeminiKey) {
        mutableStateOf(savedGeminiKey)
    }
    var showApiKeyPlain by rememberSaveable { mutableStateOf(false) }
    var apiKeyFeedbackMessage by remember { mutableStateOf<String?>(null) }

    var promptInput by rememberSaveable {
        mutableStateOf("Gece yarısı OLED siyah ekranda yeşil neon ışıklarla aydınlanan fütüristik sözlük kütüphanesi")
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 0. Tema / Renk Seçici Kartı (OLED Siyah, Koyu Gri, Klasik Beyaz)
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, OledHairlineDivider, RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Palette,
                            contentDescription = null,
                            tint = EksiNeonGreen
                        )
                        Text(
                            text = "Arayüz Teması (OLED / Koyu / Beyaz)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = EksiNeonGreen
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppThemeMode.entries.forEach { mode ->
                            val isSelected = currentTheme == mode
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.selectThemeMode(mode) },
                                label = {
                                    Text(
                                        text = mode.title,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EksiNeonGreen,
                                    selectedLabelColor = OledOnAccentColor,
                                    containerColor = MaterialTheme.colorScheme.background,
                                    labelColor = OledTextPrimary
                                ),
                                modifier = Modifier.testTag("profile_theme_${mode.storageKey}")
                            )
                        }
                    }
                }
            }
        }

        // 1. Oturum & Cloudflare Kartı
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, OledHairlineDivider, RoundedCornerShape(14.dp))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = EksiNeonGreen
                        )
                        Text(
                            text = "Profil, Oturum & Bağlantı",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = EksiNeonGreen
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Ekşi Sözlük Oturumu: ${if (hasUserSession) "Açık" else "Misafir Modu"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (hasUserSession) EksiNeonGreen else OledTextSecondary
                    )
                    Text(
                        text = "• Bağlantı Durumu: ${if (hasCfClearance) "Doğrulandı (Aktif)" else "Bekleniyor"}",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (hasCfClearance) EksiNeonGreen else Color(0xFFFFB74D)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.openUserLogin() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EksiNeonGreen,
                                contentColor = OledOnAccentColor
                            ),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.testTag("profile_login_button")
                        ) {
                            Text("Ekşi Giriş Yap", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { viewModel.openCloudflareVerification() },
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.testTag("open_auth_webview_button")
                        ) {
                            Text("Doğrulama", color = EksiNeonGreen)
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.cookieJar.clearAllCookies {
                                    viewModel.refreshCookieState()
                                }
                            },
                            shape = RoundedCornerShape(50)
                        ) {
                            Text("Sıfırla", color = OledTextSecondary)
                        }
                    }
                }
            }
        }

        // 1.5 Gemini API Anahtarı (Google AI Studio - Free Tier & SharedPreferences)
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, OledHairlineDivider, RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = EksiNeonGreen
                            )
                            Text(
                                text = "Gemini API Anahtarı (Google AI Studio)",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = EksiNeonGreen
                            )
                        }

                        Surface(
                            color = if (isGeminiKeyConfigured) OledPillGreenBg else MaterialTheme.colorScheme.background,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.border(
                                width = 0.6.dp,
                                color = if (isGeminiKeyConfigured) EksiNeonGreen else OledHairlineDivider,
                                shape = RoundedCornerShape(50)
                            )
                        ) {
                            Text(
                                text = if (isGeminiKeyConfigured) "Aktif" else "Girilmedi",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isGeminiKeyConfigured) EksiNeonGreen else OledTextSecondary,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Text(
                        text = "Başlık içi '✨ AI Özet' (gemini-3.5-flash-lite) özelliği için ücretsiz Google AI Studio API anahtarınızı buraya girin. Anahtarınız yalnızca cihazınızda (SharedPreferences) saklanır.",
                        style = MaterialTheme.typography.bodySmall,
                        color = OledTextSecondary
                    )

                    OutlinedTextField(
                        value = apiKeyInput,
                        onValueChange = {
                            apiKeyInput = it
                            apiKeyFeedbackMessage = null
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("gemini_api_key_input"),
                        singleLine = true,
                        label = { Text("AIzaSy... (Gemini API Key)") },
                        visualTransformation = if (showApiKeyPlain) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailingIcon = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                AssistChip(
                                    onClick = { showApiKeyPlain = !showApiKeyPlain },
                                    label = {
                                        Text(
                                            text = if (showApiKeyPlain) "Gizle" else "Göster",
                                            fontSize = 11.sp
                                        )
                                    },
                                    modifier = Modifier.padding(end = 6.dp)
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EksiNeonGreen,
                            unfocusedBorderColor = OledHairlineDivider,
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            focusedTextColor = OledTextPrimary,
                            unfocusedTextColor = OledTextPrimary
                        )
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                viewModel.saveGeminiApiKey(apiKeyInput)
                                apiKeyFeedbackMessage = "Gemini API anahtarı cihazınıza (SharedPreferences) kaydedildi."
                            },
                            enabled = apiKeyInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EksiNeonGreen,
                                contentColor = OledOnAccentColor
                            ),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("save_gemini_api_key_button")
                        ) {
                            Text("Anahtarı Kaydet", fontWeight = FontWeight.Bold)
                        }

                        if (savedGeminiKey.isNotBlank() || apiKeyInput.isNotBlank()) {
                            OutlinedButton(
                                onClick = {
                                    apiKeyInput = ""
                                    viewModel.clearGeminiApiKey()
                                    apiKeyFeedbackMessage = "Kayıtlı API anahtarı temizlendi."
                                },
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.testTag("clear_gemini_api_key_button")
                            ) {
                                Text("Temizle", color = OledTextSecondary)
                            }
                        }
                    }

                    apiKeyFeedbackMessage?.let { feedback ->
                        Text(
                            text = feedback,
                            style = MaterialTheme.typography.labelSmall,
                            color = EksiNeonGreen,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 2. Okunan Yer Takibi (Room ReadStateDao)
        item {
            Text(
                text = "Son Okunan Başlıklar & Kalınan Yer (${readStates.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = EksiNeonGreen
            )
        }

        if (readStates.isEmpty()) {
            item {
                Text(
                    text = "Henüz okunan başlık kaydı yok. Başlık okurken kaldığınız entryId, pageNumber ve scrollOffset otomatik kaydedilir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OledTextSecondary
                )
            }
        } else {
            items(readStates, key = { it.topicKey }) { state: TopicReadStateEntity ->
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.5.dp, OledHairlineDivider, RoundedCornerShape(12.dp))
                        .clickable { onOpenTopicUrl(state.topicTitle, state.topicUrl) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state.topicTitle,
                                style = MaterialTheme.typography.titleSmall,
                                color = OledTextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Kalınan Sayfa: ${state.pageNumber}/${state.totalKnownPages} • Entry #${state.entryId}",
                                style = MaterialTheme.typography.labelSmall,
                                color = EksiNeonGreen
                            )
                        }
                        IconButton(
                            onClick = { viewModel.deleteReadState(state.topicKey) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Kaydı Sil",
                                tint = OledTextSecondary
                            )
                        }
                    }
                }
            }
        }

        // 3. Favorilenen / Şükela Entry Arşivi
        item {
            Text(
                text = "Favorilenen Entry'ler (${bookmarks.size})",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = OledTextPrimary
            )
        }

        items(bookmarks, key = { it.entryId }) { item: BookmarkedEntryEntity ->
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, OledHairlineDivider, RoundedCornerShape(12.dp))
                    .clickable { onOpenTopicUrl(item.topicTitle, item.topicUrl) }
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.topicTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = EksiNeonGreen,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = { viewModel.removeBookmark(item.entryId) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Sil",
                                tint = OledTextSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = item.contentText,
                        style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 22.sp),
                        color = OledTextPrimary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "@${item.author} • ${item.dateText}",
                        style = MaterialTheme.typography.labelSmall,
                        color = OledTextMuted
                    )
                }
            }
        }

        // 4. Gemini Görsel Üretim Stüdyosu (1K/2K/4K ve 8 Aspect Ratio seçeneği)
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(0.5.dp, OledHairlineDivider, RoundedCornerShape(14.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Gemini Görsel Stüdyosu (1K/2K/4K & En-Boy Oranı)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = EksiNeonGreen
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        StudioImageModel.entries.forEach { model ->
                            FilterChip(
                                selected = selectedModel == model,
                                onClick = { viewModel.selectImageModel(model) },
                                label = { Text(model.displayName, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EksiNeonGreen,
                                    selectedLabelColor = OledOnAccentColor,
                                    containerColor = MaterialTheme.colorScheme.background,
                                    labelColor = OledTextPrimary
                                )
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StudioImageSize.entries.forEach { size ->
                            FilterChip(
                                selected = selectedSize == size,
                                onClick = { viewModel.selectImageSize(size) },
                                label = { Text(size.label, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EksiNeonGreen,
                                    selectedLabelColor = OledOnAccentColor,
                                    containerColor = MaterialTheme.colorScheme.background,
                                    labelColor = OledTextPrimary
                                )
                            )
                        }
                    }

                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        StudioAspectRatio.entries.forEach { ratio ->
                            FilterChip(
                                selected = selectedRatio == ratio,
                                onClick = { viewModel.selectAspectRatio(ratio) },
                                label = { Text(ratio.label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = EksiNeonGreen,
                                    selectedLabelColor = OledOnAccentColor,
                                    containerColor = MaterialTheme.colorScheme.background,
                                    labelColor = OledTextPrimary
                                )
                            )
                        }
                    }

                    OutlinedTextField(
                        value = promptInput,
                        onValueChange = { promptInput = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Görsel İstemi (Prompt)") },
                        minLines = 2,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = EksiNeonGreen,
                            unfocusedBorderColor = OledHairlineDivider,
                            focusedContainerColor = MaterialTheme.colorScheme.background,
                            unfocusedContainerColor = MaterialTheme.colorScheme.background,
                            focusedTextColor = OledTextPrimary,
                            unfocusedTextColor = OledTextPrimary
                        )
                    )

                    Button(
                        onClick = { viewModel.generateEntryVisual(promptInput) },
                        enabled = aiImageState !is AiImageState.Loading && promptInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EksiNeonGreen,
                            contentColor = OledOnAccentColor
                        ),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Görsel Üret (${selectedSize.label} • ${selectedRatio.label})",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    when (val state = aiImageState) {
                        is AiImageState.Idle -> Unit
                        is AiImageState.Loading -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    color = EksiNeonGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    text = "Görsel üretiliyor...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OledTextSecondary
                                )
                            }
                        }
                        is AiImageState.Success -> {
                            Image(
                                bitmap = state.bitmap.asImageBitmap(),
                                contentDescription = "Üretilen Görsel",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.FillWidth
                            )
                        }
                        is AiImageState.Error -> {
                            Text(
                                text = state.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}
