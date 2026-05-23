package dev.lyo.hortay.data

/**
 * How the merged feed is ordered. `Newest` is the canonical Twitter / Telegram
 * top-down chronology — newest posts at the top, scroll DOWN for older.
 * `OldestUnreadFirst` is the reverse-feed / chat-app idiom — strict ascending
 * by date, so OLDEST posts on top and NEWEST at the bottom; scrolling DOWN
 * advances forward in time.
 */
enum class FeedOrder { Newest, OldestUnreadFirst }
