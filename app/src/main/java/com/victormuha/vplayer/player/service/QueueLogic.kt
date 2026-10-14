package com.victormuha.vplayer.player.service

import java.io.File

sealed class TrackSource {
    data class Local(val file: File) : TrackSource()
    object Stream : TrackSource()
    object Unavailable : TrackSource()
}

internal object QueueLogic {
    /** Local file always wins (no network, no token); otherwise stream only when online. */
    fun chooseSource(localFile: File?, offline: Boolean): TrackSource = when {
        localFile != null -> TrackSource.Local(localFile)
        offline -> TrackSource.Unavailable
        else -> TrackSource.Stream
    }

    /** Next/previous position in the play order, or null at the boundary without wrap. */
    fun step(pos: Int, size: Int, forward: Boolean, wrap: Boolean): Int? {
        if (size <= 0) return null
        val next = if (forward) pos + 1 else pos - 1
        return when {
            next in 0 until size -> next
            !wrap -> null
            forward -> 0
            else -> size - 1
        }
    }

    /** First position starting at [start] (inclusive) whose track is playable; visits each item once. */
    fun findPlayable(size: Int, start: Int, forward: Boolean, wrap: Boolean, playable: (Int) -> Boolean): Int? {
        if (size <= 0 || start !in 0 until size) return null
        var pos = start
        repeat(size) {
            if (playable(pos)) return pos
            pos = step(pos, size, forward, wrap) ?: return null
        }
        return null
    }

    fun order(size: Int, shuffle: Boolean, current: Int, random: kotlin.random.Random = kotlin.random.Random): List<Int> {
        if (!shuffle || size <= 1) return (0 until size).toList()
        val rest = (0 until size).filter { it != current }.shuffled(random)
        return if (current in 0 until size) listOf(current) + rest else rest
    }
}
