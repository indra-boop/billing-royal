package id.jerco.billingroyal.core

import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Utilitas tanggal. Server tidak mengirim zona waktu: semua diperlakukan sebagai Asia/Makassar (WITA). */
object AppDates {
    val ZONE: ZoneId = ZoneId.of("Asia/Makassar")

    private val SERVER_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val MONTHS = arrayOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")

    fun today(clock: Clock = Clock.system(ZONE)): LocalDate = LocalDate.now(clock.withZone(ZONE))

    /** Tampilan "dd MMM yyyy" (bahasa Indonesia), mis. "05 Okt 2026". */
    fun display(date: LocalDate): String =
        "%02d %s %d".format(date.dayOfMonth, MONTHS[date.monthValue - 1], date.year)

    fun displayMonth(month: YearMonth): String = "${MONTHS[month.monthValue - 1]} ${month.year}"

    fun apiDate(date: LocalDate): String = date.toString()          // YYYY-MM-DD
    fun apiMonth(month: YearMonth): String = month.toString()       // YYYY-MM

    fun parseApiDate(text: String?): LocalDate? = try {
        if (text.isNullOrBlank()) null else LocalDate.parse(text.trim().take(10))
    } catch (_: DateTimeParseException) {
        null
    }

    fun parseServerDateTime(text: String?): ZonedDateTime? = try {
        if (text.isNullOrBlank()) null else LocalDateTime.parse(text.trim(), SERVER_DATETIME).atZone(ZONE)
    } catch (_: DateTimeParseException) {
        null
    }

    fun displayServerDateTime(text: String?): String {
        val dt = parseServerDateTime(text) ?: return text.orEmpty()
        return "${display(dt.toLocalDate())} %02d:%02d WITA".format(dt.hour, dt.minute)
    }
}
