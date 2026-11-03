package com.astra.pocketauto

import java.time.Instant
import java.time.ZoneOffset

/**
 * Offline/paper-only trade simulator.
 * It never sends orders and must only receive completed OHLC candles.
 */
class PaperTradingEngine(
    private val settings: Settings = Settings()
) {
    data class Settings(
        val startingBalance: Double = 100.0,
        val stakeAmount: Double = 1.0,
        val payoutRate: Double = 0.80,
        val expiryCandles: Int = 1,
        val minimumConfidence: Int = 70,
        val maxTradesPerUtcDay: Int = 20,
        val maxDailyLoss: Double = 5.0,
        val maxConsecutiveLosses: Int = 3
    ) {
        init {
            require(startingBalance > 0.0)
            require(stakeAmount > 0.0 && stakeAmount <= startingBalance)
            require(payoutRate in 0.0..1.0)
            require(expiryCandles >= 1)
            require(minimumConfidence in 1..100)
            require(maxTradesPerUtcDay >= 1)
            require(maxDailyLoss > 0.0)
            require(maxConsecutiveLosses >= 1)
        }
    }

    data class PaperTrade(
        val direction: StrategyEngine.Direction,
        val entryPrice: Double,
        val entryTimeMillis: Long,
        val expiryAtCandleIndex: Int,
        val stake: Double,
        val confidence: Int
    )

    data class Snapshot(
        val balance: Double,
        val openTrade: PaperTrade?,
        val tradesToday: Int,
        val dailyNet: Double,
        val consecutiveLosses: Int,
        val status: String
    )

    data class TradeResult(
        val trade: PaperTrade,
        val outcome: Outcome,
        val netChange: Double,
        val balanceAfter: Double
    )

    enum class Outcome { WIN, LOSS, DRAW }

    private var balance = settings.startingBalance
    private var openTrade: PaperTrade? = null
    private var currentUtcDay: Long? = null
    private var tradesToday = 0
    private var dailyNet = 0.0
    private var consecutiveLosses = 0
    private var candleIndex = -1
    private var lastTimestamp = Long.MIN_VALUE

    @Synchronized
    fun onCompletedCandle(
        candle: StrategyEngine.Candle,
        analysis: StrategyEngine.Analysis
    ): Pair<Snapshot, TradeResult?> {
        if (!candle.isValid() || candle.timestampMillis <= lastTimestamp ||
            candle.timestampMillis <= 0L
        ) {
            return snapshot("تم تجاهل شمعة غير صالحة أو غير مرتبة") to null
        }

        lastTimestamp = candle.timestampMillis
        candleIndex++

        val utcDay = Instant.ofEpochMilli(candle.timestampMillis)
            .atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
        if (currentUtcDay != utcDay) {
            currentUtcDay = utcDay
            tradesToday = 0
            dailyNet = 0.0
            consecutiveLosses = 0
        }

        val result = settleIfDue(candle)
        if (openTrade == null && canOpenTrade() &&
            analysis.direction != StrategyEngine.Direction.WAIT &&
            analysis.confidence >= settings.minimumConfidence &&
            analysis.direction in listOf(StrategyEngine.Direction.CALL, StrategyEngine.Direction.PUT)
        ) {
            openTrade = PaperTrade(
                direction = analysis.direction,
                entryPrice = candle.close,
                entryTimeMillis = candle.timestampMillis,
                expiryAtCandleIndex = candleIndex + settings.expiryCandles,
                stake = settings.stakeAmount,
                confidence = analysis.confidence
            )
            tradesToday++
        }

        val status = when {
            openTrade != null -> "صفقة تجريبية مفتوحة"
            dailyNet <= -settings.maxDailyLoss -> "توقف: حد الخسارة اليومية"
            consecutiveLosses >= settings.maxConsecutiveLosses -> "توقف: خسائر متتالية"
            tradesToday >= settings.maxTradesPerUtcDay -> "توقف: الحد اليومي للصفقات"
            else -> "انتظار إشارة مؤهلة"
        }
        return snapshot(status) to result
    }

    private fun settleIfDue(candle: StrategyEngine.Candle): TradeResult? {
        val trade = openTrade ?: return null
        if (candleIndex < trade.expiryAtCandleIndex) return null

        val outcome = when {
            candle.close == trade.entryPrice -> Outcome.DRAW
            trade.direction == StrategyEngine.Direction.CALL && candle.close > trade.entryPrice -> Outcome.WIN
            trade.direction == StrategyEngine.Direction.PUT && candle.close < trade.entryPrice -> Outcome.WIN
            else -> Outcome.LOSS
        }
        val net = when (outcome) {
            Outcome.WIN -> trade.stake * settings.payoutRate
            Outcome.LOSS -> -trade.stake
            Outcome.DRAW -> 0.0
        }
        balance += net
        dailyNet += net
        consecutiveLosses = when (outcome) {
            Outcome.WIN -> 0
            Outcome.LOSS -> consecutiveLosses + 1
            Outcome.DRAW -> consecutiveLosses
        }
        openTrade = null
        return TradeResult(trade, outcome, net, balance)
    }

    private fun canOpenTrade(): Boolean =
        balance >= settings.stakeAmount &&
            dailyNet > -settings.maxDailyLoss &&
            tradesToday < settings.maxTradesPerUtcDay &&
            consecutiveLosses < settings.maxConsecutiveLosses

    private fun snapshot(status: String) = Snapshot(
        balance = balance,
        openTrade = openTrade,
        tradesToday = tradesToday,
        dailyNet = dailyNet,
        consecutiveLosses = consecutiveLosses,
        status = status
    )

    @Synchronized
    fun currentSnapshot(): Snapshot = snapshot("حالة المحاكي")
}
