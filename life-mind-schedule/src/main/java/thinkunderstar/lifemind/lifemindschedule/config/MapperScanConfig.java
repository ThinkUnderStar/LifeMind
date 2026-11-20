package thinkunderstar.lifemind.lifemindschedule.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * Mapper 扫描。
 *
 * <p>单独成配置类而不是在启动类上加 {@code @MapperScan}：
 * 启动类只负责启动，扫描范围属于持久层配置，放这里改起来不用动入口。
 */
@Configuration
@MapperScan("thinkunderstar.lifemind.lifemindschedule.mapper")
public class MapperScanConfig {
}
