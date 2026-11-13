package com.example.ui.social

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.network.RedditComment
import com.example.network.RedditCommentsPage
import com.example.network.RedditPost
import com.example.network.SocialFeedKind
import com.example.network.XPost
import com.example.network.xLiveSearchUrl
import com.example.ui.theme.EksiNeonGreen
import com.example.ui.theme.OledHairlineDivider
import com.example.ui.theme.OledOnAccentColor
import com.example.ui.theme.OledTextMuted
import com.example.ui.theme.OledTextPrimary
import com.example.ui.theme.OledTextSecondary

data class CardTranslation(
    val loading: Boolean = false,
    val text: String? = null,
    val showing: Boolean = false,
    val error: String? = null
)

@Composable
fun SocialFeedPane(
    state: SocialFeedUi,
    translations: Map<String, CardTranslation>,
    userAgent: String,
    onQueryDraft: (String) -> Unit,
    onRefresh: () -> Unit,
    onTranslate: (String, String, String) -> Unit,
    onSummarize: () -> Unit,
    loadRedditComments: suspend (String) -> RedditCommentsPage,
    modifier: Modifier = Modifier
) {
    var webPage by remember { mutableStateOf<Pair<String, String>?>(null) }
    var redditPost by remember { mutableStateOf<RedditPost?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = state.queryDraft,
                onValueChange = onQueryDraft,
                modifier = Modifier
                    .weight(1f)
                    .testTag("social_query_field"),
                singleLine = true,
                label = { Text("Sosyal arama") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onRefresh() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = EksiNeonGreen,
                    unfocusedBorderColor = OledHairlineDivider,
                    focusedTextColor = OledTextPrimary,
                    unfocusedTextColor = OledTextPrimary
                )
            )
            IconButton(onClick = onRefresh, modifier = Modifier.testTag("social_refresh")) {
                Icon(Icons.Default.Refresh, contentDescription = "Yenile", tint = EksiNeonGreen)
            }
        }

        if (state.loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = EksiNeonGreen,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "summary_action", contentType = "social_action") {
                AssistChip(
                    onClick = onSummarize,
                    enabled = !state.loading,
                    label = {
                        Text(
                            text = if (state.kind == SocialFeedKind.REDDIT) {
                                "✨ Tüm Reddit Tartışmasını Türkçe Özetle"
                            } else {
                                "✨ Tüm X Tartışmasını Türkçe Özetle"
                            },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                )
            }

            if (state.kind == SocialFeedKind.X) {
                item(key = "x_live", contentType = "social_action") {
                    Button(
                        onClick = {
                            webPage = "X araması" to xLiveSearchUrl(state.queryDraft.ifBlank { state.query })
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EksiNeonGreen,
                            contentColor = OledOnAccentColor
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("🐦 X'te Canlı Ara (Uygulama İçi)", fontWeight = FontWeight.Bold)
                    }
                    Text(
                        text = "X giriş isteyebilir. Çerezler bu WebView'da kalır; Ekşi oturum ekranına yazılmaz.",
                        color = OledTextMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                item(key = "shared_title", contentType = "social_header") {
                    Text(
                        text = "📌 Başlıkta Paylaşılan Tweetler",
                        color = EksiNeonGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (state.x.shared.isEmpty() && !state.loading) {
                    item(key = "shared_empty", contentType = "social_note") {
                        Text(
                            text = "Bu sayfadaki entry'lerde X veya Twitter durum bağlantısı yok.",
                            color = OledTextSecondary,
                            fontSize = 13.sp
                        )
                    }
                }
                items(state.x.shared, key = { "x_${it.id}" }, contentType = { "x_card" }) { post ->
                    XCard(post, translations[post.id], onTranslate)
                }
                item(key = "recent_title", contentType = "social_header") {
                    Text(
                        text = "Son arama",
                        color = EksiNeonGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
                state.x.recentNote?.let { note ->
                    item(key = "recent_note", contentType = "social_note") {
                        Text(text = note, color = OledTextSecondary, fontSize = 13.sp)
                    }
                }
                items(state.x.recent, key = { "xr_${it.id}" }, contentType = { "x_card" }) { post ->
                    XCard(post, translations[post.id], onTranslate)
                }
            } else {
                val error = state.reddit.error
                if (!error.isNullOrBlank()) {
                    item(key = "reddit_error", contentType = "social_note") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = error, color = OledTextPrimary, fontSize = 14.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = onRefresh) { Text("Yeniden dene") }
                                Button(
                                    onClick = {
                                        webPage = "Reddit" to state.reddit.openUrl.ifBlank { "https://www.reddit.com" }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = EksiNeonGreen,
                                        contentColor = OledOnAccentColor
                                    )
                                ) { Text("Reddit'te aç") }
                            }
                        }
                    }
                }
                items(state.reddit.posts, key = { "rd_${it.id}" }, contentType = { "reddit_card" }) { post ->
                    RedditCard(
                        post = post,
                        translation = translations[post.id],
                        onTranslate = onTranslate,
                        onOpen = { redditPost = post }
                    )
                }
            }
        }
    }

    redditPost?.let { post ->
        RedditDetailSheet(
            post = post,
            loadComments = { loadRedditComments(post.permalink) },
            onOpenWeb = { webPage = post.title to "https://www.reddit.com${post.permalink}" },
            onDismiss = { redditPost = null }
        )
    }
    webPage?.let { (title, url) ->
        SocialWebViewSheet(
            title = title,
            url = url,
            userAgent = userAgent,
            onDismiss = { webPage = null }
        )
    }
}

@Composable
private fun RedditCard(
    post: RedditPost,
    translation: CardTranslation?,
    onTranslate: (String, String, String) -> Unit,
    onOpen: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen)
            .border(0.5.dp, OledHairlineDivider, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "${post.subreddit} • ${post.author} • ▲ ${post.ups} • ${post.commentCount} yorum",
                color = OledTextSecondary,
                fontSize = 11.sp
            )
            Text(text = post.title, color = OledTextPrimary, fontWeight = FontWeight.Bold)
            if (post.selfText.isNotBlank()) {
                Text(
                    text = post.selfText,
                    color = OledTextPrimary,
                    fontSize = 13.sp,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
            post.imageUrl?.let { image ->
                AsyncImage(
                    model = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            }
            TranslationBlock(
                id = post.id,
                title = post.title,
                body = post.selfText,
                translation = translation,
                onTranslate = onTranslate
            )
        }
    }
}

@Composable
private fun XCard(
    post: XPost,
    translation: CardTranslation?,
    onTranslate: (String, String, String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, OledHairlineDivider, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = post.author, color = EksiNeonGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(text = post.text, color = OledTextPrimary, fontSize = 14.sp)
            Text(text = post.url, color = OledTextMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TranslationBlock(
                id = post.id,
                title = post.author,
                body = post.text,
                translation = translation,
                onTranslate = onTranslate
            )
        }
    }
}

@Composable
private fun TranslationBlock(
    id: String,
    title: String,
    body: String,
    translation: CardTranslation?,
    onTranslate: (String, String, String) -> Unit
) {
    AssistChip(
        onClick = { onTranslate(id, title, body) },
        label = {
            Text(
                text = when {
                    translation?.loading == true -> "Çevriliyor..."
                    translation?.showing == true -> "Orijinali göster"
                    else -> "🇹🇷 Türkçeye Çevir"
                },
                fontSize = 11.sp
            )
        }
    )
    if (translation?.showing == true && !translation.text.isNullOrBlank()) {
        Text(text = translation.text, color = OledTextPrimary, fontSize = 13.sp)
    }
    translation?.error?.let { error ->
        Text(text = error, color = OledTextSecondary, fontSize = 12.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RedditDetailSheet(
    post: RedditPost,
    loadComments: suspend () -> RedditCommentsPage,
    onOpenWeb: () -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var page by remember(post.id) { mutableStateOf<RedditCommentsPage?>(null) }
    var loading by remember(post.id) { mutableStateOf(true) }
    LaunchedEffect(post.id) {
        loading = true
        page = runCatching { loadComments() }.getOrElse {
            RedditCommentsPage(error = "Yorumlar alınamadı.", openUrl = "https://www.reddit.com${post.permalink}")
        }
        loading = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 240.dp, max = 640.dp)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(text = post.title, color = OledTextPrimary, fontWeight = FontWeight.Bold)
            Text(
                text = "${post.subreddit} • ${post.author} • ▲ ${post.ups}",
                color = OledTextSecondary,
                fontSize = 12.sp
            )
            if (post.selfText.isNotBlank()) {
                Text(text = post.selfText, color = OledTextPrimary, fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenWeb) { Text("WebView'da aç") }
            }
            Text(text = "Yorumlar", color = EksiNeonGreen, fontWeight = FontWeight.Bold)
            if (loading) {
                CircularProgressIndicator(color = EksiNeonGreen, modifier = Modifier.size(22.dp))
            } else {
                page?.error?.let { error ->
                    Text(text = error, color = OledTextSecondary)
                }
                page?.comments.orEmpty().forEach { comment ->
                    CommentRow(comment)
                }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: RedditComment) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = "${comment.author} • ▲ ${comment.ups}", color = OledTextSecondary, fontSize = 11.sp)
        Text(text = comment.body, color = OledTextPrimary, fontSize = 13.sp)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SocialWebViewSheet(
    title: String,
    url: String,
    userAgent: String,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().height(560.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = OledTextPrimary,
                    fontWeight = FontWeight.Bold
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Kapat", tint = OledTextSecondary)
                }
            }
            key(url) {
                val webHolder = remember { mutableStateOf<WebView?>(null) }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        WebView(context).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = userAgent
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = WebViewClient()
                            loadUrl(url)
                            webHolder.value = this
                        }
                    }
                )
                DisposableEffect(url) {
                    onDispose {
                        webHolder.value?.apply {
                            stopLoading()
                            destroy()
                        }
                    }
                }
            }
        }
    }
}
