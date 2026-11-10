package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import com.example.data.remote.EksiParser

private data class LinkPalette(
    val bkz: Color,
    val hiddenBkz: Color,
    val hiddenBackground: Color,
    val external: Color
)

private fun palette(isLight: Boolean): LinkPalette {
    return if (isLight) {
        LinkPalette(
            bkz = Color(0xFF2E7D32),
            hiddenBkz = Color(0xFF2E7D32),
            hiddenBackground = Color(0xFFE8F5E9),
            external = Color(0xFF1565C0)
        )
    } else {
        LinkPalette(
            bkz = Color(0xFF81C784),
            hiddenBkz = Color(0xFFFFB74D),
            hiddenBackground = Color(0xFF112415),
            external = Color(0xFF64B5F6)
        )
    }
}

/**
 * Re-applies link colors from the current theme. The parser stores only semantic tags,
 * so a cached [AnnotatedString] picks up a theme change the next time it is drawn.
 */
@Composable
fun rememberThemedEntryText(
    source: AnnotatedString,
    onBkzClick: (String) -> Unit,
    onExternalUrlClick: (String) -> Unit
): AnnotatedString {
    val isLight = LocalAppThemeMode.current.isLight
    return remember(source, isLight, onBkzClick, onExternalUrlClick) {
        source.withThemedLinks(
            isLight = isLight,
            onBkzClick = onBkzClick,
            onExternalUrlClick = onExternalUrlClick
        )
    }
}

fun AnnotatedString.withThemedLinks(
    isLight: Boolean,
    onBkzClick: (String) -> Unit,
    onExternalUrlClick: (String) -> Unit
): AnnotatedString {
    val colors = palette(isLight)
    val annotations = getStringAnnotations(start = 0, end = length)
    if (annotations.isEmpty()) return this
    return buildAnnotatedString {
        var cursor = 0
        val sorted = annotations.sortedBy { it.start }
        for (annotation in sorted) {
            if (annotation.start < cursor) continue
            if (annotation.start > cursor) {
                append(text.substring(cursor, annotation.start))
            }
            val end = annotation.end.coerceAtMost(text.length)
            val slice = text.substring(annotation.start, end)
            when (annotation.tag) {
                EksiParser.TAG_BKZ -> withLink(
                    LinkAnnotation.Clickable(
                        tag = annotation.tag,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = colors.bkz,
                                fontWeight = FontWeight.SemiBold
                            )
                        ),
                        linkInteractionListener = { onBkzClick(annotation.item) }
                    )
                ) { append(slice) }
                EksiParser.TAG_HIDDEN_BKZ -> withLink(
                    LinkAnnotation.Clickable(
                        tag = annotation.tag,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = colors.hiddenBkz,
                                background = colors.hiddenBackground,
                                fontWeight = FontWeight.SemiBold
                            )
                        ),
                        linkInteractionListener = { onBkzClick(annotation.item) }
                    )
                ) { append(slice) }
                EksiParser.TAG_EXTERNAL_URL -> withLink(
                    LinkAnnotation.Clickable(
                        tag = annotation.tag,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = colors.external,
                                fontWeight = FontWeight.Medium,
                                textDecoration = TextDecoration.Underline
                            )
                        ),
                        linkInteractionListener = {
                            if (annotation.item.isNotBlank()) onExternalUrlClick(annotation.item)
                        }
                    )
                ) { append(slice) }
                else -> append(slice)
            }
            cursor = end
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}
