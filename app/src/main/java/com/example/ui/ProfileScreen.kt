package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ActionSubmitState
import com.example.data.model.AuthorProfileData
import com.example.data.model.DirectMessageItem
import com.example.data.model.EntryItem
import com.example.data.model.MessageThreadDetail
import com.example.data.model.MessageThreadItem
import com.example.data.model.MessagesUiState
import com.example.data.model.ProfileUiState
import com.example.data.model.ThreadDetailUiState
import com.example.data.model.TopicItem
import com.example.ui.sheets.SendMessageDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    viewModel: EksinViewModel,
    onOpenTopic: (TopicItem) -> Unit,
    onOpenAuthorProfile: (String) -> Unit,
    onRequestLogin: () -> Unit
) {
    val isUserLoggedIn by viewModel.isUserLoggedIn.collectAsStateWithLifecycle()
    val currentUsername by viewModel.currentUsername.collectAsStateWithLifecycle()
    val myProfileState by viewModel.myProfileState.collectAsStateWithLifecycle()
    val messageThreadsState by viewModel.messageThreadsState.collectAsStateWithLifecycle()
    val activeThreadDetailState by viewModel.activeThreadDetailState.collectAsStateWithLifecycle()
    val messageSubmitState by viewModel.messageSubmitState.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showNewMessageDialog by remember { mutableStateOf(false) }
    var showThreadDetail by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        if (isUserLoggedIn) {
            if (myProfileState is ProfileUiState.Idle) viewModel.loadMyProfile()
            if (messageThreadsState is MessagesUiState.Idle) viewModel.loadMessageThreads()
        }
    }

    LaunchedEffect(activeThreadDetailState) {
        if (activeThreadDetailState !is ThreadDetailUiState.Hidden) {
            showThreadDetail = true
        }
    }

    LaunchedEffect(messageSubmitState) {
        if (messageSubmitState is ActionSubmitState.Success) {
            showNewMessageDialog = false
            snackbarHostState.showSnackbar("Mesaj gönderildi")
        }
    }

    if (!isUserLoggedIn) {
        LoginRequiredContent(onRequestLogin)
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Profilim") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Mesajlar") }
                )
            }
            if (selectedTab == 0) {
                MyProfileTab(
                    username = currentUsername.orEmpty(),
                    state = myProfileState,
                    onRefresh = viewModel::loadMyProfile,
                    onOpenTopic = onOpenTopic
                )
            } else {
                MessagesTab(
                    threadsState = messageThreadsState,
                    detailState = activeThreadDetailState,
                    showThreadDetail = showThreadDetail,
                    submitState = messageSubmitState,
                    onOpenThread = { thread ->
                        viewModel.openMessageThread(thread)
                        showThreadDetail = true
                    },
                    onBackFromDetail = { showThreadDetail = false },
                    onOpenAuthorProfile = onOpenAuthorProfile,
                    onSendReply = { to, message, threadUrl ->
                        viewModel.sendDirectMessage(to, message, threadUrl)
                    },
                    onNewMessage = { showNewMessageDialog = true },
                    onRefresh = viewModel::loadMessageThreads
                )
            }
        }
    }

    if (showNewMessageDialog) {
        SendMessageDialog(
            submitState = messageSubmitState,
            onSend = { to, msg -> viewModel.sendDirectMessage(to, msg, "") },
            onDismiss = { showNewMessageDialog = false }
        )
    }
}

@Composable
private fun LoginRequiredContent(onRequestLogin: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Eksi Sözlük Hesabına Giriş Yap",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    "Entry girmek, profilini ve mesajlarını görüntülemek için giriş yapmalısın.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(onClick = onRequestLogin) {
                    Text("Giriş Yap")
                }
            }
        }
    }
}

@Composable
private fun MyProfileTab(
    username: String,
    state: ProfileUiState,
    onRefresh: () -> Unit,
    onOpenTopic: (TopicItem) -> Unit
) {
    when (state) {
        is ProfileUiState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        is ProfileUiState.Error -> ErrorCard(state.message, onRefresh)
        is ProfileUiState.Success -> {
            val profile = state.profile
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ProfileHeaderCard(
                        nick = username,
                        profile = profile
                    )
                }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Son Entry'ler",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        IconButton(onClick = onRefresh) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Yenile",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                items(profile.recentEntries) { entry ->
                    EntryCard(
                        entry = entry,
                        onClick = {
                            onOpenTopic(
                                TopicItem(
                                    id = entry.id,
                                    title = entry.topicTitle,
                                    slug = "",
                                    url = entry.topicUrl
                                )
                            )
                        }
                    )
                }
            }
        }
        else -> Unit
    }
}

