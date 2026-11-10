package com.example.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.ai.GeminiAnalysisMode
import com.example.ai.GeminiHelper
import com.example.ai.GeminiImageGenerationResult
import com.example.ai.GeminiStreamState
import com.example.data.local.BookmarkedEntryEntity
import com.example.data.local.TopicReadStateEntity
import com.example.data.model.AutocompleteSuggestionState
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
import com.example.ai.GeminiModels
import com.example.data.repository.EksiRepository
import com.example.di.ViewModelCacheCleaner
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
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
    data class Error(val message: String) : AiImageState
}

/**
 * Başlık içi Direct Page State (Doğrudan Sayfa Durumu):
 * Paging 3'ün `prevKey` / `nextKey` sonsuz geri sarma döngüsü yerine
 * kullanıcının seçtiği `currentPage` sayfasını tekil ve deterministik olarak tutar.
 */
enum class TopicScrollAnchor {
    /** Same page was refreshed in place. Do not move the list. */
    KEEP,
    /** The reader picked another page. */
    TOP,
    /** First open should return to the saved entry when one exists. */
    RESTORE
}

sealed interface TopicPageUiState {
    data object Idle : TopicPageUiState
    data class Loading(val targetPage: Int) : TopicPageUiState
    data class Success(
        val pageData: TopicPageData,
        val entries: List<EntryItem>,
        val loadSequence: Int,
        val scrollAnchor: TopicScrollAnchor = TopicScrollAnchor.TOP
    ) : TopicPageUiState
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
    PRO_STUDIO(GeminiModels.IMAGE_PRO, "Gemini 3 Pro Image (Stüdyo 1K/2K/4K)"),
    NANO_BANANA(GeminiModels.IMAGE_FAST, "Gemini Nano Banana 2.1 (Hızlı)")
}

private enum class ActivePagingTab {
    GUNDEM,
    BUGUN,
    OLAYLAR,
    CHANNELS,
    SEARCH,
    NONE
}

data class ActiveAuthSheet(
    val url: String,
    val mode: AuthWebViewMode,
    val reason: String
)

