package com.astra.pocketauto

import kotlin.math.abs

/**
 * Candle-based research strategy for ASTRA.
 * Requires completed OHLC candles from a trustworthy source.
 * Screen-motion samples are NOT candle prices and must never be passed here.
 */
class StrategyEngine(
    private val settings: Settings = Settings()
) {
    data class Settings(
        val rsiPeriod: Int = 14,
        val rsiOverbought: Double = 70.0,
        val rsiOversold: Double = 30.0,
        val fastEmaPeriod: Int = 9,
        val slowEmaPeriod: Int = 21,
        val supportResistanceLookback: Int = 20,
        val proximityPercent: Double = 0.0015,
        val minimumConfidence: Int = 70
    )

    data class Candle(
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val timestampMillis: Long = 0L
    ) {
        fun isValid(): Boolean =
            open.isFinite() && high.isFinite() && low.isFinite() && close.isFinite() &&
                open > 0.0 && high >= maxOf(open, close) &&
                low <= minOf(open, close) && low > 0.0
    }

    enum class Direction { CALL, PUT, WAIT }

    data class Analysis(
        val direction: Direction,
        val confidence: Int,
        val rsi: Double?,
        val support: Double?,
        val resistance: Double?,
        val reasons: List<String>
    )

    fun analyze(candles: List<Candle>): Analysis {
        val required = maxOf(
            settings.rsiPeriod + 2,
            settings.slowEmaPeriod + 1,
            settings.supportResistanceLookback + 2
        )
        if (candles.size < required || candles.any { !it.isValid() }) {
            return wait("بيانات الشموع غير كافية أو تحتوي على قيم غير صالحة")
        }

        val closes = candles.map { it.close }
        val latest = candles.last()
        val previous = candles[candles.lastIndex - 1]
        val rsiValues = calculateRsi(closes, settings.rsiPeriod)
        val rsiNow = rsiValues.lastOrNull() ?: return wait("تعذر حساب RSI")
        val rsiPrevious = rsiValues.getOrNull(rsiValues.lastIndex - 1) ?: rsiNow

        val fastEma = ema(closes, settings.fastEmaPeriod)
        val slowEma = ema(closes, settings.slowEmaPeriod)
        val bullishTrend = fastEma > slowEma && latest.close > slowEma
        val bearishTrend = fastEma < slowEma && latest.close < slowEma

        val history = candles.dropLast(1).takeLast(settings.supportResistanceLookback)
        val support = history.minOf { it.low }
        val resistance = history.maxOf { it.high }
        val nearSupport = abs(latest.close - support) / latest.close <= settings.proximityPercent
        val nearResistance = abs(resistance - latest.close) / latest.close <= settings.proximityPercent

        val bullishEngulfing = previous.close < previous.open &&
            latest.close > latest.open &&
            latest.open <= previous.close && latest.close >= previous.open
        val bearishEngulfing = previous.close > previous.open &&
            latest.close < latest.open &&
            latest.open >= previous.close && latest.close <= previous.open

        val candleRange = (latest.high - latest.low).coerceAtLeast(1e-12)
        val lowerWick = minOf(latest.open, latest.close) - latest.low
        val upperWick = latest.high - maxOf(latest.open, latest.close)
        val bullishRejection = nearSupport && lowerWick / candleRange >= 0.45 &&
            latest.close > latest.open
        val bearishRejection = nearResistance && upperWick / candleRange >= 0.45 &&
            latest.close < latest.open

        val bullishRsi = rsiNow > rsiPrevious &&
            rsiNow in 50.0..(settings.rsiOverbought - 2.0)
        val bearishRsi = rsiNow < rsiPrevious &&
            rsiNow in (settings.rsiOversold + 2.0)..50.0

        val callReasons = mutableListOf<String>()
        val putReasons = mutableListOf<String>()
        if (bullishTrend) callReasons += "الاتجاه الصاعد: EMA ${settings.fastEmaPeriod} أعلى من EMA ${settings.slowEmaPeriod}"
        if (bearishTrend) putReasons += "الاتجاه الهابط: EMA ${settings.fastEmaPeriod} أسفل من EMA ${settings.slowEmaPeriod}"
        if (bullishRsi) callReasons += "RSI صاعد ويؤكد الزخم (${format(rsiNow)})"
        if (bearishRsi) putReasons += "RSI هابط ويؤكد الزخم (${format(rsiNow)})"
        if (bullishEngulfing) callReasons += "نموذج ابتلاع شرائي"
        if (bearishEngulfing) putReasons += "نموذج ابتلاع بيعي"
        if (bullishRejection) callReasons += "رفض سعري صاعد قرب الدعم"
        if (bearishRejection) putReasons += "رفض سعري هابط قرب المقاومة"
        if (nearSupport) callReasons += "السعر قريب من الدعم ${format(support)}"
        if (nearResistance) putReasons += "السعر قريب من المقاومة ${format(resistance)}"

        val callScore = (if (bullishTrend) 30 else 0) +
            (if (bullishRsi) 25 else 0) +
            (if (bullishEngulfing || bullishRejection) 25 else 0) +
            (if (nearSupport) 20 else 0)
        val putScore = (if (bearishTrend) 30 else 0) +
            (if (bearishRsi) 25 else 0) +
            (if (bearishEngulfing || bearishRejection) 25 else 0) +
            (if (nearResistance) 20 else 0)

        return when {
            rsiNow >= settings.rsiOverbought ->
                Analysis(Direction.WAIT, 0, rsiNow, support, resistance,
                    listOf("RSI في منطقة تشبع شرائي؛ تجنب مطاردة الصعود"))
            rsiNow <= settings.rsiOversold ->
                Analysis(Direction.WAIT, 0, rsiNow, support, resistance,
                    listOf("RSI في منطقة تشبع بيعي؛ تجنب مطاردة الهبوط"))
            callScore >= settings.minimumConfidence && callScore > putScore ->
                Analysis(Direction.CALL, callScore, rsiNow, support, resistance, callReasons)
            putScore >= settings.minimumConfidence && putScore > callScore ->
                Analysis(Direction.PUT, putScore, rsiNow, support, resistance, putReasons)
            else ->
                Analysis(Direction.WAIT, maxOf(callScore, putScore), rsiNow, support, resistance,
                    listOf("لا يوجد توافق كافٍ بين الاتجاه وRSI والشموع والدعم/المقاومة"))
        }
    }

    private fun calculateRsi(values: List<Double>, period: Int): List<Double> {
        if (period <= 0 || values.size <= period) return emptyList()
        var gains = 0.0
        var losses = 0.0
        for (i in 1..period) {
            val change = values[i] - values[i - 1]
            if (change >= 0) gains += change else losses -= change
        }
        var averageGain = gains / period
        var averageLoss = losses / period
        val result = MutableList(period) { Double.NaN }
        result += rsiValue(averageGain, averageLoss)

        for (i in period + 1 until values.size) {
            val change = values[i] - values[i - 1]
            averageGain = (averageGain * (period - 1) + change.coerceAtLeast(0.0)) / period
            averageLoss = (averageLoss * (period - 1) + (-change).coerceAtLeast(0.0)) / period
            result += rsiValue(averageGain, averageLoss)
        }
        return result
    }

    private fun rsiValue(gain: Double, loss: Double): Double = when {
        loss == 0.0 && gain == 0.0 -> 50.0
        loss == 0.0 -> 100.0
        gain == 0.0 -> 0.0
        else -> 100.0 - (100.0 / (1.0 + gain / loss))
    }

    private fun ema(values: List<Double>, period: Int): Double {
        val alpha = 2.0 / (period + 1.0)
        var current = values.take(period).average()
        for (i in period until values.size) {
            current = values[i] * alpha + current * (1.0 - alpha)
        }
        return current
    }

    private fun wait(reason: String) =
        Analysis(Direction.WAIT, 0, null, null, null, listOf(reason))

    private fun format(value: Double) = "%.5f".format(java.util.Locale.US, value)
}
