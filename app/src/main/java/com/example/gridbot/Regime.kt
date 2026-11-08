package com.example.gridbot

import kotlin.math.abs

enum class Regime { UP, RANGE, DOWN, VOLATILE, UNKNOWN }

/**
 * Classifies the market from hourly candles using only past data.
 * The label only decides whether an entry is allowed and how big it is.
 * It never predicts price and never opens a short position.
 */
object Regimes {
    const val MIN_CANDLES = 80

    private fun ema(values: List<Double>, n: Int): Double {
        val k = 2.0 / (n + 1)
        var e = values.take(n).average()
        for (i in n until values.size) e = values[i] * k + e * (1 - k)
        return e
    }

    /** Average true range over the last 24 candles, as a % of price. */
    fun atrPct(c: List<Candle>): Double {
        if (c.size < 2) return 0.0
        val w = c.takeLast(24)
        return w.zipWithNext { a, b -> maxOf(b.h - b.l, abs(b.h - a.c), abs(b.l - a.c)) }.average() / w.last().c * 100
    }

    fun of(c: List<Candle>): Regime {
        if (c.size < MIN_CANDLES) return Regime.UNKNOWN
        val closes = c.map { it.c }
        val last = closes.last()
        val e = ema(closes, 50)
        val ePrev = ema(closes.dropLast(6), 50)
        val slopePct = (e - ePrev) / ePrev * 100            // EMA change over the last 6 hours
        val window = c.takeLast(24)
        val atrPct = window.zipWithNext { a, b -> maxOf(b.h - b.l, abs(b.h - a.c), abs(b.l - a.c)) }
            .average() / window.last().c * 100              // average true range over 24 hours, in %
        return when {
            atrPct > 4.0 -> Regime.VOLATILE
            last > e && slopePct > 0.3 -> Regime.UP
            last < e && slopePct < -0.3 -> Regime.DOWN
            else -> Regime.RANGE
        }
    }
}
