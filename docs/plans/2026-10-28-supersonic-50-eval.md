# SuperSonic 50 问评测结果

- 时间：2026-10-28 06:32:48
- 总数：50，PASS：50，PARTIAL：0，FAIL：0
- 通过率：100.0%

| # | 分类 | 数据集 | 问题 | 结果 | 生成 SQL |
|---:|---|---|---|---|---|
| 1 | DWS 聚合 | dws_sales_master_0000 | 按状态统计记录数 | PASS | `with t_4 as (SELECT `cnt`, `status` FROM `synthetic`.`dws_sales_master_0000`) SELECT status, SUM(cnt) AS `_record_count_` FROM t_4 GROUP BY status LIMIT 1000` |
| 2 | DWS 聚合 | dws_sales_master_0000 | 按区域统计总金额 | PASS | `with t_4 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_sales_master_0000`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_4 GROUP BY region LIMIT 1000` |
| 3 | DWS 趋势 | dws_sales_master_0000 | 按日期统计总金额 | PASS | `with t_4 as (SELECT `dt`, `total_amount` FROM `synthetic`.`dws_sales_master_0000`) SELECT dt, SUM(total_amount) AS `_total_amount_` FROM t_4 GROUP BY dt LIMIT 1000` |
| 4 | DWS TopN | dws_sales_master_0000 | 总金额最高的3个区域，并返回对应总金额 | PASS | `with t_4 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_sales_master_0000`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_4 GROUP BY region ORDER BY `_total_amount_` DESC LIMIT 3` |
| 5 | DWS 聚合 | dws_order_master_0001 | 按状态统计记录数 | PASS | `with t_17 as (SELECT `cnt`, `status` FROM `synthetic`.`dws_order_master_0001`) SELECT status, SUM(cnt) AS `_record_count_` FROM t_17 GROUP BY status LIMIT 1000` |
| 6 | DWS 聚合 | dws_order_master_0001 | 按区域统计总金额 | PASS | `with t_17 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_order_master_0001`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_17 GROUP BY region LIMIT 1000` |
| 7 | DWS 趋势 | dws_order_master_0001 | 按日期统计总金额 | PASS | `with t_17 as (SELECT `dt`, `total_amount` FROM `synthetic`.`dws_order_master_0001`) SELECT dt, SUM(total_amount) AS `_total_amount_` FROM t_17 GROUP BY dt LIMIT 1000` |
| 8 | DWS TopN | dws_order_master_0001 | 总金额最高的3个区域，并返回对应总金额 | PASS | `with t_17 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_order_master_0001`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_17 GROUP BY region ORDER BY `_total_amount_` DESC LIMIT 3` |
| 9 | DWS 聚合 | dws_customer_master_0002 | 按状态统计记录数 | PASS | `with t_18 as (SELECT `cnt`, `status` FROM `synthetic`.`dws_customer_master_0002`) SELECT status, SUM(cnt) AS `_record_count_` FROM t_18 GROUP BY status LIMIT 1000` |
| 10 | DWS 聚合 | dws_customer_master_0002 | 按区域统计总金额 | PASS | `with t_18 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_customer_master_0002`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_18 GROUP BY region LIMIT 1000` |
| 11 | DWS 趋势 | dws_customer_master_0002 | 按日期统计总金额 | PASS | `with t_18 as (SELECT `dt`, `total_amount` FROM `synthetic`.`dws_customer_master_0002`) SELECT dt, SUM(total_amount) AS `_total_amount_` FROM t_18 GROUP BY dt LIMIT 1000` |
| 12 | DWS TopN | dws_customer_master_0002 | 总金额最高的3个区域，并返回对应总金额 | PASS | `with t_18 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_customer_master_0002`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_18 GROUP BY region ORDER BY `_total_amount_` DESC LIMIT 3` |
| 13 | DWS 聚合 | dws_product_master_0003 | 按状态统计记录数 | PASS | `with t_19 as (SELECT `cnt`, `status` FROM `synthetic`.`dws_product_master_0003`) SELECT status, SUM(cnt) AS `_record_count_` FROM t_19 GROUP BY status LIMIT 1000` |
| 14 | DWS 聚合 | dws_product_master_0003 | 按区域统计总金额 | PASS | `with t_19 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_product_master_0003`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_19 GROUP BY region LIMIT 1000` |
| 15 | DWS 趋势 | dws_product_master_0003 | 按日期统计总金额 | PASS | `with t_19 as (SELECT `dt`, `total_amount` FROM `synthetic`.`dws_product_master_0003`) SELECT dt, SUM(total_amount) AS `_total_amount_` FROM t_19 GROUP BY dt LIMIT 1000` |
| 16 | DWS TopN | dws_product_master_0003 | 总金额最高的3个区域，并返回对应总金额 | PASS | `with t_19 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_product_master_0003`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_19 GROUP BY region ORDER BY `_total_amount_` DESC LIMIT 3` |
| 17 | DWS 聚合 | dws_inventory_master_0004 | 按状态统计记录数 | PASS | `with t_20 as (SELECT `cnt`, `status` FROM `synthetic`.`dws_inventory_master_0004`) SELECT status, SUM(cnt) AS `_record_count_` FROM t_20 GROUP BY status LIMIT 1000` |
| 18 | DWS 聚合 | dws_inventory_master_0004 | 按区域统计总金额 | PASS | `with t_20 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_inventory_master_0004`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_20 GROUP BY region LIMIT 1000` |
| 19 | DWS 趋势 | dws_inventory_master_0004 | 按日期统计总金额 | PASS | `with t_20 as (SELECT `dt`, `total_amount` FROM `synthetic`.`dws_inventory_master_0004`) SELECT dt, SUM(total_amount) AS `_total_amount_` FROM t_20 GROUP BY dt ORDER BY dt LIMIT 1000` |
| 20 | DWS TopN | dws_inventory_master_0004 | 总金额最高的3个区域，并返回对应总金额 | PASS | `with t_20 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_inventory_master_0004`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_20 GROUP BY region ORDER BY `_total_amount_` DESC LIMIT 3` |
| 21 | DWS 聚合 | dws_finance_master_0005 | 按状态统计记录数 | PASS | `with t_21 as (SELECT `cnt`, `status` FROM `synthetic`.`dws_finance_master_0005`) SELECT status, SUM(cnt) AS `_record_count_` FROM t_21 GROUP BY status LIMIT 1000` |
| 22 | DWS 聚合 | dws_finance_master_0005 | 按区域统计总金额 | PASS | `with t_21 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_finance_master_0005`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_21 GROUP BY region LIMIT 1000` |
| 23 | DWS 趋势 | dws_finance_master_0005 | 按日期统计总金额 | PASS | `with t_21 as (SELECT `dt`, `total_amount` FROM `synthetic`.`dws_finance_master_0005`) SELECT dt, SUM(total_amount) AS `_total_amount_` FROM t_21 GROUP BY dt ORDER BY dt LIMIT 1000` |
| 24 | DWS TopN | dws_finance_master_0005 | 总金额最高的3个区域，并返回对应总金额 | PASS | `with t_21 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_finance_master_0005`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_21 GROUP BY region ORDER BY `_total_amount_` DESC LIMIT 3` |
| 25 | DWD 聚合 | dwd_sales_master_0000 | 按状态统计金额 | PASS | `with t_5 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_sales_master_0000`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_5 GROUP BY status LIMIT 1000` |
| 26 | DWD 聚合 | dwd_sales_master_0000 | 按区域统计数量 | PASS | `with t_5 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_sales_master_0000`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_5 GROUP BY region LIMIT 1000` |
| 27 | DWD 聚合 | dwd_order_master_0001 | 按状态统计金额 | PASS | `with t_6 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_order_master_0001`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_6 GROUP BY status LIMIT 1000` |
| 28 | DWD 聚合 | dwd_order_master_0001 | 按区域统计数量 | PASS | `with t_6 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_order_master_0001`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_6 GROUP BY region LIMIT 1000` |
| 29 | DWD 聚合 | dwd_customer_master_0002 | 按状态统计金额 | PASS | `with t_7 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_customer_master_0002`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_7 GROUP BY status LIMIT 1000` |
| 30 | DWD 聚合 | dwd_customer_master_0002 | 按区域统计数量 | PASS | `with t_7 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_customer_master_0002`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_7 GROUP BY region LIMIT 1000` |
| 31 | DWD 聚合 | dwd_product_master_0003 | 按状态统计金额 | PASS | `with t_8 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_product_master_0003`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_8 GROUP BY status LIMIT 1000` |
| 32 | DWD 聚合 | dwd_product_master_0003 | 按区域统计数量 | PASS | `with t_8 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_product_master_0003`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_8 GROUP BY region LIMIT 1000` |
| 33 | DWD 聚合 | dwd_inventory_master_0004 | 按状态统计金额 | PASS | `with t_9 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_inventory_master_0004`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_9 GROUP BY status LIMIT 1000` |
| 34 | DWD 聚合 | dwd_inventory_master_0004 | 按区域统计数量 | PASS | `with t_9 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_inventory_master_0004`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_9 GROUP BY region LIMIT 1000` |
| 35 | DWD 聚合 | dwd_finance_master_0005 | 按状态统计金额 | PASS | `with t_10 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_finance_master_0005`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_10 GROUP BY status LIMIT 1000` |
| 36 | DWD 聚合 | dwd_finance_master_0005 | 按区域统计数量 | PASS | `with t_10 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_finance_master_0005`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_10 GROUP BY region LIMIT 1000` |
| 37 | DWD 聚合 | dwd_hr_master_0006 | 按状态统计金额 | PASS | `with t_11 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_hr_master_0006`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_11 GROUP BY status LIMIT 1000` |
| 38 | DWD 聚合 | dwd_hr_master_0006 | 按区域统计数量 | PASS | `with t_11 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_hr_master_0006`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_11 GROUP BY region LIMIT 1000` |
| 39 | DWD 聚合 | dwd_supply_master_0007 | 按状态统计金额 | PASS | `with t_12 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_supply_master_0007`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_12 GROUP BY status LIMIT 1000` |
| 40 | DWD 聚合 | dwd_supply_master_0007 | 按区域统计数量 | PASS | `with t_12 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_supply_master_0007`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_12 GROUP BY region LIMIT 1000` |
| 41 | DWD 聚合 | dwd_marketing_master_0008 | 按状态统计金额 | PASS | `with t_13 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_marketing_master_0008`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_13 GROUP BY status LIMIT 1000` |
| 42 | DWD 聚合 | dwd_marketing_master_0008 | 按区域统计数量 | PASS | `with t_13 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_marketing_master_0008`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_13 GROUP BY region LIMIT 1000` |
| 43 | DWD 聚合 | dwd_service_master_0009 | 按状态统计金额 | PASS | `with t_14 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_service_master_0009`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_14 GROUP BY status LIMIT 1000` |
| 44 | DWD 聚合 | dwd_service_master_0009 | 按区域统计数量 | PASS | `with t_14 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_service_master_0009`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_14 GROUP BY region LIMIT 1000` |
| 45 | DWD 聚合 | dwd_risk_master_0010 | 按状态统计金额 | PASS | `with t_15 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_risk_master_0010`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_15 GROUP BY status LIMIT 1000` |
| 46 | DWD 聚合 | dwd_risk_master_0010 | 按区域统计数量 | PASS | `with t_15 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_risk_master_0010`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_15 GROUP BY region LIMIT 1000` |
| 47 | DWD 聚合 | dwd_compliance_master_0011 | 按状态统计金额 | PASS | `with t_16 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_compliance_master_0011`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_16 GROUP BY status LIMIT 1000` |
| 48 | DWD 聚合 | dwd_compliance_master_0011 | 按区域统计数量 | PASS | `with t_16 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_compliance_master_0011`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_16 GROUP BY region LIMIT 1000` |
| 49 | ODS 明细 | ods_order_master_0001 | 按状态统计金额 | PASS | `with t_22 as (SELECT `amount`, `status` FROM `synthetic`.`ods_order_master_0001`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_22 GROUP BY status LIMIT 1000` |
| 50 | ODS 明细 | ods_product_master_0003 | 按状态统计金额 | PASS | `with t_23 as (SELECT `amount`, `status` FROM `synthetic`.`ods_product_master_0003`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_23 GROUP BY status LIMIT 1000` |

## 失败/部分通过明细

无。
