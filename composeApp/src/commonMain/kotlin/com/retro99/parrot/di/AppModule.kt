package com.retro99.parrot.di

import com.retro99.analytics.implementation.di.AnalyticsModule
import com.retro99.auth.data.di.AuthDataModule
import com.retro99.auth.domain.di.AuthDomainModule
import com.retro99.base.buildconfig.di.BuildConfigModule
import com.retro99.base.ui.di.BaseUiModule
import com.retro99.books.data.di.BooksDataModule
import com.retro99.books.domain.di.BooksDomainModule
import com.retro99.books.ui.di.BooksUiModule
import com.retro99.cloud.implementation.di.CloudModule
import com.retro99.cloudaccount.data.di.CloudAccountDataModule
import com.retro99.cloudaccount.domain.di.CloudAccountDomainModule
import com.retro99.cloudaccount.ui.di.CloudAccountUiModule
import com.retro99.database.implementation.di.DatabaseModule
import com.retro99.home.data.di.HomeDataModule
import com.retro99.home.ui.di.HomeUiModule
import com.retro99.login.data.di.LoginDataModule
import com.retro99.login.domain.di.LoginDomainModule
import com.retro99.login.ui.di.LoginUiModule
import com.retro99.network.implementation.di.NetworkingModule
import com.retro99.preferences.implementation.di.PreferencesModule
import com.retro99.reader.data.di.ReaderDataModule
import com.retro99.reader.domain.di.ReaderDomainModule
import com.retro99.reader.ui.di.ReaderUiModule
import com.retro99.server.implementation.di.ServerModule
import com.retro99.server.audiobookshelf.di.AudiobookshelfModule
import com.retro99.server.local.di.LocalServerModule
import com.retro99.server.parrotcloud.di.ParrotCloudModule
import com.retro99.server.storyteller.di.StorytellerModule
import com.retro99.settings.data.di.SettingsDataModule
import com.retro99.settings.domain.di.SettingsDomainModule
import com.retro99.settings.ui.di.SettingsUiModule
import com.retro99.statistics.data.di.StatisticsDataModule
import com.retro99.statistics.domain.di.StatisticsDomainModule
import com.retro99.statistics.ui.di.StatisticsUiModule
import com.retro99.sync.domain.di.SyncDomainModule
import com.retro99.user.implementation.di.UserModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Configuration
import org.koin.core.annotation.Module

@Module(
    includes = [
        // Core infrastructure modules (order matters - dependencies first)
        AnalyticsModule::class,
        PreferencesModule::class,
        UserModule::class,  // Must be before modules that depend on UserRegistry
        CloudModule::class,
        DatabaseModule::class,
        NetworkingModule::class,
        ServerModule::class,
        // Base modules
        BaseUiModule::class,
        BuildConfigModule::class,
        // Server implementations
        StorytellerModule::class,
        AudiobookshelfModule::class,
        LocalServerModule::class,
        ParrotCloudModule::class,
        // Feature modules
        AuthDomainModule::class,
        AuthDataModule::class,
        CloudAccountDomainModule::class,
        CloudAccountDataModule::class,
        CloudAccountUiModule::class,
        SyncDomainModule::class,
        LoginUiModule::class,
        LoginDomainModule::class,
        LoginDataModule::class,
        HomeUiModule::class,
        HomeDataModule::class,
        BooksDomainModule::class,
        BooksDataModule::class,
        BooksUiModule::class,
        ReaderDomainModule::class,
        ReaderDataModule::class,
        ReaderUiModule::class,
        SettingsDomainModule::class,
        SettingsDataModule::class,
        SettingsUiModule::class,
        StatisticsDomainModule::class,
        StatisticsDataModule::class,
        StatisticsUiModule::class,
    ],
)
@Configuration
@ComponentScan("com.retro99.parrot")
class AppModule
