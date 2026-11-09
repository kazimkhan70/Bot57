package com.example.gridbot

import kotlin.math.sqrt

/** Indicators for the dip-buy style. Every input is a closed candle, so nothing uses future data. */
object Signals {
    private fun emaSeries(v: List<Double>, n: Int): List<Double> {
        if (v.size < n) return emptyList()
        val k = 2.0 / (n + 1)
        var e = v.take(n).average()
        val out = arrayListOf(e)
        for (i in n until v.size) { e = v[i] * k + e * (1 - k); out.add(e) }
        return out
    }

    /** MACD histogram on the last closed bar and the bar before it. */
    fun macdHist(closes: List<Double>): Pair<Double, Double> {
        val f = emaSeries(closes, 12)
        val s = emaSeries(closes, 26)
        val macd = s.indices.map { f[it + (f.size - s.size)] - s[it] }
        val sig = emaSeries(macd, 9)
        val hist = macd.takeLast(sig.size).zip(sig) { m, g -> m - g }
        return hist.last() to hist[hist.size - 2]
    }

    /** Bollinger %B over 20 bars: 0 = at the lower band, 1 = at the upper band. */
    fun percentB(closes: List<Double>, n: Int = 20): Double {
        val w = closes.takeLast(n)
        val mean = w.average()
        val sd = sqrt(w.sumOf { (it - mean) * (it - mean) } / w.size)
        val up = mean + 2 * sd
        val lo = mean - 2 * sd
        return if (up > lo) (closes.last() - lo) / (up - lo) else 0.5
    }

    /** Where the last price sits inside the last 24 hourly candles: 0 = day low, 1 = day high. */
    fun rangePos(c: List<Candle>): Double {
        val w = c.takeLast(24)
        val hi = w.maxOf { it.h }
        val lo = w.minOf { it.l }
        return if (hi > lo) (c.last().c - lo) / (hi - lo) else 0.5
    }

    /** Volume of the last 24 candles compared with the 24 before them. */
    fun volRatio(c: List<Candle>): Double {
        val last = c.takeLast(24).sumOf { it.v }
        val prev = c.dropLast(24).takeLast(24).sumOf { it.v }
        return if (prev > 0) last / prev else 1.0
    }

    /**
     * Dip-buy: RSI oversold, price near the lower Bollinger Band, MACD histogram turning up,
     * price in the lower part of the last 24 hours, and the 1h market not in a downtrend.
     */
    fun dipBuy(c: List<Candle>): Boolean {
        if (c.size < Regimes.MIN_CANDLES) return false
        val closes = c.map { it.c }
        val rsi = Indicators.rsi(closes, 14)
        val (hist, histPrev) = macdHist(closes)
        return rsi < 35 && percentB(closes) < 0.2 && hist > histPrev &&
            rangePos(c) < 0.4 && Regimes.of(c) != Regime.DOWN
    }
}
