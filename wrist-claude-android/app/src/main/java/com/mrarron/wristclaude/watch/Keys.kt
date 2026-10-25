package com.mrarron.wristclaude.watch

import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import java.util.UUID

/** AppMessage keys (numbers assigned by the watch build, see docs/companion.md). */
object Keys {
    val WATCH_UUID: UUID = UUID.fromString("12e8fcb0-c07f-4215-994f-c5e405f093fc")

    const val ASK_TEXT = 10000u
    const val ASK_KIND = 10001u
    const val QUICK_INDEX = 10002u
    const val STATUS = 10003u
    const val CHUNK = 10004u
    const val CHUNK_FINAL = 10005u
    const val ERROR = 10006u
    const val REMINDER_TIME = 10007u
    const val REMINDER_TEXT = 10008u
    const val QUICK_NAMES = 10009u
    const val READY = 10010u
    /** Both ways: the sender's version in the versionCode scheme (2.3.0 = 230). Watch: with READY; phone: in the hello. */
    const val APP_VERSION = 10011u
    const val COMPANION = 10012u
    const val ROUTE = 10013u
    const val SPEAK = 10014u
    const val CONFIRM_ID = 10015u
    const val CONFIRM_TEXT = 10016u
    const val CONFIRM_RESULT = 10017u
}

/** Builds a dictionary: ints become signed Int32 (the watch reads ->value->int32), strings become Text. */
fun dict(vararg pairs: Pair<UInt, Any>): PebbleDictionary = pairs.associate { (k, v) ->
    k to when (v) {
        is Int -> PebbleDictionaryItem.Int32(v)
        is Long -> PebbleDictionaryItem.Int32(v.toInt())
        is Boolean -> PebbleDictionaryItem.Int32(if (v) 1 else 0)
        is String -> PebbleDictionaryItem.Text(v)
        else -> throw IllegalArgumentException("Unsupported value type")
    }
}

/** Reads an integer item of any width/signedness (the receiver gets Int32 or UInt32). */
fun PebbleDictionary.int(key: UInt): Int? = when (val v = this[key]) {
    is PebbleDictionaryItem.Int32 -> v.value
    is PebbleDictionaryItem.UInt32 -> v.value.toInt()
    is PebbleDictionaryItem.Int16 -> v.value.toInt()
    is PebbleDictionaryItem.UInt16 -> v.value.toInt()
    is PebbleDictionaryItem.Int8 -> v.value.toInt()
    is PebbleDictionaryItem.UInt8 -> v.value.toInt()
    else -> null
}

fun PebbleDictionary.text(key: UInt): String? = when (val v = this[key]) {
    is PebbleDictionaryItem.Text -> v.value
    is PebbleDictionaryItem.Bytes -> v.value.toString(Charsets.UTF_8).trimEnd('\u0000')
    else -> null
}
