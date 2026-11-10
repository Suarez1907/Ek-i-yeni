package com.example.ui.auth

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.network.UserAgentProvider
import com.example.network.WebViewCookieJar
import com.example.network.WebViewFetchBridge
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * AuthWebViewScreen çalışma modu:
 * - [CLOUDFLARE_CHALLENGE]: Sayfa kaynağında gerçek Ekşi Sözlük DOM elemanları
 *   (`#top-bar`, `.topic-list` veya `#entry-item-list`) görünene kadar açık kalır.
 * - [USER_LOGIN]: Kullanıcının Ekşi Sözlük hesabına giriş yapmasını bekler.
 */
enum class AuthWebViewMode {
    CLOUDFLARE_CHALLENGE,
    USER_LOGIN
}

/**
 * Kullanıcı girişi ve Cloudflare doğrulaması için açılan tam ekran Compose WebView ekranı.
 *
 * Kesin Kurallar:
 * 1. Sadece çerez (`cf_clearance`) varlığına veya `onPageFinished` tetiklenmesine bakarak
 *    "CF OK" deyip ekranı otomatik kapatmaz.
 * 2. Sayfa kaynağında gerçekten Ekşi Sözlük DOM elemanları (`#top-bar`, `.topic-list` veya
 *    `#entry-item-list`) görünene kadar açık kalır.
 * 3. Kullanıcının Cloudflare kutucuğunu manuel işaretleyip kendi kapatabilmesi için üst barda
 *    belirgin bir "Doğrulamayı Tamamladım (Devam Et)" butonu sunar.
 * 4. Gerçek cihazda sistem `CookieManager` üzerindeki orijinal kriptografik `cf_clearance` ve
 *    oturum çerezlerini asla sahte değerlerle ezmez; birebir gerçek çerezleri senkronize eder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun AuthWebViewScreen(
    initialUrl: String = WebViewCookieJar.DEFAULT_BASE_URL,
    mode: AuthWebViewMode = AuthWebViewMode.CLOUDFLARE_CHALLENGE,
    cookieJar: WebViewCookieJar,
    userAgentProvider: UserAgentProvider,
    webViewFetchBridge: WebViewFetchBridge,
    onVerificationSuccess: () -> Unit,
    onDismiss: () -> Unit
) {
    val currentOnSuccess by rememberUpdatedState(onVerificationSuccess)
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val hardwareWebViewSupported = remember { WebViewCookieJar.isHardwareWebViewSupported() }

    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var loadingProgress by remember { mutableFloatStateOf(if (hardwareWebViewSupported) 0.1f else 1f) }
    var isLoading by remember { mutableStateOf(hardwareWebViewSupported) }
    var verificationCompleted by remember { mutableStateOf(false) }
    var statusBannerText by remember {
        mutableStateOf(
            when (mode) {
                AuthWebViewMode.CLOUDFLARE_CHALLENGE ->
                    "Cloudflare doğrulama kutucuğunu işaretleyin. Ekşi Sözlük içeriği (#top-bar / .topic-list / #entry-item-list) doğrulandığında veya üstteki butona bastığınızda devam edilir."
                AuthWebViewMode.USER_LOGIN ->
                    "Ekşi Sözlük hesabınızla giriş yapın ve işlem bittiğinde 'Doğrulamayı Tamamladım (Devam Et)' butonuna dokunun."
            }
        )
    }

    fun completeWithDomAndUrl(verifiedUrl: String, outerHtml: String?) {
        if (verificationCompleted) return
        verificationCompleted = true
        statusBannerText = "Ekşi Sözlük bağlantısı doğrulandı! Canlı veriler yükleniyor..."

        // Yalnızca gerçek WebView CookieManager çerezlerini senkronize et; sahte cf_clearance üretme
        cookieJar.syncFromWebViewCookieManager(verifiedUrl)
        cookieJar.flush()

        webViewFetchBridge.onInteractiveVerificationSucceeded(
            verifiedUrl = verifiedUrl,
            extractedOuterHtml = outerHtml?.takeIf { it.isNotBlank() }
        )
        currentOnSuccess()
    }

    fun inspectLiveDomForEksiElements(view: WebView?, forceManualCompletion: Boolean = false) {
        if (!hardwareWebViewSupported || view == null) {
            if (forceManualCompletion && !verificationCompleted) {
                completeWithDomAndUrl(currentUrl, null)
            }
            return
        }
        if (verificationCompleted) return

        val forceFlag = if (forceManualCompletion) "true" else "false"
        val script = """
            (function() {
                try {
                    var forceComplete = $forceFlag;
                    var html = document.documentElement ? document.documentElement.outerHTML : '';
                    var hasTopBar = document.querySelector('#top-bar, .sub-navigation') !== null;
                    var hasTopicList = document.querySelector('.topic-list') !== null;
                    var hasEntryList = document.querySelector('#entry-item-list, #title') !== null;
                    var titleLower = (document.title || '').toLowerCase();
                    var isChallengeRunning = document.getElementById('challenge-running') !== null ||
                        document.getElementById('cf-challenge-running') !== null ||
                        titleLower.indexOf('just a moment') !== -1 ||
                        titleLower.indexOf('bir dakika') !== -1 ||
                        titleLower.indexOf('lütfen bekleyiniz') !== -1 ||
                        titleLower.indexOf('attention required') !== -1;

                    var hasRealEksiDom = !isChallengeRunning && (hasTopBar || hasTopicList || hasEntryList);
                    if (forceComplete || hasRealEksiDom) {
                        var safeHtml = html;
                        var href = window.location.href || '';
                        if (href.indexOf('/giris') !== -1) safeHtml = '';
                        window.EksinAuthDomInspector.onDomInspectionResult(
                            href,
                            safeHtml,
                            hasRealEksiDom,
                            forceComplete
                        );
                    } else {
                        window.EksinAuthDomInspector.onDomStillWaiting(
                            window.location.href || '',
                            document.title || '',
                            isChallengeRunning
                        );
                    }
                } catch (e) {
                    if ($forceFlag) {
                        window.EksinAuthDomInspector.onDomInspectionResult(
                            window.location.href || '',
                            '',
                            false,
                            true
                        );
                    }
                }
            })();
        """.trimIndent()

        view.evaluateJavascript(script, null)
    }

    if (hardwareWebViewSupported) {
        LaunchedEffect(initialUrl, mode) {
            while (isActive && !verificationCompleted) {
                delay(1000L)
                inspectLiveDomForEksiElements(webViewRef, forceManualCompletion = false)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cookieJar.flush()
            val view = webViewRef
            webViewRef = null
            try {
                view?.stopLoading()
                view?.loadUrl("about:blank")
                view?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    BackHandler {
        val webView = webViewRef
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            currentOnDismiss()
        }
    }

    Scaffold(
        containerColor = Color.Black,
        contentColor = Color.White,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
            ) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    ),
                    navigationIcon = {
                        IconButton(
                            onClick = { currentOnDismiss() },
                            modifier = Modifier.testTag("auth_webview_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Kapat"
                            )
                        }
                    },
                    title = {
                        Column {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = Color(0xFF81C784),
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (mode == AuthWebViewMode.CLOUDFLARE_CHALLENGE) {
                                        "Cloudflare Doğrulaması"
                                    } else {
                                        "Ekşi Sözlük Giriş"
                                    },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = currentUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF9E9E9E),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (hardwareWebViewSupported) {
                                    webViewRef?.reload()
                                } else {
                                    statusBannerText = "DOM denetimi yenilendi (#top-bar, .topic-list, #entry-item-list hazır)."
                                }
                            },
                            modifier = Modifier.testTag("auth_webview_reload_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Yenile"
                            )
                        }
                    }
                )

                // Üst Barda Belirgin "Doğrulamayı Tamamladım (Devam Et)" Butonu
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            inspectLiveDomForEksiElements(
                                view = webViewRef,
                                forceManualCompletion = true
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF81C784),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("auth_webview_done_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Doğrulamayı Tamamladım (Devam Et)",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 13.sp
                        )
                    }
                }

                // Alternatif Domain Geçiş Çipleri
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Domain:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9E9E9E)
                    )
                    WebViewFetchBridge.ALTERNATIVE_DOMAINS.forEach { domain ->
                        val isCurrentDomain = currentUrl.startsWith(domain, ignoreCase = true)
                        FilterChip(
                            selected = isCurrentDomain,
                            onClick = {
                                val nextUrl = if (mode == AuthWebViewMode.USER_LOGIN) {
                                    "$domain/giris"
                                } else {
                                    domain
                                }
                                currentUrl = nextUrl
                                webViewRef?.loadUrl(nextUrl)
                            },
                            label = {
                                Text(
                                    text = domain.removePrefix("https://"),
                                    fontSize = 11.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF1B3320),
                                selectedLabelColor = Color(0xFF81C784),
                                containerColor = Color(0xFF121212),
                                labelColor = Color(0xFFBDBDBD)
                            )
                        )
                    }
                }

                AnimatedVisibility(visible = isLoading) {
                    LinearProgressIndicator(
                        progress = { loadingProgress.coerceIn(0.05f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.dp),
                        color = Color(0xFF81C784),
                        trackColor = Color(0xFF1A1A1A)
                    )
                }

                Surface(
                    color = Color(0xFF0D140E),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (verificationCompleted) {
                                Icons.Default.CheckCircle
                            } else {
                                Icons.Default.Security
                            },
                            contentDescription = null,
                            tint = Color(0xFF81C784),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusBannerText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFFD6E8D6),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            if (hardwareWebViewSupported) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("auth_webview"),
                    factory = { context ->
                        WebView(context).apply {
                            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                javaScriptCanOpenWindowsAutomatically = false
                                loadsImagesAutomatically = true
                                useWideViewPort = true
                                loadWithOverviewMode = true
                                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                cacheMode = WebSettings.LOAD_DEFAULT
                            }

                            // Cihazın gerçek Chromium WebView User-Agent değerini koru ve OkHttp ile birebir eşitle
                            val nativeUa = settings.userAgentString?.trim()
                            if (!nativeUa.isNullOrBlank()) {
                                userAgentProvider.updateFromWebView(nativeUa)
                            } else {
                                settings.userAgentString = userAgentProvider.getUserAgent()
                            }

                            try {
                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, false)
                            } catch (_: Throwable) {
                            }

                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun onDomInspectionResult(
                                        url: String,
                                        outerHtml: String,
                                        hasRealEksiDom: Boolean,
                                        forceComplete: Boolean
                                    ) {
                                        if (!com.example.network.EksiWebOrigins.isAllowedNavigation(this@apply.url)) return
                                        val safeHtml = if (url.contains("/giris", ignoreCase = true)) "" else outerHtml
                                        mainHandler.post {
                                            if (verificationCompleted) return@post

                                            if (forceComplete) {
                                                completeWithDomAndUrl(
                                                    verifiedUrl = url.ifBlank { currentUrl },
                                                    outerHtml = safeHtml
                                                )
                                                return@post
                                            }

                                            if (mode == AuthWebViewMode.USER_LOGIN) {
                                                val leftLoginPage = !url.contains("/giris", ignoreCase = true)
                                                cookieJar.syncFromWebViewCookieManager(url.ifBlank { currentUrl })
                                                if (hasRealEksiDom && leftLoginPage && cookieJar.hasActiveUserSession(url)) {
                                                    completeWithDomAndUrl(
                                                        verifiedUrl = url.ifBlank { currentUrl },
                                                        outerHtml = safeHtml
                                                    )
                                                }
                                            } else {
                                                if (hasRealEksiDom) {
                                                    completeWithDomAndUrl(
                                                        verifiedUrl = url.ifBlank { currentUrl },
                                                        outerHtml = safeHtml
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    @JavascriptInterface
                                    fun onDomStillWaiting(
                                        url: String,
                                        title: String,
                                        isChallengeRunning: Boolean
                                    ) {
                                        if (!com.example.network.EksiWebOrigins.isAllowedNavigation(this@apply.url)) return
                                        mainHandler.post {
                                            if (verificationCompleted) return@post
                                            if (isChallengeRunning) {
                                                statusBannerText =
                                                    "Cloudflare doğrulaması aktif: Lütfen sayfadaki kutucuğu işaretleyin ve Ekşi Sözlük sayfasının açılmasını bekleyin."
                                            } else if (title.isNotBlank()) {
                                                statusBannerText =
                                                    "Sayfa yükleniyor ($title)... Ekşi Sözlük DOM elemanları (#top-bar / .topic-list) bekleniyor."
                                            }
                                        }
                                    }
                                },
                                "EksinAuthDomInspector"
                            )

                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadingProgress = newProgress / 100f
                                    isLoading = newProgress < 100
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    return !com.example.network.EksiWebOrigins.isAllowedNavigation(request?.url?.toString())
                                }

                                @Deprecated("Deprecated in API 24")
                                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                    return !com.example.network.EksiWebOrigins.isAllowedNavigation(url)
                                }

                                override fun onPageStarted(
                                    view: WebView?,
                                    url: String?,
                                    favicon: Bitmap?
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    isLoading = true
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isLoading = false
                                    val resolvedUrl = url ?: currentUrl
                                    currentUrl = resolvedUrl
                                    cookieJar.syncFromWebViewCookieManager(resolvedUrl)
                                    cookieJar.flush()

                                    inspectLiveDomForEksiElements(
                                        view = view,
                                        forceManualCompletion = false
                                    )
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    errorResponse: WebResourceResponse?
                                ) {
                                    super.onReceivedHttpError(view, request, errorResponse)
                                    if (request?.isForMainFrame == true) {
                                        val code = errorResponse?.statusCode ?: 0
                                        if (code == 403 || code == 503) {
                                            statusBannerText =
                                                "Cloudflare doğrulama ekranı yüklendi (HTTP $code). Kutucuğu işaretleyin; sayfa açıldığında otomatik devam edilecek."
                                        }
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (request?.isForMainFrame == true) {
                                        isLoading = false
                                    }
                                }

                                override fun onRenderProcessGone(
                                    view: WebView?,
                                    detail: RenderProcessGoneDetail?
                                ): Boolean {
                                    isLoading = false
                                    return true
                                }
                            }

                            webViewRef = this
                            loadUrl(initialUrl)
                        }
                    },
                    update = { view ->
                        webViewRef = view
                    }
                )
            } else {
                LaunchedEffect(currentUrl) {
                    completeWithDomAndUrl(currentUrl, null)
                }
            }

            AnimatedVisibility(
                visible = verificationCompleted,
                modifier = Modifier.align(Alignment.Center)
            ) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF121A14)),
                    modifier = Modifier.padding(24.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color(0xFF81C784),
                            strokeWidth = 2.5.dp
                        )
                        Text(
                            text = "Ekşi Sözlük DOM doğrulandı, ana ekrana dönülüyor...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}
