package com.goals.app.widget

import com.goals.app.data.api.DEFAULT_SERVER_URL
import com.goals.app.data.api.GoalsApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WidgetDi {

    @Provides
    @Singleton
    @Named("widget")
    fun provideWidgetOkHttpClient(
        sessions: WidgetSessionStore,
        cache: WidgetCache
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val builder = chain.request().newBuilder()
                    .addHeader("X-Session-ID", sessions.widgetSessionIdNow())
                // X-Sequence enables backend conflict detection on POST /api/logs/.
                // Only include if we have a known seq; otherwise let server skip the check.
                val lastSeq = cache.snapshot().lastSeq
                if (lastSeq > 0L) {
                    builder.addHeader("X-Sequence", lastSeq.toString())
                }
                chain.proceed(builder.build())
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    @Named("widget")
    fun provideWidgetGoalsApi(@Named("widget") client: OkHttpClient): GoalsApi {
        return Retrofit.Builder()
            .baseUrl(DEFAULT_SERVER_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GoalsApi::class.java)
    }
}
