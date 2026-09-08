package com.datagov.data.connector.file;

import com.datagov.common.error.ErrorCode;
import com.datagov.data.connector.ConnectorExceptions;
import com.datagov.data.spi.ConnectionConfig;
import com.datagov.data.spi.ConnectivityResult;
import com.datagov.data.spi.ConnectorCapabilities;
import com.datagov.data.spi.DataSourceType;
import com.datagov.data.spi.FileCatalogReader;
import com.datagov.data.spi.catalog.CatalogModel.FileEntry;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Vector;

/**
 * SFTP 连接器(功能 2)。
 *
 * <p><b>关于 StrictHostKeyChecking</b>:默认关闭,可通过扩展参数
 * {@code strictHostKeyChecking=yes} 打开。这是一个明确的安全权衡:
 * 打开它需要平台维护 known_hosts 并在每次新增数据源时人工录入主机指纹,
 * P1 阶段没有这套运维流程,强行打开会让功能不可用;关闭它则意味着
 * 无法防御中间人攻击。
 *
 * <p>因此把它做成<b>可配置且默认宽松</b>,并在此明确记录:面向公网的 SFTP
 * 数据源应当在扩展参数里打开该选项。P4 的 Governance 应把"是否启用主机密钥
 * 校验"纳入数据源合规检查项。
 */
@Component
public class SftpConnector implements FileCatalogReader {

    private static final Logger log = LoggerFactory.getLogger(SftpConnector.class);

    @Override
    public Set<DataSourceType> supportedTypes() {
        return Set.of(DataSourceType.SFTP);
    }

    @Override
    public ConnectorCapabilities capabilities(DataSourceType type) {
        return ConnectorCapabilities.fileBased();
    }

    @Override
    public ConnectivityResult testConnection(DataSourceType type, ConnectionConfig config) {
        long startedAt = System.nanoTime();
        Session session = null;
        try {
            session = openSession(config);
            ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(config.connectTimeoutMillis());
            String version = "SFTP protocol " + channel.getServerVersion();
            channel.disconnect();
            return ConnectivityResult.success(elapsed(startedAt), version);

        } catch (JSchException | SftpException ex) {
            ErrorCode code = classifyJSch(ex);
            log.debug("SFTP 连通性测试失败 target={}", config.masked());
            return ConnectivityResult.failure(code, elapsed(startedAt),
                    ConnectorExceptions.userMessage(code, config.host(), config.port()),
                    ex.getClass().getSimpleName() + ": " + ex.getMessage());
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<FileEntry> listEntries(DataSourceType type, ConnectionConfig config, String path) {
        String target = FtpConnector.resolvePath(config, path);
        Session session = null;
        ChannelSftp channel = null;
        try {
            session = openSession(config);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(config.connectTimeoutMillis());

            // ls 一个文件返回的是它自己那一条,与 ls 一个目录的结果分不出来 ——
            // 与 FTP 同源的问题,后果也一样:给每一条拼路径时多拼一层。
            // SFTP 有 stat,直接问就行,不必像 FTP 那样靠 CWD 试探。
            boolean targetIsDirectory = channel.stat(target).isDir();

            List<FileEntry> entries = new ArrayList<>();
            Vector<ChannelSftp.LsEntry> listing = channel.ls(target);
            for (ChannelSftp.LsEntry entry : listing) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                entries.add(new FileEntry(
                        name,
                        targetIsDirectory ? FtpConnector.joinPath(target, name) : target,
                        entry.getAttrs().isDir(),
                        entry.getAttrs().getSize(),
                        Instant.ofEpochSecond(entry.getAttrs().getMTime())));
            }
            return entries;

        } catch (JSchException | SftpException ex) {
            throw ConnectorExceptions.introspectFailed("列出 SFTP 目录 " + target, ex);
        } finally {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    /**
     * 打开文件读取(功能 12)。
     *
     * <p>返回的流持有 session 与 channel:关流时才一并断开。提前断开会让读到
     * 一半的流突然失效。
     */
    @Override
    public java.io.InputStream openFile(DataSourceType type, ConnectionConfig config, String path)
            throws java.io.IOException {
        String target = FtpConnector.resolvePath(config, path);
        Session session = null;
        ChannelSftp channel = null;
        try {
            session = openSession(config);
            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(config.connectTimeoutMillis());

            java.io.InputStream stream = channel.get(target);
            Session finalSession = session;
            ChannelSftp finalChannel = channel;
            return new java.io.FilterInputStream(stream) {
                @Override
                public void close() throws java.io.IOException {
                    super.close();
                    if (finalChannel.isConnected()) {
                        finalChannel.disconnect();
                    }
                    if (finalSession.isConnected()) {
                        finalSession.disconnect();
                    }
                }
            };
        } catch (JSchException | SftpException e) {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
            throw new java.io.IOException("打不开 SFTP 文件 " + target, e);
        }
    }

    private Session openSession(ConnectionConfig config) throws JSchException {
        JSch jsch = new JSch();
        int port = config.port() != null && config.port() > 0
                ? config.port() : DataSourceType.SFTP.defaultPort();

        Session session = jsch.getSession(config.username(), config.host(), port);
        if (config.password() != null) {
            session.setPassword(config.password());
        }
        session.setConfig("StrictHostKeyChecking",
                config.property("strictHostKeyChecking", "no"));
        session.setTimeout(config.readTimeoutMillis());
        session.connect(config.connectTimeoutMillis());
        return session;
    }

    /**
     * JSch 不提供结构化的错误码,只能看消息 —— 这是该库的局限,不是本项目的选择。
     * 只区分"认证失败"与"连不上"两大类,再细分不可靠。
     */
    private static ErrorCode classifyJSch(Exception ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (message.contains("auth")) {
            return ErrorCode.DAT_AUTH_FAILED;
        }
        if (message.contains("timeout")) {
            return ErrorCode.DAT_TIMEOUT;
        }
        return ErrorCode.DAT_CONNECT_FAILED;
    }

    private static long elapsed(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000L;
    }
}
