package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.LruCache
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.ai.GeminiAnalysisMode
import com.example.ai.GeminiHelper
import com.example.ai.GeminiImageGenerationResult
import com.example.ai.GeminiStreamState
import com.example.data.local.BookmarkedEntryEntity
import com.example.data.local.TopicReadStateEntity
import com.example.data.model.DebeAndStatsCategory
import com.example.data.model.DebeAndStatsUiState
import com.example.data.model.EksiChannel
import com.example.data.model.EntryItem
import com.example.data.model.LeftFrameSection
import com.example.data.model.PeriodicRankingTab
import com.example.data.model.PeriodicTimeRange
import com.example.data.model.RankedTopicOrEntryItem
import com.example.data.model.SearchFilter
import com.example.data.model.SearchSortOrder
import com.example.data.model.TopicFilterMode
import com.example.data.model.TopicItem
import com.example.data.model.TopicPageData
import com.example.data.repository.EksiRepository
import com.example.di.AppModule
import com.example.network.CloudflareChallengeEvent
import com.example.network.CloudflareChallengeManager
import com.example.network.ContentSource
import com.example.network.UserAgentProvider
import com.example.network.WebViewCookieJar
import com.example.network.WebViewFetchBridge
import com.example.ui.auth.AuthWebViewMode
import com.example.ui.sheets.BkzPreviewUiState
import com.example.ui.theme.AppThemeMode
import com.example.ui.theme.ThemePreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

sealed interface AiImageState {
    data object Idle : AiImageState
    data object Loading : AiImageState
    data class Success(
        val bitmap: Bitmap,
        val modelUsed: String,
        val sizeLabel: String,
        val aspectRatioLabel: String
    ) : AiImageState
    data class LocalFallbackPreview(
        val bitmap: Bitmap,
        val reason: String,
        val sizeLabel: String,
        val aspectRatioLabel: String
    ) : AiImageState
    data class Error(val message: String) : AiImageState
}

/**
 * Başlık içi Direct Page State (Doğrudan Sayfa Durumu):
 * Paging 3'ün `prevKey` / `nextKey` sonsuz geri sarma döngüsü yerine
 * kullanıcının seçtiği `currentPage` sayfasını tekil ve deterministik olarak tutar.
 */
sealed interface TopicPageUiState {
    data object Idle : TopicPageUiState
    @Immutable
    data class Loading(val targetPage: Int) : TopicPageUiState
    @Immutable
    data class Success(
        val pageData: TopicPageData,
        val entries: List<EntryItem>,
        val loadSequence: Int
    ) : TopicPageUiState
    @Immutable
    data class Error(
        val targetPage: Int,
        val message: String
    ) : TopicPageUiState
}

enum class StudioImageSize(val label: String, val pixelDimension: Int) {
    SIZE_1K("1K", 1024),
    SIZE_2K("2K", 2048),
    SIZE_4K("4K", 4096)
}

enum class StudioAspectRatio(val label: String) {
    RATIO_1_1("1:1"),
    RATIO_2_3("2:3"),
    RATIO_3_2("3:2"),
    RATIO_3_4("3:4"),
    RATIO_4_3("4:3"),
    RATIO_9_16("9:16"),
    RATIO_16_9("16:9"),
    RATIO_21_9("21:9")
}

enum class StudioImageModel(val modelId: String, val displayName: String) {
    PRO_STUDIO("gemini-3-pro-image", "Gemini 3 Pro Image (Stüdyo 1K/2K/4K)"),
    NANO_BANANA("gemini-nano-banana-2.1", "Gemini Nano Banana 2.1 (Hızlı)")
}

