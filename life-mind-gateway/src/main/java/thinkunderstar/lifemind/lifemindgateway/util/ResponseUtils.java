package thinkunderstar.lifemind.lifemindgateway.util;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 封装的webflux框架下响应对象的工具
 */
public class ResponseUtils {
    /**
     * 给前端传入json格式的错误信息
     */
    public static Mono<Void> writeError(ServerWebExchange exchange,
                                  HttpStatus status,
                                  String code,
                                  String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        String body = String.format(
                "{\"code\":\"%s\",\"message\":\"%s\",\"path\":\"%s\"}",
                code, message, exchange.getRequest().getPath().value()
        );

        DataBuffer buffer = response.bufferFactory()
                .wrap(body.getBytes(StandardCharsets.UTF_8));

        return response.writeWith(Mono.just(buffer));
    }
}
