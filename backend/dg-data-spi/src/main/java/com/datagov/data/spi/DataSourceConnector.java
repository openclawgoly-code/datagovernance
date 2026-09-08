package com.datagov.data.spi;

import java.util.Set;

/**
 * 连接器基础契约 —— 所有数据源类型都必须实现的最小能力。
 *
 * <p>能力按接口切分而非塞进一个胖接口: FTP 连接器不该被迫实现
 * {@code listColumns} 再抛 UnsupportedOperationException。
 * 需要更多能力时实现 {@link RelationalCatalogReader} 或 {@link FileCatalogReader}。
 *
 * <p><b>实现约束</b>: 实现类必须是无状态的,连接在方法内部开启并保证关闭。
 * 连接器不得缓存 {@link ConnectionConfig} —— 里面有明文密码。
 */
public interface DataSourceConnector {

    /** 本连接器负责的类型。一个实现可覆盖多个类型(如 Doris 与 StarRocks)。 */
    Set<DataSourceType> supportedTypes();

    /** 声明指定类型的能力,供 UI 与 Metadata 决策。 */
    ConnectorCapabilities capabilities(DataSourceType type);

    /**
     * 连通性测试。
     *
     * <p><b>不得抛出异常</b> —— 连接失败是这个方法的正常返回值之一,
     * 应封装为 {@link ConnectivityResult#failure}。只有编程错误才允许逃逸。
     * 这样调用方无需 try/catch 就能把结果直接呈现给用户。
     */
    ConnectivityResult testConnection(DataSourceType type, ConnectionConfig config);
}
