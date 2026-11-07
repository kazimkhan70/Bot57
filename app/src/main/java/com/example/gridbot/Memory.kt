package com.example.gridbot

import java.time.Instant
import java.time.ZoneOffset

/** Tags each closed trade with its most likely cause, so the log shows what to fix. */
object LossTagger {
    fun tag(t: Trade): String = when {
        t.pnlUsd >= 0 -> "win"
        t.reason.startsWith("stop-loss") && t.heldMin <= 10 -> "stopped out within 10 min: entry too late or stop too tight"
        t.reason.startsWith("stop-loss") -> "stop-loss hit later: trend turned against it"
        t.reason.startsWith("time stop") -> "faded before target: signal too weak"
        t.reason == "trailing stop" -> "gave back gains: trail too tight"
        t.slipPct > 0.2 -> "bad fill: slippage above 0.2%"
        else -> "other: ${t.reason}"
    }
}

/**
 * Remembers results by coin, UTC entry hour and BTC regime (down / flat / up).
 * It only blocks a group after enough trades, so it doesn't react to noise.
 * It stores plain numbers and never infers a cause.
 */
class Memory(private val store: Store) {
    companion object {
        const val COIN_MIN = 5
        const val COIN_MAX_WIN = 0.2      // block a coin won by under 20% after 5+ trades
        const val GROUP_MIN = 15
        const val GROUP_MAX_AVG = -0.3    // block an hour or regime averaging under -0.3% after 15+ trades
    }

    init {
        // First run with an existing trade history: learn from it (regime unknown, so "flat").
        if (!store.exists("memory.properties")) store.trades().forEach { record(it, it.ts - it.heldMin * 60_000L, "flat") }
    }

    private fun bump(p: java.util.Properties, key: String, pct: Double) {
        p.setProperty("$key.n", (p.getProperty("$key.n", "0").toInt() + 1).toString())
        p.setProperty("$key.w", (p.getProperty("$key.w", "0").toInt() + if (pct > 0) 1 else 0).toString())
        p.setProperty("$key.s", (p.getProperty("$key.s", "0").toDouble() + pct).toString())
    }

    fun record(t: Trade, entryMs: Long, regime: String) {
        val hour = "%02d".format(Instant.ofEpochMilli(entryMs).atZone(ZoneOffset.UTC).hour)
        val p = store.props("memory.properties")
        bump(p, "coin:${t.symbol}", t.pnlPct)
        bump(p, "hour:$hour", t.pnlPct)
        bump(p, "regime:$regime", t.pnlPct)
        store.saveProps("memory.properties", p)
    }

    private fun stat(p: java.util.Properties, key: String): Triple<Int, Int, Double>? {
        val n = p.getProperty("$key.n")?.toIntOrNull() ?: return null
        return Triple(n, p.getProperty("$key.w", "0").toInt(), p.getProperty("$key.s", "0").toDouble())
    }

    /** Why this entry should wait, or null if memory has no objection. */
    fun block(symbol: String, regime: String, now: Long): String? {
        val p = store.props("memory.properties")
        val hour = "%02d".format(Instant.ofEpochMilli(now).atZone(ZoneOffset.UTC).hour)
        stat(p, "coin:$symbol")?.let { (n, w, _) ->
            if (n >= COIN_MIN && w.toDouble() / n < COIN_MAX_WIN) return "memory: $symbol won only $w of $n past trades"
        }
        stat(p, "hour:$hour")?.let { (n, _, s) ->
            if (n >= GROUP_MIN && s / n < GROUP_MAX_AVG) return "memory: ${hour}:00 UTC averaged ${"%.2f".format(s / n)}% over $n trades"
        }
        stat(p, "regime:$regime")?.let { (n, _, s) ->
            if (n >= GROUP_MIN && s / n < GROUP_MAX_AVG) return "memory: '$regime' BTC regime averaged ${"%.2f".format(s / n)}% over $n trades"
        }
        return null
    }

    fun summary(): String {
        val p = store.props("memory.properties")
        val names = p.stringPropertyNames().filter { it.endsWith(".n") }.map { it.removeSuffix(".n") }
        val trades = names.filter { it.startsWith("regime:") }.sumOf { p.getProperty("$it.n", "0").toInt() }
        val blocked = names.count { k ->
            val (n, w, s) = stat(p, k) ?: return@count false
            if (k.startsWith("coin:")) n >= COIN_MIN && w.toDouble() / n < COIN_MAX_WIN
            else n >= GROUP_MIN && s / n < GROUP_MAX_AVG
        }
        return "$trades trades remembered · $blocked groups currently blocked"
    }
}
