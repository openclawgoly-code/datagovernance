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
}