@OptIn(ExperimentalCoroutinesApi::class)
@Stable
@HiltViewModel
class EksinViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: EksiRepository,
    val cookieJar: WebViewCookieJar,
    val userAgentProvider: UserAgentProvider,
    val webViewFetchBridge: WebViewFetchBridge,
    private val cloudflareManager: CloudflareChallengeManager,
    private val geminiHelper: GeminiHelper,
    private val viewModelCacheCleaner: ViewModelCacheCleaner,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // 1. AKILLI OKUNDU TAKİBİ (SharedPreferences + Map<String, Int>: topicKey -> lastSeenEntryCount)
    private val readCountPrefs: SharedPreferences by lazy {
        appContext.getSharedPreferences(PREFS_READ_TOPIC_COUNTS, Context.MODE_PRIVATE)
    }

    private val _lastSeenEntryCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val lastSeenEntryCounts: StateFlow<Map<String, Int>> = _lastSeenEntryCounts.asStateFlow()

    // 4. ARAYÜZ RENK / TEMA SEÇENEĞİ (OLED Siyah, Koyu Gri, Klasik Beyaz)
    private val _themeMode = MutableStateFlow(AppThemeMode.OLED_BLACK)
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _showThemeDialog = MutableStateFlow(false)
    val showThemeDialog: StateFlow<Boolean> = _showThemeDialog.asStateFlow()

    // Sol Frame Sekmesi
    private val _selectedSection = MutableStateFlow(LeftFrameSection.GUNDEM)
    val selectedSection: StateFlow<LeftFrameSection> = _selectedSection.asStateFlow()

    // Hafızadaki Gündem 1. sayfa başlıkları ve anında ekrana basılan (Progressive Loading) Bugün başlıkları
    val gundemTopics: StateFlow<List<TopicItem>> = repository.gundemTopics
    val bugunTopics: StateFlow<List<TopicItem>> = repository.bugunTopics
    val isBugunLoading: StateFlow<Boolean> = repository.isBugunLoading
    val bugunIsApproximate: StateFlow<Boolean> = repository.bugunIsApproximate
    private var bugunPrefetchJob: Job? = null
    private val initialBugunPrefetchScheduled = AtomicBoolean(false)

    // 1. KANALLAR SEKMESİ (#spor, #teknoloji, #siyaset, #ekonomi, ...)
    private val _selectedChannel = MutableStateFlow(EksiChannel.SPOR)
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

    private val _activePagingTab = MutableStateFlow(ActivePagingTab.GUNDEM)
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

    // 3. ANLIK ARAMA ÖNERİLERİ (AUTOCOMPLETE) STATE VE DEBOUNCE JOB
    private val _autocompleteState = MutableStateFlow(AutocompleteSuggestionState())
    val autocompleteState: StateFlow<AutocompleteSuggestionState> = _autocompleteState.asStateFlow()
    private var autocompleteJob: Job? = null

    private val _selectedTopic = MutableStateFlow<TopicItem?>(null)
    val selectedTopic: StateFlow<TopicItem?> = _selectedTopic.asStateFlow()

    // Direct Page State (`currentPage` ve `totalPages`) + `Map<Int, TopicPageData>` Prefetch Cache
    private val _currentPage = MutableStateFlow(1)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _topicPageUiState = MutableStateFlow<TopicPageUiState>(TopicPageUiState.Idle)
    val topicPageUiState: StateFlow<TopicPageUiState> = _topicPageUiState.asStateFlow()

    private val pageMemoryCache = ConcurrentHashMap<Int, TopicPageData>()
    private val topicWarmCache = ConcurrentHashMap<String, TopicPageData>()
    private var topicWarmJob: Job? = null
    private var openTopicJob: Job? = null
    private var activePageLoadJob: Job? = null
    private var activePrefetchJob: Job? = null
    private val pageLoadSequenceGenerator = AtomicInteger(0)
    private var loadedTopicSessionKey: String = ""

    private val _restoredReadState = MutableStateFlow<TopicReadStateEntity?>(null)
    val restoredReadState: StateFlow<TopicReadStateEntity?> = _restoredReadState.asStateFlow()

    // 2. BAŞLIK İÇİ VARSAYILAN "SADECE BUGÜN" MODU (TODAY_POPULAR)
    private val _topicFilterMode = MutableStateFlow(TopicFilterMode.TODAY_POPULAR)
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

    private val _geminiStreamState = MutableStateFlow<GeminiStreamState>(GeminiStreamState.Idle)
    val geminiStreamState: StateFlow<GeminiStreamState> = _geminiStreamState.asStateFlow()
    private var activeGeminiJob: Job? = null

    private val _isGeminiKeyConfigured = MutableStateFlow(false)
    val isGeminiKeyConfigured: StateFlow<Boolean> = _isGeminiKeyConfigured.asStateFlow()

    val geminiKeyDecryptionWarning: StateFlow<String?> = geminiHelper.keyDecryptionWarning
    val lastContentSource: StateFlow<ContentSource> = webViewFetchBridge.lastContentSource

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
     * Gündem, Bugün ve Olaylar sekmeleri birbirinden tamamen bağımsız StateFlow / PagingFlow
     * olarak tutulur; böylece sekme değiştirildiğinde veya başlık detayından geri dönüldüğünde
     * ne liste verisi ne de kaydırma konumu sıfırlanır.
     */
    val gundemTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_activePagingTab, _gundemRefreshTrigger, _refreshTrigger) { tab, g, r ->
            tab to (g + r)
        }.flatMapLatest { (tab, _) ->
            if (tab == ActivePagingTab.GUNDEM) repository.getGundemTopicsPager()
            else flowOf(PagingData.empty())
        }.cachedIn(viewModelScope)

    val bugunTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_activePagingTab, _bugunRefreshTrigger, _refreshTrigger) { tab, b, r ->
            tab to (b + r)
        }.flatMapLatest { (tab, _) ->
            if (tab == ActivePagingTab.BUGUN) repository.getBugunTopicsPager()
            else flowOf(PagingData.empty())
        }.cachedIn(viewModelScope)

    val olaylarTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_activePagingTab, _olaylarRefreshTrigger, _refreshTrigger) { tab, o, r ->
            tab to (o + r)
        }.flatMapLatest { (tab, _) ->
            if (tab == ActivePagingTab.OLAYLAR) repository.getOlaylarTopicsPager()
            else flowOf(PagingData.empty())
        }.cachedIn(viewModelScope)

    /**
     * 1. KANALLAR Paging 3 Akışı (`https://eksisozluk.com/basliklar/kanal/{kanal_slug}?p={page}`).
     */
    val channelTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_activePagingTab, _selectedChannel, _refreshTrigger) { tab, channel, _ ->
            tab to channel
        }.flatMapLatest { (tab, channel) ->
            if (tab == ActivePagingTab.CHANNELS) repository.getChannelTopicsPager(channel)
            else flowOf(PagingData.empty())
        }.cachedIn(viewModelScope)

    val searchTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_activePagingTab, _submittedSearchFilter, _refreshTrigger) { tab, filter, _ ->
            tab to filter
        }.flatMapLatest { (tab, filter) ->
            if (tab == ActivePagingTab.SEARCH && filter.keywords.isNotBlank()) {
                repository.getSearchTopicsPager(filter)
            } else {
                flowOf(PagingData.empty())
            }
        }.cachedIn(viewModelScope)

    private val memoryCacheCleaner: () -> Unit = { pageMemoryCache.clear() }

    init {
        viewModelCacheCleaner.register(memoryCacheCleaner)
        repository.setOnGundemFirstPageLoadedListener {
            scheduleBackgroundBugunPrefetchAfterGundem()
        }

        viewModelScope.launch(Dispatchers.IO) {
            _lastSeenEntryCounts.value = loadSavedReadCountsFromPrefs()
            _themeMode.value = ThemePreferencesManager.loadSavedTheme(appContext)
            val loggedIn = cookieJar.hasActiveUserSession()
            _hasUserSession.value = loggedIn
            repository.setUserLoggedInState(loggedIn)
            _hasCfClearance.value = webViewFetchBridge.isDomVerified()
            _isGeminiKeyConfigured.value = geminiHelper.hasValidApiKey()
        }

        viewModelScope.launch {
            cloudflareManager.challengeEvents.collect { event: CloudflareChallengeEvent ->
                _pendingCloudflareChallenge.value = event
            }
        }
        restoreTopicAfterProcessDeath()
    }

    private fun restoreTopicAfterProcessDeath() {
        val url = savedStateHandle.get<String>(KEY_TOPIC_URL)?.takeIf { it.isNotBlank() } ?: return
        if (_selectedTopic.value != null) return
        val title = savedStateHandle.get<String>(KEY_TOPIC_TITLE).orEmpty().ifBlank { url }
        val id = savedStateHandle.get<String>(KEY_TOPIC_ID).orEmpty().ifBlank { url }
        openTopic(
            topic = TopicItem(id = id, title = title, slug = title, url = url),
            preferTodayMode = savedStateHandle.get<Boolean>(KEY_TOPIC_TODAY) ?: true
        )
    }

    private fun rememberOpenTopic(topic: TopicItem, preferTodayMode: Boolean) {
        savedStateHandle[KEY_TOPIC_URL] = topic.url
        savedStateHandle[KEY_TOPIC_TITLE] = topic.title
        savedStateHandle[KEY_TOPIC_ID] = topic.id
        savedStateHandle[KEY_TOPIC_TODAY] = preferTodayMode
    }

    fun setActivePagingTab(tabName: String) {
        _activePagingTab.value = when (tabName) {
            "BUGUN" -> ActivePagingTab.BUGUN
            "OLAYLAR" -> ActivePagingTab.OLAYLAR
            "CHANNELS" -> ActivePagingTab.CHANNELS
            "SEARCH" -> ActivePagingTab.SEARCH
            "NONE" -> ActivePagingTab.NONE
            else -> ActivePagingTab.GUNDEM
        }
    }

    override fun onCleared() {
        repository.setOnGundemFirstPageLoadedListener { }
        viewModelCacheCleaner.unregister(memoryCacheCleaner)
        super.onCleared()
    }

    /**
     * Gündem 1. sayfası yüklendikten hemen 500ms sonra arka planda sessizce "Bugün" sekmesinin
     * verisini (`prefetchBugunTopics`) hazırlar. Böylece kullanıcı "Bugün" sekmesine tıkladığında
     * 0 milisaniye bekleme süresiyle anında hazır listeyi görür.
     */
    private fun scheduleBackgroundBugunPrefetchAfterGundem() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (!initialBugunPrefetchScheduled.compareAndSet(false, true)) {
                if (repository.hasCachedBugunTopics()) return@launch
            }
            bugunPrefetchJob?.cancel()
            bugunPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
                delay(500L)
                val loaded = repository.prefetchBugunTopics(forceRefresh = false)
                if (loaded.isNotEmpty()) {
                    _bugunRefreshTrigger.value += 0
                }
            }
        }
    }

    /**
     * Arka planda veya ihtiyaç halinde "Bugün" başlıklarını (`prefetchBugunTopics`) hazırlar.
     * `forceRefresh == false` iken hafızada hazır liste varsa ağ isteği atmadan anında korur.
     */
    fun prefetchBugunTopics(forceRefresh: Boolean = false) {
        if (!forceRefresh && repository.hasCachedBugunTopics()) {
            return
        }
        viewModelScope.launch(Dispatchers.Main.immediate) {
            bugunPrefetchJob?.cancel()
            bugunPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
                val loaded = repository.prefetchBugunTopics(forceRefresh = forceRefresh)
                if (loaded.isNotEmpty() && forceRefresh) {
                    _bugunRefreshTrigger.value += 1
                }
            }
        }
    }

    /**
     * Kullanıcı özellikle "Bugün" sekmesindeyken tekrar "Bugün" butonuna veya "Yenile" butonuna
     * bastığında önbelleği temizleyip "Bugün" listesini paralel hızlı kaynaklardan yeniden çeker.
     */
    fun forceRefreshBugun() {
        _selectedSection.value = LeftFrameSection.BUGUN
        repository.clearBugunCache()
        _bugunRefreshTrigger.value += 1
    }

    /**
     * 1. AKILLI OKUNDU TAKİBİ (`Map<String, Int>` -> `topicKey -> lastSeenEntryCount`):
     * SharedPreferences'tan kayıtlı `topicKey -> lastSeenEntryCount` eşleşmelerini yükler.
     */
    private fun loadSavedReadCountsFromPrefs(): Map<String, Int> {
        val result = mutableMapOf<String, Int>()
        for ((key, value) in readCountPrefs.all) {
            if (value is Int) {
                result[key] = value
            }
        }
        return result
    }

    fun normalizeTopicReadKey(rawUrl: String): String {
        return repository.normalizeTopicKey(rawUrl).trim().lowercase()
    }

    /**
     * Kullanıcı listeden bir başlığa tıkladığında o anki `entryCount` değerini `lastSeenEntryCount`
     * olarak hem `SharedPreferences` içine hem de `lastSeenEntryCounts` StateFlow'una kaydeder.
     */
    fun recordTopicSeenEntryCount(
        rawUrl: String,
        topicTitle: String,
        entryCount: Int
    ) {
        val urlKey = normalizeTopicReadKey(rawUrl)
        val titleKey = "title:${topicTitle.trim().lowercase()}"
        val safeCount = entryCount.coerceAtLeast(0)

        val currentMap = _lastSeenEntryCounts.value.toMutableMap()
        if (urlKey.isNotBlank()) currentMap[urlKey] = safeCount
        if (topicTitle.isNotBlank()) currentMap[titleKey] = safeCount
        if (currentMap.size > MAX_READ_COUNT_KEYS) {
            val overflow = currentMap.size - MAX_READ_COUNT_KEYS
            currentMap.keys.take(overflow).forEach { currentMap.remove(it) }
        }
        _lastSeenEntryCounts.value = currentMap
        val snapshot = currentMap.toMap()
        viewModelScope.launch(Dispatchers.IO) {
            val editor = readCountPrefs.edit()
            editor.clear()
            snapshot.forEach { (key, value) -> editor.putInt(key, value) }
            editor.apply()
        }
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
    }

    // 3. GELİŞMİŞ DEBE & DÖNEMSEL ENLER YÖNETİMİ
    fun selectDebeAndStatsCategory(category: DebeAndStatsCategory) {
        if (_debeAndStatsCategory.value != category) {
            _debeAndStatsCategory.value = category
            loadDebeAndStats()
        } else if (_debeAndStatsUiState.value !is DebeAndStatsUiState.Success) {
            loadDebeAndStats()
        }
    }

    /**
     * Üst bardaki "DEBE" butonu, üst çipteki "🏆 DEBE & Enler" ve alt bardaki "DEBE"
     * sekmesi tıklandığında her zaman "Dünün En Beğenilenleri (DEBE)" (`DebeAndStatsCategory.DEBE`)
     * sekmesini aktif eder ve DEBE listesini yükler.
     */
    fun openDebeSection(forceReload: Boolean = false) {
        val categoryChanged = _debeAndStatsCategory.value != DebeAndStatsCategory.DEBE
        _debeAndStatsCategory.value = DebeAndStatsCategory.DEBE
        val currentState = _debeAndStatsUiState.value
        val isEmptySuccess = currentState is DebeAndStatsUiState.Success && currentState.items.isEmpty()
        if (forceReload || categoryChanged || currentState !is DebeAndStatsUiState.Success || isEmptySuccess) {
            loadDebeAndStats()
        }
    }

    /**
     * Alt veya üst bardaki "Gündem" butonuna tekrar (re-select) basıldığında
     * Gündem önbelleğini temizler (`clearGundemCache`) ve Gündem başlıklarını baştan çeker.
     * Gündem yeniden yüklendikten 500ms sonra arka planda "Bugün" önbelleği de güncellenir.
     */
    fun forceRefreshGundem() {
        _selectedSection.value = LeftFrameSection.GUNDEM
        initialBugunPrefetchScheduled.set(false)
        repository.clearGundemCache()
        _gundemRefreshTrigger.value += 1
    }

    fun selectPeriodicTimeRange(period: PeriodicTimeRange) {
        if (_periodicTimeRange.value != period) {
            _periodicTimeRange.value = period
            loadDebeAndStats()
        }
    }

    fun selectPeriodicRankingTab(tab: PeriodicRankingTab) {
        if (_periodicRankingTab.value != tab) {
            _periodicRankingTab.value = tab
            loadDebeAndStats()
        }
    }

    fun loadDebeAndStats() {
        activeDebeStatsJob?.cancel()
        val category = _debeAndStatsCategory.value
        val period = _periodicTimeRange.value
        val tab = _periodicRankingTab.value

        activeDebeStatsJob = viewModelScope.launch {
            _debeAndStatsUiState.value = DebeAndStatsUiState.Loading
            if (category == DebeAndStatsCategory.DEBE) {
                val res = repository.fetchDebeItems()
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
                val res = repository.fetchPeriodicBestItems(period = period, rankingTab = tab)
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

    private suspend fun enrichItemsWithFavoriteCountsInBackground(
        initialItems: List<RankedTopicOrEntryItem>,
        subtitle: String,
        sortDescendingAfterUpdate: Boolean
    ) {
        val needsEnrichment = initialItems.withIndex().filter { (_, item) ->
            item.favoriteCount <= 0 || item.entryId.isNullOrBlank()
        }.take(MAX_FAVORITE_ENRICHMENT)
        if (needsEnrichment.isEmpty()) return

        val workingArray = initialItems.toMutableList()
        val stateMutex = kotlinx.coroutines.sync.Mutex()
        val semaphore = Semaphore(4)

        withContext(Dispatchers.IO) {
            coroutineScope {
                needsEnrichment.forEach { (index, item) ->
                    if (item.favoriteCount <= 0 || item.entryId.isNullOrBlank()) {
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

    /**
     * 3. KURAL: Sekme geçişlerinde hafızadaki hazır "Bugün" listesini korur; her sekme değişiminde
     * baştan ağ isteği atmaz! Yalnızca kullanıcı ZATEN "Bugün" sekmesindeyken tekrar "Bugün"e
     * tıklarsa (`alreadyOnBugun`) önbelleği temizleyip taze veri çeker.
     */
    fun selectSection(section: LeftFrameSection) {
        val previousSection = _selectedSection.value
        _selectedSection.value = section

        if (section == LeftFrameSection.BUGUN) {
            if (previousSection == LeftFrameSection.BUGUN) {
                // Kullanıcı zaten Bugün sekmesindeyken tekrar tıkladı (Re-select / Pull-to-Refresh):
                // Önbelleği temizle ki yeni paralel istek çalışsın.
                repository.clearBugunCache()
            } else if (!repository.hasCachedBugunTopics()) {
                // Başka sekmeden Bugün'e geçildi ve henüz önbellek dolmadıysa arka planda başlat
                prefetchBugunTopics(forceRefresh = false)
            }
        }
    }

    /**
     * 3. ARAMA KUTUSUNA YAZMAYA BAŞLAYINCA ANLIK ÖNERİLER (AUTOCOMPLETE):
     */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        autocompleteJob?.cancel()

        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _autocompleteState.value = AutocompleteSuggestionState(query = trimmed)
            return
        }

        val instantLocalMatches = repository.getInstantLocalTopicMatches(trimmed)
        _autocompleteState.value = AutocompleteSuggestionState(
            query = trimmed,
            isLoading = true,
            titleSuggestions = instantLocalMatches,
            nickSuggestions = emptyList()
        )

        autocompleteJob = viewModelScope.launch {
            delay(250L)
            val (remoteTitles, remoteNicks) = repository.fetchAutocompleteSuggestions(trimmed)
            _autocompleteState.value = AutocompleteSuggestionState(
                query = trimmed,
                isLoading = false,
                titleSuggestions = remoteTitles,
                nickSuggestions = remoteNicks
            )
        }
    }

    fun dismissAutocomplete() {
        autocompleteJob?.cancel()
        _autocompleteState.value = AutocompleteSuggestionState()
    }

    /**
     * Autocomplete listesinden bir `@yazar` (`Nick`) önerisine tıklandığında
     * ilgili yazarın başlığını veya entry'lerini açar.
     */
    fun openAuthorSuggestion(nick: String) {
        val cleanNick = nick.trim().removePrefix("@")
        if (cleanNick.isEmpty()) return
        dismissAutocomplete()
        openBkzQueryFull(cleanNick)
    }

    fun selectSearchSortOrder(sortOrder: SearchSortOrder) {
        _searchSortOrder.value = sortOrder
        if (_submittedSearchFilter.value.keywords.isNotBlank()) {
            submitSearch()
        }
    }

    fun submitSearch() {
        val trimmed = _searchQuery.value.trim()
        autocompleteJob?.cancel()
        _autocompleteState.value = AutocompleteSuggestionState()
        _submittedSearchFilter.value = SearchFilter(
            keywords = trimmed,
            sortOrder = _searchSortOrder.value
        )
    }

    /**
     * Başlık İlk Açılış Noktası:
     * 1. Tıklanan başlığın o anki `entryCount` değerini `lastSeenEntryCount` olarak kaydeder.
     * 2. Room veritabanından "kalınan sayfa" (`ReadState`) SADECE başlığa ilk girildiği anda
     *    tek seferlik (`getSavedTopicReadState`) okunur.
     * 3. Bellek önbelleği (`pageMemoryCache`) temizlenir ve hedef başlangıç sayfası yüklenir.
     */
    fun openTopic(
        topic: TopicItem,
        preferTodayMode: Boolean = true
    ) {
        openTopicJob?.cancel()
        activePageLoadJob?.cancel()
        activePrefetchJob?.cancel()
        pageMemoryCache.clear()
        val warmKey = topic.cleanUrl.ifBlank { topic.url }
        val warmed = if (preferTodayMode) topicWarmCache[warmKey] else null
        if (warmed != null) {
            pageMemoryCache[warmed.currentPage.coerceAtLeast(1)] = warmed
        }

        recordTopicSeenEntryCount(
            rawUrl = topic.cleanUrl,
            topicTitle = topic.title,
            entryCount = topic.entryCount
        )

        _bkzPreviewState.value = BkzPreviewUiState.Hidden
        _activeTopicMetadata.value = null
        _inTopicSearchQuery.value = ""
        _authorFilterInTopic.value = null
        _selectedTopic.value = topic
        rememberOpenTopic(topic, preferTodayMode)
        val sessionKey = "${topic.id}|${topic.url}"
        loadedTopicSessionKey = sessionKey
        _restoredReadState.value = null

        openTopicJob = viewModelScope.launch {
            val isDirectEntry = topic.url.contains("/entry/", ignoreCase = true) ||
                topic.url.contains("a=debe", ignoreCase = true)

            val startPage: Int
            if (preferTodayMode && !isDirectEntry) {
                _topicFilterMode.value = TopicFilterMode.TODAY_POPULAR
                _restoredReadState.value = null
                startPage = 1
            } else {
                _topicFilterMode.value = TopicFilterMode.ALL
                val savedState = if (isDirectEntry) {
                    null
                } else {
                    repository.getSavedTopicReadState(
                        rawOrCleanTopicUrl = topic.cleanUrl,
                        topicId = topic.id
                    )
                }
                if (loadedTopicSessionKey != sessionKey) return@launch
                _restoredReadState.value = savedState
                startPage = if (isDirectEntry) 1 else (savedState?.pageNumber ?: 1).coerceAtLeast(1)
            }

            if (loadedTopicSessionKey != sessionKey) return@launch
            _currentPage.value = startPage
            _totalPages.value = startPage.coerceAtLeast(1)
            goToPage(
                targetPage = startPage,
                forceRefresh = warmed == null || startPage != warmed.currentPage,
                preserveRestoredReadState = true,
                scrollAnchor = if (_restoredReadState.value != null) {
                    TopicScrollAnchor.RESTORE
                } else {
                    TopicScrollAnchor.TOP
                },
                revalidateQuietly = warmed != null && startPage == warmed.currentPage
            )
        }
    }

    /**
     * Starts the topic's first page when the finger goes down, so the open can paint
     * from memory if the request finishes before the click.
     */
    fun warmTopicOnPress(topic: TopicItem) {
        val key = topic.cleanUrl.ifBlank { topic.url }
        if (key.isBlank() || topicWarmCache.containsKey(key)) return
        if (topic.url.contains("/entry/", ignoreCase = true)) return
        topicWarmJob?.cancel()
        topicWarmJob = viewModelScope.launch(Dispatchers.IO) {
            val page = repository.fetchSingleTopicPage(
                initialTopicUrl = topic.url,
                page = 1,
                filterMode = TopicFilterMode.TODAY_POPULAR
            ).getOrNull() ?: return@launch
            topicWarmCache[key] = page
            while (topicWarmCache.size > 6) {
                topicWarmCache.keys.firstOrNull()?.let { topicWarmCache.remove(it) }
            }
        }
    }

    fun ensureTopicInitializedOnce(topic: TopicItem) {
        val key = "${topic.id}|${topic.url}"
        if (loadedTopicSessionKey == key && _topicPageUiState.value !is TopicPageUiState.Idle) {
            return
        }
        openTopic(topic = topic, preferTodayMode = true)
    }

    /**
     * 2. KURAL: Direct Page State Sayfa Geçişi (`goToPage`):
     * - Kullanıcının seçtiği `targetPage` (`?p=X`) sayfasını ekranda gösterir.
     * - Seçili filtre modunu (`Bugün`, `Tümü`, `Şükela Bugün`, `Şükela Tümü`, `Başlıkta Ara`)
     *   ve aktif sorgu parametresini (`?a=popular` veya `?day=YYYY-MM-DD`) 2..N sayfalarında da
     *   KESİNLİKLE KORUR.
     */
    fun goToPage(
        targetPage: Int,
        forceRefresh: Boolean = false,
        preserveRestoredReadState: Boolean = false,
        scrollAnchor: TopicScrollAnchor? = null,
        revalidateQuietly: Boolean = false
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
        val trackedFallbackActive = activeMetadata?.usedTrackedFallback == true
        val todayFallbackActive = activeMetadata?.usedTodayFallback == true

        val baseTopicUrl = when {
            activeMetadata != null &&
                activeMetadata.activeFilterQueryUrl.isNotBlank() &&
                !activeMetadata.activeFilterQueryUrl.contains("/entry/", ignoreCase = true) -> {
                activeMetadata.activeFilterQueryUrl
            }
            activeMetadata != null &&
                activeMetadata.cleanTopicUrl.isNotBlank() &&
                !activeMetadata.cleanTopicUrl.contains("/entry/", ignoreCase = true) -> {
                activeMetadata.cleanTopicUrl
            }
            else -> currentTopic.url
        }

        val displayedPage = (_topicPageUiState.value as? TopicPageUiState.Success)?.pageData?.currentPage
        val anchor = scrollAnchor ?: if (displayedPage != null && displayedPage == requestedPage) {
            TopicScrollAnchor.KEEP
        } else {
            TopicScrollAnchor.TOP
        }
        _currentPage.value = requestedPage

        if (!forceRefresh) {
            val cachedPage = pageMemoryCache[requestedPage]
            if (cachedPage != null) {
                applyLoadedPageData(cachedPage, anchor)
                if (revalidateQuietly) {
                    revalidatePageQuietly(
                        baseTopicUrl = cachedPage.activeFilterQueryUrl.ifBlank { baseTopicUrl },
                        requestedPage = requestedPage,
                        filterMode = filterMode,
                        inTopicKeyword = inTopicKeyword,
                        trackedFallbackActive = cachedPage.usedTrackedFallback,
                        todayFallbackActive = cachedPage.usedTodayFallback,
                        sessionAtStart = loadedTopicSessionKey
                    )
                }
                prefetchNextPageInBackground(
                    baseTopicUrl = cachedPage.activeFilterQueryUrl.ifBlank { baseTopicUrl },
                    currentPage = cachedPage.currentPage,
                    totalPages = cachedPage.pageCount,
                    filterMode = filterMode,
                    inTopicKeyword = inTopicKeyword,
                    trackedFallbackActive = cachedPage.usedTrackedFallback,
                    todayFallbackActive = cachedPage.usedTodayFallback
                )
                return
            }
        }

        val sessionAtStart = loadedTopicSessionKey
        activePageLoadJob = viewModelScope.launch {
            _topicPageUiState.value = TopicPageUiState.Loading(targetPage = requestedPage)

            val result = repository.fetchSingleTopicPage(
                initialTopicUrl = baseTopicUrl,
                resolvedCleanTopicUrl = baseTopicUrl,
                page = requestedPage,
                filterMode = filterMode,
                inTopicSearchKeyword = inTopicKeyword,
                trackedFallbackAlreadyActive = trackedFallbackActive,
                todayFallbackAlreadyActive = todayFallbackActive
            )

            if (loadedTopicSessionKey != sessionAtStart) return@launch
            result.fold(
                onSuccess = { pageData ->
                    pageMemoryCache[pageData.currentPage] = pageData
                    applyLoadedPageData(pageData, anchor)

                    prefetchNextPageInBackground(
                        baseTopicUrl = pageData.activeFilterQueryUrl.ifBlank { baseTopicUrl },
                        currentPage = pageData.currentPage,
                        totalPages = pageData.pageCount,
                        filterMode = filterMode,
                        inTopicKeyword = inTopicKeyword,
                        trackedFallbackActive = pageData.usedTrackedFallback,
                        todayFallbackActive = pageData.usedTodayFallback
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

    private fun revalidatePageQuietly(
        baseTopicUrl: String,
        requestedPage: Int,
        filterMode: TopicFilterMode,
        inTopicKeyword: String,
        trackedFallbackActive: Boolean,
        todayFallbackActive: Boolean,
        sessionAtStart: String
    ) {
        activePageLoadJob = viewModelScope.launch {
            val result = repository.fetchSingleTopicPage(
                initialTopicUrl = baseTopicUrl,
                resolvedCleanTopicUrl = baseTopicUrl,
                page = requestedPage,
                filterMode = filterMode,
                inTopicSearchKeyword = inTopicKeyword,
                trackedFallbackAlreadyActive = trackedFallbackActive,
                todayFallbackAlreadyActive = todayFallbackActive
            )
            if (loadedTopicSessionKey != sessionAtStart) return@launch
            if (_currentPage.value != requestedPage) return@launch
            result.onSuccess { pageData ->
                pageMemoryCache[pageData.currentPage] = pageData
                applyLoadedPageData(pageData, TopicScrollAnchor.KEEP)
            }
        }
    }

    private fun applyLoadedPageData(pageData: TopicPageData, scrollAnchor: TopicScrollAnchor) {
        val resolvedPage = pageData.currentPage.coerceAtLeast(1)
        val resolvedTotal = pageData.pageCount.coerceAtLeast(resolvedPage)
        _currentPage.value = resolvedPage
        _totalPages.value = resolvedTotal
        _activeTopicMetadata.value = pageData

        val currentTopic = _selectedTopic.value
        if (currentTopic != null) {
            val newTitle = pageData.topicTitle.takeIf { it.isNotBlank() } ?: currentTopic.title
            if (currentTopic.title != newTitle) {
                _selectedTopic.value = currentTopic.copy(title = newTitle)
            }
            if (pageData.cleanTopicUrl.isNotBlank() && !pageData.cleanTopicUrl.contains("/entry/")) {
                val existingSeen = _lastSeenEntryCounts.value[normalizeTopicReadKey(currentTopic.cleanUrl)]
                    ?: currentTopic.entryCount
                recordTopicSeenEntryCount(
                    rawUrl = pageData.cleanTopicUrl,
                    topicTitle = newTitle,
                    entryCount = existingSeen
                )
            }
        }

        val seq = pageLoadSequenceGenerator.incrementAndGet()
        _topicPageUiState.value = TopicPageUiState.Success(
            pageData = pageData,
            entries = pageData.entries,
            loadSequence = seq,
            scrollAnchor = scrollAnchor
        )
    }

    /**
     * Kullanıcı `currentPage` (X) sayfasındayken SADECE `X + 1` sayfasını aktif filtreyi
     * (`?a=popular`, `?day=...`, `?a=dailynice`, `?a=nice`) koruyarak sessizce `pageMemoryCache`
     * içine indirir.
     */
    private fun prefetchNextPageInBackground(
        baseTopicUrl: String,
        currentPage: Int,
        totalPages: Int,
        filterMode: TopicFilterMode,
        inTopicKeyword: String,
        trackedFallbackActive: Boolean,
        todayFallbackActive: Boolean
    ) {
        val nextPage = currentPage + 1
        if (nextPage > totalPages) return
        if (pageMemoryCache.containsKey(nextPage)) return

        activePrefetchJob?.cancel()
        activePrefetchJob = viewModelScope.launch {
            val result = repository.fetchSingleTopicPage(
                initialTopicUrl = baseTopicUrl,
                resolvedCleanTopicUrl = baseTopicUrl,
                page = nextPage,
                filterMode = filterMode,
                inTopicSearchKeyword = inTopicKeyword,
                trackedFallbackAlreadyActive = trackedFallbackActive,
                todayFallbackAlreadyActive = todayFallbackActive
            )
            result.onSuccess { prefetchedData ->
                pageMemoryCache[nextPage] = prefetchedData
            }
        }
    }

    fun jumpToTopicPage(targetPage: Int) {
        goToPage(targetPage = targetPage, forceRefresh = false)
    }

    fun refreshCurrentTopicPage() {
        val page = _currentPage.value.coerceAtLeast(1)
        pageMemoryCache.remove(page)
        goToPage(
            targetPage = page,
            forceRefresh = true,
            scrollAnchor = TopicScrollAnchor.KEEP
        )
    }

    /**
     * "TÜMÜNÜ GÖSTER (İlk Entry'den İtibaren Tüm Başlığı Yükle)" Butonu Aksiyonu:
     */
    fun showAllTopicEntriesFromFirstPage() {
        val current = _selectedTopic.value ?: return
        val metadata = _activeTopicMetadata.value
        val resolvedCleanUrl = metadata?.cleanTopicUrl?.takeIf {
            it.isNotBlank() && !it.contains("/entry/", ignoreCase = true)
        } ?: current.cleanUrl

        activePageLoadJob?.cancel()
        activePrefetchJob?.cancel()
        pageMemoryCache.clear()

        _authorFilterInTopic.value = null
        _inTopicSearchQuery.value = ""
        _restoredReadState.value = null
        _topicFilterMode.value = TopicFilterMode.ALL
        _activeTopicMetadata.value = metadata?.copy(
            activeFilterQueryUrl = resolvedCleanUrl,
            usedTodayFallback = false,
            isSingleEntryView = false
        )
        val updatedTopic = current.copy(
            title = metadata?.topicTitle?.takeIf { it.isNotBlank() } ?: current.title,
            url = resolvedCleanUrl
        )
        _selectedTopic.value = updatedTopic
        loadedTopicSessionKey = "${updatedTopic.id}|${updatedTopic.url}"
        _currentPage.value = 1
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
        pageMemoryCache.clear()
        _restoredReadState.value = null
        _authorFilterInTopic.value = cleanAuthor
        _topicFilterMode.value = TopicFilterMode.SEARCH_IN_TOPIC
        _inTopicSearchQuery.value = "@$cleanAuthor"
        _currentPage.value = 1
        goToPage(targetPage = 1, forceRefresh = true)
    }

    fun clearAuthorFilterInTopic() {
        pageMemoryCache.clear()
        _restoredReadState.value = null
        _authorFilterInTopic.value = null
        _inTopicSearchQuery.value = ""
        _topicFilterMode.value = TopicFilterMode.ALL
        _currentPage.value = 1
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
        val normalized = normalizeTopicReadKey(topicKey)
        val updated = _lastSeenEntryCounts.value.toMutableMap()
        updated.remove(normalized)
        readCountPrefs.edit().remove(normalized).apply()
        _lastSeenEntryCounts.value = updated

        viewModelScope.launch {
            repository.deleteTopicReadState(topicKey)
        }
    }

    fun selectTopicFilterMode(mode: TopicFilterMode) {
        if (mode == TopicFilterMode.ALL) {
            showAllTopicEntriesFromFirstPage()
            return
        }
        pageMemoryCache.clear()
        _restoredReadState.value = null
        if (mode != TopicFilterMode.SEARCH_IN_TOPIC) {
            _authorFilterInTopic.value = null
            _inTopicSearchQuery.value = ""
        }
        _activeTopicMetadata.value = _activeTopicMetadata.value?.copy(
            usedTodayFallback = false,
            activeFilterQueryUrl = _activeTopicMetadata.value?.cleanTopicUrl.orEmpty()
        )
        _topicFilterMode.value = mode
        _currentPage.value = 1
        goToPage(targetPage = 1, forceRefresh = true)
    }

    fun updateInTopicSearchQuery(keyword: String) {
        _inTopicSearchQuery.value = keyword
    }

    fun submitInTopicSearch() {
        pageMemoryCache.clear()
        _restoredReadState.value = null
        _currentPage.value = 1
        goToPage(targetPage = 1, forceRefresh = true)
    }

    fun closeTopicDetail() {
        activePageLoadJob?.cancel()
        activePrefetchJob?.cancel()
        pageMemoryCache.clear()
        loadedTopicSessionKey = ""
        _selectedTopic.value = null
        savedStateHandle.remove<String>(KEY_TOPIC_URL)
        savedStateHandle.remove<String>(KEY_TOPIC_TITLE)
        savedStateHandle.remove<String>(KEY_TOPIC_ID)
        savedStateHandle.remove<Boolean>(KEY_TOPIC_TODAY)
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
        val loggedIn = cookieJar.hasActiveUserSession(activeDomain)
        _hasUserSession.value = loggedIn
        repository.setUserLoggedInState(loggedIn)
    }

    fun triggerRefresh() {
        refreshCookieState()
        initialBugunPrefetchScheduled.set(false)
        repository.clearBugunCache()
        _refreshTrigger.value += 1
        loadDebeAndStats()
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
        val configured = geminiHelper.hasValidApiKey()
        _isGeminiKeyConfigured.value = configured
        if (!configured) {
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
        _isGeminiKeyConfigured.value = geminiHelper.hasValidApiKey()
    }

    fun clearGeminiApiKey() {
        activeGeminiJob?.cancel()
        geminiHelper.clearUserApiKey()
        _isGeminiKeyConfigured.value = false
        if (_geminiStreamState.value !is GeminiStreamState.Idle) {
            _geminiStreamState.value = GeminiStreamState.AwaitingApiKey()
        }
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
                    val imgResult = geminiHelper.generateImageFromPrompt(
                        promptText = cleanPrompt,
                        modelId = chosenModel.modelId,
                        aspectRatio = chosenRatio.label,
                        imageSize = chosenSize.label,
                        targetWidth = chosenSize.pixelDimension.coerceAtMost(2048),
                        targetHeight = chosenSize.pixelDimension.coerceAtMost(2048)
                    )
                ) {
                    is GeminiImageGenerationResult.Success -> {
                        _aiImageState.value = AiImageState.Success(
                            bitmap = imgResult.bitmap,
                            modelUsed = imgResult.modelUsed,
                            sizeLabel = chosenSize.label,
                            aspectRatioLabel = chosenRatio.label
                        )
                    }
                    is GeminiImageGenerationResult.LocalFallbackPreview -> {
                        _aiImageState.value = AiImageState.Success(
                            bitmap = imgResult.bitmap,
                            modelUsed = "${imgResult.modelLabel} (Yerel Önizleme)",
                            sizeLabel = chosenSize.label,
                            aspectRatioLabel = chosenRatio.label
                        )
                    }
                    is GeminiImageGenerationResult.Error -> {
                        _aiImageState.value = AiImageState.Error(imgResult.reason)
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
        private const val PREFS_READ_TOPIC_COUNTS = "eksin_read_topic_counts_prefs"
        private const val MAX_READ_COUNT_KEYS = 400
        private const val MAX_FAVORITE_ENRICHMENT = 8
        private const val KEY_TOPIC_URL = "open_topic_url"
        private const val KEY_TOPIC_TITLE = "open_topic_title"
        private const val KEY_TOPIC_ID = "open_topic_id"
        private const val KEY_TOPIC_TODAY = "open_topic_today"
    }
}
