package dev.lyo.hortay.di

import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChatFoldersRepository
import dev.lyo.hortay.data.StartupCoordinator
import dev.lyo.hortay.data.CommentsRepository
import dev.lyo.hortay.data.CountryRepository
import dev.lyo.hortay.data.CustomEmojiRepository
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.IosTdAuthStateMachine
import dev.lyo.hortay.data.IosTdSender
import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.MediaCache
import dev.lyo.hortay.data.MessageMapper
import dev.lyo.hortay.data.NoopTimelineSnapshotStore
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StatsRepository
import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.TimelineSnapshotStore
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.report.ReportDialogState
import dev.lyo.hortay.data.report.ReportExplainerStore
import dev.lyo.hortay.data.report.ReportLogStore
import dev.lyo.hortay.data.report.ReportRepository
import dev.lyo.hortay.tdlib.TypedTdClient
import dev.lyo.hortay.ui.media.VideoPlayerPool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.module.Module
import org.koin.dsl.module

val tdlibIosModule: Module = module {
    // ---- TDLib core -------------------------------------------------------
    single(createdAtStart = true) { TypedTdClient(scope = get<CoroutineScope>()) }
    single<TdSender> { IosTdSender(get<TypedTdClient>(), get<CoroutineScope>()) }

    single(createdAtStart = true) {
        IosTdAuthStateMachine(
            client = get(),
            scope = get<CoroutineScope>(),
            res = get<StringResolver>(),
        )
    }

    // ---- Media + platform singletons ----------------------------------------
    single { MediaCache(get<TdSender>(), get<CoroutineScope>()) }
    single { CustomEmojiRepository() }
    single { VideoPlayerPool() }
    single<TimelineSnapshotStore> { NoopTimelineSnapshotStore }

    // ---- Shared repos (lifted from androidMain to commonMain) -------------
    single { MessageMapper(get<TdSender>(), get<StringResolver>()) }

    single {
        PostsRepository(
            td = get<TdSender>(),
            mapper = get(),
            scope = get<CoroutineScope>(),
            userMessages = get<UserMessageBus>(),
            connection = get<IosTdAuthStateMachine>().connection,
            snapshotStore = get<TimelineSnapshotStore>(),
            foreground = MutableStateFlow(true),
            res = get<StringResolver>(),
        )
    }

    single {
        StartupCoordinator(
            authStage = get<IosTdAuthStateMachine>().authStage,
            posts = get<PostsRepository>().posts,
            scope = get<CoroutineScope>(),
        )
    }

    single { CommentsRepository(get<TdSender>(), get(), get<CoroutineScope>(), get<StringResolver>()) }
    single { ChannelActionsRepository(get<TdSender>(), get<UserMessageBus>(), get<IosTdAuthStateMachine>().connection, get<StringResolver>()) }
    single { ChatFoldersRepository(get<TdSender>(), get<CoroutineScope>()) }
    single { CountryRepository(get<TdSender>(), get<StringResolver>(), get<CoroutineScope>()) }
    single { StatsRepository(get<TdSender>()) }
    single { ReportRepository(get<TdSender>(), get<StringResolver>(), get<ReportLogStore>()) }

    // ---- HortayBackend (iOS — delegates to real shared repos) -------------
    single {
        HortayBackend(
            auth = get<IosTdAuthStateMachine>(),
            countriesRepo = get(),
            channelActions = get(),
            postsRepo = get(),
            commentsRepo = get(),
            folders = get<ChatFoldersRepository>(),
            reportRepo = get(),
            reportDialogs = get<ReportDialogState>(),
            reportLogStore = get<ReportLogStore>(),
            reportExplainerStore = get<ReportExplainerStore>(),
            settingsStore = get<SettingsStore>(),
            stats = get<StatsRepository>(),
            backendScope = get<CoroutineScope>(),
        )
    }
}
