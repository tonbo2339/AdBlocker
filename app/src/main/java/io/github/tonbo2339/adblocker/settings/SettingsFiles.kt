package io.github.tonbo2339.adblocker.settings

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 書き出すファイルの名前に付ける日付 (例: AdBlocker-settings-20261005.json)。 */
internal fun fileDate(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
