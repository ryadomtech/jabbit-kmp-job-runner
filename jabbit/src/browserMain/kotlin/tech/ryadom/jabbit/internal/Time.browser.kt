@file:OptIn(ExperimentalWasmJsInterop::class)

package tech.ryadom.jabbit.internal

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.js

private fun nowMillis(): Double = js("Date.now()")

internal actual fun currentTimeMillis(): Long = nowMillis().toLong()
