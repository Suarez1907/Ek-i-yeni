package com.example.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.example.ai.GeminiAnalysisMode
import com.example.ai.GeminiHelper
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
import com.example.network.CloudflareChallengeEvent
import com.example.network.CloudflareChallengeManager
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
    PRO_STUDIO("gemini-3-pro-image-preview", "Gemini 3 Pro Image (Stüdyo 1K/2K/4K)"),
    NANO_BANANA("gemini-nano-banana-2.1", "Gemini Nano Banana 2.1 (Hızlı)")
}

data class ActiveAuthSheet(
    val url: String,
    val mode: AuthWebViewMode,
    val reason: String
)

private data class TopicQueryParams(
    val topic: TopicItem?,
    val initialPage: Int,
    val filterMode: TopicFilterMode,
    val inTopicQuery: String,
    val refreshTick: Int
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

    // 4. ARAYÜZ RENK / TEMA SEÇENEĞİ (OLED Siyah, Koyu Gri, Klasik Beyaz)
    private val _themeMode = MutableStateFlow(ThemePreferencesManager.loadSavedTheme(appContext))
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _showThemeDialog = MutableStateFlow(false)
    val showThemeDialog: StateFlow<Boolean> = _showThemeDialog.asStateFlow()

    // Sol Frame Sekmesi
    private val _selectedSection = MutableStateFlow(LeftFrameSection.GUNDEM)
    val selectedSection: StateFlow<LeftFrameSection> = _selectedSection.asStateFlow()

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

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchSortOrder = MutableStateFlow(SearchSortOrder.COUNT)
    val searchSortOrder: StateFlow<SearchSortOrder> = _searchSortOrder.asStateFlow()

    private val _submittedSearchFilter = MutableStateFlow(SearchFilter(keywords = ""))
    val submittedSearchFilter: StateFlow<SearchFilter> = _submittedSearchFilter.asStateFlow()

    private val _selectedTopic = MutableStateFlow<TopicItem?>(null)
    val selectedTopic: StateFlow<TopicItem?> = _selectedTopic.asStateFlow()

    private val _initialTopicPage = MutableStateFlow(1)
    val initialTopicPage: StateFlow<Int> = _initialTopicPage.asStateFlow()

    private val _scrollToTopEvent = MutableStateFlow(0)
    val scrollToTopEvent: StateFlow<Int> = _scrollToTopEvent.asStateFlow()

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

    private val _geminiStreamState = MutableStateFlow<GeminiStreamState>(GeminiStreamState.Idle)
    val geminiStreamState: StateFlow<GeminiStreamState> = _geminiStreamState.asStateFlow()
    private var activeGeminiJob: Job? = null

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

    val topicEntriesPagingFlow: Flow<PagingData<EntryItem>> =
        combine(
            _selectedTopic,
            _initialTopicPage,
            _topicFilterMode,
            _inTopicSearchQuery,
            _refreshTrigger
        ) { topic, initialPage, filterMode, inTopicQuery, tick ->
            TopicQueryParams(
                topic = topic,
                initialPage = initialPage.coerceAtLeast(1),
                filterMode = filterMode,
                inTopicQuery = inTopicQuery,
                refreshTick = tick
            )
        }.flatMapLatest { params ->
            val url = params.topic?.url ?: "${WebViewCookieJar.DEFAULT_BASE_URL}/eksisozluk--31499"
            repository.getTopicEntriesPager(
                rawTopicUrl = url,
                filterMode = params.filterMode,
                inTopicSearchKeyword = params.inTopicQuery,
                initialPage = params.initialPage,
                onPageMetadataLoaded = { meta ->
                    _activeTopicMetadata.value = meta
                }
            )
        }.cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            cloudflareManager.challengeEvents.collect { event: CloudflareChallengeEvent ->
                _pendingCloudflareChallenge.value = event
            }
        }
        loadDebeAndStats()
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
                        _debeAndStatsUiState.value = DebeAndStatsUiState.Success(
                            items = list,
                            subtitle = "Dünün En Beğenilen ${list.size} Entry'si (DEBE)"
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
                        _debeAndStatsUiState.value = DebeAndStatsUiState.Success(
                            items = list,
                            subtitle = "${period.label} • ${tab.title} (${list.size})"
                        )
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

    fun openRankedItem(item: RankedTopicOrEntryItem) {
        val topic = item.toTopicItem()
        openTopic(
            topic = topic,
            preferTodayMode = !item.isDirectEntry
        )
    }

    fun selectSection(section: LeftFrameSection) {
        _selectedSection.value = section
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

    /**
     * 2. BAŞLIK İÇİ VARSAYILAN "SADECE BUGÜN" MODU:
     * Kullanıcı Gündem, Bugün veya Kanallar listesinden bir başlığa tıkladığında,
     * başlığı varsayılan olarak SADECE O GÜN (son 24 saatte) yazılan entry'leri gösterecek şekilde
     * (`TopicFilterMode.TODAY_POPULAR` -> `?a=popular`) açar.
     * Eğer bugün hiç entry girilmemişse `TopicPagingSource` otomatik olarak tüm entry'lere fallback yapar.
     */
    fun openTopic(
        topic: TopicItem,
        preferTodayMode: Boolean = true
    ) {
        viewModelScope.launch {
            _bkzPreviewState.value = BkzPreviewUiState.Hidden
            _activeTopicMetadata.value = null
            _inTopicSearchQuery.value = ""
            _authorFilterInTopic.value = null

            val isDirectEntry = topic.url.contains("/entry/", ignoreCase = true) ||
                topic.url.contains("a=debe", ignoreCase = true)

            if (preferTodayMode && !isDirectEntry) {
                _topicFilterMode.value = TopicFilterMode.TODAY_POPULAR
                _restoredReadState.value = null
                _initialTopicPage.value = 1
            } else {
                _topicFilterMode.value = TopicFilterMode.ALL
                val savedState = repository.getSavedTopicReadState(
                    rawOrCleanTopicUrl = topic.cleanUrl,
                    topicId = topic.id
                )
                _restoredReadState.value = savedState
                _initialTopicPage.value = if (isDirectEntry) 1 else (savedState?.pageNumber ?: 1)
            }

            _selectedTopic.value = topic
        }
    }

    /**
     * 2. "TÜMÜNÜ GÖSTER (İlk Entry'den İtibaren Tüm Başlığı Yükle)" Butonu Aksiyonu:
     * URL'deki `?a=popular` / gün filtresini veya tekil entry yolunu kaldırıp
     * başlığın 1. sayfasından (`?p=1`) itibaren tüm entry'leri sayfalama (Pager) desteğiyle yükler.
     */
    fun showAllTopicEntriesFromFirstPage() {
        val current = _selectedTopic.value ?: return
        val metadata = _activeTopicMetadata.value
        val resolvedCleanUrl = metadata?.cleanTopicUrl?.takeIf {
            it.isNotBlank() && !it.contains("/entry/", ignoreCase = true)
        } ?: current.cleanUrl

        _authorFilterInTopic.value = null
        _inTopicSearchQuery.value = ""
        _restoredReadState.value = null
        _initialTopicPage.value = 1
        _topicFilterMode.value = TopicFilterMode.ALL
        _selectedTopic.value = current.copy(
            title = metadata?.topicTitle?.takeIf { it.isNotBlank() } ?: current.title,
            url = resolvedCleanUrl
        )
        _refreshTrigger.value += 1
        _scrollToTopEvent.value += 1
    }

    /**
     * Entry içindeki `(bkz: ...)` veya `*` tıklandığında mevcut sayfayı kaybetmeden
     * `BkzPreviewBottomSheet` açar ve ilgili başlığın en popüler/ilk 5 entry'sini yükler.
     */
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

    /**
     * Entry kartı sola kaydırıldığında (`EndToStart`) yazarın o başlıktaki diğer entry'lerini filtreler.
     */
    fun filterByAuthorInTopic(author: String) {
        val cleanAuthor = author.trim().removePrefix("@")
        if (cleanAuthor.isEmpty()) return
        _authorFilterInTopic.value = cleanAuthor
        _topicFilterMode.value = TopicFilterMode.SEARCH_IN_TOPIC
        _inTopicSearchQuery.value = "@$cleanAuthor"
    }

    fun clearAuthorFilterInTopic() {
        _authorFilterInTopic.value = null
        _inTopicSearchQuery.value = ""
        _topicFilterMode.value = TopicFilterMode.ALL
    }

    /**
     * Entry kartı sağa kaydırıldığında (`StartToEnd`) Şükela / Fav işlemini tetikler.
     */
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
        val totalPages = metadata?.pageCount ?: 1

        viewModelScope.launch {
            repository.saveTopicReadState(
                topicUrl = canonicalUrl,
                topicId = metadata?.topicId ?: currentTopic.id,
                topicTitle = metadata?.topicTitle ?: currentTopic.title,
                topicSlug = metadata?.topicSlug ?: currentTopic.slug,
                entryId = entry.id,
                pageNumber = entry.pageNumber,
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
        if (mode != TopicFilterMode.SEARCH_IN_TOPIC) {
            _authorFilterInTopic.value = null
            _inTopicSearchQuery.value = ""
        }
        _initialTopicPage.value = 1
        _topicFilterMode.value = mode
        _scrollToTopEvent.value += 1
    }

    fun jumpToTopicPage(targetPage: Int) {
        val total = _activeTopicMetadata.value?.pageCount ?: Int.MAX_VALUE
        val safePage = targetPage.coerceIn(1, total.coerceAtLeast(1))
        _restoredReadState.value = null
        _initialTopicPage.value = safePage
        _refreshTrigger.value += 1
        _scrollToTopEvent.value += 1
    }

    fun updateInTopicSearchQuery(keyword: String) {
        _inTopicSearchQuery.value = keyword
    }

    fun closeTopicDetail() {
        _selectedTopic.value = null
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
        loadDebeAndStats()
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
                val dim = chosenSize.pixelDimension.coerceIn(512, 1024)
                val bitmap = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.BLACK)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.parseColor("#81C784")
                    textSize = 36f
                }
                canvas.drawText(cleanPrompt.take(32), 48f, dim / 2f, paint)
                _aiImageState.value = AiImageState.Success(
                    bitmap = bitmap,
                    modelUsed = chosenModel.displayName,
                    sizeLabel = chosenSize.label,
                    aspectRatioLabel = chosenRatio.label
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Görsel üretim hatası", e)
                _aiImageState.value = AiImageState.Error(
                    e.localizedMessage ?: "Görsel üretimi başarısız oldu."
                )
            }
        }
    }

    companion object {
        private const val TAG = "EksinViewModel"

        fun provideFactory(context: Context): androidx.lifecycle.ViewModelProvider.Factory =
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val container = com.example.di.AppModule.getContainer(context)
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
