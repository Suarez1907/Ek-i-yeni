package com.example.ui

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
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
import com.example.data.model.ActionSubmitState
import com.example.data.model.AuthorEntryTab
import com.example.data.model.AuthorProfileEntriesUiState
import com.example.data.model.AutocompleteSuggestionState
import com.example.data.model.DebeAndStatsCategory
import com.example.data.model.MessageThreadItem
import com.example.data.model.MessagesUiState
import com.example.data.model.ProfileUiState
import com.example.data.model.ThreadDetailUiState
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
import kotlinx.coroutines.delay
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
sealed interface TopicPageUiState {
    data object Idle : TopicPageUiState
    data class Loading(val targetPage: Int) : TopicPageUiState
    data class Success(
        val pageData: TopicPageData,
        val entries: List<EntryItem>,
        val loadSequence: Int
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
    PRO_STUDIO("gemini-3-pro-image", "Gemini 3 Pro Image (Stüdyo 1K/2K/4K)"),
    NANO_BANANA("gemini-nano-banana-2.1", "Gemini Nano Banana 2.1 (Hızlı)")
}

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
    private val geminiHelper: GeminiHelper
) : ViewModel() {

    // 1. AKILLI OKUNDU TAKİBİ (SharedPreferences + Map<String, Int>: topicKey -> lastSeenEntryCount)
    private val readCountPrefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_READ_TOPIC_COUNTS, Context.MODE_PRIVATE)

    private val _lastSeenEntryCounts = MutableStateFlow<Map<String, Int>>(loadSavedReadCountsFromPrefs())
    val lastSeenEntryCounts: StateFlow<Map<String, Int>> = _lastSeenEntryCounts.asStateFlow()

    // 4. ARAYÜZ RENK / TEMA SEÇENEĞİ (OLED Siyah, Koyu Gri, Klasik Beyaz)
    private val _themeMode = MutableStateFlow(ThemePreferencesManager.loadSavedTheme(appContext))
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

    private val _hasCfClearance = MutableStateFlow(webViewFetchBridge.isDomVerified())
    val hasCfClearance: StateFlow<Boolean> = _hasCfClearance.asStateFlow()

    private val _hasUserSession = MutableStateFlow(cookieJar.hasActiveUserSession())
    val hasUserSession: StateFlow<Boolean> = _hasUserSession.asStateFlow()

    private val _isUserLoggedIn = MutableStateFlow(cookieJar.hasActiveUserSession())
    val isUserLoggedIn: StateFlow<Boolean> = _isUserLoggedIn.asStateFlow()

    private val _currentUsername = MutableStateFlow<String?>(null)
    val currentUsername: StateFlow<String?> = _currentUsername.asStateFlow()

    private val _myProfileState = MutableStateFlow<ProfileUiState>(ProfileUiState.Idle)
    val myProfileState: StateFlow<ProfileUiState> = _myProfileState.asStateFlow()

    private val _activeAuthorNick = MutableStateFlow<String?>(null)
    val activeAuthorNick: StateFlow<String?> = _activeAuthorNick.asStateFlow()

    private val _authorEntryTab = MutableStateFlow(AuthorEntryTab.RECENT)
    val authorEntryTab: StateFlow<AuthorEntryTab> = _authorEntryTab.asStateFlow()

    private val _authorProfileEntriesState =
        MutableStateFlow<AuthorProfileEntriesUiState>(AuthorProfileEntriesUiState.Idle)
    val authorProfileEntriesState: StateFlow<AuthorProfileEntriesUiState> =
        _authorProfileEntriesState.asStateFlow()
    private var activeAuthorEntryTabJob: Job? = null

    private val _selectedAuthorProfileState = MutableStateFlow<ProfileUiState>(ProfileUiState.Idle)
    val selectedAuthorProfileState: StateFlow<ProfileUiState> = _selectedAuthorProfileState.asStateFlow()

    private val _messageThreadsState = MutableStateFlow<MessagesUiState>(MessagesUiState.Idle)
    val messageThreadsState: StateFlow<MessagesUiState> = _messageThreadsState.asStateFlow()

    private val _activeThreadDetailState = MutableStateFlow<ThreadDetailUiState>(ThreadDetailUiState.Hidden)
    val activeThreadDetailState: StateFlow<ThreadDetailUiState> = _activeThreadDetailState.asStateFlow()
    private var activeMessageThread: MessageThreadItem? = null

    private val _entrySubmitState = MutableStateFlow<ActionSubmitState>(ActionSubmitState.Idle)
    val entrySubmitState: StateFlow<ActionSubmitState> = _entrySubmitState.asStateFlow()

    private val _messageSubmitState = MutableStateFlow<ActionSubmitState>(ActionSubmitState.Idle)
    val messageSubmitState: StateFlow<ActionSubmitState> = _messageSubmitState.asStateFlow()

    private val _geminiStreamState = MutableStateFlow<GeminiStreamState>(GeminiStreamState.Idle)
    val geminiStreamState: StateFlow<GeminiStreamState> = _geminiStreamState.asStateFlow()
    private var activeGeminiJob: Job? = null

    private val _geminiApiKey = MutableStateFlow(geminiHelper.getUserSavedApiKey())
    val geminiApiKey: StateFlow<String> = _geminiApiKey.asStateFlow()

    private val _isGeminiKeyConfigured = MutableStateFlow(geminiHelper.hasValidApiKey())
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
        combine(_gundemRefreshTrigger, _refreshTrigger) { g, r -> g + r }
            .flatMapLatest {
                repository.getGundemTopicsPager()
            }
            .cachedIn(viewModelScope)

    val bugunTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_bugunRefreshTrigger, _refreshTrigger) { b, r -> b + r }
            .flatMapLatest {
                repository.getBugunTopicsPager()
            }
            .cachedIn(viewModelScope)

    val olaylarTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_olaylarRefreshTrigger, _refreshTrigger) { o, r -> o + r }
            .flatMapLatest {
                repository.getOlaylarTopicsPager()
            }
            .cachedIn(viewModelScope)

    val leftFramePagingFlow: Flow<PagingData<TopicItem>> =
        combine(_selectedSection, _refreshTrigger) { section, _ -> section }
            .flatMapLatest { section ->
                repository.getLeftFrameTopicsPager(section)
            }
            .cachedIn(viewModelScope)

    /**
     * 1. KANALLAR Paging 3 Akışı (`https://eksisozluk.com/basliklar/kanal/{kanal_slug}?p={page}`).
     */
    val channelTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_selectedChannel, _refreshTrigger) { channel, _ -> channel }
            .flatMapLatest { channel ->
                repository.getChannelTopicsPager(channel)
            }
            .cachedIn(viewModelScope)

    val searchTopicsPagingFlow: Flow<PagingData<TopicItem>> =
        combine(_submittedSearchFilter, _refreshTrigger) { filter, _ -> filter }
            .flatMapLatest { filter ->
                repository.getSearchTopicsPager(filter)
            }
            .cachedIn(viewModelScope)

    init {
        AppModule.registerViewModelCacheCleaner {
            pageMemoryCache.clear()
        }
        repository.setUserLoggedInState(_hasUserSession.value)

        // 3. KURAL: Uygulama ilk açıldığında Gündem yüklendikten hemen 500ms sonra arka planda
        // sessizce "Bugün" listesini (`prefetchBugunTopics()`) hazırla!
        repository.setOnGundemFirstPageLoadedListener {
            scheduleBackgroundBugunPrefetchAfterGundem()
        }

        viewModelScope.launch {
            cloudflareManager.challengeEvents.collect { event: CloudflareChallengeEvent ->
                _pendingCloudflareChallenge.value = event
            }
        }
        loadDebeAndStats()
    }

    /**
     * Gündem 1. sayfası yüklendikten hemen 500ms sonra arka planda sessizce "Bugün" sekmesinin
     * verisini (`prefetchBugunTopics`) hazırlar. Böylece kullanıcı "Bugün" sekmesine tıkladığında
     * 0 milisaniye bekleme süresiyle anında hazır listeyi görür.
     */
    private fun scheduleBackgroundBugunPrefetchAfterGundem() {
        if (!initialBugunPrefetchScheduled.compareAndSet(false, true)) {
            if (repository.hasCachedBugunTopics()) return
        }
        bugunPrefetchJob?.cancel()
        bugunPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500L)
            prefetchBugunTopics(forceRefresh = false)
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
        bugunPrefetchJob?.cancel()
        bugunPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val loaded = repository.prefetchBugunTopics(forceRefresh = forceRefresh)
            if (loaded.isNotEmpty()) {
                _bugunRefreshTrigger.value += 1
            }
        }
    }

    /**
     * Kullanıcı özellikle "Bugün" sekmesindeyken tekrar "Bugün" butonuna veya "Yenile" butonuna
     * bastığında önbelleği temizleyip "Bugün" listesini paralel hızlı kaynaklardan yeniden çeker.
     */
    fun forceRefreshBugun() {
        _selectedSection.value = LeftFrameSection.BUGUN
        bugunPrefetchJob?.cancel()
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
        val editor = readCountPrefs.edit()

        if (urlKey.isNotBlank()) {
            currentMap[urlKey] = safeCount
            editor.putInt(urlKey, safeCount)
        }
        if (topicTitle.isNotBlank()) {
            currentMap[titleKey] = safeCount
            editor.putInt(titleKey, safeCount)
        }

        editor.apply()
        _lastSeenEntryCounts.value = currentMap
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
                            sortDescendingAfterUpdate = true
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
        if (initialItems.none { it.favoriteCount <= 0 || it.entryId.isNullOrBlank() }) return

        val workingArray = initialItems.toMutableList()
        val stateMutex = kotlinx.coroutines.sync.Mutex()
        val semaphore = Semaphore(4)

        withContext(Dispatchers.IO) {
            coroutineScope {
                initialItems.forEachIndexed { index, item ->
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
     * Sekme geçişlerinde yalnızca seçimi günceller. Yükleme ve yenileme, PagingSource
     * tarafından tek yerden yönetilir.
     */
    fun selectSection(section: LeftFrameSection) {
        _selectedSection.value = section
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
            if (_searchQuery.value.trim() != trimmed) return@launch
            val (remoteTitles, remoteNicks) = repository.fetchAutocompleteSuggestions(trimmed)
            if (_searchQuery.value.trim() != trimmed) return@launch
            val mergedTitles = (instantLocalMatches + remoteTitles)
                .distinctBy { it.title.trim().lowercase() }
                .take(12)
            _autocompleteState.value = AutocompleteSuggestionState(
                query = trimmed,
                isLoading = false,
                titleSuggestions = mergedTitles,
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
        activePageLoadJob?.cancel()
        activePrefetchJob?.cancel()
        pageMemoryCache.clear()

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
        loadedTopicSessionKey = "${topic.id}|${topic.url}"

        viewModelScope.launch {
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
                _restoredReadState.value = savedState
                startPage = if (isDirectEntry) 1 else (savedState?.pageNumber ?: 1).coerceAtLeast(1)
            }

            _currentPage.value = startPage
            _totalPages.value = startPage.coerceAtLeast(1)
            goToPage(
                targetPage = startPage,
                forceRefresh = true,
                preserveRestoredReadState = true
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
        preserveRestoredReadState: Boolean = false
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

        _currentPage.value = requestedPage

        if (!forceRefresh) {
            val cachedPage = pageMemoryCache[requestedPage]
            if (cachedPage != null) {
                applyLoadedPageData(cachedPage)
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

            result.fold(
                onSuccess = { pageData ->
                    pageMemoryCache[pageData.currentPage] = pageData
                    applyLoadedPageData(pageData)

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

    private fun applyLoadedPageData(pageData: TopicPageData) {
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
            loadSequence = seq
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
        goToPage(targetPage = page, forceRefresh = true)
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
        refreshSessionState()
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
        _isUserLoggedIn.value = loggedIn
        repository.setUserLoggedInState(loggedIn)
    }

    fun refreshSessionState() {
        val activeDomain = webViewFetchBridge.getActiveBaseDomain()
        val loggedIn = cookieJar.hasActiveUserSession(activeDomain)
        _isUserLoggedIn.value = loggedIn
        _hasUserSession.value = loggedIn
        repository.setUserLoggedInState(loggedIn)
        if (loggedIn) {
            loadMyProfile(forceRefresh = true)
            loadMessageThreads(forceRefresh = true)
        } else {
            _currentUsername.value = null
            _myProfileState.value = ProfileUiState.Idle
            _messageThreadsState.value = MessagesUiState.Idle
        }
    }

    /** Ayarlar üzerinden çıkış: oturum çerezlerini ve kişisel UI durumunu sıfırlar. */
    fun logout() {
        cookieJar.clearAllCookies()
        _activeAuthSheet.value = null
        _activeAuthorNick.value = null
        _selectedAuthorProfileState.value = ProfileUiState.Idle
        _activeThreadDetailState.value = ThreadDetailUiState.Hidden
        _isUserLoggedIn.value = false
        _hasUserSession.value = false
        _currentUsername.value = null
        _myProfileState.value = ProfileUiState.Idle
        _messageThreadsState.value = MessagesUiState.Idle
        _messageSubmitState.value = ActionSubmitState.Idle
        repository.setUserLoggedInState(false)
        triggerRefresh()
    }

    fun loadMyProfile(forceRefresh: Boolean = false) {
        if (!_isUserLoggedIn.value) {
            _myProfileState.value = ProfileUiState.Idle
            return
        }
        viewModelScope.launch {
            _myProfileState.value = ProfileUiState.Loading
            try {
                var nick = _currentUsername.value?.trim().orEmpty()
                if (nick.isEmpty()) {
                    val fetched = runCatching { repository.fetchCurrentUsername() }.getOrNull()
                    val fetchedNick: String? = when (fetched) {
                        is Result<*> -> fetched.getOrNull() as? String
                        is String -> fetched
                        else -> null
                    }
                    val cleanNick = fetchedNick?.trim().orEmpty()
                    if (cleanNick.isNotEmpty()) {
                        _currentUsername.value = cleanNick
                        nick = cleanNick
                    }
                }
                if (nick.isEmpty()) {
                    _myProfileState.value = ProfileUiState.Error("Kullanıcı adı alınamadı.")
                    return@launch
                }
                repository.fetchAuthorProfile(
                    nick = nick,
                    isCurrentUser = true,
                    forceRefresh = forceRefresh
                ).fold(
                    onSuccess = { profile ->
                        _myProfileState.value = ProfileUiState.Success(profile)
                    },
                    onFailure = { error ->
                        _myProfileState.value = ProfileUiState.Error(
                            error.localizedMessage ?: "Profil yüklenemedi."
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _myProfileState.value = ProfileUiState.Error(
                    e.localizedMessage ?: "Profil yüklenemedi."
                )
            }
        }
    }

    fun openAuthorProfile(nick: String) {
        val cleanNick = nick.trim().removePrefix("@")
        if (cleanNick.isEmpty()) return
        if (_activeAuthorNick.value != cleanNick) {
            resetAuthorEntryTab()
        }
        _activeAuthorNick.value = cleanNick
        viewModelScope.launch {
            _selectedAuthorProfileState.value = ProfileUiState.Loading
            try {
                repository.fetchAuthorProfile(
                    nick = cleanNick,
                    isCurrentUser = cleanNick.equals(_currentUsername.value, ignoreCase = true),
                    forceRefresh = false
                ).fold(
                    onSuccess = { profile ->
                        _selectedAuthorProfileState.value = ProfileUiState.Success(profile)
                    },
                    onFailure = { error ->
                        _selectedAuthorProfileState.value = ProfileUiState.Error(
                            error.localizedMessage ?: "Yazar profili yüklenemedi."
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _selectedAuthorProfileState.value = ProfileUiState.Error(
                    e.localizedMessage ?: "Yazar profili yüklenemedi."
                )
            }
        }
    }

    fun closeAuthorProfile() {
        _activeAuthorNick.value = null
        resetAuthorEntryTab()
        _selectedAuthorProfileState.value = ProfileUiState.Idle
    }

    /**
     * Yazar profili sekmelerinden birini seçer ve ilgili entry listesini yükler.
     * Önceki sekmenin isteği iptal edilir; geç gelen sonuçlar yeni sekmenin state'ini ezmez.
     */
    fun selectAuthorEntryTab(nick: String, tab: AuthorEntryTab) {
        val cleanNick = nick.trim().removePrefix("@")
        if (cleanNick.isEmpty()) return
        _authorEntryTab.value = tab
        loadAuthorEntryTab(cleanNick, tab, forceRefresh = false)
    }

    /** Aktif yazar profil sekmesini önbellekten bağımsız olarak yeniden yükler. */
    fun refreshAuthorEntryTab(nick: String) {
        val cleanNick = nick.trim().removePrefix("@")
        if (cleanNick.isEmpty()) return
        loadAuthorEntryTab(cleanNick, _authorEntryTab.value, forceRefresh = true)
    }

    /** Yazar değiştiğinde sekmeyi "Son" sekmesine sıfırlar ve bekleyen isteği iptal eder. */
    fun resetAuthorEntryTab() {
        activeAuthorEntryTabJob?.cancel()
        activeAuthorEntryTabJob = null
        _authorEntryTab.value = AuthorEntryTab.RECENT
        _authorProfileEntriesState.value = AuthorProfileEntriesUiState.Idle
    }

    private fun loadAuthorEntryTab(
        nick: String,
        tab: AuthorEntryTab,
        forceRefresh: Boolean
    ) {
        activeAuthorEntryTabJob?.cancel()
        activeAuthorEntryTabJob = viewModelScope.launch {
            _authorProfileEntriesState.value = AuthorProfileEntriesUiState.Loading
            try {
                val entries = repository.fetchAuthorProfileEntries(
                    nick = nick,
                    tab = tab,
                    forceRefresh = forceRefresh
                )
                if (_authorEntryTab.value != tab) return@launch
                _authorProfileEntriesState.value =
                    AuthorProfileEntriesUiState.Success(tab, entries)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_authorEntryTab.value != tab) return@launch
                _authorProfileEntriesState.value = AuthorProfileEntriesUiState.Error(
                    e.localizedMessage ?: "Entry listesi yüklenemedi."
                )
            }
        }
    }

    fun loadMessageThreads(forceRefresh: Boolean = false) {
        if (!_isUserLoggedIn.value) {
            _messageThreadsState.value = MessagesUiState.Idle
            return
        }
        viewModelScope.launch {
            _messageThreadsState.value = MessagesUiState.Loading
            try {
                repository.fetchMessageThreads(forceRefresh = forceRefresh).fold(
                    onSuccess = { threads ->
                        _messageThreadsState.value = MessagesUiState.Success(threads)
                    },
                    onFailure = { error ->
                        _messageThreadsState.value = MessagesUiState.Error(
                            error.localizedMessage ?: "Mesajlar yüklenemedi."
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messageThreadsState.value = MessagesUiState.Error(
                    e.localizedMessage ?: "Mesajlar yüklenemedi."
                )
            }
        }
    }

    fun openMessageThread(thread: MessageThreadItem) {
        activeMessageThread = thread
        _activeThreadDetailState.value = ThreadDetailUiState.Loading(thread.participantNick)
        viewModelScope.launch {
            try {
                repository.fetchMessageThreadDetail(
                    threadUrl = thread.threadUrl,
                    participantNick = thread.participantNick
                ).fold(
                    onSuccess = { detail ->
                        _activeThreadDetailState.value = ThreadDetailUiState.Success(detail)
                    },
                    onFailure = { error ->
                        _activeThreadDetailState.value = ThreadDetailUiState.Error(
                            participantNick = thread.participantNick,
                            message = error.localizedMessage ?: "Konuşma yüklenemedi."
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _activeThreadDetailState.value = ThreadDetailUiState.Error(
                    participantNick = thread.participantNick,
                    message = e.localizedMessage ?: "Konuşma yüklenemedi."
                )
            }
        }
    }

    fun closeMessageThread() {
        activeMessageThread = null
        _activeThreadDetailState.value = ThreadDetailUiState.Hidden
    }

    fun submitEntry(
        topicUrl: String,
        topicTitle: String,
        topicId: String,
        content: String
    ) {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            _entrySubmitState.value = ActionSubmitState.Error("Entry içeriği boş olamaz.")
            return
        }
        if (!_isUserLoggedIn.value) {
            openUserLogin()
            _entrySubmitState.value = ActionSubmitState.Error(
                "Entry girmek için giriş yapmalısınız."
            )
            return
        }
        viewModelScope.launch {
            _entrySubmitState.value = ActionSubmitState.Submitting
            try {
                repository.submitEntry(
                    topicUrl = topicUrl,
                    topicTitle = topicTitle,
                    topicId = topicId,
                    content = trimmed
                ).fold(
                    onSuccess = {
                        _entrySubmitState.value = ActionSubmitState.Success("Entry gönderildi")
                        refreshCurrentTopicPage()
                    },
                    onFailure = { error ->
                        _entrySubmitState.value = ActionSubmitState.Error(
                            error.localizedMessage ?: "Entry gönderilemedi."
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _entrySubmitState.value = ActionSubmitState.Error(
                    e.localizedMessage ?: "Entry gönderilemedi."
                )
            }
        }
    }

    fun resetEntrySubmitState() {
        _entrySubmitState.value = ActionSubmitState.Idle
    }

    fun sendDirectMessage(
        toNick: String,
        message: String,
        replyThreadUrl: String? = null
    ) {
        val cleanToNick = toNick.trim().removePrefix("@")
        val trimmed = message.trim()
        if (cleanToNick.isEmpty() || trimmed.isEmpty()) {
            _messageSubmitState.value = ActionSubmitState.Error("Alıcı ve mesaj boş olamaz.")
            return
        }
        if (!_isUserLoggedIn.value) {
            openUserLogin()
            _messageSubmitState.value = ActionSubmitState.Error(
                "Mesaj göndermek için giriş yapmalısınız."
            )
            return
        }
        viewModelScope.launch {
            _messageSubmitState.value = ActionSubmitState.Submitting
            try {
                repository.sendDirectMessage(
                    toNick = cleanToNick,
                    message = trimmed,
                    replyThreadUrl = replyThreadUrl
                ).fold(
                    onSuccess = {
                        _messageSubmitState.value = ActionSubmitState.Success("Mesaj gönderildi")
                        val thread = activeMessageThread
                        if (replyThreadUrl != null && thread != null) {
                            openMessageThread(thread)
                        } else {
                            loadMessageThreads(forceRefresh = true)
                        }
                    },
                    onFailure = { error ->
                        _messageSubmitState.value = ActionSubmitState.Error(
                            error.localizedMessage ?: "Mesaj gönderilemedi."
                        )
                    }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _messageSubmitState.value = ActionSubmitState.Error(
                    e.localizedMessage ?: "Mesaj gönderilemedi."
                )
            }
        }
    }

    fun resetMessageSubmitState() {
        _messageSubmitState.value = ActionSubmitState.Idle
    }

    fun triggerRefresh() {
        refreshCookieState()
        initialBugunPrefetchScheduled.set(false)
        bugunPrefetchJob?.cancel()
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
                        modelId = chosenModel.modelId
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

        fun provideFactory(context: Context): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
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
                        geminiHelper = container.geminiHelper
                    ) as T
                }
            }
    }
}
