package com.datagov.data.connector.http;

import com.datagov.common.error.ErrorCode;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataSourceConnector;
import com.datagov.data.spi.DataSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Set;

/**
 * RestAPI 数据源连接器(功能 4)。
 *
 * <p>只支持连通性测试:HTTP 接口没有"库表结构"可言,{@code canBrowseCatalog}
 * 与 {@code canBrowseFiles} 都声明为 false,UI 据此不渲染结构树。
 *
 * <p><b>凭据从哪来</b>:本连接器只认 {@link ConnectionConfig} 里已经解析好的
 * 用户名口令。接口认证凭据(Bearer / API Key / OAuth2)由 Platform Space 的
 * 凭据托管持有,在 Metadata 组装 ConnectionConfig 时解密注入 ——
 * Data Space 不知道凭据存在哪、怎么加密,这是它不该知道的事。
 */
@Component
public class RestApiConnector implements DataSourceConnector {

    private static final Logger log = LoggerFactory.getLogger(RestApiConnector.class);

    @Override
    public Set<DataSourceType> supportedTypes() {
        return Set.of(DataSourceType.REST_API);
    }

    @Override
    public ConnectorCapabilities capabilities(DataSourceType type) {
        return ConnectorCapabilities.httpBased();
    }

    @Override
    public ConnectivityResult testConnection(DataSourceType type, ConnectionConfig config) {
        long startedAt = System.nanoTime();

        String baseUrl = config.baseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return ConnectivityResult.failure(ErrorCode.DAT_CONNECT_FAILED, 0L,
                    "未配置接口地址(baseUrl)", null);
        }

        try {
            URI uri = URI.create(baseUrl.trim());
            RestClient client = RestClient.builder()
                    .requestFactory(requestFactory(config))
                    .build();

            int status = client.get()
                    .uri(uri)
                    .exchange((request, response) -> response.getStatusCode().value(), false);

            return interpret(status, elapsed(startedAt), baseUrl);

        } catch (ResourceAccessException ex) {
            // 网络层失败:DNS 解析不了、连接被拒、读超时
            boolean timeout = ex.getMessage() != null
                    && ex.getMessage().toLowerCase().contains("timed out");
            ErrorCode code = timeout ? ErrorCode.DAT_TIMEOUT : ErrorCode.DAT_CONNECT_FAILED;
            log.debug("RestAPI 连通性测试失败 baseUrl={}", baseUrl);
            return ConnectivityResult.failure(code, elapsed(startedAt),
                    (timeout ? "请求超时: " : "无法访问: ") + baseUrl,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());

        } catch (IllegalArgumentException ex) {
            return ConnectivityResult.failure(ErrorCode.DAT_CONNECT_FAILED, elapsed(startedAt),
                    "接口地址格式不合法: " + baseUrl, ex.getMessage());

        } catch (RuntimeException ex) {
            log.warn("RestAPI 连通性测试出现未预期异常 baseUrl={}", baseUrl, ex);
            return ConnectivityResult.failure(ErrorCode.DAT_CONNECT_FAILED, elapsed(startedAt),
                    "无法访问: " + baseUrl,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    /**
     * 把 HTTP 状态码翻译成连通性结论。
     *
     * <p>关键判断:<b>401/403 算认证失败而不是连通失败</b>。服务端能返回 401
     * 恰恰证明网络是通的,问题出在凭据上 —— 把它报成"连不上"会让用户白白
     * 去查防火墙。这个区分正是 {@link ConnectivityResult} 要求给出可行动信息的意义。
     */
    private static ConnectivityResult interpret(int status, long latencyMillis, String baseUrl) {
        if (status >= 200 && status < 400) {
            return ConnectivityResult.success(latencyMillis, "HTTP " + status);
        }
        if (status == 401 || status == 403) {
            return ConnectivityResult.failure(ErrorCode.DAT_AUTH_FAILED, latencyMillis,
                    "接口可达但认证被拒(HTTP %d),请检查所选凭据".formatted(status),
                    "GET " + baseUrl + " -> " + status);
        }
        if (status == 404) {
            return ConnectivityResult.failure(ErrorCode.DAT_CONNECT_FAILED, latencyMillis,
                    "接口地址不存在(HTTP 404),请检查 baseUrl 路径",
                    "GET " + baseUrl + " -> 404");
        }
        if (status >= 500) {
            return ConnectivityResult.failure(ErrorCode.DAT_CONNECT_FAILED, latencyMillis,
                    "接口可达但服务端报错(HTTP %d)".formatted(status),
                    "GET " + baseUrl + " -> " + status);
        }
        return ConnectivityResult.failure(ErrorCode.DAT_CONNECT_FAILED, latencyMillis,
                "接口返回异常状态 HTTP " + status,
                "GET " + baseUrl + " -> " + status);
    }

    private static SimpleClientHttpRequestFactory requestFactory(ConnectionConfig config) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.connectTimeoutMillis());
        factory.setReadTimeout(config.readTimeoutMillis());
        return factory;
    }

    private static long elapsed(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
