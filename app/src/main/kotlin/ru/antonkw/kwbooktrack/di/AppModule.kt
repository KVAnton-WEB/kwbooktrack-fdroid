package ru.antonkw.kwbooktrack.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import ru.antonkw.kwbooktrack.data.exporter.AppVersionProvider
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppVersionProvider(@ApplicationContext context: Context): AppVersionProvider =
        object : AppVersionProvider {
            override val appVersion: String =
                try {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0"
                } catch (_: Exception) {
                    "0.1.0"
                }
        }
}
