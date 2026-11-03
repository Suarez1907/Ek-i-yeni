package com.example.ui.sheets

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.EntryItem
import com.example.data.model.TopicItem
import com.example.data.model.TopicPageData
import com.example.ui.components.AuthorMiniAvatar
import com.example.ui.components.EntryListSkeletonShimmer
import com.example.ui.theme.EksiNeonGreen
import com.example.ui.theme.OledHairlineDivider
import com.example.ui.theme.OledOnAccentColor
import com.example.ui.theme.OledPillGreenBg
import com.example.ui.theme.OledTextMuted
import com.example.ui.theme.OledTextPrimary
import com.example.ui.theme.OledTextSecondary

/**
 * `(bkz: ...)`, gizli `[✨ bkz: ...]` veya yazar önizleme durumu.
 */
sealed interface BkzPreviewUiState {
    data object Hidden : BkzPreviewUiState
    data class Loading(val bkzQuery: String) : BkzPreviewUiState
    data class Success(
        val bkzQuery: String,
        val pageData: TopicPageData
    ) : BkzPreviewUiState
    data class Error(
        val bkzQuery: String,
        val message: String
    ) : BkzPreviewUiState
}

/**
 * Akıllı "(bkz:)" ve Yazar Alt Penceresi (Bottom Sheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BkzPreviewBottomSheet(
    state: BkzPreviewUiState,
    onDismiss: () -> Unit,
    onOpenFullTopic: (TopicItem) -> Unit
) {
    if (state is BkzPreviewUiState.Hidden) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val queryTitle = when (state) {
        is BkzPreviewUiState.Loading -> state.bkzQuery
        is BkzPreviewUiState.Success -> state.pageData.topicTitle.ifBlank { state.bkzQuery }
        is BkzPreviewUiState.Error -> state.bkzQuery
        BkzPreviewUiState.Hidden -> ""
    }

    fun triggerOpenFullTopic() {
        when (state) {
            is BkzPreviewUiState.Success -> {
                val pageData = state.pageData
                onOpenFullTopic(
                    TopicItem(
                        id = pageData.topicId.ifBlank { pageData.cleanTopicUrl },
                        title = pageData.topicTitle.ifBlank { state.bkzQuery },
                        slug = pageData.topicSlug,
                        url = pageData.cleanTopicUrl
                    )
                )
            }
            is BkzPreviewUiState.Loading -> {
                val encoded = Uri.encode(state.bkzQuery)
                onOpenFullTopic(
                    TopicItem(
                        id = state.bkzQuery,
                        title = state.bkzQuery,
                        slug = state.bkzQuery.replace(" ", "-"),
                        url = "https://eksisozluk.com/?q=$encoded"
                    )
                )
            }
            is BkzPreviewUiState.Error -> {
                val encoded = Uri.encode(state.bkzQuery)
                onOpenFullTopic(
                    TopicItem(
                        id = state.bkzQuery,
                        title = state.bkzQuery,
                        slug = state.bkzQuery.replace(" ", "-"),
                        url = "https://eksisozluk.com/?q=$encoded"
                    )
                )
            }
            BkzPreviewUiState.Hidden -> Unit
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 280.dp, max = 640.dp)
                .background(MaterialTheme.colorScheme.background)
                .padding(horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            color = OledPillGreenBg,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = "✨ Önizleme",
                                style = MaterialTheme.typography.labelSmall,
                                color = EksiNeonGreen,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                        if (state is BkzPreviewUiState.Success) {
                            Text(
                                text = "${state.pageData.pageCount} sayfa",
                                style = MaterialTheme.typography.labelSmall,
                                color = OledTextSecondary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = queryTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        color = OledPillGreenBg,
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable { triggerOpenFullTopic() }
                            .border(0.8.dp, EksiNeonGreen.copy(alpha = 0.6f), RoundedCornerShape(50))
                            .testTag("bkz_open_full_topic_button")
                    ) {
                        Text(
                            text = "Başlığa Git ↗",
                            color = EksiNeonGreen,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(34.dp)
                            .testTag("bkz_preview_close_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Kapat",
                            tint = OledTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(10.dp))

            when (state) {
                is BkzPreviewUiState.Loading -> {
                    EntryListSkeletonShimmer(cardCount = 2)
                }

                is BkzPreviewUiState.Success -> {
                    val entries = state.pageData.entries
                    if (entries.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Bu başlıkta henüz entry bulunmuyor.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = OledTextSecondary
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false),
                            contentPadding = PaddingValues(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(
                                items = entries,
                                key = { entry -> "bkz_${entry.id}" }
                            ) { entry ->
                                BkzPreviewEntryCard(entry = entry)
                            }
                        }
                    }
                }

                is BkzPreviewUiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Button(
                            onClick = { triggerOpenFullTopic() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EksiNeonGreen,
                                contentColor = OledOnAccentColor
                            ),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text("Başlığa Git ↗", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                BkzPreviewUiState.Hidden -> Unit
            }
        }
    }
}

@Composable
private fun BkzPreviewEntryCard(entry: EntryItem) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .border(0.5.dp, OledHairlineDivider, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = entry.annotatedContent,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontSize = 15.sp,
                    lineHeight = 24.sp
                ),
                color = OledTextPrimary
            )
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = OledHairlineDivider, thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    color = OledPillGreenBg,
                    shape = RoundedCornerShape(50)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            tint = EksiNeonGreen,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "${entry.favoriteCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = EksiNeonGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = entry.author,
                            style = MaterialTheme.typography.labelMedium,
                            color = EksiNeonGreen,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = entry.date,
                            style = MaterialTheme.typography.labelSmall,
                            color = OledTextMuted,
                            fontSize = 10.sp
                        )
                    }
                    AuthorMiniAvatar(
                        authorName = entry.author,
                        avatarUrl = entry.avatarUrl,
                        size = 24.dp
                    )
                }
            }
        }
    }
}
