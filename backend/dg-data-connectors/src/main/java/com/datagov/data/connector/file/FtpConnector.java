package com.datagov.data.connector.file;

import com.datagov.common.error.ErrorCode;
import com.datagov.data.connector.ConnectorExceptions;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.FileCatalogReader;
import com.datagov.data.spi.catalog.CatalogModel.FileEntry;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * FTP 连接器(功能 2)。
 */
@Component
public class FtpConnector implements FileCatalogReader {

    private static final Logger log = LoggerFactory.getLogger(FtpConnector.class);

    @Override
    public Set<DataSourceType> supportedTypes() {
        return Set.of(DataSourceType.FTP);
    }

    @Override
    public ConnectorCapabilities capabilities(DataSourceType type) {
        return ConnectorCapabilities.fileBased();
    }

    @Override
    public ConnectivityResult testConnection(DataSourceType type, ConnectionConfig config) {
        long startedAt = System.nanoTime();
        FTPClient client = newClient(config);
        try {
            client.connect(config.host(), portOrDefault(config));
            if (!FTPReply.isPositiveCompletion(client.getReplyCode())) {
                return ConnectivityResult.failure(ErrorCode.DAT_CONNECT_FAILED, elapsed(startedAt),
                        ConnectorExceptions.userMessage(ErrorCode.DAT_CONNECT_FAILED, config.host(), config.port()),
                        "FTP 应答码 " + client.getReplyCode());
            }
            if (!client.login(nullToAnonymous(config.username()), nullToEmpty(config.password()))) {
                return ConnectivityResult.failure(ErrorCode.DAT_AUTH_FAILED, elapsed(startedAt),
                        ConnectorExceptions.userMessage(ErrorCode.DAT_AUTH_FAILED, config.host(), config.port()),
                        "FTP 登录被拒,应答码 " + client.getReplyCode());
            }
            String serverInfo = client.getSystemType();
            return ConnectivityResult.success(elapsed(startedAt), serverInfo);

        } catch (IOException ex) {
            ErrorCode code = ConnectorExceptions.classify(ex);
            log.debug("FTP 连通性测试失败 target={}", config.masked());
            return ConnectivityResult.failure(code, elapsed(startedAt),
                    ConnectorExceptions.userMessage(code, config.host(), config.port()),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            quietlyDisconnect(client);
        }
    }

    @Override
    public List<FileEntry> listEntries(DataSourceType type, ConnectionConfig config, String path) {
        String target = resolvePath(config, path);
        FTPClient client = newClient(config);
        try {
            client.connect(config.host(), portOrDefault(config));
            if (!client.login(nullToAnonymous(config.username()), nullToEmpty(config.password()))) {
                throw ConnectorExceptions.introspectFailed("FTP 登录被拒", null);
            }
            // 被动模式:主动模式要求服务端回连客户端端口,在 NAT/容器环境里几乎必然失败
            client.enterLocalPassiveMode();

            List<FileEntry> entries = new ArrayList<>();
            for (FTPFile file : client.listFiles(target)) {
                if (file == null || ".".equals(file.getName()) || "..".equals(file.getName())) {
                    continue;
                }
                entries.add(new FileEntry(
                        file.getName(),
                        joinPath(target, file.getName()),
                        file.isDirectory(),
                        file.getSize(),
                        file.getTimestamp() == null ? null : file.getTimestamp().toInstant()));
            }
            return entries;

        } catch (IOException ex) {
            throw ConnectorExceptions.introspectFailed("列出 FTP 目录 " + target, ex);
        } finally {
            quietlyDisconnect(client);
        }
    }

    /**
     * 打开文件读取(功能 12)。
     *
     * <p>返回的流<b>持有连接</b>:关流时才断开。提前断开会让读到一半的流突然
     * 失效,而 FTP 的失效表现是静默截断 —— 你得到半个文件却不会收到任何错误。
     */
    @Override
    public java.io.InputStream openFile(DataSourceType type, ConnectionConfig config, String path)
            throws IOException {
        String target = resolvePath(config, path);
        FTPClient client = newClient(config);
        try {
            client.connect(config.host(), portOrDefault(config));
            if (!client.login(nullToAnonymous(config.username()), nullToEmpty(config.password()))) {
                throw new IOException("FTP 登录被拒");
            }
            client.enterLocalPassiveMode();
            // 二进制模式:ASCII 模式会在跨平台时改写换行符,把 CRLF 变成 LF ——
            // 对 CSV 无害,对任何带校验的文件都是破坏
            client.setFileType(FTPClient.BINARY_FILE_TYPE);

            java.io.InputStream stream = client.retrieveFileStream(target);
            if (stream == null) {
                throw new IOException("打不开 FTP 文件 %s(%s)"
                        .formatted(target, client.getReplyString().trim()));
            }
            return new java.io.FilterInputStream(stream) {
                @Override
                public void close() throws IOException {
                    super.close();
                    // completePendingCommand 必须调:不调的话控制连接会停在
                    // 一个未完成的传输上,下一次操作直接失败
                    try {
                        client.completePendingCommand();
                    } finally {
                        quietlyDisconnect(client);
                    }
                }
            };
        } catch (IOException e) {
            quietlyDisconnect(client);
            throw e;
        }
    }

    private FTPClient newClient(ConnectionConfig config) {
        FTPClient client = new FTPClient();
        client.setConnectTimeout(config.connectTimeoutMillis());
        client.setDefaultTimeout(config.readTimeoutMillis());
        client.setControlEncoding(config.property("controlEncoding", "UTF-8"));
        return client;
    }

    private void quietlyDisconnect(FTPClient client) {
        try {
            if (client.isConnected()) {
                client.logout();
                client.disconnect();
            }
        } catch (IOException ex) {
            // 断开失败无关紧要,不该掩盖真正的业务异常
            log.trace("FTP 断开连接时出错", ex);
        }
    }

    static String resolvePath(ConnectionConfig config, String path) {
        if (path != null && !path.isBlank()) {
            return path;
        }
        // database 字段在文件型数据源里承载「根目录」
        return config.database() == null || config.database().isBlank() ? "/" : config.database();
    }

    static String joinPath(String parent, String name) {
        if (parent == null || parent.isBlank()) {
            return "/" + name;
        }
        return parent.endsWith("/") ? parent + name : parent + "/" + name;
    }

    private int portOrDefault(ConnectionConfig config) {
        return config.port() != null && config.port() > 0 ? config.port() : DataSourceType.FTP.defaultPort();
    }

    private static String nullToAnonymous(String username) {
        return username == null || username.isBlank() ? "anonymous" : username;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static long elapsed(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }

    /** 供 SFTP 连接器复用的时间转换 */
    static Instant toInstant(long epochSeconds) {
        return Instant.ofEpochSecond(epochSeconds);
    }
}
