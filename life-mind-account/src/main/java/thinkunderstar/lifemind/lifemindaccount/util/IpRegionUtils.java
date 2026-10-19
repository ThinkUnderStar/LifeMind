package thinkunderstar.lifemind.lifemindaccount.util;

import lombok.extern.slf4j.Slf4j;
import org.lionsoul.ip2region.xdb.Searcher;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.StreamUtils;

/**
 * IP 归属地解析工具，基于 ip2region xdb 数据文件。
 *
 * <p>启动时懒加载：将 xdb 全量读入内存（content buffer 模式，{@link Searcher} 线程安全），
 * 后续查询直接走内存。数据文件放在 classpath 根目录（resources/ip2region.xdb）。
 *
 * <p>数据文件缺失或解析失败时统一返回 null（业务侧降级处理），不向上抛异常。
 */
@Slf4j
public class IpRegionUtils {

    /** classpath 根目录下的 xdb 数据文件名 */
    private static final String XDB_FILE = "ip2region.xdb";

    /** 全局单例 Searcher，content buffer 模式线程安全 */
    private static volatile Searcher searcher;

    /** 加载失败标记：失败后不再反复尝试加载，直接降级 */
    private static volatile boolean loadFailed = false;

    private IpRegionUtils() {
    }

    private static Searcher getSearcher() {
        if (searcher != null) {
            return searcher;
        }
        if (loadFailed) {
            return null;
        }
        synchronized (IpRegionUtils.class) {
            if (searcher != null) {
                return searcher;
            }
            try {
                ClassPathResource resource = new ClassPathResource(XDB_FILE);
                byte[] content = StreamUtils.copyToByteArray(resource.getInputStream());
                searcher = Searcher.newWithBuffer(content);
                log.info("ip2region.xdb 加载成功，共 {} 字节", content.length);
            } catch (Exception e) {
                loadFailed = true;
                log.warn("ip2region.xdb 加载失败，地区解析将返回 null: {}", e.getMessage());
            }
        }
        return searcher;
    }

    /**
     * 解析 IP 归属地。
     *
     * @param ip 客户端 IP
     * @return 归属地字符串，如 "中国|0|广东省|深圳市|电信"；内网 IP、数据文件缺失或解析失败返回 null
     */
    public static String getRegion(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        Searcher s = getSearcher();
        if (s == null) {
            return null;
        }
        try {
            String region = s.search(ip);
            // 内网 IP / 未收录地址返回形如 "0|0|0|内网IP|内网IP"，视为无归属地
            if (region == null || region.startsWith("0|0|0|")) {
                return null;
            }
            return region;
        } catch (Exception e) {
            log.debug("IP 地区解析失败 ip={}: {}", ip, e.getMessage());
            return null;
        }
    }
}