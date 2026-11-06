package com.example.gridbot

import java.math.BigDecimal
import java.math.RoundingMode

data class GridConfig(
    val symbol: String,
    val lower: Double,
    val upper: Double,
    val levels: Int,
    val quotePerGrid: Double,
    val paper: Boolean,
    val stopLoss: Double,   // price below which bot sells everything and stops (0 = off)
)

/**
 * Long-only spot grid. Buys when price crosses DOWN through a grid line,
 * sells that lot when price reaches the next line up.
 */
class GridEngine(
    private val cfg: GridConfig,
    private val api: BinanceApi?,
    private val log: (String) -> Unit,
    private val fee: Double = 0.001,
) : Strategy {
    private val lines: List<Double> =
        (0 until cfg.levels).map { cfg.lower + (cfg.upper - cfg.lower) * it / (cfg.levels - 1) }
    private val held = HashMap<Int, BigDecimal>() // line index -> base qty
    private val cost = HashMap<Int, Double>()
    private var last: Double? = null
    private val step: BigDecimal =
        if (!cfg.paper && api != null) api.stepSize(cfg.symbol) else BigDecimal("0.00001")

    override var realizedPnl = 0.0; private set
    override var stopped = false; private set
    override var buysPaused = false
    var trades = 0; private set

    override fun openLots() = held.size
    override fun unrealized(price: Double): Double =
        held.entries.sumOf { (i, q) -> q.toDouble() * price * (1 - fee) - (cost[i] ?: 0.0) }

    override fun onPrice(p: Double) {
        if (stopped) return
        val prev = last
        last = p
        if (prev == null) return

        if (cfg.stopLoss > 0 && p <= cfg.stopLoss) {
            log("Stop-loss hit at $p — selling all and stopping")
            for (i in held.keys.toList()) sell(i, p)
            stopped = true
            return
        }

        // sells first
        for (i in held.keys.toList()) {
            if (i + 1 < lines.size && p >= lines[i + 1]) sell(i, p)
        }
        // buys: price crossed down through line i
        if (!buysPaused) {
            for (i in 0 until lines.size - 1) {
                if (i !in held && prev > lines[i] && p <= lines[i]) buy(i, p)
            }
        }
    }

    private fun buy(i: Int, p: Double) {
        try {
            val qty = if (cfg.paper) {
                BigDecimal(cfg.quotePerGrid * (1 - fee) / p).setScale(6, RoundingMode.DOWN)
            } else api!!.marketBuy(cfg.symbol, cfg.quotePerGrid)
            held[i] = qty
            cost[i] = cfg.quotePerGrid
            trades++
            log("BUY ${qty.toPlainString()} @ ~$p (line $i)")
        } catch (e: Exception) { log("Buy failed: ${e.message}") }
    }

    private fun sell(i: Int, p: Double) {
        val qty = held[i] ?: return
        try {
            val sellQty = qty.divide(step, 0, RoundingMode.DOWN).multiply(step)
            val proceeds = if (cfg.paper) sellQty.toDouble() * p * (1 - fee)
            else api!!.marketSell(cfg.symbol, sellQty).toDouble()
            val pnl = proceeds - (cost[i] ?: 0.0)
            realizedPnl += pnl
            held.remove(i); cost.remove(i)
            trades++
            log("SELL ${sellQty.toPlainString()} @ ~$p  pnl=${"%.2f".format(pnl)}")
        } catch (e: Exception) { log("Sell failed: ${e.message}") }
    }
}
