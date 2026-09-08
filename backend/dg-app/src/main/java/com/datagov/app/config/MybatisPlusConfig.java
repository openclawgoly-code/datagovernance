package com.datagov.app.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 装配。
 *
 * <p><b>分页插件不是可选项。</b> 没有它时 {@code selectPage} 不会报错,
 * 而是<b>静默返回全表数据</b> —— 一个几万行的数据源列表会被整个读进内存,
 * 并且 total 字段还是对的,所以从接口响应上完全看不出问题。
 * 这类"错得很安静"的配置遗漏值得单独写一个类并留下注释。
 */
@Configuration
@MapperScan({"com.datagov.platform.mapper", "com.datagov.metadata.mapper"})
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.POSTGRE_SQL);
        // 单页上限,防止前端传 size=999999 把库拖垮
        pagination.setMaxLimit(500L);
        // 超出最大页后返回空而不是回到第一页 —— 回到第一页会让分页遍历陷入死循环
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);

        return interceptor;
    }
}
