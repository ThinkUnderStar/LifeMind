package thinkunderstar.lifemind.lifemindschedule.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 时间格式宽容解析的验证。
 *
 * <p>这些写法不是拍脑袋列的：前四条来自「模型可能怎么生成时间」的实际猜测，
 * 其中空格分隔和单数字小时是最常见的两种。它们在默认 Jackson 配置下全部解析失败，
 * 而失败后接口只会回一句「请求参数格式不正确」，模型拿同样的格式再试一次还是失败。
 */
class JacksonConfigTest {

    /**
     * 只装本服务的自定义反序列化器，不启动 Spring 上下文
     */
    private final ObjectMapper mapper = buildMapper();

    private ObjectMapper buildMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        SimpleModule module = new SimpleModule();
        module.addDeserializer(LocalDateTime.class, new JacksonConfig.LenientLocalDateTimeDeserializer());
        module.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(JacksonConfig.OUTPUT_FORMATTER));
        objectMapper.registerModule(module);
        return objectMapper;
    }

    private LocalDateTime parse(String raw) throws Exception {
        return parseRaw("\"" + raw + "\"");
    }

    private LocalDateTime parseRaw(String jsonValue) throws Exception {
        String json = "{\"time\":" + jsonValue + "}";
        return mapper.readValue(json, TimeHolder.class).getTime();
    }

    @Test
    @DisplayName("ISO 格式：带 T、带秒")
    void parseIso() throws Exception {
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30),
                parse("2026-11-20T09:30:00"));
    }

    @Test
    @DisplayName("ISO 格式：带 T、不带秒")
    void parseIsoWithoutSeconds() throws Exception {
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30),
                parse("2026-11-20T09:30"));
    }

    @Test
    @DisplayName("空格分隔：最常见的写法")
    void parseSpaceSeparated() throws Exception {
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30),
                parse("2026-11-20 09:30:00"));
    }

    @Test
    @DisplayName("单数字小时与分钟：模型经常这么写")
    void parseSingleDigitTime() throws Exception {
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30),
                parse("2026-11-20 9:30"));
        assertEquals(LocalDateTime.of(2026, 1, 5, 8, 5),
                parse("2026-1-5 8:5"));
    }

    @Test
    @DisplayName("斜杠日期")
    void parseSlashDate() throws Exception {
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30),
                parse("2026/11/20 09:30"));
    }

    @Test
    @DisplayName("只有日期：补成当天零点")
    void parseDateOnly() throws Exception {
        assertEquals(LocalDateTime.of(2026, 11, 20, 0, 0),
                parse("2026-11-20"));
    }

    @Test
    @DisplayName("带毫秒与带时区后缀")
    void parseFractionAndZone() throws Exception {
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30, 0),
                parse("2026-11-20 09:30:00.123"));
        assertEquals(LocalDateTime.of(2026, 11, 20, 9, 30, 0),
                parse("2026-11-20T09:30:00+08:00"));
    }

    @Test
    @DisplayName("毫秒时间戳：数字与数字字符串都收")
    void parseEpochMillis() throws Exception {
        long millis = 1763600000000L;
        LocalDateTime expected = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        assertEquals(expected, parseRaw(String.valueOf(millis)));
        assertEquals(expected, parse(String.valueOf(millis)));
    }

    @Test
    @DisplayName("空字符串按 null 处理，不当作错误")
    void parseBlankAsNull() throws Exception {
        assertNull(parse(""));
    }

    @Test
    @DisplayName("非法时间要报错，且错误信息里带上原始值")
    void parseInvalidThrows() {
        InvalidFormatException e = assertThrows(InvalidFormatException.class,
                () -> parse("2026-13-45 09:00"));
        String message = String.valueOf(e.getOriginalMessage());
        assertTrue(message.contains("时间格式无法识别"), "错误信息应说明时间格式问题：" + message);
    }

    @Test
    @DisplayName("输出格式统一为 yyyy-MM-dd HH:mm:ss")
    void serializeUsesReadableFormat() throws Exception {
        String json = mapper.writeValueAsString(new TimeHolder(LocalDateTime.of(2026, 11, 20, 9, 30, 5)));
        assertTrue(json.contains("2026-11-20 09:30:05"), "实际输出：" + json);
    }

    /**
     * 承载时间的简单载体
     */
    public static class TimeHolder {

        private LocalDateTime time;

        public TimeHolder() {
        }

        public TimeHolder(LocalDateTime time) {
            this.time = time;
        }

        public LocalDateTime getTime() {
            return time;
        }

        public void setTime(LocalDateTime time) {
            this.time = time;
        }
    }
}
