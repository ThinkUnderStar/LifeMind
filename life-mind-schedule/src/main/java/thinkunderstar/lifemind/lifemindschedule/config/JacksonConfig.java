package thinkunderstar.lifemind.lifemindschedule.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 时间字段的 JSON 格式配置。
 *
 * <p>为什么不用 Jackson 默认的 ISO-8601：默认格式只接受 {@code 2026-11-20T09:30:00}，
 * 而本服务的调用方有两个——前端和 agent。前端自己拼的字符串通常是
 * {@code 2026-11-20 09:30:00}，模型生成的时间更是五花八门（空格、斜杠、只有日期、
 * 甚至直接给时间戳）。默认配置下这些全部抛 {@code HttpMessageNotReadableException}，
 * 表现为一个笼统的「请求参数格式不正确」，模型看不懂错在哪，只会拿同样的格式重试。
 *
 * <p>因此这里做两件事：
 * <ul>
 *     <li><b>输出统一为 {@code yyyy-MM-dd HH:mm:ss}</b>：和 MySQL 的 datetime 展示一致，
 *         人和模型都更容易读；</li>
 *     <li><b>输入尽量宽容</b>：ISO、空格分隔、斜杠分隔、单数字时分、纯日期、毫秒时间戳都收，
 *         实在解析不出来时错误信息里带上原始值，让调用方能自我修正。</li>
 * </ul>
 *
 * <p>只在反序列化端放宽，不做「猜时区」这类聪明事：一律按服务器本地时区解释，
 * 与本服务其余地方（{@code LocalDateTime.now()}）保持一致。
 */
@Configuration
public class JacksonConfig {

    /**
     * 对外输出格式：与数据库 datetime 的展示形式一致
     */
    public static final DateTimeFormatter OUTPUT_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer lifeMindDateTimeCustomizer() {
        return builder -> {
            builder.serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(OUTPUT_FORMATTER));
            builder.deserializerByType(LocalDateTime.class, new LenientLocalDateTimeDeserializer());
        };
    }

    /**
     * 宽容的 {@code LocalDateTime} 反序列化器。
     *
     * <p>接受 {@code 2026-11-20T09:30:00}、{@code 2026-11-20 09:30:00}、
     * {@code 2026/11/20 9:30}、{@code 2026-11-20}、{@code 1763600000000} 等写法。
     *
     * <p>不用「多个 DateTimeFormatter 依次 try」的写法：那样每位数字都得凑满两位，
     * {@code 9:30} 这种模型常用的写法会全部落空；手写拆分再补零更稳，也更容易说清规则。
     */
    static class LenientLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            // 时间戳：模型偶尔会把「明天九点」算成毫秒数传过来
            if (parser.currentToken() != null && parser.currentToken().isNumeric()) {
                long millis = parser.getLongValue();
                return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
            }

            String raw = parser.getValueAsString();
            if (raw == null || raw.isBlank()) {
                return null;
            }

            LocalDateTime parsed = parse(raw);
            if (parsed == null) {
                // 错误信息带上原始值：模型看到「2026-13-45」才知道自己写错了月份，
                // 只回一句「格式不正确」它无从修正
                throw context.weirdStringException(raw, LocalDateTime.class,
                        "时间格式无法识别，支持 2026-11-20 09:30:00 / 2026-11-20T09:30:00 / 2026-11-20");
            }
            return parsed;
        }

        /**
         * 手动拆分解析，任一段不合法就返回 null 交给上层报错。
         */
        private LocalDateTime parse(String raw) {
            String value = raw.trim()
                    .replace('/', '-')
                    .replace('T', ' ')
                    .replaceAll("\\s+", " ");

            // 去掉时区后缀：本服务不使用带时区的时间，留着反而解析不了
            int plus = value.indexOf('+');
            if (plus > 10) {
                value = value.substring(0, plus).trim();
            }
            if (value.endsWith("Z")) {
                value = value.substring(0, value.length() - 1).trim();
            }

            String datePart = value;
            String timePart = null;
            int space = value.indexOf(' ');
            if (space > 0) {
                datePart = value.substring(0, space);
                timePart = value.substring(space + 1).trim();
            }

            // 数字字符串也按毫秒时间戳解释：调用方把时间戳当字符串传（多加了一对引号）
            // 是很常见的手误，13 位的十进制串不可能是日期，认成时间戳不会误伤正常输入
            if (timePart == null && datePart.matches("\\d{12,14}")) {
                return LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(Long.parseLong(datePart)), ZoneId.systemDefault());
            }

            LocalDate date = parseDate(datePart);
            if (date == null) {
                return null;
            }
            if (timePart == null || timePart.isEmpty()) {
                return date.atStartOfDay();
            }

            LocalTime time = parseTime(timePart);
            return time == null ? null : LocalDateTime.of(date, time);
        }

        /**
         * 日期段：补零后按 ISO 解析，{@code 2026-1-5} 与 {@code 2026-01-05} 等价。
         */
        private LocalDate parseDate(String text) {
            String[] parts = text.split("-");
            if (parts.length != 3) {
                return null;
            }
            try {
                int year = Integer.parseInt(parts[0]);
                int month = Integer.parseInt(parts[1]);
                int day = Integer.parseInt(parts[2]);
                return LocalDate.of(year, month, day);
            } catch (NumberFormatException | java.time.DateTimeException e) {
                return null;
            }
        }

        /**
         * 时间段：{@code 9:30}、{@code 09:30:00}、{@code 09:30:00.123} 都收，秒可省。
         */
        private LocalTime parseTime(String text) {
            String main = text;
            int dot = text.indexOf('.');
            if (dot > 0) {
                main = text.substring(0, dot);
            }

            String[] parts = main.split(":");
            if (parts.length < 2 || parts.length > 3) {
                return null;
            }
            try {
                int hour = Integer.parseInt(parts[0].trim());
                int minute = Integer.parseInt(parts[1].trim());
                int second = parts.length == 3 ? Integer.parseInt(parts[2].trim()) : 0;
                return LocalTime.of(hour, minute, second);
            } catch (NumberFormatException | java.time.DateTimeException e) {
                return null;
            }
        }
    }
}
