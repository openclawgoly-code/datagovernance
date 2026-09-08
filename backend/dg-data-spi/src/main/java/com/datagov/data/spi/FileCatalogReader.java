package com.datagov.data.spi;

import com.datagov.data.spi.catalog.CatalogModel.FileEntry;

import java.util.List;

/**
 * 文件型数据源的目录浏览能力(功能 2: FTP / SFTP)。
 */
public interface FileCatalogReader extends DataSourceConnector {

    /**
     * 列出指定目录下的条目。
     *
     * @param path 绝对或相对于连接根目录的路径;null 或空表示根目录
     */
    List<FileEntry> listEntries(DataSourceType type, ConnectionConfig config, String path);

    /**
     * 打开一个文件读取(功能 12 文件解析入库)。
     *
     * <p><b>返回流而不是字节数组</b>:待解析的文件可能有几百万行,一次读进内存
     * 会把执行器的堆吃光 —— 而那不只影响这一个任务,同进程的其它执行会一起 OOM。
     *
     * <p>调用方负责关闭。实现方在流关闭时才断开底层连接 —— 提前断开会让读到
     * 一半的流突然失效。
     */
    java.io.InputStream openFile(DataSourceType type, ConnectionConfig config, String path)
            throws java.io.IOException;
}
