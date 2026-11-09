package com.example.gridbot

import kotlin.math.max

/**
 * Replays both entry styles on real 1-hour history, split into an earlier and a later half.
 * Uses the same stop, target and fee rules as the live bot. It uses 1-hour candles, so the
 * 90-minute live time limit becomes a 6-hour limit here.
 */
object Replay {
    private const val FEE_ROUND_TRIP = 0.2
    private const val MAX_BARS = 6
    private val SKIP = setOf("USDCUSDT", "FDUSDUSDT", "TUSDUSDT", "USDPUSDT", "DAIUSDT", "EURUSDT")

    data class Stats(val trades: Int, val wins: Int, val totalPct: Double)

    /** Momentum rule adapted to 1h candles: volume spike of 3x on a green candle that is up over 3 hours. */
    private fun momentumBuy(w: List<Candle>): Boolean {
        if (w.size < 25) return false
        val avg = w.dropLast(1).takeLast(20).map { it.v }.average()
        if (avg <= 0) return false
        val last = w.last()
        return last.v / avg >= 3.0 && last.c > last.o && last.c / w[w.size - 4].c - 1 > 0.003
    }

    fun simulate(cs: List<Candle>, style: String, from: Int, to: Int): Stats {
        var trades = 0
        var wins = 0
        var total = 0.0
        var i = max(from, Regimes.MIN_CANDLES)
        while (i < to - 1) {
            val w = cs.subList(0, i + 1)
            val go = if (style == "dip") Signals.dipBuy(w) else momentumBuy(w)
            if (!go) { i++; continue }
            val entry = cs[i].c
            val stopPct = Regimes.atrPct(w).coerceIn(0.8, 3.0)
            val tpPct = (stopPct * 2).coerceIn(1.0, 6.0)
            val stop = entry * (1 - stopPct / 100)
            val tp = entry * (1 + tpPct / 100)
            var exitIdx = -1
            var exitPrice = 0.0
            for (j in i + 1..minOf(i + MAX_BARS, to - 1)) {
                val b = cs[j]
                if (b.l <= stop) { exitIdx = j; exitPrice = stop; break }   // stop first if both are hit
                if (b.h >= tp) { exitIdx = j; exitPrice = tp; break }
            }
            if (exitIdx < 0) {
                exitIdx = minOf(i + MAX_BARS, to - 1)
                exitPrice = cs[exitIdx].c
            }
            val net = (exitPrice / entry - 1) * 100 - FEE_ROUND_TRIP
            trades++
            if (net > 0) wins++
            total += net
            i = exitIdx + 1
        }
        return Stats(trades, wins, total)
    }

    /** Runs both styles on each coin. Returns a text summary. */
    fun compareCandles(data: Map<String, List<Candle>>): String {
        val sb = StringBuilder()
        sb.appendLine("Replay on ${data.size} coins · 1h candles · stop = 1h volatility (0.8–3%), target = 2× stop, max 6h hold, 0.2% fees")
        sb.appendLine("Each half is about 3 weeks. History only, not a forecast.")
        for (style in listOf("momentum", "dip")) {
            for (half in listOf("earlier", "later")) {
                var t = 0
                var w = 0
                var pct = 0.0
                for ((_, cs) in data) {
                    val mid = cs.size / 2
                    val range = if (half == "earlier") Pair(0, mid) else Pair(mid, cs.size)
                    val s = simulate(cs, style, range.first, range.second)
                    t += s.trades
                    w += s.wins
                    pct += s.totalPct
                }
                val wr = if (t > 0) w * 100.0 / t else 0.0
                val per = if (t > 0) pct / t else 0.0
                sb.appendLine("${style.uppercase()} · $half half: $t trades · win ${"%.0f".format(wr)}% · " +
                    "total ${"%+.2f".format(pct)}% · per trade ${"%+.2f".format(per)}%")
            }
        }
        sb.appendLine()
        sb.append("A style is only a candidate if it wins in both halves and across most coins.")
        return sb.toString()
    }

    /** Downloads 1h history for the 10 most-traded USDT pairs and compares both styles. */
    fun compare(api: BinanceApi): String {
        val coins = api.tickers24h()
            .filter { it.symbol.endsWith("USDT") && it.symbol !in SKIP &&
                !it.symbol.endsWith("UPUSDT") && !it.symbol.endsWith("DOWNUSDT") }
            .sortedByDescending { it.quoteVol }
            .take(10)
            .map { it.symbol }
        val data = coins.associateWith { api.klines(it, "1h", 1000) }.filterValues { it.size >= 200 }
        if (data.isEmpty()) return "No history downloaded. Check the connection and try again."
        return compareCandles(data)
    }
}
