package ua.com.radiokot.photoprism.features.common.logic

import ua.com.radiokot.photoprism.util.LocalDate

/**
 * 日期分组工具，用于按日/月对列表项进行分组。
 *
 * 从 Gallery 的 [GalleryListViewModelImpl] 中的分组逻辑抽取，
 * 供 gallery、albums 及其他需要按时间线分组的 Feature 复用。
 */
class DateGroupingUseCase {

    /**
     * 日期分组结果中的分组头信息。
     */
    sealed interface Header {
        /** 月份标题（如 "2024 年 3 月"） */
        data class Month(val label: String) : Header
        /** 日期标题（如 "3 月 15 日"） */
        data class Day(val label: String, val isToday: Boolean) : Header
    }

    /**
     * 将日期列表分组为 [DateGroup] 序列。
     * 按年份-月份分组，每组包含该月内的所有日期。
     *
     * @param dates 已排序的日期列表
     * @param monthFormat 月份格式化函数，接收 year, month 返回显示标签
     * @param dayFormat 日期格式化函数，接收 day, month, isToday 返回显示标签
     * @return 分组列表，每个元素为 (header, 该分组下的日期列表)
     */
    fun groupByDay(
        dates: List<LocalDate>,
        monthFormat: (year: Int, month: Int) -> String,
        dayFormat: (day: Int, month: Int, isToday: Boolean) -> String,
    ): List<DateGroup> {
        if (dates.isEmpty()) return emptyList()

        val result = mutableListOf<DateGroup>()
        var currentMonthDates = mutableListOf<LocalDate>()
        var currentMonth: LocalDate? = null

        for (date in dates) {
            val monthStart = date // 使用日期代表月份

            if (currentMonth == null || !monthStart.isSameMonthAs(currentMonth!!)) {
                // 月份变化，输出上一个月的分组
                if (currentMonthDates.isNotEmpty()) {
                    addDayGroups(currentMonthDates, currentMonth!!, dayFormat, result)
                }
                // 添加月份 header
                val monthLabel = monthFormat(monthStart.year + 1900, monthStart.month + 1)
                result.add(DateGroup(Header.Month(monthLabel), emptyList()))
                currentMonth = monthStart
                currentMonthDates = mutableListOf()
            }

            currentMonthDates.add(date)
        }

        // 处理最后一个月份
        if (currentMonthDates.isNotEmpty() && currentMonth != null) {
            addDayGroups(currentMonthDates, currentMonth, dayFormat, result)
        }

        return result
    }

    /**
     * 将同一月份内的日期按日分组，每个日分组前插入 [Header.Day]。
     */
    private fun addDayGroups(
        dates: List<LocalDate>,
        month: LocalDate,
        dayFormat: (day: Int, month: Int, isToday: Boolean) -> String,
        result: MutableList<DateGroup>,
    ) {
        val today = LocalDate()
        var currentDayDates = mutableListOf<LocalDate>()
        var currentDay: LocalDate? = null

        for (date in dates) {
            if (currentDay == null || !date.isSameDayAs(currentDay!!)) {
                // 输出上一个日的分组
                if (currentDayDates.isNotEmpty()) {
                    // day groups 作为 item 数据，由调用方决定如何处理
                }
                val isToday = date.isSameDayAs(today)
                val dayLabel = dayFormat(date.date, date.month + 1, isToday)
                result.add(DateGroup(Header.Day(dayLabel, isToday), mutableListOf(date)))
                currentDay = date
                currentDayDates = mutableListOf(date)
            } else {
                currentDayDates.add(date)
            }
        }
    }

    /**
     * 日期分组，包含分组类型和该分组下的日期列表。
     */
    data class DateGroup(
        val header: Header,
        val dates: List<LocalDate>,
    )
}
