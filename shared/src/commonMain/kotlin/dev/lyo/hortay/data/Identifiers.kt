package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable
import kotlin.jvm.JvmInline

@Immutable
@JvmInline
value class ChatId(val value: Long)

@Immutable
@JvmInline
value class MessageId(val value: Long)

@Immutable
@JvmInline
value class UserId(val value: Long)
