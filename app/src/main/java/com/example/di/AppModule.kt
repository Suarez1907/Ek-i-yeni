package com.example.di

import android.content.Context
import androidx.room.Room
import com.example.BuildConfig
import com.example.ai.GeminiHelper
import com.example.data.local.AppDatabase
import com.example.data.local.EksinDao
import com.example.data.local.ReadStateDao
import com.example.data.remote.EksiParser
import com.example.data.repository.EksiRepository
import com.example.network.CloudflareChallengeManager
import com.example.network.CloudflareInterceptor
import com.example.network.UserAgentInterceptor
import com.example.network.UserAgentProvider
import com.example.network.WebViewCookieJar
import com.example.network.WebViewFetchBridge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Volatile
    private var singletonContainer: EksinServiceContainer? = null

    fun getContainer(context: Context): EksinServiceContainer {
        return singletonContainer ?: synchronized(this) {
            singletonContainer ?: createContainer(context.applicationContext).also {
                singletonContainer = it
            }
        }
    }

    private fun createContainer(appContext: Context): EksinServiceContainer {
        val cookieJar = WebViewCookieJar()
        val userAgentProvider = UserAgentProvider(appContext)
        val cloudflareManager = CloudflareChallengeManager()
        val webViewFetchBridge = WebViewFetchBridge(
            appContext = appContext,
            userAgentProvider = userAgentProvider,
            cookieJar = cookieJar,
            cloudflareManager = cloudflareManager
        )
        val database = provideAppDatabase(appContext)
        val readStateDao = provideReadStateDao(database)
        val eksinDao = provideEksinDao(database)
        val parser = EksiParser()
        val repository = EksiRepository(
            webViewFetchBridge = webViewFetchBridge,
            eksiParser = parser,
            readStateDao = readStateDao,
            eksinDao = eksinDao
        )
        val geminiHelper = GeminiHelper()

        return EksinServiceContainer(
            repository = repository,
            cookieJar = cookieJar,
            userAgentProvider = userAgentProvider,
            webViewFetchBridge = webViewFetchBridge,
            cloudflareManager = cloudflareManager,
            geminiHelper = geminiHelper
        )
    }

    @Provides
    @Singleton
    fun provideWebViewFetchBridge(
        @ApplicationContext context: Context,
        userAgentProvider: UserAgentProvider,
        cookieJar: WebViewCookieJar,
        cloudflareManager: CloudflareChallengeManager
    ): WebViewFetchBridge {
        return WebViewFetchBridge(
            appContext = context,
            userAgentProvider = userAgentProvider,
            cookieJar = cookieJar,
            cloudflareManager = cloudflareManager
        )
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(
        cookieJar: WebViewCookieJar,
        userAgentInterceptor: UserAgentInterceptor,
        cloudflareInterceptor: CloudflareInterceptor
    ): OkHttpClient {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BASIC
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

        return OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(cloudflareInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "eksin_oled.db"
        ).fallbackToDestructiveMigration(dropAllTables = true).build()
    }

    @Provides
    @Singleton
    fun provideReadStateDao(database: AppDatabase): ReadStateDao = database.readStateDao()

    @Provides
    @Singleton
    fun provideEksinDao(database: AppDatabase): EksinDao = database.eksinDao()
}

data class EksinServiceContainer(
    val repository: EksiRepository,
    val cookieJar: WebViewCookieJar,
    val userAgentProvider: UserAgentProvider,
    val webViewFetchBridge: WebViewFetchBridge,
    val cloudflareManager: CloudflareChallengeManager,
    val geminiHelper: GeminiHelper
)
