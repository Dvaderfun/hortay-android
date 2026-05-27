package dev.lyo.hortay.di

import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.ChatId
import dev.lyo.hortay.data.FeedSource
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.MessageId
import dev.lyo.hortay.ui.timeline.ChannelViewModel
import dev.lyo.hortay.ui.timeline.TimelineViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * KMP ViewModel registrations. Both ViewModels here are
 * `androidx.lifecycle.ViewModel` (KMP since lifecycle 2.8). Compose-side
 * call-sites use `koinViewModel { parametersOf(...) }` from
 * `koin-compose-viewmodel`; the per-`NavTarget` `ViewModelStoreOwner` from
 * nav3's `rememberViewModelStoreNavEntryDecorator` carries through unchanged
 * because `koinViewModel` reads `LocalViewModelStoreOwner`.
 *
 * `TimelineViewModel` takes a `FeedSource` runtime parameter — the same
 * screen is mounted with `PostsRepository` from auth mode and `WebFeedSource`
 * from guest mode. The call-site key `feed::class.simpleName` keeps the two
 * instances independent inside the same `ViewModelStore`.
 *
 * `ChannelViewModel` takes `chatId` + `scrollToMessageId` runtime params;
 * the key `channel:$chatId` keeps per-channel instances independent within
 * a nav3 entry-decorator scope.
 */
val viewModelModule = module {
    viewModel { (feed: FeedSource) ->
        TimelineViewModel(repo = feed, bookmarks = get<BookmarkStore>())
    }
    viewModel { (chatId: ChatId, scrollToMessageId: MessageId?) ->
        ChannelViewModel(
            backend = get<HortayBackend>(),
            bookmarks = get<BookmarkStore>(),
            chatId = chatId,
            scrollToMessageId = scrollToMessageId,
        )
    }
}
