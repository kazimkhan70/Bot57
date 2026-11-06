package com.example.gridbot

import java.math.BigDecimal

/** Market data from Binance with small caches so the bot stays inside rate limits. */
class BinanceMarket(private val api: BinanceApi) : Market {
    private var tradableAt = 0L
    private var tradableCache: Set<String> = emptySet()
    private val rulesCache = HashMap<String, Rules>()

    override fun now() = System.currentTimeMillis()

    override fun tradable(): Set<String> {
        if (now() - tradableAt > 3_600_000 || tradableCache.isEmpty()) {
            tradableCache = api.tradableUsdt(); tradableAt = now()
        }
        return tradableCache
    }

    override fun tickers() = api.tickers24h()
    override fun klines(symbol: String, interval: String, limit: Int) = api.klines(symbol, interval, limit)
    override fun book(symbol: String) = api.book(symbol)
    override fun rules(symbol: String): Rules = rulesCache.getOrPut(symbol) { api.rules(symbol) }
}

/** Real money. Only ever created after the paper-results unlock has passed. */
class LiveBroker(private val api: BinanceApi) : Broker {
    override val paper = false
    override fun cash() = api.usdtFree()
    override fun buy(symbol: String, quote: Double) = api.buyFill(symbol, quote)
    override fun sell(symbol: String, qty: BigDecimal) = api.sellFill(symbol, qty)
}
