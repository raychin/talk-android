/*
 * Nextcloud Talk - Android Client
 *
 * SPDX-FileCopyrightText: 2017-2018 Mario Danic <mario@lovelyhq.com>
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.nextcloud.talk.utils

import android.content.Context
import android.content.res.Resources
import android.icu.text.RelativeDateTimeFormatter
import android.icu.text.RelativeDateTimeFormatter.Direction
import android.icu.text.RelativeDateTimeFormatter.RelativeUnit
import com.nextcloud.talk.R
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class DateUtils(val context: Context) {
    private val cal = Calendar.getInstance()
    private val tz = cal.timeZone

    /* date formatter in local timezone and locale */
    private var format: DateFormat = DateFormat.getDateTimeInstance(
        // dateStyle
        DateFormat.DEFAULT,
        // timeStyle
        DateFormat.SHORT,
        context.resources.configuration.locales[0]
    )

    /* date formatter in local timezone and locale */
    private var formatTime: DateFormat = DateFormat.getTimeInstance(
        // timeStyle
        DateFormat.SHORT,
        context.resources.configuration.locales[0]
    )

    init {
        format.timeZone = tz
        formatTime.timeZone = tz
    }

    fun getLocalDateTimeStringFromTimestamp(timestampMilliseconds: Long): String =
        format.format(Date(timestampMilliseconds))

    fun getLocalTimeStringFromTimestamp(timestampSeconds: Long): String =
        formatTime.format(Date(timestampSeconds * DateConstants.SECOND_DIVIDER))

    fun isSameDate(date1: Date, date2: Date): Boolean {
        val startDateCalendar = Calendar.getInstance().apply { time = date1 }
        val endDateCalendar = Calendar.getInstance().apply { time = date2 }
        val isSameDay = startDateCalendar.get(Calendar.YEAR) == endDateCalendar.get(Calendar.YEAR) &&
            startDateCalendar.get(Calendar.DAY_OF_YEAR) == endDateCalendar.get(Calendar.DAY_OF_YEAR)
        return isSameDay
    }

    fun getTimeDifferenceInSeconds(time2: Long, time1: Long): Long {
        val difference = (time2 - time1)
        return abs(difference)
    }

    fun relativeStartTimeForLobby(timestampMilliseconds: Long, resources: Resources): String {
        val fmt = RelativeDateTimeFormatter.getInstance()
        val timeLeftMillis = timestampMilliseconds - System.currentTimeMillis()
        val minutes = timeLeftMillis.toDouble() / DateConstants.SECOND_DIVIDER / DateConstants.MINUTES_DIVIDER
        val hours = minutes / DateConstants.HOURS_DIVIDER
        val days = hours / DateConstants.DAYS_DIVIDER

        val minutesInt = minutes.roundToInt()
        val hoursInt = hours.roundToInt()
        val daysInt = days.roundToInt()

        return when {
            daysInt > 0 -> {
                fmt.format(
                    daysInt.toDouble(),
                    Direction.NEXT,
                    RelativeUnit.DAYS
                )
            }

            hoursInt > 0 -> {
                fmt.format(
                    hoursInt.toDouble(),
                    Direction.NEXT,
                    RelativeUnit.HOURS
                )
            }

            minutesInt > 1 -> {
                fmt.format(
                    minutesInt.toDouble(),
                    Direction.NEXT,
                    RelativeUnit.MINUTES
                )
            }

            else -> {
                resources.getString(R.string.nc_lobby_start_soon)
            }
        }
    }

    fun getShowTimeString(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp
        val calendar = Calendar.getInstance().apply { timeInMillis = timestamp }
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        return when {
            timestamp >= today.timeInMillis -> {
                // 当天消息
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))
            }
            timestamp >= today.timeInMillis - 86400000L -> {
                // 昨天消息
                "昨天 ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))}"
            }
            diff < 604800000L -> {
                // 近7天内消息
                val dayOfWeek = when (calendar.get(Calendar.DAY_OF_WEEK)) {
                    Calendar.MONDAY -> "周一"
                    Calendar.TUESDAY -> "周二"
                    Calendar.WEDNESDAY -> "周三"
                    Calendar.THURSDAY -> "周四"
                    Calendar.FRIDAY -> "周五"
                    Calendar.SATURDAY -> "周六"
                    Calendar.SUNDAY -> "周日"
                    else -> ""
                }
                "$dayOfWeek ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))}"
            }
            else -> {
                // 超过7天的消息
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
            }
        }
    }

}