@Composable
private fun MessagesTab(
    threadsState: MessagesUiState,
    detailState: ThreadDetailUiState,
    showThreadDetail: Boolean,
    submitState: ActionSubmitState,
    onOpenThread: (MessageThreadItem) -> Unit,
    onBackFromDetail: () -> Unit,
    onOpenAuthorProfile: (String) -> Unit,
    onSendReply: (String, String, String) -> Unit,
    onNewMessage: () -> Unit,
    onRefresh: () -> Unit
) {
    if (showThreadDetail && detailState !is ThreadDetailUiState.Hidden) {
        when (detailState) {
            is ThreadDetailUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            is ThreadDetailUiState.Error -> ErrorCard(detailState.message, onRefresh)
            is ThreadDetailUiState.Success -> ThreadDetailContent(
                detail = detailState.detail,
                submitState = submitState,
                onBack = onBackFromDetail,
                onOpenAuthorProfile = onOpenAuthorProfile,
                onSendReply = onSendReply
            )
            else -> Unit
        }
    } else {
        when (threadsState) {
            is MessagesUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
            is MessagesUiState.Error -> ErrorCard(threadsState.message, onRefresh)
            is MessagesUiState.Success -> ThreadListContent(
                threads = threadsState.threads,
                onOpenThread = onOpenThread,
                onNewMessage = onNewMessage
            )
            else -> Unit
        }
    }
}

@Composable
private fun ThreadListContent(
    threads: List<MessageThreadItem>,
    onOpenThread: (MessageThreadItem) -> Unit,
    onNewMessage: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(threads) { thread ->
                MessageThreadCard(thread = thread, onClick = { onOpenThread(thread) })
            }
        }
        FloatingActionButton(
            onClick = onNewMessage,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(Icons.Default.Add, contentDescription = "Yeni Mesaj")
        }
    }
}

@Composable
private fun MessageThreadCard(thread: MessageThreadItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = thread.participantNick.take(1).uppercase(),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = thread.participantNick,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = thread.lastMessageSnippet,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = thread.lastDateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (thread.isUnread) {
                    Text(
                        text = "Yeni",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
private fun ThreadDetailContent(
    detail: MessageThreadDetail,
    submitState: ActionSubmitState,
    onBack: () -> Unit,
    onOpenAuthorProfile: (String) -> Unit,
    onSendReply: (String, String, String) -> Unit
) {
    var reply by remember { mutableStateOf("") }
    val isSubmitting = submitState is ActionSubmitState.Submitting

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Geri",
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = detail.participantNick,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .clickable { onOpenAuthorProfile(detail.participantNick) }
            )
        }
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(detail.messages) { message ->
                MessageBubble(message)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = reply,
                onValueChange = { reply = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Yanıt yaz...") },
                maxLines = 4
            )
            Button(
                onClick = {
                    if (reply.isNotBlank()) {
                        onSendReply(detail.participantNick, reply.trim(), detail.threadUrl)
                        reply = ""
                    }
                },
                enabled = reply.isNotBlank() && !isSubmitting
            ) {
                if (isSubmitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Gönder")
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: DirectMessageItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isOutgoing) Arrangement.End else Arrangement.Start
    ) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (message.isOutgoing) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = message.senderNick,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = message.contentText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (message.isOutgoing) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = message.dateText,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ProfileHeaderCard(
    nick: String,
    profile: AuthorProfileData
) {
    val badgeText = profile.badgeText ?: "çaylak/yazar"
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = nick.take(1).uppercase(),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
                Column {
                    Text(
                        text = nick,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatBox("Entry", profile.entryCountText, Modifier.weight(1f))
                StatBox("Takipçi", profile.followerCountText, Modifier.weight(1f))
                StatBox("Takip", profile.followingCountText, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.small
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EntryCard(entry: EntryItem, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = entry.topicTitle,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = entry.plainText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = entry.date,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "${entry.favoriteCount} favori",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.padding(24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Button(onClick = onRetry) {
                    Text("Yeniden Dene")
                }
            }
        }
    }
}
