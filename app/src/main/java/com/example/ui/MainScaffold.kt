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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.example.data.local.BookmarkedEntryEntity
import com.example.data.local.TopicReadStateEntity
import com.example.data.model.LeftFrameSection
import com.example.data.model.SearchSortOrder
import com.example.data.model.TopicItem
import com.example.ui.auth.AuthWebViewScreen
import com.example.ui.components.TopicListSkeletonShimmer
import com.example.ui.theme.EksiNeonGreen
import com.example.ui.theme.OledCardSurface
import com.example.ui.theme.OledHairlineDivider
import com.example.ui.theme.OledPillGreenBg
import com.example.ui.theme.OledPitchBlack
import com.example.ui.theme.OledTextMuted
import com.example.ui.theme.OledTextPrimary
import com.example.ui.theme.OledTextSecondary
import com.example.ui.theme.OledTopicRead
import com.example.ui.theme.OledTopicUnread

/**
 * Tek elle kullanıma uygun Alt Navigasyon Barı sekmeleri:
 * Gündem, Bugün, Olaylar, Arama, Profil
 */
enum class MainNavDestination(
    val title: String,
    val icon: ImageVector,
    val testTag: String
) {
    GUNDEM("Gündem", Icons.Default.Whatshot, "nav_gundem"),
    BUGUN("Bugün", Icons.Default.Today, "nav_bugun"),
    OLAYLAR("Olaylar", Icons.Default.NotificationsActive, "nav_olaylar"),
    ARAMA("Arama", Icons.Default.Search, "nav_arama"),
    PROFIL("Profil", Icons.Default.Person, "nav_profil")
}

