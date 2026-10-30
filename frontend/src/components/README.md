# P0 共享组件

这些组件来自 `openspec/changes/frontend-p0-foundation`，页面只组合它们，不重复造状态色、页头、空态和实体跳转。

## PageHeader

```vue
<PageHeader title="字段血缘" description="列级上下游与影响分析">
  <template #actions>
    <el-button>刷新</el-button>
  </template>
</PageHeader>
```

## FilterBar

```vue
<FilterBar>
  <el-input v-model="keyword" placeholder="搜索资产" clearable />
  <el-select v-model="level" placeholder="敏感级别" clearable>
    <el-option label="L4" value="L4" />
  </el-select>
  <template #actions>
    <el-button type="primary">查询</el-button>
  </template>
</FilterBar>
```

## EntityLink

```vue
<EntityLink kind="dataset" id="ds_01..." label="dws_sales_master_0000" />
<EntityLink kind="column" id="col_01..." dataset-id="ds_01..." label="phone" />
```

## StatusTag / SensitivityBadge

```vue
<StatusTag status="SUGGESTED" />
<SensitivityBadge level="L3" category="PII" status="SUGGESTED" />
```

## DetailTabs

```vue
<DetailTabs v-model="tab" :tabs="[
  { key: 'overview', label: '概览' },
  { key: 'schema', label: '字段' },
]">
  <template #overview>...</template>
  <template #schema>...</template>
</DetailTabs>
```

## MetadataPanel / QuerySqlBlock

```vue
<MetadataPanel title="属性" :items="[{ label: '来源', value: source }]" />
<QuerySqlBlock :sql="answer.sql" />
```
