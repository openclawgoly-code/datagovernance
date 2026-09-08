-- ═══════════════════════════════════════════════════════════════════════
-- V8 · 版本历史表允许同一版本有多条事件
--
-- 两张版本历史表原本都建了 UNIQUE (对象ID, version)。那个约束隐含一个错误的
-- 假设:「一个版本只会产生一条历史记录」。
--
-- 实际不成立 —— 版本号只在<b>定义内容</b>变更时 +1,而历史表记录的是<b>事件</b>,
-- 状态变更(发布、停用、启用、归档)不改内容也就不改版本号。于是:
--
--   v1 CREATED  → 插入成功
--   v1 PUBLISHED → 唯一约束冲突,整个发布操作回滚
--
-- 症状是「新建的任务能编译,一点发布就 500」。数据源那边同样:
-- 停用之后再启用会撞上同一个约束。P1 的验收脚本没有覆盖「停用后再启用」
-- 这条路径,所以它一直潜伏着 —— 是 P2 的发布流程先撞上来的。
--
-- 修法是去掉唯一性,保留普通索引。「某个版本的快照」仍然可查:取该版本
-- 最后一条即可,而这恰恰比原来更准确 —— 它给出的是那一版最终的样子。
-- ═══════════════════════════════════════════════════════════════════════

DROP INDEX IF EXISTS uk_md_ds_version;
CREATE INDEX idx_md_ds_version ON md_datasource_version (datasource_id, version DESC, changed_at DESC);

ALTER TABLE ctl_job_definition_version DROP CONSTRAINT IF EXISTS uk_ctl_job_version;
CREATE INDEX idx_ctl_job_version_event
  ON ctl_job_definition_version (job_definition_id, version DESC, changed_at DESC);