/**
 * Premium OLED Ana İskelet (`MainScaffold.kt`):
 * - Kural 1: Sadeleştirilmiş Üst Bar ("CF OK" metni ve hata bandı bağlantı sağlamken tamamen gizli;
 *   kalkan ikonu üzerinde küçük yeşil durum noktası; modern kapsül "DEBE" çipi; aşağı kaydırırken
 *   yumuşak animasyonla gizlenen ve yukarı kaydırınca geri gelen üst bar).
 * - Kural 2: Gerçek OLED siyah (#000000) zemin, #161616 ince ayırıcılar, 15sp #EDEDED okunmamış /
 *   #888888 okunmuş başlık metinleri ve sağda #112415 dolgulu #81C784 metinli hap (pill) rozetleri.
 * - Kural 6: Veriler yüklenirken dönen çember yerine `TopicListSkeletonShimmer` animasyonu.
 */
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

    val readStateByUrl = remember(allReadStates) {
        allReadStates.associateBy { it.topicKey }
    }

    var currentDestination by rememberSaveable { mutableStateOf(MainNavDestination.GUNDEM) }

    // 1. Kullanıcı Cloudflare Doğrulama veya Giriş WebView Ekranını açtığında göster
    activeAuthSheet?.let { sheet ->
        AuthWebViewScreen(
            initialUrl = sheet.url,
            mode = sheet.mode,
            cookieJar = viewModel.cookieJar,
            userAgentProvider = viewModel.userAgentProvider,
            webViewFetchBridge = viewModel.webViewFetchBridge,
            onVerificationSuccess = { viewModel.onAuthVerificationCompleted() },
            onDismiss = { viewModel.onAuthVerificationDismissed() }
        )
        return
    }

    // 2. Başlık Detay Ekranı
    selectedTopic?.let { topic ->
        TopicDetailScreen(
            topic = topic,
            viewModel = viewModel,
            onBack = { viewModel.closeTopicDetail() }
        )
        return
    }

    val leftFrameListState = rememberLazyListState()
    val isHeaderVisible = rememberDirectionalHeaderVisibility(leftFrameListState)
    val isDebeActive = currentDestination == MainNavDestination.GUNDEM &&
        selectedSection == LeftFrameSection.DEBE

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = OledPitchBlack,
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            AnimatedVisibility(
                visible = isHeaderVisible || currentDestination == MainNavDestination.ARAMA || currentDestination == MainNavDestination.PROFIL,
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
                        .background(OledPitchBlack)
                ) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = OledPitchBlack,
                            titleContentColor = OledTextPrimary
                        ),
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(EksiNeonGreen)
                                )
                                Text(
                                    text = "ekşin oled",
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 19.sp,
                                    letterSpacing = (-0.3).sp,
                                    color = OledTextPrimary
                                )
                                if (isDebeActive) {
                                    Surface(
                                        color = OledPillGreenBg,
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text(
                                            text = "dünün en beğenilenleri",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = EksiNeonGreen,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        },
                        actions = {
                            // Modern Kapsül "DEBE" Butonu (Kural 1)
                            if (currentDestination == MainNavDestination.GUNDEM ||
                                currentDestination == MainNavDestination.BUGUN
                            ) {
                                Surface(
                                    color = if (isDebeActive) EksiNeonGreen else OledPillGreenBg,
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .clip(RoundedCornerShape(50))
                                        .clickable {
                                            currentDestination = MainNavDestination.GUNDEM
                                            if (selectedSection == LeftFrameSection.DEBE) {
                                                viewModel.selectSection(LeftFrameSection.GUNDEM)
                                            } else {
                                                viewModel.selectSection(LeftFrameSection.DEBE)
                                            }
                                        }
                                        .border(
                                            width = 0.8.dp,
                                            color = EksiNeonGreen.copy(alpha = if (isDebeActive) 1f else 0.45f),
                                            shape = RoundedCornerShape(50)
                                        )
                                        .testTag("debe_capsule_chip")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Whatshot,
                                            contentDescription = null,
                                            tint = if (isDebeActive) Color.Black else EksiNeonGreen,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "DEBE",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = if (isDebeActive) Color.Black else EksiNeonGreen
                                        )
                                    }
                                }
                            }

                            // Kalkan ikonu + sadece küçük yeşil durum noktası ("CF OK" metni gizlendi - Kural 1)
                            IconButton(
                                onClick = { viewModel.openCloudflareVerification() },
                                modifier = Modifier.testTag("top_cloudflare_button")
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.Security,
                                        contentDescription = "Cloudflare Durumu",
                                        tint = OledTextSecondary,
                                        modifier = Modifier.size(21.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(
                                                if (hasCfClearance) EksiNeonGreen else Color(0xFFFFB74D)
                                            )
                                            .border(1.2.dp, OledPitchBlack, CircleShape)
                                            .testTag("cf_status_badge")
                                    )
                                }
                            }

                            IconButton(
                                onClick = { viewModel.triggerRefresh() },
                                modifier = Modifier.testTag("top_refresh_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Yenile",
                                    tint = OledTextSecondary,
                                    modifier = Modifier.size(21.dp)
                                )
                            }
                        }
                    )
                    HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)
                }
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = OledPitchBlack,
                contentColor = OledTextPrimary,
                tonalElevation = 0.dp,
                modifier = Modifier.border(0.5.dp, OledHairlineDivider)
            ) {
                val navColors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color.Black,
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
                            currentDestination = destination
                            when (destination) {
                                MainNavDestination.GUNDEM ->
                                    viewModel.selectSection(LeftFrameSection.GUNDEM)
                                MainNavDestination.BUGUN ->
                                    viewModel.selectSection(LeftFrameSection.BUGUN)
                                MainNavDestination.OLAYLAR ->
                                    viewModel.selectSection(LeftFrameSection.OLAYLAR)
                                MainNavDestination.ARAMA,
                                MainNavDestination.PROFIL -> Unit
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
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
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
                .background(OledPitchBlack)
        ) {
            // Kural 1: Bağlantı sorunsuz çalışırken hata bandı tamamen gizlidir;
            // yalnızca bağlantı koptuğunda ve doğrulama gerektiğinde görünür.
            if (!hasCfClearance && pendingCfChallenge != null) {
                val challenge = pendingCfChallenge
                if (challenge != null) {
                    Surface(
                        color = Color(0xFF14100C),
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
                    .background(OledPitchBlack)
            ) {
                when (currentDestination) {
                    MainNavDestination.GUNDEM,
                    MainNavDestination.BUGUN,
                    MainNavDestination.OLAYLAR -> {
                        val pagingItems = viewModel.leftFramePagingFlow.collectAsLazyPagingItems()
                        LaunchedEffect(pagingItems.itemCount) {
                            if (pagingItems.itemCount > 0) {
                                viewModel.refreshCookieState()
                            }
                        }

                        Column(modifier = Modifier.fillMaxSize()) {
                            // DEBE aktifse üstte zarif bir bilgi başlığı göster
                            AnimatedVisibility(visible = isDebeActive) {
                                Surface(
                                    color = Color(0xFF0A140C),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Whatshot,
                                                contentDescription = null,
                                                tint = EksiNeonGreen,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "DEBE — Dünün En Beğenilen Entry'leri",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = EksiNeonGreen
                                            )
                                        }
                                        Text(
                                            text = "Gündeme Dön ✕",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = OledTextSecondary,
                                            modifier = Modifier.clickable {
                                                viewModel.selectSection(LeftFrameSection.GUNDEM)
                                            }
                                        )
                                    }
                                }
                            }

                            TopicPagingList(
                                pagingItems = pagingItems,
                                listState = leftFrameListState,
                                isDebeMode = isDebeActive,
                                readStateByUrl = readStateByUrl,
                                onTopicClick = { viewModel.openTopic(it) },
                                onOpenCloudflare = { viewModel.openCloudflareVerification() },
                                emptyMessage = "${selectedSection.title} sekmesinde başlık bulunamadı."
                            )
                        }
                    }

                    MainNavDestination.ARAMA -> {
                        SearchScreenContent(
                            viewModel = viewModel,
                            readStateByUrl = readStateByUrl,
                            onTopicClick = { viewModel.openTopic(it) },
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
                                    )
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Kullanıcı listeyi aşağı kaydırırken üst barı gizleyen, yukarı kaydırınca veya listenin
 * en üstündeyken geri getiren yön algılayıcı (Kural 1).
 */
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

@Composable
private fun SearchScreenContent(
    viewModel: EksinViewModel,
    readStateByUrl: Map<String, TopicReadStateEntity>,
    onTopicClick: (TopicItem) -> Unit,
    onOpenCloudflare: () -> Unit
) {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortOrder by viewModel.searchSortOrder.collectAsStateWithLifecycle()
    val submittedFilter by viewModel.submittedSearchFilter.collectAsStateWithLifecycle()
    val searchPagingItems = viewModel.searchTopicsPagingFlow.collectAsLazyPagingItems()
    val searchListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(OledPitchBlack)
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
                    text = "Başlık ara (p=1, 2, 3... kesintisiz sayfalama)",
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
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
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
                    viewModel.submitSearch()
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = EksiNeonGreen,
                unfocusedBorderColor = OledHairlineDivider,
                focusedContainerColor = OledCardSurface,
                unfocusedContainerColor = OledCardSurface,
                focusedTextColor = OledTextPrimary,
                unfocusedTextColor = OledTextPrimary
            ),
            shape = RoundedCornerShape(14.dp)
        )

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
                        selectedLabelColor = Color.Black,
                        containerColor = OledCardSurface,
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
                    text = "Aramak istediğiniz başlığı yazıp klavyeden Ara tuşuna basın.",
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
                onTopicClick = onTopicClick,
                onOpenCloudflare = onOpenCloudflare,
                emptyMessage = "\"${submittedFilter.keywords}\" için başlık sonucu bulunamadı."
            )
        }
    }
}

