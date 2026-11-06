package com.example.gridbot

import java.math.BigDecimal
import java.math.RoundingMode

interface Strategy {
    val realizedPnl: Double
    val stopped: Boolean
    var buysPaused: Boolean
    fun openLots(): Int
    fun unrealized(price: Double): Double
    fun onPrice(p: Double)
}

object Indicators {
    /** EMA seeded with the SMA of the first [n] values. Needs values.size >= n. */
    fun ema(values: List<Double>, n: Int): Double {
        val k = 2.0 / (n + 1)
        var e = values.take(n).average()
        for (i in n until values.size) e = values[i] * k + e * (1 - k)
        return e
    }

    /** Wilder RSI. Needs values.size > n. */
    fun rsi(values: List<Double>, n: Int): Double {
        var gain = 0.0; var loss = 0.0
        for (i in 1..n) {
            val d = values[i] - values[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        gain /= n; loss /= n
        for (i in n + 1 until values.size) {
            val d = values[i] - values[i - 1]
            gain = (gain * (n - 1) + maxOf(d, 0.0)) / n
            loss = (loss * (n - 1) + maxOf(-d, 0.0)) / n
        }
        return if (loss == 0.0) 100.0 else 100.0 - 100.0 / (1 + gain / loss)
    }
}

data class TrendConfig(
    val symbol: String,
    val quote: Double,
    val slPct: Double,   // stop-loss % below entry (0 = off)
    val tpPct: Double,   // take-profit % above entry (0 = off)
    val paper: Boolean,
    val fast: Int = 9,
    val slow: Int = 21,
    val rsiLen: Int = 14,
    val rsiMaxEntry: Double = 70.0,
    val rsiExit: Double = 80.0,
)

/**
 * Long-only trend follower: buy when fast EMA crosses above slow EMA (and RSI is not overbought),
 * sell on cross back down, RSI spike, stop-loss or take-profit. One position at a time.
 * Call [onCandle] once per newly closed candle and [onPrice] on every price tick.
 */
class TrendEngine(
    private val cfg: TrendConfig,
    private val api: BinanceApi?,
    private val log: (String) -> Unit,
    private val fee: Double = 0.001,
) : Strategy {
    private var qty: BigDecimal? = null
    private var entry = 0.0
    private var cost = 0.0
    private val step: BigDecimal =
        if (!cfg.paper && api != null) api.stepSize(cfg.symbol) else BigDecimal("0.00001")

    override var realizedPnl = 0.0; private set
    override var stopped = false; private set
    override var buysPaused = false
    var trades = 0; private set

    override fun openLots() = if (qty != null) 1 else 0
    override fun unrealized(price: Double): Double =
        qty?.let { it.toDouble() * price * (1 - fee) - cost } ?: 0.0

    override fun onPrice(p: Double) {
        if (qty == null) return
        if (cfg.slPct > 0 && p <= entry * (1 - cfg.slPct / 100)) sell(p, "stop-loss")
        else if (cfg.tpPct > 0 && p >= entry * (1 + cfg.tpPct / 100)) sell(p, "take-profit")
    }

    fun onCandle(closes: List<Double>, price: Double) {
        if (closes.size < cfg.slow + cfg.rsiLen + 2) return
        val prev = closes.dropLast(1)
        val f = Indicators.ema(closes, cfg.fast); val s = Indicators.ema(closes, cfg.slow)
        val fp = Indicators.ema(prev, cfg.fast); val sp = Indicators.ema(prev, cfg.slow)
        val r = Indicators.rsi(closes, cfg.rsiLen)
        val crossUp = fp <= sp && f > s
        val crossDown = fp >= sp && f < s
        if (qty == null) {
            if (crossUp && r < cfg.rsiMaxEntry && !buysPaused) buy(price, r)
        } else if (crossDown || r > cfg.rsiExit) {
            sell(price, if (crossDown) "cross down" else "RSI ${"%.0f".format(r)}")
        }
    }

    private fun buy(p: Double, rsi: Double) {
        try {
            val q = if (cfg.paper) BigDecimal(cfg.quote * (1 - fee) / p).setScale(6, RoundingMode.DOWN)
            else api!!.marketBuy(cfg.symbol, cfg.quote)
            qty = q; entry = p; cost = cfg.quote; trades++
            log("BUY ${q.toPlainString()} @ ~$p (EMA cross up, RSI ${"%.0f".format(rsi)})")
        } catch (e: Exception) { log("Buy failed: ${e.message}") }
    }

    private fun sell(p: Double, why: String) {
        val q = qty ?: return
        try {
            val sq = q.divide(step, 0, RoundingMode.DOWN).multiply(step)
            val proceeds = if (cfg.paper) sq.toDouble() * p * (1 - fee)
            else api!!.marketSell(cfg.symbol, sq).toDouble()
            val pnl = proceeds - cost
            realizedPnl += pnl; trades++
            qty = null
            log("SELL ${sq.toPlainString()} @ ~$p ($why) pnl=${"%.2f".format(pnl)}")
        } catch (e: Exception) { log("Sell failed: ${e.message}") }
    }
}

data class DcaConfig(
    val symbol: String,
    val quote: Double,       // USDT per buy
    val everyMin: Int,       // minutes between buys
    val maxSpend: Double,    // stop buying while open cost >= this (0 = off)
    val tpPct: Double,       // sell everything when profit on open cost >= this % (0 = never sell)
    val paper: Boolean,
)

/**
 * Dollar-cost averaging: buy a fixed USDT amount every N minutes, no timing.
 * Optionally sells everything at a take-profit % and then starts a new cycle.
 */
class DcaEngine(
    private val cfg: DcaConfig,
    private val api: BinanceApi?,
    private val log: (String) -> Unit,
    private val fee: Double = 0.001,
) : Strategy {
    private var qty = BigDecimal.ZERO
    private var cost = 0.0
    private var nextBuy = 0L
    private val step: BigDecimal =
        if (!cfg.paper && api != null) api.stepSize(cfg.symbol) else BigDecimal("0.00001")

    override var realizedPnl = 0.0; private set
    override val stopped = false
    override var buysPaused = false
    var totalSpent = 0.0; private set
    var trades = 0; private set

    override fun openLots() = if (qty.signum() > 0) 1 else 0
    override fun unrealized(price: Double): Double =
        if (qty.signum() > 0) qty.toDouble() * price * (1 - fee) - cost else 0.0

    override fun onPrice(p: Double) = onTick(p, System.currentTimeMillis())

    fun onTick(p: Double, now: Long) {
        if (qty.signum() > 0 && cfg.tpPct > 0 && qty.toDouble() * p * (1 - fee) >= cost * (1 + cfg.tpPct / 100)) sellAll(p)
        if (buysPaused || now < nextBuy) return
        if (cfg.maxSpend > 0 && cost + cfg.quote > cfg.maxSpend) return
        nextBuy = now + (if (buy(p)) cfg.everyMin * 60_000L else 60_000L)
    }

    private fun buy(p: Double): Boolean = try {
        val q = if (cfg.paper) BigDecimal(cfg.quote * (1 - fee) / p).setScale(6, RoundingMode.DOWN)
        else api!!.marketBuy(cfg.symbol, cfg.quote)
        qty += q; cost += cfg.quote; totalSpent += cfg.quote; trades++
        log("BUY ${q.toPlainString()} @ ~$p (DCA, open cost ${"%.2f".format(cost)})")
        true
    } catch (e: Exception) { log("Buy failed: ${e.message}"); false }

    private fun sellAll(p: Double) {
        try {
            val sq = qty.divide(step, 0, RoundingMode.DOWN).multiply(step)
            val proceeds = if (cfg.paper) sq.toDouble() * p * (1 - fee)
            else api!!.marketSell(cfg.symbol, sq).toDouble()
            val pnl = proceeds - cost
            realizedPnl += pnl; trades++
            log("SELL ${sq.toPlainString()} @ ~$p (take-profit) pnl=${"%.2f".format(pnl)}")
            qty = BigDecimal.ZERO; cost = 0.0
        } catch (e: Exception) { log("Sell failed: ${e.message}") }
    }
}
