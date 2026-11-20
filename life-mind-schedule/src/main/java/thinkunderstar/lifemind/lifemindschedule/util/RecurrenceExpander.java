package thinkunderstar.lifemind.lifemindschedule.util;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 重复规则展开器：把一条规则算成具体的时刻列表。
 *
 * <p>刻意做成纯函数、不碰数据库也不抛业务异常：规则的边界条件（月末、跨年、
 * 一周多天、间隔周）是最容易算错、也最值得单独测的部分，混在 Service 里
 * 就只能靠起整个 Spring 才测得动。
 *
 * <p>不支持的东西，一律不"尽力猜"：时区、夏令时、法定节假日、RFC5545 的 BYSETPOS
 * 这类高级用法。猜错的代价是日程落在错误的日子，比直接不支持更难被发现。
 *
 * <p>次数上限在这里也生效（{@link #MAX_OCCURRENCES}）：即使调用方给了更大的
 * {@code countLimit}，也只展开到这里为止，避免一次请求往库里灌几千行。
 */
public final class RecurrenceExpander {

    /**
     * 单次最多展开多少条。与 DTO 上的 {@code countLimit} 上限一致，
     * 这里是最后一道防线——Service 层算错也不会把库灌爆。
     */
    public static final int MAX_OCCURRENCES = 100;

    /**
     * 星期几的合法取值，顺序用于格式化时稳定输出
     */
    private static final List<DayOfWeek> WEEK_ORDER = List.of(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
            DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY);

    private RecurrenceExpander() {
    }

    /**
     * 一次展开出来的时刻。
     *
     * @param index 在系列里是第几次，从 1 开始
     * @param time  具体时刻
     */
    public record Occurrence(int index, LocalDateTime time) {
    }

    /**
     * 按规则算出所有时刻（含已经过去的）。
     *
     * <p>要不要跳过过去的时刻由调用方决定：创建时跳过（不能一建完就收到一堆提醒），
     * 但序号 {@link Occurrence#index()} 仍然按完整规则计算，
     * 这样「第几次」不会因为跳过了前几次而错位。
     *
     * @param freq       DAILY / WEEKLY / MONTHLY
     * @param interval   每 N 天/周/月，小于 1 按 1 处理
     * @param byDayCsv   星期几，如 {@code "MO,WE"}，仅 WEEKLY 使用；空则取锚点当天
     * @param untilDate  终止日期（含当天），可为 null
     * @param countLimit 总次数上限（含第一次），可为 null
     * @param baseTime   锚点时刻，其日期决定第一天、其时间决定每次的时分
     * @return 按时间升序的时刻列表，可能为空
     */
    public static List<Occurrence> expand(String freq,
                                          Integer interval,
                                          String byDayCsv,
                                          LocalDate untilDate,
                                          Integer countLimit,
                                          LocalDateTime baseTime) {
        if (baseTime == null) {
            throw new IllegalArgumentException("锚点时刻不能为空");
        }
        int step = interval == null || interval < 1 ? 1 : interval;
        // countLimit 为空时用上限兜底：没有次数上限就必须有 untilDate，两者都空是调用方的错，
        // 这里不抛异常而是取最大值，保证「有规则就能算出东西」
        int limit = countLimit == null ? MAX_OCCURRENCES : Math.min(countLimit, MAX_OCCURRENCES);
        if (limit <= 0) {
            return List.of();
        }

        LocalDate startDate = baseTime.toLocalDate();
        LocalTime startTime = baseTime.toLocalTime();

        List<LocalDate> dates = switch (freq) {
            case "DAILY" -> daily(startDate, step, untilDate, limit);
            case "WEEKLY" -> weekly(startDate, step, parseByDay(byDayCsv), untilDate, limit);
            case "MONTHLY" -> monthly(startDate, step, untilDate, limit);
            default -> throw new IllegalArgumentException("不支持的频率：" + freq);
        };

        List<Occurrence> occurrences = new ArrayList<>(dates.size());
        for (int i = 0; i < dates.size(); i++) {
            occurrences.add(new Occurrence(i + 1, dates.get(i).atTime(startTime)));
        }
        return occurrences;
    }

    private static List<LocalDate> daily(LocalDate start, int step, LocalDate until, int limit) {
        List<LocalDate> dates = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            LocalDate date = start.plusDays((long) i * step);
            if (until != null && date.isAfter(until)) {
                break;
            }
            dates.add(date);
        }
        return dates;
    }

    /**
     * 每周：按「第 0 周 / 第 N 周」判断，而不是「距离上次多少天」。
     *
     * <p>这样 {@code interval=2} 的含义稳定是「隔一周」，
     * 不会因为锚点那天不是命中的星期几就把节奏算歪。
     */
    private static List<LocalDate> weekly(LocalDate start,
                                          int step,
                                          Set<DayOfWeek> days,
                                          LocalDate until,
                                          int limit) {
        Set<DayOfWeek> effectiveDays = days.isEmpty() ? EnumSet.of(start.getDayOfWeek()) : days;
        LocalDate startWeek = start.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));

        // 安全阀：按「最多要扫多少天」估一个上限，避免 untilDate 为空时死循环。
        // 一周最多 7 天，每次命中至少隔 step 周
        long safety = (long) limit * 7 * step + 7;

        List<LocalDate> dates = new ArrayList<>(limit);
        LocalDate cursor = start;
        for (long scanned = 0; dates.size() < limit && scanned < safety; scanned++) {
            if (until != null && cursor.isAfter(until)) {
                break;
            }
            LocalDate cursorWeek = cursor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            long weeks = ChronoUnit.WEEKS.between(startWeek, cursorWeek);
            if (weeks % step == 0 && effectiveDays.contains(cursor.getDayOfWeek())) {
                dates.add(cursor);
            }
            cursor = cursor.plusDays(1);
        }
        return dates;
    }

    /**
     * 每月：始终从锚点日期推算，不在上一个月的结果上叠加。
     *
     * <p>差别很实在：锚点是 1 月 31 日时，「1/31 → 2/28 → 3/28」是叠加算出来的错误结果，
     * 从锚点直接推算得到的是「3/31」——后者才符合用户说「每月 31 号」的意思。
     */
    private static List<LocalDate> monthly(LocalDate start, int step, LocalDate until, int limit) {
        List<LocalDate> dates = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            LocalDate date = start.plusMonths((long) i * step);
            if (until != null && date.isAfter(until)) {
                break;
            }
            dates.add(date);
        }
        return dates;
    }

    /**
     * 解析 {@code "MO,WE"} 形式的星期串；空串或 null 返回空集合（表示「取锚点当天」）。
     *
     * <p>不认识的取值直接抛——这是编程错误，不是用户输入错误，
     * 静默丢掉会让规则悄悄少一天。
     */
    public static Set<DayOfWeek> parseByDay(String csv) {
        if (csv == null || csv.isBlank()) {
            return EnumSet.noneOf(DayOfWeek.class);
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        for (String part : csv.split(",")) {
            String token = part.trim().toUpperCase();
            if (token.isEmpty()) {
                continue;
            }
            days.add(parseDay(token));
        }
        return days.isEmpty() ? EnumSet.noneOf(DayOfWeek.class) : days;
    }

    /**
     * 把星期集合格式化成 {@code "MO,WE"}，按周一到周日排序以保证稳定输出。
     */
    public static String formatByDay(Set<DayOfWeek> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        String csv = WEEK_ORDER.stream()
                .filter(days::contains)
                .map(day -> day.name().substring(0, 2))
                .collect(Collectors.joining(","));
        return csv.isEmpty() ? null : csv;
    }

    /**
     * 把 CSV 形式的星期串规范化：{@code "we,MO"} → {@code "MO,WE"}。
     */
    public static String normalizeByDay(String csv) {
        return formatByDay(parseByDay(csv));
    }

    /**
     * 拆成有序列表，供响应体使用。
     */
    public static List<String> toDayList(String csv) {
        Set<DayOfWeek> days = parseByDay(csv);
        if (days.isEmpty()) {
            return List.of();
        }
        // 不做自然排序：WEEK_ORDER 已经是周一到周日，
        // 按字母排会得到 FR,MO,SA,SU,TH,TU,WE 这种对用户毫无意义的顺序
        return WEEK_ORDER.stream()
                .filter(days::contains)
                .map(day -> day.name().substring(0, 2))
                .toList();
    }

    /**
     * 单个星期取值解析：只认 MO/TU/WE/TH/FR/SA/SU。
     */
    public static DayOfWeek parseDay(String token) {
        return switch (token) {
            case "MO" -> DayOfWeek.MONDAY;
            case "TU" -> DayOfWeek.TUESDAY;
            case "WE" -> DayOfWeek.WEDNESDAY;
            case "TH" -> DayOfWeek.THURSDAY;
            case "FR" -> DayOfWeek.FRIDAY;
            case "SA" -> DayOfWeek.SATURDAY;
            case "SU" -> DayOfWeek.SUNDAY;
            default -> throw new IllegalArgumentException("无法识别的星期取值：" + token);
        };
    }
}
