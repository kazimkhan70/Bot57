package com.example.gridbot

object Backtest {
    private const val DAY_MS = 86_400_000.0

    /** Replays candles through the same engines used live (paper mode, 0.1% fee per side). */
    fun run(candles: List<Candle>, grid: GridConfig?, trend: TrendConfig?, dca: DcaConfig? = null): String {
        if (candles.size < 60) return "Not enough price data."
        val msgs = ArrayList<String>()
        val log: (String) -> Unit = { msgs.add(it) }

        val strat: Strategy
        val trendEngine: TrendEngine?
        val dcaEngine: DcaEngine?
        var capital: Double
        val name: String
        if (dca != null) {
            dcaEngine = DcaEngine(dca.copy(paper = true), null, log)
            trendEngine = null
            strat = dcaEngine; capital = dca.quote; name = "DCA (${dca.quote} USDT every ${dca.everyMin} min)"
        } else if (trend != null) {
            dcaEngine = null
            trendEngine = TrendEngine(trend.copy(paper = true), null, log)
            strat = trendEngine; capital = trend.quote; name = "Trend (EMA ${trend.fast}/${trend.slow} + RSI)"
        } else {
            val g = grid!!.copy(paper = true)
            trendEngine = null; dcaEngine = null
            strat = GridEngine(g, null, log)
            capital = g.quotePerGrid * (g.levels - 1); name = "Grid"
        }

        val closes = ArrayList<Double>()
        for (c in candles) {
            val path = if (c.c >= c.o) listOf(c.o, c.l, c.h, c.c) else listOf(c.o, c.h, c.l, c.c)
            for (p in path) { if (dcaEngine != null) dcaEngine.onTick(p, c.t) else strat.onPrice(p) }
            closes.add(c.c)
            trendEngine?.onCandle(closes, c.c)
            if (strat.stopped) break
        }

        if (dcaEngine != null) capital = maxOf(dcaEngine.totalSpent, 1.0)
        val lastPrice = candles.last().c
        val unreal = strat.unrealized(lastPrice)
        val total = strat.realizedPnl + unreal
        val days = (candles.last().t - candles.first().t) / DAY_MS
        val tradeCount = msgs.count { it.startsWith("BUY") || it.startsWith("SELL") }
        return buildString {
            appendLine("$name — ${"%.1f".format(days)} days, ${candles.size} candles")
            appendLine("Trades: $tradeCount")
            appendLine("Realized PnL: ${"%.2f".format(strat.realizedPnl)} USDT")
            appendLine("Open positions: ${strat.openLots()} (unrealized ${"%.2f".format(unreal)})")
            appendLine("Total: ${"%.2f".format(total)} USDT = ${"%.2f".format(total / capital * 100)}% of ${"%.0f".format(capital)} USDT capital")
            appendLine()
            append("Includes 0.1% fee per trade; ignores slippage. Past results do not predict future results.")
        }
    }
}
