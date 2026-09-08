package com.datagov.data.connector.http;

import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.DataSourceType;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RestAPI 连接器验证 —— 用 JDK 自带的 HttpServer 起一个<b>真实</b> HTTP 服务,
 * 不 mock HTTP 客户端。真实起服务才能验证超时、状态码解析、连接释放这些
 * mock 掩盖不了的行为。
 */
@DisplayName("RestAPI 连接器")
class RestApiConnectorTest {

    private static HttpServer server;
    private static int port;

    private final RestApiConnector connector = new RestApiConnector();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        server.createContext("/ok", exchange -> respond(exchange, 200, "{\"status\":\"up\"}"));
        server.createContext("/created", exchange -> respond(exchange, 201, ""));
        server.createContext("/unauthorized", exchange -> respond(exchange, 401, ""));
        server.createContext("/forbidden", exchange -> respond(exchange, 403, ""));
        server.createContext("/missing", exchange -> respond(exchange, 404, ""));
        server.createContext("/broken", exchange -> respond(exchange, 503, ""));

        server.setExecutor(null);
        server.start();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("2xx 视为连通成功,并带回状态码与耗时")
    void successOnOk() {
        ConnectivityResult result = probe("/ok");

        assertThat(result.success()).isTrue();
        assertThat(result.serverVersion()).isEqualTo("HTTP 200");
        assertThat(result.errorCode()).isNull();
        assertThat(result.latencyMillis()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("3xx 之前的所有成功状态都算连通")
    void successOnCreated() {
        assertThat(probe("/created").success()).isTrue();
    }

    @Test
    @DisplayName("401/403 归类为认证失败,而不是连不上")
    void authFailureOnUnauthorized() {
        // 服务端能返回 401 恰恰证明网络是通的。报成"连不上"会让用户白查防火墙 ——
        // 这个区分正是 ConnectivityResult 要求给出可行动信息的意义。
        for (String path : new String[]{"/unauthorized", "/forbidden"}) {
            ConnectivityResult result = probe(path);
            assertThat(result.success()).as(path).isFalse();
            assertThat(result.errorCode().code()).as(path).isEqualTo("DAT_AUTH_FAILED");
            assertThat(result.message()).as(path).contains("认证被拒");
        }
    }

    @Test
    @DisplayName("404 提示检查地址路径,而不是笼统地说连接失败")
    void notFoundGivesActionableMessage() {
        ConnectivityResult result = probe("/missing");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("接口地址不存在").contains("baseUrl");
    }

    @Test
    @DisplayName("5xx 说明接口可达但服务端有问题 —— 与网络不通要分开表述")
    void serverErrorIsDistinguishedFromUnreachable() {
        ConnectivityResult result = probe("/broken");

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("接口可达但服务端报错");
    }

    @Test
    @DisplayName("未配置 baseUrl 时立即失败,不发起任何请求")
    void missingBaseUrlFailsFast() {
        ConnectivityResult result = connector.testConnection(
                DataSourceType.REST_API, ConnectionConfig.builder().build());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("未配置接口地址");
        assertThat(result.latencyMillis()).isZero();
    }

    @Test
    @DisplayName("地址格式不合法时给出格式错误而不是网络错误")
    void malformedUrlIsReportedAsFormatError() {
        ConnectivityResult result = connector.testConnection(DataSourceType.REST_API,
                ConnectionConfig.builder().baseUrl("这不是一个 URL ::: %%%").build());

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("不合法");
    }

    @Test
    @DisplayName("目标端口无人监听时返回失败结果而非抛异常")
    void unreachableHostReturnsFailure() {
        ConnectivityResult result = connector.testConnection(DataSourceType.REST_API,
                ConnectionConfig.builder()
                        .baseUrl("http://127.0.0.1:1/whatever")
                        .connectTimeoutMillis(2_000)
                        .readTimeoutMillis(2_000)
                        .build());

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isNotNull();
        assertThat(result.detail()).isNotBlank();
    }

    private ConnectivityResult probe(String path) {
        return connector.testConnection(DataSourceType.REST_API,
                ConnectionConfig.builder()
                        .baseUrl("http://127.0.0.1:" + port + path)
                        .connectTimeoutMillis(3_000)
                        .readTimeoutMillis(3_000)
                        .build());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange,
                                int status, String body) throws IOException {
        byte[] bytes = body.getBytes();
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
