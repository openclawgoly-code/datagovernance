package com.datagov.metadata.domain;

import com.datagov.data.spi.DataSourceType;

import java.util.List;

/**
 * 厂商预设。
 *
 * <p>泛微、金蝶、用友不是数据库类型 —— 它们的产品跑在 SQL Server / Oracle /
 * MySQL / PostgreSQL / Db2 / 达梦 / 金仓上。把厂商做成 {@link DataSourceType}
 * 会让连接器矩阵按“客户买了哪家 ERP”爆炸;做成 profile 则只影响表单默认值、
 * 系统表过滤和排查提示,底层仍然是标准 JDBC 连接。
 *
 * <p>{@link #suggestedTypes} 只是建议,不是白名单:同一个产品版本在不同客户
 * 现场可能跑在不同数据库上,最终以客户实际库为准。
 */
public enum VendorProfile {

    GENERIC("通用", List.of(), null),

    WEAVER_ECOLOGY("泛微 e-cology",
            List.of(DataSourceType.SQLSERVER, DataSourceType.ORACLE,
                    DataSourceType.MYSQL, DataSourceType.DAMENG, DataSourceType.KINGBASE),
            "https://www.weaver.com.cn/subpage/aboutus/news/news-detail.html?id=23240"),

    WEAVER_EOFFICE("泛微 e-office",
            List.of(DataSourceType.MYSQL),
            "https://www.weaver.com.cn/"),

    KINGDEE_EAS("金蝶 EAS",
            List.of(DataSourceType.ORACLE, DataSourceType.SQLSERVER, DataSourceType.DB2,
                    DataSourceType.MYSQL, DataSourceType.POSTGRESQL, DataSourceType.DAMENG),
            "https://vip.kingdee.com/eascloud/help/8.6hybrid/BAS.web/topics/noname23.html"),

    KINGDEE_K3CLOUD("金蝶 K/3 Cloud / 云星空",
            List.of(DataSourceType.SQLSERVER, DataSourceType.ORACLE),
            "https://vip.kingdee.com/"),

    YONYOU_NC("用友 NC / NCC",
            List.of(DataSourceType.ORACLE, DataSourceType.SQLSERVER, DataSourceType.DB2),
            "https://www.yonyou.com/"),

    YONYOU_U8("用友 U8 / U8+",
            List.of(DataSourceType.SQLSERVER, DataSourceType.ORACLE, DataSourceType.DAMENG,
                    DataSourceType.KINGBASE, DataSourceType.POSTGRESQL),
            "https://www.yonyou.com/"),

    YONYOU_YONBIP("用友 YonBIP",
            List.of(DataSourceType.ORACLE, DataSourceType.MYSQL, DataSourceType.POSTGRESQL),
            "https://www.yonyou.com/");

    private final String displayName;
    private final List<DataSourceType> suggestedTypes;
    private final String docUrl;

    VendorProfile(String displayName, List<DataSourceType> suggestedTypes, String docUrl) {
        this.displayName = displayName;
        this.suggestedTypes = List.copyOf(suggestedTypes);
        this.docUrl = docUrl;
    }

    public String displayName() {
        return displayName;
    }

    public List<DataSourceType> suggestedTypes() {
        return suggestedTypes;
    }

    public String docUrl() {
        return docUrl;
    }
}
