package com.adachi.lockdown.ui

/** "9:30" -> 570 ; returns null on garbage. */
fun parseHm(text: String): Int? {
    val parts = text.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/** 570 -> "9:30" */
fun formatMin(min: Int): String = "%d:%02d".format(min / 60, min % 60)

fun formatCountdown(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
