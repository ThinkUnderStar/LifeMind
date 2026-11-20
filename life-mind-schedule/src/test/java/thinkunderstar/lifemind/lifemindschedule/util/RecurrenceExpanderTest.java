package thinkunderstar.lifemind.lifemindschedule.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复规则的展开算术。
 *
 * <p>这些用例覆盖的都是「算错了但接口照样返回 200」的地方：月末回落、按周间隔、
 * 一周多天、次数与日期双终点，以及次数上限的兜底。它们不启 Spring，
 * 因为展开器本身是纯函数——把它测明白，Service 剩下的就只是「存哪些行」。
 */
class RecurrenceExpanderTest {

    /**
     * 2026-11-25 是星期三（用于所有按周的用例）
     */
    private static final LocalDateTime WED = LocalDateTime.of(2026, 11, 25, 19, 0);

    @Test
    @DisplayName("每天：按间隔逐日推进，序号从 1 开始，时分保持不变")
    void dailyKeepsTimeOfDay() {
        List<RecurrenceExpander.Occurrence> result =
                RecurrenceExpander.expand("DAILY", 1, null, null, 3, WED);

        assertEquals(3, result.size());
        assertEquals(LocalDateTime.of(2026, 11, 25, 19, 0), result.get(0).time());
        assertEquals(LocalDateTime.of(2026, 11, 26, 19, 0), result.get(1).time());
        assertEquals(LocalDateTime.of(2026, 11, 27, 19, 0), result.get(2).time());
        assertEquals(1, result.get(0).index());
        assertEquals(3, result.get(2).index());
    }

    @Test
    @DisplayName("每天 + 间隔 2：隔一天一次")
    void dailyWithInterval() {
        List<RecurrenceExpander.Occurrence> result =
                RecurrenceExpander.expand("DAILY", 2, null, null, 3, WED);

        assertEquals(LocalDateTime.of(2026, 11, 25, 19, 0), result.get(0).time());
        assertEquals(LocalDateTime.of(2026, 11, 27, 19, 0), result.get(1).time());
        assertEquals(LocalDateTime.of(2026, 11, 29, 19, 0), result.get(2).time());
    }

    @Test
    @DisplayName("每周多天：从锚点当天开始，命中星期就收，跨周继续")
    void weeklyMultipleDays() {
        List<RecurrenceExpander.Occurrence> result =
                RecurrenceExpander.expand("WEEKLY", 1, "MO,WE,FR", null, 5, WED);

        assertEquals(List.of(
                LocalDateTime.of(2026, 11, 25, 19, 0),   // 三
                LocalDateTime.of(2026, 11, 27, 19, 0),   // 五
                LocalDateTime.of(2026, 11, 30, 19, 0),   // 一
                LocalDateTime.of(2026, 12, 2, 19, 0),    // 三
                LocalDateTime.of(2026, 12, 4, 19, 0)     // 五
        ), result.stream().map(RecurrenceExpander.Occurrence::time).toList());
    }

    @Test
    @DisplayName("每周 + 间隔 2：隔周一次，不是每 14 天一次")
    void weeklyWithInterval() {
        List<RecurrenceExpander.Occurrence> result =
                RecurrenceExpander.expand("WEEKLY", 2, null, null, 3, WED);

        assertEquals(List.of(
                LocalDateTime.of(2026, 11, 25, 19, 0),
                LocalDateTime.of(2026, 12, 9, 19, 0),
                LocalDateTime.of(2026, 12, 23, 19, 0)
        ), result.stream().map(RecurrenceExpander.Occurrence::time).toList());
    }

    @Test
    @DisplayName("每月：始终从锚点推算，1/31 之后是 2/28 再回到 3/31，不会漂移")
    void monthlyDoesNotDrift() {
        LocalDateTime jan31 = LocalDateTime.of(2026, 1, 31, 9, 0);

        List<RecurrenceExpander.Occurrence> result =
                RecurrenceExpander.expand("MONTHLY", 1, null, null, 3, jan31);

        assertEquals(List.of(
                LocalDateTime.of(2026, 1, 31, 9, 0),
                LocalDateTime.of(2026, 2, 28, 9, 0),
                LocalDateTime.of(2026, 3, 31, 9, 0)
        ), result.stream().map(RecurrenceExpander.Occurrence::time).toList());
    }

    @Test
    @DisplayName("终止日期：含当天，超出即停")
    void untilDateIsInclusive() {
        List<RecurrenceExpander.Occurrence> result =
                RecurrenceExpander.expand("DAILY", 1, null, LocalDate.of(2026, 11, 27), null, WED);

        assertEquals(3, result.size());
        assertEquals(LocalDate.of(2026, 11, 27), result.get(2).time().toLocalDate());
    }

    @Test
    @DisplayName("次数与日期都给：谁先到就停谁")
    void countAndUntilWhicheverFirst() {
        List<RecurrenceExpander.Occurrence> byCount =
                RecurrenceExpander.expand("DAILY", 1, null, LocalDate.of(2026, 12, 31), 2, WED);
        List<RecurrenceExpander.Occurrence> byUntil =
                RecurrenceExpander.expand("DAILY", 1, null, LocalDate.of(2026, 11, 26), 10, WED);

        assertEquals(2, byCount.size());
        assertEquals(2, byUntil.size());
    }

    @Test
    @DisplayName("次数超过上限时按上限截断，不会一次灌出几千行")
    void countIsClamped() {
        List<RecurrenceExpander.Occurrence> result =
                RecurrenceExpander.expand("DAILY", 1, null, null, 5000, WED);

        assertEquals(RecurrenceExpander.MAX_OCCURRENCES, result.size());
    }

    @Test
    @DisplayName("锚点为空直接抛：这是编程错误，不该静默算出一个空结果")
    void baseTimeRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> RecurrenceExpander.expand("DAILY", 1, null, null, 3, null));
    }

    @Test
    @DisplayName("星期串：大小写与顺序都被规范化")
    void byDayNormalization() {
        assertEquals("MO,WE", RecurrenceExpander.normalizeByDay("we,MO"));
        assertEquals(List.of("MO", "WE"), RecurrenceExpander.toDayList("WE,MO"));
        assertNull(RecurrenceExpander.normalizeByDay("  "));
        assertTrue(RecurrenceExpander.toDayList(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> RecurrenceExpander.parseByDay("XX"));
    }
}