/**
 * Kural 2 & Kural 6:
 * - Gerçek OLED siyah (#000000) zemin üzerinde çok ince (#161616) ayırıcı çizgiler.
 * - Başlık metinleri 15sp (#EDEDED), daha önce okunmuş başlıklar hafif soluk (#888888).
 * - Sağ taraftaki entry sayıları koyu yeşil (#112415) dolgulu, parlak yeşil (#81C784) metinli
 *   yuvarlak hap (pill badge) içinde hizalı.
 * - Yükleme sırasında dönen çember yerine `TopicListSkeletonShimmer` gösterilir.
 */
@Composable
private fun TopicPagingList(
    pagingItems: LazyPagingItems<TopicItem>,
    listState: LazyListState,
    isDebeMode: Boolean,
    readStateByUrl: Map<String, TopicReadStateEntity>,
    onTopicClick: (TopicItem) -> Unit,
    onOpenCloudflare: () -> Unit,
    emptyMessage: String
) {
    val refreshState = pagingItems.loadState.refresh

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OledPitchBlack)
    ) {
        if (refreshState is LoadState.Loading && pagingItems.itemCount == 0) {
            TopicListSkeletonShimmer()
            return@Box
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .background(OledPitchBlack)
                .testTag("topic_paging_list"),
            contentPadding = PaddingValues(vertical = 2.dp)
        ) {
            items(
                count = pagingItems.itemCount,
                key = { index -> "${index}_${pagingItems.peek(index)?.url ?: "topic"}" }
            ) { index ->
                val item = pagingItems[index] ?: return@items
                val savedRead = readStateByUrl[item.cleanUrl]
                val isRead = savedRead != null

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(OledPitchBlack)
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
                        // DEBE modunda sıra numarası rozeti (#1, #2, #3...)
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
                                fontWeight = if (isRead) FontWeight.Normal else FontWeight.Medium
                            )
                            if (savedRead != null) {
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
                        isDebeMode -> "★ DEBE"
                        item.entryCountText.isNotBlank() -> item.entryCountText
                        item.entryCount > 0 -> item.entryCount.toString()
                        else -> ""
                    }

                    if (badgeText.isNotBlank()) {
                        Spacer(modifier = Modifier.width(12.dp))
                        // Kural 2: Koyu yeşil (#112415) dolgulu, parlak yeşil (#81C784) metinli yuvarlak hap (pill badge)
                        Surface(
                            color = OledPillGreenBg,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = badgeText,
                                color = EksiNeonGreen,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                // Kural 2: Çok ince (#161616) ayırıcı çizgi
                HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)
            }

            if (pagingItems.loadState.append is LoadState.Loading) {
                item {
                    TopicListSkeletonShimmer(itemCount = 3)
                }
            }
        }

        if (refreshState is LoadState.Error && pagingItems.itemCount == 0) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = OledCardSurface),
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
                                contentColor = Color.Black
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

    val selectedModel by viewModel.selectedImageModel.collectAsStateWithLifecycle()
    val selectedSize by viewModel.selectedImageSize.collectAsStateWithLifecycle()
    val selectedRatio by viewModel.selectedAspectRatio.collectAsStateWithLifecycle()
    val aiImageState by viewModel.aiImageState.collectAsStateWithLifecycle()

    var promptInput by rememberSaveable {
        mutableStateOf("Gece yarısı OLED siyah ekranda yeşil neon ışıklarla aydınlanan fütüristik sözlük kütüphanesi")
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(OledPitchBlack),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Oturum & Cloudflare Kartı
        item {
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = OledCardSurface),
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
                                contentColor = Color.Black
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
                    colors = CardDefaults.cardColors(containerColor = OledCardSurface),
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
                colors = CardDefaults.cardColors(containerColor = OledCardSurface),
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
                colors = CardDefaults.cardColors(containerColor = OledCardSurface),
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
                                    selectedLabelColor = Color.Black,
                                    containerColor = OledPitchBlack,
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
                                    selectedLabelColor = Color.Black,
                                    containerColor = OledPitchBlack,
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
                                    selectedLabelColor = Color.Black,
                                    containerColor = OledPitchBlack,
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
                            focusedContainerColor = OledPitchBlack,
                            unfocusedContainerColor = OledPitchBlack,
                            focusedTextColor = OledTextPrimary,
                            unfocusedTextColor = OledTextPrimary
                        )
                    )

                    Button(
                        onClick = { viewModel.generateEntryVisual(promptInput) },
                        enabled = aiImageState !is AiImageState.Loading && promptInput.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EksiNeonGreen,
                            contentColor = Color.Black
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
