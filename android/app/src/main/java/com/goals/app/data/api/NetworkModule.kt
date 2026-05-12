package com.goals.app.data.api

import com.goals.app.widget.WidgetCache
import com.goals.app.widget.WidgetSessionStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

val DEFAULT_SERVER_URL: String get() = com.goals.app.BuildConfig.SERVER_URL

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    /** Synchronous accessor for legacy call sites (e.g. SseClient). */
    fun appSessionId(store: WidgetSessionStore): String = store.appSessionIdNow()

    @Provides
    @Singleton
    fun provideOkHttpClient(sessions: WidgetSessionStore, cache: WidgetCache): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .addHeader("X-Session-ID", sessions.appSessionIdNow())
                val lastSeq = cache.snapshot().lastSeq
                if (lastSeq > 0L) {
                    builder.addHeader("X-Sequence", lastSeq.toString())
                }
                chain.proceed(builder.build())
            }
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideGoalsApi(okHttpClient: OkHttpClient): GoalsApi {
        return Retrofit.Builder()
            .baseUrl(DEFAULT_SERVER_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GoalsApi::class.java)
    }
}
