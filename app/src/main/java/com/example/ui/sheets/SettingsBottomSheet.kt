package com.example.ui.sheets

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsBottomSheet(
    isUserLoggedIn: Boolean,
    currentUsername: String?,
    hasSavedApiKey: Boolean,
    savedApiKey: String,
    currentThemeTitle: String,
    hasCfClearance: Boolean,
    onOpenLogin: () -> Unit,
    onLogout: () -> Unit,
    onSaveApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onOpenThemeSelector: () -> Unit,
    onOpenCloudflare: () -> Unit,
    onDismiss: () -> Unit
) {
    var apiKeyInput by remember { mutableStateOf(savedApiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var apiKeyMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(savedApiKey) {
        apiKeyInput = savedApiKey
    }

    val username = currentUsername?.trim()?.removePrefix("@")?.takeIf { it.isNotBlank() }
    val accountStatus = if (isUserLoggedIn && username != null) "@$username" else "Giriş yapılmadı"
    val cloudflareStatus = if (hasCfClearance) {
        "Cloudflare doğrulaması tamamlandı"
    } else {
        "Cloudflare doğrulaması gerekli"
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Ayarlar",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Hesap, API anahtarı ve görünüm tercihleri",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            SettingsCard(title = "Ekşi Sözlük Hesabı") {
                Text(
                    text = "Durum: $accountStatus",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onOpenLogin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isUserLoggedIn) "Hesap Değiştir" else "Ekşi Sözlük'e Giriş Yap",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                if (isUserLoggedIn) {
                    OutlinedButton(
                        onClick = onLogout,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Çıkış Yap",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }

            SettingsCard(title = "Yapay Zeka (Gemini API)") {
                Text(
                    text = if (hasSavedApiKey) {
                        "Durum: API anahtarı kayıtlı"
                    } else {
                        "Durum: API anahtarı kayıtlı değil"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { value ->
                        apiKeyInput = value
                        apiKeyMessage = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            text = "Gemini API Anahtarı",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    placeholder = {
                        Text(
                            text = "API anahtarınızı girin",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (apiKeyVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    trailingIcon = {
                        IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                            Text(
                                text = if (apiKeyVisible) "Gizle" else "Göster",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                )
                apiKeyMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            val apiKey = apiKeyInput.trim()
                            if (apiKey.isNotBlank()) {
                                onSaveApiKey(apiKey)
                                apiKeyMessage = "API anahtarı kaydedildi."
                            }
                        },
                        enabled = apiKeyInput.isNotBlank()
                    ) {
                        Text(
                            text = "API Anahtarını Kaydet",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    if (hasSavedApiKey) {
                        TextButton(
                            onClick = {
                                onClearApiKey()
                                apiKeyInput = ""
                                apiKeyMessage = "API anahtarı silindi."
                            }
                        ) {
                            Text(
                                text = "Sil",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }

            SettingsCard(title = "Görünüm") {
                SettingsActionRow(
                    title = "Aktif tema",
                    value = currentThemeTitle,
                    actionLabel = "Değiştir",
                    onClick = onOpenThemeSelector
                )
            }

            SettingsCard(title = "Bağlantı & Cloudflare") {
                SettingsActionRow(
                    title = "Doğrulama durumu",
                    value = cloudflareStatus,
                    actionLabel = "Yönet",
                    onClick = onOpenCloudflare
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            content()
        }
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    value: String,
    actionLabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Text(
            text = actionLabel,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
    }
}
