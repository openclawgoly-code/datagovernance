# SuperSonic 50 问评测结果

- 时间：2026-10-29 00:26:49
- 总数：50，PASS：49，PARTIAL：0，FAIL：1
- 通过率：98.0%

| # | 分类 | 数据集 | 问题 | 结果 | 生成 SQL |
|---:|---|---|---|---|---|
| 1 | DWS 聚合 | dws_sales_master_0000 | 按状态统计记录数 | PASS | `with t_36 as (SELECT `cnt`, `status` FROM `synthetic`.`dws_sales_master_0000`) SELECT status, SUM(cnt) AS `_record_count_` FROM t_36 GROUP BY status LIMIT 1000` |
| 2 | DWS 聚合 | dws_sales_master_0000 | 按区域统计总金额 | PASS | `with t_36 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_sales_master_0000`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_36 GROUP BY region LIMIT 1000` |
| 3 | DWS 趋势 | dws_sales_master_0000 | 按日期统计总金额 | PASS | `with t_36 as (SELECT `dt`, `total_amount` FROM `synthetic`.`dws_sales_master_0000`) SELECT dt, SUM(total_amount) AS `_total_amount_` FROM t_36 GROUP BY dt ORDER BY dt LIMIT 1000` |
| 4 | DWS TopN | dws_sales_master_0000 | 总金额最高的3个区域，并返回对应总金额 | PASS | `with t_36 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_sales_master_0000`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_36 GROUP BY region ORDER BY `_total_amount_` DESC LIMIT 3` |
| 5 | DWS 聚合 | dws_order_master_0001 | 按状态统计记录数 | FAIL | `with t_37 as (SELECT `cnt`, `status` FROM `synthetic`.`dws_order_master_0001`) SELECT status, COUNT(cnt) AS `_记录数_` FROM t_37 GROUP BY status LIMIT 1000` |
| 6 | DWS 聚合 | dws_order_master_0001 | 按区域统计总金额 | PASS | `with t_37 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_order_master_0001`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_37 GROUP BY region LIMIT 1000` |
| 7 | DWS 趋势 | dws_order_master_0001 | 按日期统计总金额 | PASS | `with t_37 as (SELECT `dt`, `total_amount` FROM `synthetic`.`dws_order_master_0001`) SELECT dt, SUM(total_amount) AS `_total_amount_` FROM t_37 GROUP BY dt LIMIT 1000` |
| 8 | DWS TopN | dws_order_master_0001 | 总金额最高的3个区域，并返回对应总金额 | PASS | `with t_37 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_order_master_0001`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_37 GROUP BY region ORDER BY `_total_amount_` DESC LIMIT 3` |
| 9 | DWS 聚合 | dws_customer_master_0002 | 按状态统计记录数 | PASS | `with t_38 as (SELECT `cnt`, `status` FROM `synthetic`.`dws_customer_master_0002`) SELECT status, SUM(cnt) AS `_record_count_` FROM t_38 GROUP BY status LIMIT 1000` |
| 10 | DWS 聚合 | dws_customer_master_0002 | 按区域统计总金额 | PASS | `with t_38 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_customer_master_0002`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_38 GROUP BY region LIMIT 1000` |
| 11 | DWS 趋势 | dws_customer_master_0002 | 按日期统计总金额 | PASS | `with t_38 as (SELECT `dt`, `total_amount` FROM `synthetic`.`dws_customer_master_0002`) SELECT dt, SUM(total_amount) AS `_total_amount_` FROM t_38 GROUP BY dt LIMIT 1000` |
| 12 | DWS TopN | dws_customer_master_0002 | 总金额最高的3个区域，并返回对应总金额 | PASS | `with t_38 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_customer_master_0002`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_38 GROUP BY region ORDER BY `_total_amount_` DESC LIMIT 3` |
| 13 | DWS 聚合 | dws_product_master_0003 | 按状态统计记录数 | PASS | `with t_39 as (SELECT `cnt`, `status` FROM `synthetic`.`dws_product_master_0003`) SELECT status, SUM(cnt) AS `_record_count_` FROM t_39 GROUP BY status LIMIT 1000` |
| 14 | DWS 聚合 | dws_product_master_0003 | 按区域统计总金额 | PASS | `with t_39 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_product_master_0003`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_39 GROUP BY region LIMIT 1000` |
| 15 | DWS 趋势 | dws_product_master_0003 | 按日期统计总金额 | PASS | `with t_39 as (SELECT `dt`, `total_amount` FROM `synthetic`.`dws_product_master_0003`) SELECT dt, SUM(total_amount) AS `_total_amount_` FROM t_39 GROUP BY dt LIMIT 1000` |
| 16 | DWS TopN | dws_product_master_0003 | 总金额最高的3个区域，并返回对应总金额 | PASS | `with t_39 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_product_master_0003`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_39 GROUP BY region ORDER BY `_total_amount_` DESC LIMIT 3` |
| 17 | DWS 聚合 | dws_inventory_master_0004 | 按状态统计记录数 | PASS | `with t_40 as (SELECT `cnt`, `status` FROM `synthetic`.`dws_inventory_master_0004`) SELECT status, SUM(cnt) AS `_record_count_` FROM t_40 GROUP BY status LIMIT 1000` |
| 18 | DWS 聚合 | dws_inventory_master_0004 | 按区域统计总金额 | PASS | `with t_40 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_inventory_master_0004`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_40 GROUP BY region LIMIT 1000` |
| 19 | DWS 趋势 | dws_inventory_master_0004 | 按日期统计总金额 | PASS | `with t_40 as (SELECT `dt`, `total_amount` FROM `synthetic`.`dws_inventory_master_0004`) SELECT dt, SUM(total_amount) AS `_total_amount_` FROM t_40 GROUP BY dt LIMIT 1000` |
| 20 | DWS TopN | dws_inventory_master_0004 | 总金额最高的3个区域，并返回对应总金额 | PASS | `with t_40 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_inventory_master_0004`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_40 GROUP BY region ORDER BY `_total_amount_` DESC LIMIT 3` |
| 21 | DWS 聚合 | dws_finance_master_0005 | 按状态统计记录数 | PASS | `with t_41 as (SELECT `cnt`, `status` FROM `synthetic`.`dws_finance_master_0005`) SELECT status, SUM(cnt) AS `_record_count_` FROM t_41 GROUP BY status LIMIT 1000` |
| 22 | DWS 聚合 | dws_finance_master_0005 | 按区域统计总金额 | PASS | `with t_41 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_finance_master_0005`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_41 GROUP BY region LIMIT 1000` |
| 23 | DWS 趋势 | dws_finance_master_0005 | 按日期统计总金额 | PASS | `with t_41 as (SELECT `dt`, `total_amount` FROM `synthetic`.`dws_finance_master_0005`) SELECT dt, SUM(total_amount) AS `_total_amount_` FROM t_41 GROUP BY dt LIMIT 1000` |
| 24 | DWS TopN | dws_finance_master_0005 | 总金额最高的3个区域，并返回对应总金额 | PASS | `with t_41 as (SELECT `total_amount`, `region` FROM `synthetic`.`dws_finance_master_0005`) SELECT region, SUM(total_amount) AS `_total_amount_` FROM t_41 GROUP BY region ORDER BY `_total_amount_` DESC LIMIT 3` |
| 25 | DWD 聚合 | dwd_sales_master_0000 | 按状态统计金额 | PASS | `with t_24 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_sales_master_0000`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_24 GROUP BY status LIMIT 1000` |
| 26 | DWD 聚合 | dwd_sales_master_0000 | 按区域统计数量 | PASS | `with t_24 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_sales_master_0000`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_24 GROUP BY region LIMIT 1000` |
| 27 | DWD 聚合 | dwd_order_master_0001 | 按状态统计金额 | PASS | `with t_25 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_order_master_0001`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_25 GROUP BY status LIMIT 1000` |
| 28 | DWD 聚合 | dwd_order_master_0001 | 按区域统计数量 | PASS | `with t_25 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_order_master_0001`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_25 GROUP BY region LIMIT 1000` |
| 29 | DWD 聚合 | dwd_customer_master_0002 | 按状态统计金额 | PASS | `with t_26 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_customer_master_0002`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_26 GROUP BY status LIMIT 1000` |
| 30 | DWD 聚合 | dwd_customer_master_0002 | 按区域统计数量 | PASS | `with t_26 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_customer_master_0002`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_26 GROUP BY region LIMIT 1000` |
| 31 | DWD 聚合 | dwd_product_master_0003 | 按状态统计金额 | PASS | `with t_27 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_product_master_0003`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_27 GROUP BY status LIMIT 1000` |
| 32 | DWD 聚合 | dwd_product_master_0003 | 按区域统计数量 | PASS | `with t_27 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_product_master_0003`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_27 GROUP BY region LIMIT 1000` |
| 33 | DWD 聚合 | dwd_inventory_master_0004 | 按状态统计金额 | PASS | `with t_28 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_inventory_master_0004`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_28 GROUP BY status LIMIT 1000` |
| 34 | DWD 聚合 | dwd_inventory_master_0004 | 按区域统计数量 | PASS | `with t_28 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_inventory_master_0004`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_28 GROUP BY region LIMIT 1000` |
| 35 | DWD 聚合 | dwd_finance_master_0005 | 按状态统计金额 | PASS | `with t_29 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_finance_master_0005`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_29 GROUP BY status LIMIT 1000` |
| 36 | DWD 聚合 | dwd_finance_master_0005 | 按区域统计数量 | PASS | `with t_29 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_finance_master_0005`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_29 GROUP BY region LIMIT 1000` |
| 37 | DWD 聚合 | dwd_hr_master_0006 | 按状态统计金额 | PASS | `with t_30 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_hr_master_0006`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_30 GROUP BY status LIMIT 1000` |
| 38 | DWD 聚合 | dwd_hr_master_0006 | 按区域统计数量 | PASS | `with t_30 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_hr_master_0006`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_30 GROUP BY region LIMIT 1000` |
| 39 | DWD 聚合 | dwd_supply_master_0007 | 按状态统计金额 | PASS | `with t_31 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_supply_master_0007`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_31 GROUP BY status LIMIT 1000` |
| 40 | DWD 聚合 | dwd_supply_master_0007 | 按区域统计数量 | PASS | `with t_31 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_supply_master_0007`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_31 GROUP BY region LIMIT 1000` |
| 41 | DWD 聚合 | dwd_marketing_master_0008 | 按状态统计金额 | PASS | `with t_32 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_marketing_master_0008`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_32 GROUP BY status LIMIT 1000` |
| 42 | DWD 聚合 | dwd_marketing_master_0008 | 按区域统计数量 | PASS | `with t_32 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_marketing_master_0008`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_32 GROUP BY region LIMIT 1000` |
| 43 | DWD 聚合 | dwd_service_master_0009 | 按状态统计金额 | PASS | `with t_33 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_service_master_0009`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_33 GROUP BY status LIMIT 1000` |
| 44 | DWD 聚合 | dwd_service_master_0009 | 按区域统计数量 | PASS | `with t_33 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_service_master_0009`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_33 GROUP BY region LIMIT 1000` |
| 45 | DWD 聚合 | dwd_risk_master_0010 | 按状态统计金额 | PASS | `with t_34 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_risk_master_0010`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_34 GROUP BY status LIMIT 1000` |
| 46 | DWD 聚合 | dwd_risk_master_0010 | 按区域统计数量 | PASS | `with t_34 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_risk_master_0010`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_34 GROUP BY region LIMIT 1000` |
| 47 | DWD 聚合 | dwd_compliance_master_0011 | 按状态统计金额 | PASS | `with t_35 as (SELECT `amount`, `status` FROM `synthetic`.`dwd_compliance_master_0011`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_35 GROUP BY status LIMIT 1000` |
| 48 | DWD 聚合 | dwd_compliance_master_0011 | 按区域统计数量 | PASS | `with t_35 as (SELECT `quantity`, `region` FROM `synthetic`.`dwd_compliance_master_0011`) SELECT region, SUM(quantity) AS `_total_quantity_` FROM t_35 GROUP BY region LIMIT 1000` |
| 49 | ODS 明细 | ods_order_master_0001 | 按状态统计金额 | PASS | `with t_42 as (SELECT `amount`, `status` FROM `synthetic`.`ods_order_master_0001`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_42 GROUP BY status LIMIT 1000` |
| 50 | ODS 明细 | ods_product_master_0003 | 按状态统计金额 | PASS | `with t_43 as (SELECT `amount`, `status` FROM `synthetic`.`ods_product_master_0003`) SELECT status, SUM(amount) AS `_total_amount_` FROM t_43 GROUP BY status LIMIT 1000` |

## 失败/部分通过明细

### 5. 按状态统计记录数（dws_order_master_0001）
- 结果：FAIL
- 生成 SQL：`with t_37 as (SELECT `cnt`, `status` FROM `synthetic`.`dws_order_master_0001`) SELECT status, COUNT(cnt) AS `_记录数_` FROM t_37 GROUP BY status LIMIT 1000`
- 错误：value mismatch
- 期望：{"ACTIVE": 307.0, "INACTIVE": 334.0, "PENDING": 359.0}
- 实际：{"INACTIVE": 31.0, "ACTIVE": 31.0, "PENDING": 31.0}
