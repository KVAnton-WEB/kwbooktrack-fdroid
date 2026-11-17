package ru.antonkw.kwbooktrack.ui.common

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Активность под контекстом Compose.
 *
 * Нужна, чтобы пересоздать activity при смене языка: локаль подменяется в
 * `attachBaseContext`, а без пересоздания ресурсы останутся прежними.
 */
tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
