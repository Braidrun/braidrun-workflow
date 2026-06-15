package com.fartech.agents.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.*

@LLMDescription(
    "Calendar and date/time tools for getting current time, date calculations, " +
            "timezone conversions, and generating calendar views."
)
object CalendarTools : ToolSet {

    @Tool
    @LLMDescription(
        "Get the current date and time. Supports specifying a timezone. " +
                "Returns date, time, day of week, Unix timestamp, and other useful info."
    )
    fun getCurrentDateTime(
        @LLMDescription("Timezone ID (e.g., 'Asia/Shanghai', 'America/New_York', 'UTC', 'Europe/London'). Default is system timezone.")
        timezone: String = ""
    ): String {
        return try {
            val zoneId = if (timezone.isNotBlank()) {
                try {
                    ZoneId.of(timezone)
                } catch (_: Exception) {
                    return "Error: invalid timezone '$timezone'. Examples: Asia/Shanghai, America/New_York, UTC, Europe/London"
                }
            } else {
                ZoneId.systemDefault()
            }

            val now = ZonedDateTime.now(zoneId)
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

            val sb = StringBuilder()
            sb.appendLine("Current date and time:")
            sb.appendLine("  Date: ${now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))}")
            sb.appendLine("  Time: ${now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))}")
            sb.appendLine("  Day of week: ${now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}")
            sb.appendLine("  Week of year: ${now.get(java.time.temporal.WeekFields.ISO.weekOfWeekBasedYear())}")
            sb.appendLine("  Day of year: ${now.dayOfYear}")
            sb.appendLine("  Timezone: ${zoneId.id} (${now.offset})")
            sb.appendLine("  Unix timestamp (seconds): ${now.toEpochSecond()}")
            sb.appendLine("  Unix timestamp (milliseconds): ${now.toInstant().toEpochMilli()}")
            sb.appendLine("  ISO 8601: ${now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)}")
            sb.toString()
        } catch (e: Exception) {
            "Error getting date/time: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Calculate a date by adding or subtracting days, weeks, months, or years from a given date. " +
                "Useful for 'what date is 30 days from now' or 'what was the date 2 weeks ago'."
    )
    fun calculateDate(
        @LLMDescription("Starting date in yyyy-MM-dd format (use 'today' or 'now' for current date)")
        fromDate: String = "today",
        @LLMDescription("Number of units to add (use negative for subtraction, e.g., -7)")
        amount: Long = 0,
        @LLMDescription("Unit: 'days', 'weeks', 'months', or 'years'")
        unit: String = "days"
    ): String {
        return try {
            val startDate = if (fromDate == "today" || fromDate == "now") {
                LocalDate.now()
            } else {
                LocalDate.parse(fromDate, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            }

            val resultDate = when (unit.lowercase()) {
                "days", "day" -> startDate.plusDays(amount)
                "weeks", "week" -> startDate.plusWeeks(amount)
                "months", "month" -> startDate.plusMonths(amount)
                "years", "year" -> startDate.plusYears(amount)
                else -> return "Error: invalid unit '$unit'. Use 'days', 'weeks', 'months', or 'years'."
            }

            val dayOfWeek = resultDate.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            val sign = if (amount >= 0) "+" else ""
            "Result: ${resultDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))} ($dayOfWeek)\n" +
                    "Calculated from: ${startDate.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))} $sign$amount $unit"
        } catch (e: Exception) {
            "Error calculating date: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Calculate the difference between two dates. Returns the difference in days, weeks, months, and years."
    )
    fun dateDifference(
        @LLMDescription("First date in yyyy-MM-dd format (use 'today' for current date)")
        date1: String,
        @LLMDescription("Second date in yyyy-MM-dd format (use 'today' for current date)")
        date2: String
    ): String {
        return try {
            fun parseDate(input: String, label: String): LocalDate {
                if (input == "today") return LocalDate.now()
                return try {
                    LocalDate.parse(input)
                } catch (_: java.time.format.DateTimeParseException) {
                    throw IllegalArgumentException("Invalid $label date '$input'. Expected format: yyyy-MM-dd (e.g., '2024-03-15')")
                }
            }
            val d1 = parseDate(date1, "first")
            val d2 = parseDate(date2, "second")

            val period = Period.between(d1, d2)
            val totalDays = ChronoUnit.DAYS.between(d1, d2)

            val sb = StringBuilder()
            sb.appendLine("Date difference:")
            sb.appendLine(
                "  From: ${d1.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))} (${
                    d1.dayOfWeek.getDisplayName(
                        TextStyle.FULL,
                        Locale.ENGLISH
                    )
                })"
            )
            sb.appendLine(
                "  To:   ${d2.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))} (${
                    d2.dayOfWeek.getDisplayName(
                        TextStyle.FULL,
                        Locale.ENGLISH
                    )
                })"
            )
            sb.appendLine("  Difference: ${period.years} years, ${period.months} months, ${period.days} days")
            sb.appendLine("  Total days: $totalDays")
            sb.appendLine("  Total weeks: ${"%.1f".format(totalDays / 7.0)}")
            sb.toString()
        } catch (e: Exception) {
            "Error calculating date difference: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Convert a Unix timestamp (seconds or milliseconds) to a human-readable date/time.")
    fun timestampToDate(
        @LLMDescription("Unix timestamp (seconds if <= 10 digits, milliseconds if > 10 digits)")
        timestamp: Long,
        @LLMDescription("Timezone ID (default: system timezone)")
        timezone: String = ""
    ): String {
        return try {
            val zoneId = if (timezone.isNotBlank()) ZoneId.of(timezone) else ZoneId.systemDefault()

            // Auto-detect seconds vs milliseconds
            val instant = if (timestamp > 9_999_999_999L) {
                Instant.ofEpochMilli(timestamp)
            } else {
                Instant.ofEpochSecond(timestamp)
            }

            val zdt = ZonedDateTime.ofInstant(instant, zoneId)
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")

            "Timestamp $timestamp = ${zdt.format(formatter)} (${
                zdt.dayOfWeek.getDisplayName(
                    TextStyle.FULL,
                    Locale.ENGLISH
                )
            })"
        } catch (e: Exception) {
            "Error converting timestamp: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Convert a date/time string to a Unix timestamp.")
    fun dateToTimestamp(
        @LLMDescription("Date/time string in 'yyyy-MM-dd' or 'yyyy-MM-dd HH:mm:ss' format")
        dateString: String,
        @LLMDescription("Timezone ID (default: system timezone)")
        timezone: String = ""
    ): String {
        return try {
            val zoneId = if (timezone.isNotBlank()) ZoneId.of(timezone) else ZoneId.systemDefault()

            val zdt = if (dateString.contains(" ") || dateString.contains("T")) {
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                LocalDateTime.parse(dateString.replace("T", " "), formatter).atZone(zoneId)
            } else {
                LocalDate.parse(dateString).atStartOfDay(zoneId)
            }

            val sb = StringBuilder()
            sb.appendLine("Date: ${zdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))}")
            sb.appendLine("Unix timestamp (seconds): ${zdt.toEpochSecond()}")
            sb.appendLine("Unix timestamp (milliseconds): ${zdt.toInstant().toEpochMilli()}")
            sb.toString()
        } catch (e: Exception) {
            "Error converting to timestamp: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("Convert a date/time from one timezone to another.")
    fun convertTimezone(
        @LLMDescription("Date/time string in 'yyyy-MM-dd HH:mm:ss' format")
        dateTime: String,
        @LLMDescription("Source timezone (e.g., 'Asia/Shanghai')")
        fromTimezone: String,
        @LLMDescription("Target timezone (e.g., 'America/New_York')")
        toTimezone: String
    ): String {
        return try {
            val fromZone = ZoneId.of(fromTimezone)
            val toZone = ZoneId.of(toTimezone)
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

            val localDt = LocalDateTime.parse(dateTime, formatter)
            val fromZdt = localDt.atZone(fromZone)
            val toZdt = fromZdt.withZoneSameInstant(toZone)

            "${fromZdt.format(formatter)} $fromTimezone = ${toZdt.format(formatter)} $toTimezone"
        } catch (e: Exception) {
            "Error converting timezone: ${e.message}"
        }
    }

    @Tool
    @LLMDescription("List common timezone IDs, optionally filtered by region.")
    fun listTimezones(
        @LLMDescription("Optional region filter (e.g., 'Asia', 'America', 'Europe')")
        region: String = ""
    ): String {
        return try {
            val allZones = ZoneId.getAvailableZoneIds().sorted()
            val filtered = if (region.isNotBlank()) {
                allZones.filter { it.startsWith(region, ignoreCase = true) }
            } else {
                // Show common timezones only
                allZones.filter { it.contains("/") && !it.startsWith("SystemV") && !it.startsWith("Etc") }
            }

            val sb = StringBuilder()
            sb.appendLine("Timezones${if (region.isNotBlank()) " in '$region'" else " (${filtered.size} total)"}:")
            filtered.take(50).forEach { sb.appendLine("  - $it") }
            if (filtered.size > 50) {
                sb.appendLine("  ... and ${filtered.size - 50} more")
            }
            sb.toString()
        } catch (e: Exception) {
            "Error listing timezones: ${e.message}"
        }
    }

    @Tool
    @LLMDescription(
        "Generate a text-based calendar view for a specific month. " +
                "Shows a traditional calendar grid with weekday headers."
    )
    fun showCalendar(
        @LLMDescription("Year (e.g., 2024). Use 0 for current year.")
        year: Int = 0,
        @LLMDescription("Month (1-12). Use 0 for current month.")
        month: Int = 0
    ): String {
        return try {
            val now = LocalDate.now()
            val y = if (year == 0) now.year else year
            val m = if (month == 0) now.monthValue else month

            val firstDay = LocalDate.of(y, m, 1)
            val lastDay = firstDay.plusMonths(1).minusDays(1)
            val monthName = firstDay.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

            val sb = StringBuilder()
            sb.appendLine("     $monthName $y")
            sb.appendLine(" Mo Tu We Th Fr Sa Su")

            // Offset for first day (Monday = 1, Sunday = 7)
            val startOffset = firstDay.dayOfWeek.value - 1
            sb.append("  ".repeat(startOffset))

            for (day in 1..lastDay.dayOfMonth) {
                val date = LocalDate.of(y, m, day)
                val marker = if (date == now) "*" else " "
                sb.append("%s%2d".format(marker, day))
                if ((startOffset + day) % 7 == 0) {
                    sb.appendLine()
                }
            }
            sb.appendLine()
            if (now.year == y && now.monthValue == m) {
                sb.appendLine("* = today (${now.dayOfMonth})")
            }
            sb.toString()
        } catch (e: Exception) {
            "Error generating calendar: ${e.message}"
        }
    }
}
