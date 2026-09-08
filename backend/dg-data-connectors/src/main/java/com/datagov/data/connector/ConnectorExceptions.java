package com.datagov.data.connector;

import com.datagov.common.error.BizException;
import com.datagov.common.error.ErrorCode;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Set;

/**
 * 把驱动层异常翻译成平台错误码。
 *
 * <p><b>为什么按 SQLState 判定而不是匹配异常文本</b>: 异常消息会随驱动版本、
 * 语言环境(中文 JDK 下 Oracle 会返回中文错误)甚至服务端配置而变。
 * 匹配文本的代码上线时能跑,换个驱动小版本就静默失效 —— 而且失效方式是
 * "全部归类为未知错误",没人会立刻发现。
 *
 * <p>SQLState 是 SQL 标准定义的 5 字符码,前两位是类别:
 * <ul>
 *   <li>{@code 08} 连接异常 —— 网络不通、拒绝连接</li>
 *   <li>{@code 28} 授权异常 —— 用户名口令错误</li>
 *   <li>{@code 3D} 库不存在</li>
 *   <li>{@code 57} 操作介入 —— 服务端主动断开</li>
 * </ul>
 *
 * <p>区分这几类的实际价值: 用户看到"认证失败"会去改口令,看到"网络不通"
 * 会去找网管。只回一句"连接失败"等于把排障成本全部转嫁给用户。
 */
public final class ConnectorExceptions {

    /** 连接类异常的 SQLState 类别 */
    private static final Set<String> CONNECTION_CLASSES = Set.of("08");
    /** 授权类异常的 SQLState 类别 */
    private static final Set<String> AUTH_CLASSES = Set.of("28");

    private ConnectorExceptions() {
    }

    /**
     * 把 {@link SQLException} 归类为平台错误码。
     *
     * <p>判定顺序: 异常类型 → SQLState 类别 → 厂商错误码 → 兜底。
     * 前面的判据更可靠,所以先用。
     */
    public static ErrorCode classify(SQLException ex) {
        // 1. JDBC 4 的类型化异常最可靠 —— 驱动已经替我们分好类了
        if (ex instanceof SQLInvalidAuthorizationSpecException) {
            return ErrorCode.DAT_AUTH_FAILED;
        }
        if (ex instanceof SQLTimeoutException) {
            return ErrorCode.DAT_TIMEOUT;
        }
        if (ex instanceof SQLTransientConnectionException) {
            return ErrorCode.DAT_CONNECT_FAILED;
        }

        // 2. SQLState 类别
        String state = ex.getSQLState();
        if (state != null && state.length() >= 2) {
            String category = state.substring(0, 2);
            if (AUTH_CLASSES.contains(category)) {
                return ErrorCode.DAT_AUTH_FAILED;
            }
            if (CONNECTION_CLASSES.contains(category)) {
                return ErrorCode.DAT_CONNECT_FAILED;
            }
        }

        // 3. 厂商错误码 —— 几个高频且各家不遵守 SQLState 的情况
        ErrorCode byVendor = classifyByVendorCode(ex.getErrorCode());
        if (byVendor != null) {
            return byVendor;
        }

        // 4. 底层原因里若含超时/主机不可达,按网络问题归类
        for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException) {
                return ErrorCode.DAT_TIMEOUT;
            }
            if (cause instanceof UnknownHostException) {
                return ErrorCode.DAT_CONNECT_FAILED;
            }
        }

        return ErrorCode.DAT_CONNECT_FAILED;
    }

    /**
     * 厂商私有错误码。只收录确实不走标准 SQLState、且高频到值得硬编码的几个。
     * 不追求覆盖全部 —— 覆盖不全时兜底到"连接失败"是可接受的降级。
     */
    private static ErrorCode classifyByVendorCode(int vendorCode) {
        return switch (vendorCode) {
            // MySQL: 1045 拒绝访问(口令错), 1044 拒绝访问库, 1049 未知库
            case 1045, 1044 -> ErrorCode.DAT_AUTH_FAILED;
            // Oracle: ORA-01017 用户名/口令无效, ORA-28000 账户被锁
            case 1017, 28000 -> ErrorCode.DAT_AUTH_FAILED;
            // Oracle: ORA-12541 无监听, ORA-12170 连接超时
            case 12541 -> ErrorCode.DAT_CONNECT_FAILED;
            case 12170 -> ErrorCode.DAT_TIMEOUT;
            default -> null;
        };
    }

    /** 文件类连接器(FTP/SFTP)的异常归类。 */
    public static ErrorCode classify(IOException ex) {
        if (ex instanceof SocketTimeoutException) {
            return ErrorCode.DAT_TIMEOUT;
        }
        if (ex instanceof UnknownHostException) {
            return ErrorCode.DAT_CONNECT_FAILED;
        }
        return ErrorCode.DAT_CONNECT_FAILED;
    }

    /** 驱动缺失。P1 各驱动都在 classpath 上,但信创环境常需要现场替换驱动 jar。 */
    public static BizException driverMissing(String driverClassName, Throwable cause) {
        return new BizException(ErrorCode.DAT_DRIVER_MISSING,
                "驱动未安装: " + driverClassName,
                "请将对应的 JDBC 驱动放入运行时 classpath 后重启", cause);
    }

    /** 结构探测失败。 */
    public static BizException introspectFailed(String what, Throwable cause) {
        return new BizException(ErrorCode.DAT_INTROSPECT_FAILED,
                "读取结构失败: " + what,
                cause == null ? null : cause.getMessage(), cause);
    }

    /**
     * 组装给用户看的一句话结论。
     *
     * <p>刻意不把原始异常消息拼进来 —— 那里面可能有连接串甚至口令。
     * 原始信息放在 {@code detail} 字段,由前端折叠在"详情"里。
     */
    public static String userMessage(ErrorCode code, String host, Integer port) {
        String target = host == null ? "目标数据源" : host + (port == null ? "" : ":" + port);
        return switch (code) {
            case DAT_AUTH_FAILED -> "认证失败: " + target + " 拒绝了所提供的用户名或口令";
            case DAT_TIMEOUT -> "连接超时: " + target + " 在超时时间内未响应";
            case DAT_DRIVER_MISSING -> "驱动未安装,无法连接 " + target;
            case DAT_INTROSPECT_FAILED -> "已连通 " + target + ",但读取库表结构失败";
            default -> "无法连接 " + target + ",请检查网络、端口与防火墙";
        };
    }
}
