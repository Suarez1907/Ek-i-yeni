package com.example.ui.social

import com.example.network.RedditPage
import com.example.network.SocialFeedKind
import com.example.network.XFeedResult

data class SocialFeedUi(
    val kind: SocialFeedKind,
    val query: String,
    val queryDraft: String,
    val loading: Boolean = false,
    val reddit: RedditPage = RedditPage(),
    val x: XFeedResult = XFeedResult()
)