@Immutable
data class ActiveAuthSheet(
    val url: String,
    val mode: AuthWebViewMode,
    val reason: String
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class EksinViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: EksiRepository,
    val cookieJar: WebViewCookieJar,
    val userAgentProvider: UserAgentProvider,
    val webViewFetchBridge: WebViewFetchBridge,
    private val cloudflareManager: CloudflareChallengeManager,
    private val geminiHelper: GeminiHelper,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // 4. ARAYÜZ RENK / TEMA SEÇENEĞİ (OLED Siyah, Koyu Gri, Klasik Beyaz)
    private val _themeMode = MutableStateFlow(ThemePreferencesManager.loadSavedTheme(appContext))
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _showThemeDialog = MutableStateFlow(false)
    val showThemeDialog: StateFlow<Boolean> = _showThemeDialog.asStateFlow()

    // Process Death Korumalı Aktif Alt Navigasyon Sekmesi
    private val _activeNavDestination = MutableStateFlow(loadInitialNavDestination())
    val activeNavDestination: StateFlow<MainNavDestination> = _activeNavDestination.asStateFlow()

    // Sol Frame Sekmesi
    private val _selectedSection = MutableStateFlow(loadInitialSection())
    val selectedSection: StateFlow<LeftFrameSection> = _selectedSection.asStateFlow()

    // 1. KANALLAR SEKMESİ (#spor, #teknoloji, #siyaset, #ekonomi, ...)
    private val _selectedChannel = MutableStateFlow(loadInitialChannel())
    val selectedChannel: StateFlow<EksiChannel> = _selectedChannel.asStateFlow()

    // 3. GELİŞMİŞ DEBE & DÖNEMSEL ENLER (Gün / Hafta / Ay)
    private val _debeAndStatsCategory = MutableStateFlow(DebeAndStatsCategory.DEBE)
    val debeAndStatsCategory: StateFlow<DebeAndStatsCategory> = _debeAndStatsCategory.asStateFlow()

    private val _periodicTimeRange = MutableStateFlow(PeriodicTimeRange.YESTERDAY)
    val periodicTimeRange: StateFlow<PeriodicTimeRange> = _periodicTimeRange.asStateFlow()

    private val _periodicRankingTab = MutableStateFlow(PeriodicRankingTab.TOP_ENTRIES)
    val periodicRankingTab: StateFlow<PeriodicRankingTab> = _periodicRankingTab.asStateFlow()

    private val _debeAndStatsUiState = MutableStateFlow<DebeAndStatsUiState>(DebeAndStatsUiState.Loading)
    val debeAndStatsUiState: StateFlow<DebeAndStatsUiState> = _debeAndStatsUiState.asStateFlow()
    private var activeDebeStatsJob: Job? = null

    private val _refreshTrigger = MutableStateFlow(0)
    private val _gundemRefreshTrigger = MutableStateFlow(0)
    private val _bugunRefreshTrigger = MutableStateFlow(0)
    private val _olaylarRefreshTrigger = MutableStateFlow(0)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchSortOrder = MutableStateFlow(SearchSortOrder.COUNT)
    val searchSortOrder: StateFlow<SearchSortOrder> = _searchSortOrder.asStateFlow()

    private val _submittedSearchFilter = MutableStateFlow(SearchFilter(keywords = ""))
    val submittedSearchFilter: StateFlow<SearchFilter> = _submittedSearchFilter.asStateFlow()

    private val _selectedTopic = MutableStateFlow<TopicItem?>(loadInitialSelectedTopic())
    val selectedTopic: StateFlow<TopicItem?> = _selectedTopic.asStateFlow()

    // 1. KURAL: Direct Page State (`currentPage` ve `totalPages`) + `LruCache<Int, TopicPageData>(12)` Prefetch Cache
    private val _currentPage = MutableStateFlow(loadInitialCurrentPage())
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _totalPages = MutableStateFlow(loadInitialCurrentPage())
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _topicPageUiState = MutableStateFlow<TopicPageUiState>(TopicPageUiState.Idle)
    val topicPageUiState: StateFlow<TopicPageUiState> = _topicPageUiState.asStateFlow()

    private val pageCacheLock = Any()
    private val pageMemoryCache = LruCache<Int, TopicPageData>(MAX_CACHED_TOPIC_PAGES)
    private var activePageLoadJob: Job? = null
    private var activePrefetchJob: Job? = null
    private val pageLoadSequenceGenerator = AtomicInteger(0)
    private var loadedTopicSessionKey: String = ""

    private val _restoredReadState = MutableStateFlow<TopicReadStateEntity?>(null)
    val restoredReadState: StateFlow<TopicReadStateEntity?> = _restoredReadState.asStateFlow()

    // 2. BAŞLIK İÇİ VARSAYILAN "SADECE BUGÜN" MODU (TODAY_POPULAR)
    private val _topicFilterMode = MutableStateFlow(loadInitialTopicFilterMode())
    val topicFilterMode: StateFlow<TopicFilterMode> = _topicFilterMode.asStateFlow()

    private val _inTopicSearchQuery = MutableStateFlow("")
    val inTopicSearchQuery: StateFlow<String> = _inTopicSearchQuery.asStateFlow()

    private val _authorFilterInTopic = MutableStateFlow<String?>(null)
    val authorFilterInTopic: StateFlow<String?> = _authorFilterInTopic.asStateFlow()

    private val _activeTopicMetadata = MutableStateFlow<TopicPageData?>(null)
    val activeTopicMetadata: StateFlow<TopicPageData?> = _activeTopicMetadata.asStateFlow()

    private val _activeAuthSheet = MutableStateFlow<ActiveAuthSheet?>(null)
    val activeAuthSheet: StateFlow<ActiveAuthSheet?> = _activeAuthSheet.asStateFlow()

    private val _pendingCloudflareChallenge = MutableStateFlow<CloudflareChallengeEvent?>(null)
    val pendingCloudflareChallenge: StateFlow<CloudflareChallengeEvent?> =
        _pendingCloudflareChallenge.asStateFlow()

    private val _bkzPreviewState = MutableStateFlow<BkzPreviewUiState>(BkzPreviewUiState.Hidden)
    val bkzPreviewState: StateFlow<BkzPreviewUiState> = _bkzPreviewState.asStateFlow()
    private var activeBkzJob: Job? = null

    private val _hasCfClearance = MutableStateFlow(false)
    val hasCfClearance: StateFlow<Boolean> = _hasCfClearance.asStateFlow()

    private val _hasUserSession = MutableStateFlow(false)
    val hasUserSession: StateFlow<Boolean> = _hasUserSession.asStateFlow()

    val lastContentSource: StateFlow<ContentSource> = webViewFetchBridge.lastContentSource

    private val _geminiStreamState = MutableStateFlow<GeminiStreamState>(GeminiStreamState.Idle)
    val geminiStreamState: StateFlow<GeminiStreamState> = _geminiStreamState.asStateFlow()
    private var activeGeminiJob: Job? = null

    private val _geminiApiKey = MutableStateFlow(geminiHelper.getUserSavedApiKey())
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _isGeminiKeyConfigured = MutableStateFlow(geminiHelper.hasValidApiKey())
    val isGeminiKeyConfigured: StateFlow<Boolean> = _isGeminiKeyConfigured.asStateFlow()

    val geminiKeyDecryptionWarning: StateFlow<String?> = geminiHelper.keyDecryptionWarning

    private val _aiImageState = MutableStateFlow<AiImageState>(AiImageState.Idle)
    val aiImageState: StateFlow<AiImageState> = _aiImageState.asStateFlow()

    private val _selectedImageModel = MutableStateFlow(StudioImageModel.PRO_STUDIO)
    val selectedImageModel: StateFlow<StudioImageModel> = _selectedImageModel.asStateFlow()

    private val _selectedImageSize = MutableStateFlow(StudioImageSize.SIZE_1K)
    val selectedImageSize: StateFlow<StudioImageSize> = _selectedImageSize.asStateFlow()

    private val _selectedAspectRatio = MutableStateFlow(StudioAspectRatio.RATIO_1_1)
    val selectedAspectRatio: StateFlow<StudioAspectRatio> = _selectedAspectRatio.asStateFlow()

    val bookmarkedEntries: StateFlow<List<BookmarkedEntryEntity>> =
        repository.observeBookmarks().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val favoritedEntryIds: StateFlow<Set<String>> =
        bookmarkedEntries
            .map { list -> list.map { it.entryId }.toSet() }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptySet()
            )

    val allReadStates: StateFlow<List<TopicReadStateEntity>> =
        repository.observeAllReadStates().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /**
     * 3. KURAL: Gündem, Bugün ve Olaylar sekmeleri birbirinden tamamen bağımsız StateFlow / PagingFlow
     * olarak tutulur; böylece sekme değiştirildiğinde veya başlık detayından geri dönüldüğünde
     * ne liste verisi ne de kaydırma konumu sıfırlanır.
     */
    val gundemTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_gundemRefreshTrigger, _refreshTrigger) { g, r -> g + r }
            .flatMapLatest { triggerCount ->
                repository.getGundemTopicsPager(bypassCacheOnFirstPage = triggerCount > 0)
            }
            .cachedIn(viewModelScope)

    val bugunTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_bugunRefreshTrigger, _refreshTrigger) { b, r -> b + r }
            .flatMapLatest { triggerCount ->
                repository.getBugunTopicsPager(bypassCacheOnFirstPage = triggerCount > 0)
            }
            .cachedIn(viewModelScope)

    val olaylarTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_olaylarRefreshTrigger, _refreshTrigger) { o, r -> o + r }
            .flatMapLatest { triggerCount ->
                repository.getOlaylarTopicsPager(bypassCacheOnFirstPage = triggerCount > 0)
            }
            .cachedIn(viewModelScope)

    val leftFramePagingFlow: Flow<PagingData<TopicItem>> =
        combine(_selectedSection, _refreshTrigger) { section, r -> section to r }
            .flatMapLatest { (section, triggerCount) ->
                repository.getLeftFrameTopicsPager(
                    section = section,
                    bypassCacheOnFirstPage = triggerCount > 0
                )
            }
            .cachedIn(viewModelScope)

    /**
     * 1. KANALLAR Paging 3 Akışı (`https://eksisozluk.com/basliklar/kanal/{kanal_slug}?p={page}`).
     */
    val channelTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_selectedChannel, _refreshTrigger) { channel, r -> channel to r }
            .flatMapLatest { (channel, triggerCount) ->
                repository.getChannelTopicsPager(
                    channel = channel,
                    bypassCacheOnFirstPage = triggerCount > 0
                )
            }
            .cachedIn(viewModelScope)

    val searchTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_submittedSearchFilter, _refreshTrigger) { filter, r -> filter to r }
            .flatMapLatest { (filter, triggerCount) ->
                repository.getSearchTopicsPager(
                    filter = filter,
                    bypassCacheOnFirstPage = triggerCount > 0
                )
            }
            .cachedIn(viewModelScope)

    init {
        AppModule.registerViewModelCacheCleaner {
            clearPageMemoryCache()
        }

        viewModelScope.launch {
            cloudflareManager.challengeEvents.collect { event: CloudflareChallengeEvent ->
                _pendingCloudflareChallenge.value = event
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            refreshCookieState()
        }

        loadDebeAndStats(forceRefresh = false)

        // Process Death Sonrası Başlık / Sayfa / Filtre Durumunu Otomatik Geri Yükle
        val restoredTopic = _selectedTopic.value
        if (restoredTopic != null) {
            loadedTopicSessionKey = "${restoredTopic.id}|${restoredTopic.url}"
            val restoredPage = _currentPage.value.coerceAtLeast(1)
            goToPage(
                targetPage = restoredPage,
                forceRefresh = false,
                preserveRestoredReadState = false,
                bypassNetworkCache = false
            )
        }
    }

    private fun getCachedPage(page: Int): TopicPageData? = synchronized(pageCacheLock) {
        pageMemoryCache.get(page)
    }

    private fun putCachedPage(page: Int, data: TopicPageData) = synchronized(pageCacheLock) {
        pageMemoryCache.put(page, data)
    }

    private fun removeCachedPage(page: Int) = synchronized(pageCacheLock) {
        pageMemoryCache.remove(page)
    }

    fun clearPageMemoryCache() = synchronized(pageCacheLock) {
        pageMemoryCache.evictAll()
    }

    private fun loadInitialNavDestination(): MainNavDestination {
        val raw = savedStateHandle.get<String>(KEY_SAVED_NAV_DESTINATION)
        return MainNavDestination.entries.firstOrNull { it.name == raw } ?: MainNavDestination.GUNDEM
    }

    private fun loadInitialSection(): LeftFrameSection {
        val raw = savedStateHandle.get<String>(KEY_SAVED_SECTION)
        return LeftFrameSection.entries.firstOrNull { it.name == raw } ?: LeftFrameSection.GUNDEM
    }

    private fun loadInitialChannel(): EksiChannel {
        val raw = savedStateHandle.get<String>(KEY_SAVED_CHANNEL)
        return EksiChannel.entries.firstOrNull { it.name == raw } ?: EksiChannel.SPOR
    }

    private fun loadInitialSelectedTopic(): TopicItem? {
        val url = savedStateHandle.get<String>(KEY_SAVED_TOPIC_URL)?.takeIf { it.isNotBlank() }
            ?: return null
        val title = savedStateHandle.get<String>(KEY_SAVED_TOPIC_TITLE)?.takeIf { it.isNotBlank() }
            ?: return null
        val id = savedStateHandle.get<String>(KEY_SAVED_TOPIC_ID).orEmpty().ifBlank { url }
        val slug = savedStateHandle.get<String>(KEY_SAVED_TOPIC_SLUG).orEmpty()
        return TopicItem(
            id = id,
            title = title,
            slug = slug,
            url = url
        )
    }

    private fun loadInitialCurrentPage(): Int {
        return (savedStateHandle.get<Int>(KEY_SAVED_CURRENT_PAGE) ?: 1).coerceAtLeast(1)
    }

    private fun loadInitialTopicFilterMode(): TopicFilterMode {
        val raw = savedStateHandle.get<String>(KEY_SAVED_TOPIC_FILTER_MODE)
        return TopicFilterMode.entries.firstOrNull { it.name == raw } ?: TopicFilterMode.TODAY_POPULAR
    }

    private fun persistSelectedTopicToSavedState(topic: TopicItem?) {
        if (topic == null) {
            savedStateHandle[KEY_SAVED_TOPIC_ID] = null
            savedStateHandle[KEY_SAVED_TOPIC_TITLE] = null
            savedStateHandle[KEY_SAVED_TOPIC_SLUG] = null
            savedStateHandle[KEY_SAVED_TOPIC_URL] = null
        } else {
            savedStateHandle[KEY_SAVED_TOPIC_ID] = topic.id
            savedStateHandle[KEY_SAVED_TOPIC_TITLE] = topic.title
            savedStateHandle[KEY_SAVED_TOPIC_SLUG] = topic.slug
            savedStateHandle[KEY_SAVED_TOPIC_URL] = topic.url
        }
    }

    private fun persistCurrentPageToSavedState(page: Int) {
        savedStateHandle[KEY_SAVED_CURRENT_PAGE] = page.coerceAtLeast(1)
    }

    private fun persistFilterModeToSavedState(mode: TopicFilterMode) {
        savedStateHandle[KEY_SAVED_TOPIC_FILTER_MODE] = mode.name
    }

    fun selectNavDestination(destination: MainNavDestination) {
        _activeNavDestination.value = destination
        savedStateHandle[KEY_SAVED_NAV_DESTINATION] = destination.name
    }

    // 4. TEMA YÖNETİMİ
    fun openThemeDialog() {
        _showThemeDialog.value = true
    }

    fun dismissThemeDialog() {
        _showThemeDialog.value = false
    }

    fun selectThemeMode(mode: AppThemeMode) {
        ThemePreferencesManager.saveTheme(appContext, mode)
        _themeMode.value = mode
        _showThemeDialog.value = false
    }

    // 1. KANALLAR YÖNETİMİ
    fun selectChannel(channel: EksiChannel) {
        _selectedChannel.value = channel
        savedStateHandle[KEY_SAVED_CHANNEL] = channel.name
    }

    // 3. GELİŞMİŞ DEBE & DÖNEMSEL ENLER YÖNETİMİ
    fun selectDebeAndStatsCategory(category: DebeAndStatsCategory) {
        if (_debeAndStatsCategory.value != category) {
            _debeAndStatsCategory.value = category
            loadDebeAndStats(forceRefresh = false)
        } else if (_debeAndStatsUiState.value !is DebeAndStatsUiState.Success) {
            loadDebeAndStats(forceRefresh = false)
        }
    }

    fun openDebeSection(forceReload: Boolean = false) {
        val categoryChanged = _debeAndStatsCategory.value != DebeAndStatsCategory.DEBE
        _debeAndStatsCategory.value = DebeAndStatsCategory.DEBE
        val currentState = _debeAndStatsUiState.value
        val isEmptySuccess = currentState is DebeAndStatsUiState.Success && currentState.items.isEmpty()
        if (forceReload || categoryChanged || currentState !is DebeAndStatsUiState.Success || isEmptySuccess) {
            loadDebeAndStats(forceRefresh = forceReload)
        }
    }

    fun forceRefreshGundem() {
        _selectedSection.value = LeftFrameSection.GUNDEM
        savedStateHandle[KEY_SAVED_SECTION] = LeftFrameSection.GUNDEM.name
        repository.clearGundemCache()
        _gundemRefreshTrigger.value += 1
    }

    fun selectPeriodicTimeRange(period: PeriodicTimeRange) {
        if (_periodicTimeRange.value != period) {
            _periodicTimeRange.value = period
            loadDebeAndStats(forceRefresh = false)
        }
    }

    fun selectPeriodicRankingTab(tab: PeriodicRankingTab) {
        if (_periodicRankingTab.value != tab) {
            _periodicRankingTab.value = tab
            loadDebeAndStats(forceRefresh = false)
        }
    }

    fun loadDebeAndStats(forceRefresh: Boolean = false) {
        activeDebeStatsJob?.cancel()
        val category = _debeAndStatsCategory.value
        val period = _periodicTimeRange.value
        val tab = _periodicRankingTab.value

        activeDebeStatsJob = viewModelScope.launch {
            _debeAndStatsUiState.value = DebeAndStatsUiState.Loading
            if (category == DebeAndStatsCategory.DEBE) {
                val res = repository.fetchDebeItems(bypassCache = forceRefresh)
                res.fold(
                    onSuccess = { list ->
                        val subtitle = "Dünün En Beğenilen ${list.size} Entry'si (DEBE)"
                        _debeAndStatsUiState.value = DebeAndStatsUiState.Success(
                            items = list,
                            subtitle = subtitle
                        )
                        enrichItemsWithFavoriteCountsInBackground(
                            initialItems = list,
                            subtitle = subtitle,
                            sortDescendingAfterUpdate = false
                        )
                    },
                    onFailure = { err ->
                        _debeAndStatsUiState.value = DebeAndStatsUiState.Error(
                            err.localizedMessage ?: "DEBE listesi yüklenemedi."
                        )
                    }
                )
            } else {
                val res = repository.fetchPeriodicBestItems(
                    period = period,
                    rankingTab = tab,
                    bypassCache = forceRefresh
                )
                res.fold(
                    onSuccess = { list ->
                        val subtitle = "${period.label} • ${tab.title} (${list.size})"
                        _debeAndStatsUiState.value = DebeAndStatsUiState.Success(
                            items = list,
                            subtitle = subtitle
                        )
                        if (tab == PeriodicRankingTab.TOP_ENTRIES) {
                            enrichItemsWithFavoriteCountsInBackground(
                                initialItems = list,
                                subtitle = subtitle,
                                sortDescendingAfterUpdate = true
                            )
                        }
                    },
                    onFailure = { err ->
                        _debeAndStatsUiState.value = DebeAndStatsUiState.Error(
                            err.localizedMessage ?: "Dönemsel enler yüklenemedi."
                        )
                    }
                )
            }
        }
    }

    /**
     * DEBE veya En Beğenilen Entry'ler ekrana gelir gelmez:
     * 1. `entryFavoriteCountCache` içinde zaten bulunan öğeleri ağ isteği yapmadan doğrudan atlar.
     * 2. Yalnızca önbellekte olmayan öğeleri `Semaphore(4)` ile kontrollü paralel partiler halinde
     *    çeker, `LruCache` içine kaydeder ve UI state'ini anlık olarak (`♥ 342` formatında) günceller.
     */
    private suspend fun enrichItemsWithFavoriteCountsInBackground(
        initialItems: List<RankedTopicOrEntryItem>,
        subtitle: String,
        sortDescendingAfterUpdate: Boolean
    ) {
        val workingArray = initialItems.map { repository.applyCachedFavoriteMetadata(it) }.toMutableList()
        if (workingArray != initialItems) {
            val initialSnapshot = if (sortDescendingAfterUpdate) {
                workingArray
                    .sortedByDescending { it.favoriteCount }
                    .distinctBy { it.entryId ?: it.url }
                    .distinctBy { it.title.trim().lowercase() }
                    .mapIndexed { idx, it -> it.copy(rank = idx + 1) }
            } else {
                workingArray.toList()
            }
            _debeAndStatsUiState.value = DebeAndStatsUiState.Success(
                items = initialSnapshot,
                subtitle = subtitle
            )
        }

        val uncachedIndices = workingArray.indices.filter { idx ->
            !repository.isFavoriteMetadataCached(workingArray[idx])
        }
        if (uncachedIndices.isEmpty()) return

        val stateMutex = kotlinx.coroutines.sync.Mutex()
        val semaphore = Semaphore(4)

        withContext(Dispatchers.IO) {
            coroutineScope {
                for (index in uncachedIndices) {
                    val item = workingArray[index]
                    launch {
                        semaphore.withPermit {
                            val enriched = repository.fetchAndCacheEntryFavoriteCount(item)
                            if (enriched.favoriteCount != item.favoriteCount ||
                                enriched.badgeText != item.badgeText ||
                                enriched.author != item.author ||
                                enriched.title != item.title ||
                                enriched.entryId != item.entryId
                            ) {
                                stateMutex.lock()
                                try {
                                    workingArray[index] = enriched
                                    val snapshot = if (sortDescendingAfterUpdate) {
                                        workingArray
                                            .sortedByDescending { it.favoriteCount }
                                            .distinctBy { it.entryId ?: it.url }
                                            .distinctBy { it.title.trim().lowercase() }
                                            .mapIndexed { idx, it -> it.copy(rank = idx + 1) }
                                    } else {
                                        workingArray.toList()
                                    }
                                    _debeAndStatsUiState.value = DebeAndStatsUiState.Success(
                                        items = snapshot,
                                        subtitle = subtitle
                                    )
                                } finally {
                                    stateMutex.unlock()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun openRankedItem(item: RankedTopicOrEntryItem) {
        val baseDomain = webViewFetchBridge.getActiveBaseDomain().ifBlank {
            WebViewCookieJar.DEFAULT_BASE_URL
        }.trimEnd('/')

        val validEntryId = item.entryId?.trim()?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }
        val baseTopic = item.toTopicItem()

        val targetTopic = when {
            validEntryId != null -> baseTopic.copy(
                id = validEntryId,
                url = "$baseDomain/entry/$validEntryId"
            )
            item.isDirectEntry -> baseTopic
            else -> baseTopic.copy(
                url = item.url.substringBefore('?')
            )
        }

        openTopic(
            topic = targetTopic,
            preferTodayMode = false
        )
    }

    fun selectSection(section: LeftFrameSection) {
        _selectedSection.value = section
        savedStateHandle[KEY_SAVED_SECTION] = section.name
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun selectSearchSortOrder(sortOrder: SearchSortOrder) {
        _searchSortOrder.value = sortOrder
        if (_submittedSearchFilter.value.keywords.isNotBlank()) {
            submitSearch()
        }
    }

    fun submitSearch() {
        _submittedSearchFilter.value = SearchFilter(
            keywords = _searchQuery.value.trim(),
            sortOrder = _searchSortOrder.value
        )
    }

    fun openTopic(
        topic: TopicItem,
        preferTodayMode: Boolean = true
    ) {
        activePageLoadJob?.cancel()
        activePrefetchJob?.cancel()
        clearPageMemoryCache()

        _bkzPreviewState.value = BkzPreviewUiState.Hidden
        _activeTopicMetadata.value = null
        _inTopicSearchQuery.value = ""
        _authorFilterInTopic.value = null
        _selectedTopic.value = topic
        persistSelectedTopicToSavedState(topic)
        loadedTopicSessionKey = "${topic.id}|${topic.url}"

        viewModelScope.launch {
            val isDirectEntry = topic.url.contains("/entry/", ignoreCase = true) ||
                topic.url.contains("a=debe", ignoreCase = true)

            val startPage: Int
            if (preferTodayMode && !isDirectEntry) {
                _topicFilterMode.value = TopicFilterMode.TODAY_POPULAR
                persistFilterModeToSavedState(TopicFilterMode.TODAY_POPULAR)
                _restoredReadState.value = null
                startPage = 1
            } else {
                _topicFilterMode.value = TopicFilterMode.ALL
                persistFilterModeToSavedState(TopicFilterMode.ALL)
                val savedState = if (isDirectEntry) {
                    null
                } else {
                    repository.getSavedTopicReadState(
                        rawOrCleanTopicUrl = topic.cleanUrl,
                        topicId = topic.id
                    )
                }
                _restoredReadState.value = savedState
                startPage = if (isDirectEntry) 1 else (savedState?.pageNumber ?: 1).coerceAtLeast(1)
            }

            _currentPage.value = startPage
            persistCurrentPageToSavedState(startPage)
            _totalPages.value = startPage.coerceAtLeast(1)
            goToPage(
                targetPage = startPage,
                forceRefresh = true,
                preserveRestoredReadState = true,
                bypassNetworkCache = false
            )
        }
    }

    fun ensureTopicInitializedOnce(topic: TopicItem) {
        val key = "${topic.id}|${topic.url}"
        if (loadedTopicSessionKey == key && _topicPageUiState.value !is TopicPageUiState.Idle) {
            return
        }
        openTopic(topic = topic, preferTodayMode = true)
    }

    fun goToPage(
        targetPage: Int,
        forceRefresh: Boolean = false,
        preserveRestoredReadState: Boolean = false,
        bypassNetworkCache: Boolean = false
    ) {
        val currentTopic = _selectedTopic.value ?: return
        val knownMax = _totalPages.value.coerceAtLeast(1)
        val requestedPage = if (_activeTopicMetadata.value != null) {
            targetPage.coerceIn(1, knownMax)
        } else {
            targetPage.coerceAtLeast(1)
        }

        if (!preserveRestoredReadState) {
            _restoredReadState.value = null
        }

        activePageLoadJob?.cancel()
        activePrefetchJob?.cancel()

        val filterMode = _topicFilterMode.value
        val inTopicKeyword = _inTopicSearchQuery.value
        val activeMetadata = _activeTopicMetadata.value

        val baseTopicUrl = if (
            requestedPage > 1 &&
            activeMetadata != null &&
            !activeMetadata.cleanTopicUrl.contains("/entry/", ignoreCase = true)
        ) {
            activeMetadata.cleanTopicUrl
        } else {
            currentTopic.url
        }

        _currentPage.value = requestedPage
        persistCurrentPageToSavedState(requestedPage)

        if (!forceRefresh) {
            val cachedPage = getCachedPage(requestedPage)
            if (cachedPage != null) {
                applyLoadedPageData(cachedPage)
                prefetchNextPageInBackground(
                    baseTopicUrl = cachedPage.cleanTopicUrl.ifBlank { baseTopicUrl },
                    currentPage = cachedPage.currentPage,
                    totalPages = cachedPage.pageCount,
                    filterMode = filterMode,
                    inTopicKeyword = inTopicKeyword
                )
                return
            }
        }

        activePageLoadJob = viewModelScope.launch {
            _topicPageUiState.value = TopicPageUiState.Loading(targetPage = requestedPage)

            val result = repository.fetchSingleTopicPage(
                initialTopicUrl = baseTopicUrl,
                page = requestedPage,
                filterMode = filterMode,
                inTopicSearchKeyword = inTopicKeyword,
                bypassCache = bypassNetworkCache
            )

            result.fold(
                onSuccess = { pageData ->
                    putCachedPage(pageData.currentPage, pageData)
                    applyLoadedPageData(pageData)

                    prefetchNextPageInBackground(
                        baseTopicUrl = pageData.cleanTopicUrl.ifBlank { baseTopicUrl },
                        currentPage = pageData.currentPage,
                        totalPages = pageData.pageCount,
                        filterMode = filterMode,
                        inTopicKeyword = inTopicKeyword
                    )
                },
                onFailure = { error ->
                    _topicPageUiState.value = TopicPageUiState.Error(
                        targetPage = requestedPage,
                        message = error.localizedMessage ?: "Sayfa yüklenemedi."
                    )
                }
            )
        }
    }

    private fun applyLoadedPageData(pageData: TopicPageData) {
        val resolvedPage = pageData.currentPage.coerceAtLeast(1)
        val resolvedTotal = pageData.pageCount.coerceAtLeast(resolvedPage)
        _currentPage.value = resolvedPage
        persistCurrentPageToSavedState(resolvedPage)
        _totalPages.value = resolvedTotal
        _activeTopicMetadata.value = pageData

        val currentTopic = _selectedTopic.value
        if (currentTopic != null && pageData.topicTitle.isNotBlank() && currentTopic.title != pageData.topicTitle) {
            val updated = currentTopic.copy(title = pageData.topicTitle)
            _selectedTopic.value = updated
            persistSelectedTopicToSavedState(updated)
        }

        val seq = pageLoadSequenceGenerator.incrementAndGet()
        _topicPageUiState.value = TopicPageUiState.Success(
            pageData = pageData,
            entries = pageData.entries,
            loadSequence = seq
        )
    }

    private fun prefetchNextPageInBackground(
        baseTopicUrl: String,
        currentPage: Int,
        totalPages: Int,
        filterMode: TopicFilterMode,
        inTopicKeyword: String
    ) {
        val nextPage = currentPage + 1
        if (nextPage > totalPages) return
        if (getCachedPage(nextPage) != null) return

        activePrefetchJob?.cancel()
        activePrefetchJob = viewModelScope.launch {
            val result = repository.fetchSingleTopicPage(
                initialTopicUrl = baseTopicUrl,
                page = nextPage,
                filterMode = filterMode,
                inTopicSearchKeyword = inTopicKeyword,
                bypassCache = false
            )
            result.onSuccess { prefetchedData ->
                putCachedPage(nextPage, prefetchedData)
            }
        }
    }

    fun jumpToTopicPage(targetPage: Int) {
        goToPage(targetPage = targetPage, forceRefresh = false)
    }

    fun refreshCurrentTopicPage() {
        val page = _currentPage.value.coerceAtLeast(1)
        removeCachedPage(page)
        goToPage(targetPage = page, forceRefresh = true, bypassNetworkCache = true)
    }

    fun showAllTopicEntriesFromFirstPage() {
        val current = _selectedTopic.value ?: return
        val metadata = _activeTopicMetadata.value
        val resolvedCleanUrl = metadata?.cleanTopicUrl?.takeIf {
            it.isNotBlank() && !it.contains("/entry/", ignoreCase = true)
        } ?: current.cleanUrl

        activePageLoadJob?.cancel()
        activePrefetchJob?.cancel()
        clearPageMemoryCache()

        _authorFilterInTopic.value = null
        _inTopicSearchQuery.value = ""
        _restoredReadState.value = null
        _topicFilterMode.value = TopicFilterMode.ALL
        persistFilterModeToSavedState(TopicFilterMode.ALL)
        val updatedTopic = current.copy(
            title = metadata?.topicTitle?.takeIf { it.isNotBlank() } ?: current.title,
            url = resolvedCleanUrl
        )
        _selectedTopic.value = updatedTopic
        persistSelectedTopicToSavedState(updatedTopic)
        loadedTopicSessionKey = "${updatedTopic.id}|${updatedTopic.url}"
        _currentPage.value = 1
        persistCurrentPageToSavedState(1)
        goToPage(targetPage = 1, forceRefresh = true)
    }

    fun openBkzPreview(bkzQuery: String) {
        val cleaned = bkzQuery.trim()
        if (cleaned.isEmpty()) return

        activeBkzJob?.cancel()
        activeBkzJob = viewModelScope.launch {
            _bkzPreviewState.value = BkzPreviewUiState.Loading(cleaned)
            val result = repository.fetchBkzPreview(cleaned)
            result.fold(
                onSuccess = { pageData ->
                    _bkzPreviewState.value = BkzPreviewUiState.Success(
                        bkzQuery = cleaned,
                        pageData = pageData
                    )
                },
                onFailure = { error ->
                    _bkzPreviewState.value = BkzPreviewUiState.Error(
                        bkzQuery = cleaned,
                        message = error.localizedMessage ?: "Önizleme yüklenemedi."
                    )
                }
            )
        }
    }

    fun dismissBkzPreview() {
        activeBkzJob?.cancel()
        _bkzPreviewState.value = BkzPreviewUiState.Hidden
    }

    fun openBkzQueryFull(bkzQuery: String) {
        val cleaned = bkzQuery.trim()
        if (cleaned.isEmpty()) return
        val encoded = Uri.encode(cleaned)
        val activeDomain = webViewFetchBridge.getActiveBaseDomain()
        val url = "$activeDomain/?q=$encoded"
        openTopic(
            TopicItem(
                id = cleaned,
                title = cleaned,
                slug = cleaned.replace(" ", "-"),
                url = url
            ),
            preferTodayMode = false
        )
    }

    fun filterByAuthorInTopic(author: String) {
        val cleanAuthor = author.trim().removePrefix("@")
        if (cleanAuthor.isEmpty()) return
        clearPageMemoryCache()
        _restoredReadState.value = null
        _authorFilterInTopic.value = cleanAuthor
        _topicFilterMode.value = TopicFilterMode.SEARCH_IN_TOPIC
        persistFilterModeToSavedState(TopicFilterMode.SEARCH_IN_TOPIC)
        _inTopicSearchQuery.value = "@$cleanAuthor"
        _currentPage.value = 1
        persistCurrentPageToSavedState(1)
        goToPage(targetPage = 1, forceRefresh = true)
    }

    fun clearAuthorFilterInTopic() {
        clearPageMemoryCache()
        _restoredReadState.value = null
        _authorFilterInTopic.value = null
        _inTopicSearchQuery.value = ""
        _topicFilterMode.value = TopicFilterMode.ALL
        persistFilterModeToSavedState(TopicFilterMode.ALL)
        _currentPage.value = 1
        persistCurrentPageToSavedState(1)
        goToPage(targetPage = 1, forceRefresh = true)
    }

    fun toggleSukelaOrFavorite(entry: EntryItem) {
        viewModelScope.launch {
            if (favoritedEntryIds.value.contains(entry.id)) {
                repository.removeBookmark(entry.id)
            } else {
                repository.toggleBookmark(entry)
            }
        }
    }

    fun saveCurrentScrollPosition(
        entry: EntryItem,
        scrollIndex: Int,
        scrollOffset: Int
    ) {
        val currentTopic = _selectedTopic.value ?: return
        val metadata = _activeTopicMetadata.value
        val canonicalUrl = metadata?.cleanTopicUrl?.ifBlank { null } ?: currentTopic.cleanUrl
        val totalPages = _totalPages.value.coerceAtLeast(1)
        val activePage = _currentPage.value.coerceAtLeast(1)

        viewModelScope.launch {
            repository.saveTopicReadState(
                topicUrl = canonicalUrl,
                topicId = metadata?.topicId ?: currentTopic.id,
                topicTitle = metadata?.topicTitle ?: currentTopic.title,
                topicSlug = metadata?.topicSlug ?: currentTopic.slug,
                entryId = entry.id,
                pageNumber = activePage,
                scrollIndex = scrollIndex,
                scrollOffset = scrollOffset,
                totalKnownPages = totalPages
            )
        }
    }

    fun markScrollPositionRestored() {
        _restoredReadState.value = null
    }

    fun deleteReadState(topicKey: String) {
        viewModelScope.launch {
            repository.deleteTopicReadState(topicKey)
        }
    }

    fun selectTopicFilterMode(mode: TopicFilterMode) {
        if (mode == TopicFilterMode.ALL) {
            showAllTopicEntriesFromFirstPage()
            return
        }
        clearPageMemoryCache()
        _restoredReadState.value = null
        if (mode != TopicFilterMode.SEARCH_IN_TOPIC) {
            _authorFilterInTopic.value = null
            _inTopicSearchQuery.value = ""
        }
        _topicFilterMode.value = mode
        persistFilterModeToSavedState(mode)
        _currentPage.value = 1
        persistCurrentPageToSavedState(1)
        goToPage(targetPage = 1, forceRefresh = true)
    }

    fun updateInTopicSearchQuery(keyword: String) {
        _inTopicSearchQuery.value = keyword
    }

    fun submitInTopicSearch() {
        clearPageMemoryCache()
        _restoredReadState.value = null
        _currentPage.value = 1
        persistCurrentPageToSavedState(1)
        goToPage(targetPage = 1, forceRefresh = true)
    }

    fun closeTopicDetail() {
        activePageLoadJob?.cancel()
        activePrefetchJob?.cancel()
        clearPageMemoryCache()
        loadedTopicSessionKey = ""
        _selectedTopic.value = null
        persistSelectedTopicToSavedState(null)
        _topicPageUiState.value = TopicPageUiState.Idle
    }

    fun openCloudflareVerification(targetUrl: String = webViewFetchBridge.getActiveBaseDomain()) {
        _pendingCloudflareChallenge.value = null
        cloudflareManager.setVerificationScreenActive(true)
        _activeAuthSheet.value = ActiveAuthSheet(
            url = targetUrl.ifBlank { webViewFetchBridge.getActiveBaseDomain() },
            mode = AuthWebViewMode.CLOUDFLARE_CHALLENGE,
            reason = "Cloudflare cf_clearance doğrulaması"
        )
    }

    fun dismissCloudflareBanner() {
        _pendingCloudflareChallenge.value = null
    }

    fun openUserLogin() {
        _pendingCloudflareChallenge.value = null
        cloudflareManager.setVerificationScreenActive(true)
        val activeDomain = webViewFetchBridge.getActiveBaseDomain()
        _activeAuthSheet.value = ActiveAuthSheet(
            url = "$activeDomain/giris",
            mode = AuthWebViewMode.USER_LOGIN,
            reason = "Ekşi Sözlük kullanıcı girişi"
        )
    }

    fun onAuthVerificationCompleted() {
        cloudflareManager.setVerificationScreenActive(false)
        _pendingCloudflareChallenge.value = null
        _activeAuthSheet.value = null
        refreshCookieState()
        triggerRefresh()
    }

    fun onAuthVerificationDismissed() {
        cloudflareManager.setVerificationScreenActive(false)
        _pendingCloudflareChallenge.value = null
        _activeAuthSheet.value = null
        refreshCookieState()
    }

    fun refreshCookieState() {
        val activeDomain = webViewFetchBridge.getActiveBaseDomain()
        _hasCfClearance.value = webViewFetchBridge.isDomVerified()
        _hasUserSession.value = cookieJar.hasActiveUserSession(activeDomain)
    }

    fun triggerRefresh() {
        refreshCookieState()
        _refreshTrigger.value += 1
        loadDebeAndStats(forceRefresh = true)
        if (_selectedTopic.value != null) {
            refreshCurrentTopicPage()
        }
    }

    fun bookmarkEntry(entry: EntryItem) {
        viewModelScope.launch {
            repository.toggleBookmark(entry)
        }
    }

    fun removeBookmark(entryId: String) {
        viewModelScope.launch {
            repository.removeBookmark(entryId)
        }
    }

    fun runGeminiAnalysisStream(
        mode: GeminiAnalysisMode,
        topicTitle: String,
        entries: List<EntryItem>
    ) {
        activeGeminiJob?.cancel()
        val savedKey = geminiHelper.getUserSavedApiKey()
        _geminiApiKey.value = savedKey
        _isGeminiKeyConfigured.value = savedKey.isNotBlank()
        if (savedKey.isBlank()) {
            _geminiStreamState.value = GeminiStreamState.AwaitingApiKey(mode)
            return
        }
        activeGeminiJob = viewModelScope.launch {
            geminiHelper.executeStreamingAnalysis(
                mode = mode,
                topicTitle = topicTitle,
                entries = entries
            ).collect { state ->
                _geminiStreamState.value = state
            }
        }
    }

    fun runFiveBulletSummaryStream(topicTitle: String, entries: List<EntryItem>) {
        runGeminiAnalysisStream(
            mode = GeminiAnalysisMode.FIVE_BULLET_SUMMARY,
            topicTitle = topicTitle,
            entries = entries
        )
    }

    fun runDebateAndConsensusStream(topicTitle: String, entries: List<EntryItem>) {
        runGeminiAnalysisStream(
            mode = GeminiAnalysisMode.DEBATE_AND_CONSENSUS,
            topicTitle = topicTitle,
            entries = entries
        )
    }

    fun clearGeminiStream() {
        activeGeminiJob?.cancel()
        _geminiStreamState.value = GeminiStreamState.Idle
    }

    fun saveGeminiApiKey(rawKey: String) {
        val trimmed = rawKey.trim()
        geminiHelper.saveUserApiKey(trimmed)
        _geminiApiKey.value = geminiHelper.getUserSavedApiKey()
        _isGeminiKeyConfigured.value = geminiHelper.hasValidApiKey()
    }

    fun clearGeminiApiKey() {
        activeGeminiJob?.cancel()
        geminiHelper.clearUserApiKey()
        _geminiApiKey.value = geminiHelper.getUserSavedApiKey()
        _isGeminiKeyConfigured.value = geminiHelper.hasValidApiKey()
        if (_geminiStreamState.value !is GeminiStreamState.Idle) {
            _geminiStreamState.value = GeminiStreamState.AwaitingApiKey()
        }
    }

    fun dismissGeminiKeyWarning() {
        geminiHelper.dismissKeyDecryptionWarning()
    }

    fun selectImageModel(model: StudioImageModel) {
        _selectedImageModel.value = model
    }

    fun selectImageSize(size: StudioImageSize) {
        _selectedImageSize.value = size
    }

    fun selectAspectRatio(ratio: StudioAspectRatio) {
        _selectedAspectRatio.value = ratio
    }

    fun generateEntryVisual(promptText: String) {
        val cleanPrompt = promptText.trim()
        if (cleanPrompt.isEmpty()) return

        val chosenModel = _selectedImageModel.value
        val chosenSize = _selectedImageSize.value
        val chosenRatio = _selectedAspectRatio.value

        viewModelScope.launch {
            _aiImageState.value = AiImageState.Loading
            try {
                when (
                    val result = geminiHelper.generateImageFromPrompt(
                        promptText = cleanPrompt,
                        modelId = chosenModel.modelId,
                        targetWidth = chosenSize.pixelDimension,
                        targetHeight = chosenSize.pixelDimension
                    )
                ) {
                    is GeminiImageGenerationResult.Success -> {
                        _aiImageState.value = AiImageState.Success(
                            bitmap = result.bitmap,
                            modelUsed = chosenModel.displayName,
                            sizeLabel = chosenSize.label,
                            aspectRatioLabel = chosenRatio.label
                        )
                    }

                    is GeminiImageGenerationResult.LocalFallbackPreview -> {
                        _aiImageState.value = AiImageState.LocalFallbackPreview(
                            bitmap = result.bitmap,
                            reason = result.reason,
                            sizeLabel = chosenSize.label,
                            aspectRatioLabel = chosenRatio.label
                        )
                    }

                    is GeminiImageGenerationResult.Error -> {
                        _aiImageState.value = AiImageState.Error(result.reason)
                    }
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Log.e(TAG, "Görsel üretimi zaman aşımına uğradı", e)
                _aiImageState.value = AiImageState.Error(
                    "Görsel üretimi 60 saniye içinde tamamlanamadı. Lütfen tekrar deneyin."
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Görsel üretim hatası", e)
                _aiImageState.value = AiImageState.Error(
                    e.localizedMessage ?: "Görsel üretimi başarısız oldu."
                )
            }
        }
    }

    companion object {
        private const val TAG = "EksinViewModel"
        private const val MAX_CACHED_TOPIC_PAGES = 12

        private const val KEY_SAVED_NAV_DESTINATION = "saved_nav_destination"
        private const val KEY_SAVED_SECTION = "saved_left_frame_section"
        private const val KEY_SAVED_CHANNEL = "saved_channel"
        private const val KEY_SAVED_TOPIC_ID = "saved_topic_id"
        private const val KEY_SAVED_TOPIC_TITLE = "saved_topic_title"
        private const val KEY_SAVED_TOPIC_SLUG = "saved_topic_slug"
        private const val KEY_SAVED_TOPIC_URL = "saved_topic_url"
        private const val KEY_SAVED_CURRENT_PAGE = "saved_current_page"
        private const val KEY_SAVED_TOPIC_FILTER_MODE = "saved_topic_filter_mode"

        fun provideFactory(context: Context): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(
                    modelClass: Class<T>,
                    extras: CreationExtras
                ): T {
                    val container = AppModule.getContainer(context)
                    val savedStateHandle = try {
                        extras.createSavedStateHandle()
                    } catch (_: Throwable) {
                        SavedStateHandle()
                    }
                    return EksinViewModel(
                        appContext = container.appContext,
                        repository = container.repository,
                        cookieJar = container.cookieJar,
                        userAgentProvider = container.userAgentProvider,
                        webViewFetchBridge = container.webViewFetchBridge,
                        cloudflareManager = container.cloudflareManager,
                        geminiHelper = container.geminiHelper,
                        savedStateHandle = savedStateHandle
                    ) as T
                }

                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val container = AppModule.getContainer(context)
                    return EksinViewModel(
                        appContext = container.appContext,
                        repository = container.repository,
                        cookieJar = container.cookieJar,
                        userAgentProvider = container.userAgentProvider,
                        webViewFetchBridge = container.webViewFetchBridge,
                        cloudflareManager = container.cloudflareManager,
                        geminiHelper = container.geminiHelper,
                        savedStateHandle = SavedStateHandle()
                    ) as T
                }
            }
    }
}
