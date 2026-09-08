# 数据治理平台

按 **Space Model** 推导出模块边界的数据治理平台。当前处于 **P1** 阶段。

> **术语警示**:本平台功能菜单中的「**空间**」是**租户**(代码里一律叫 `Workspace`),
> 而架构文档中的「**Space**」是**边界**概念。二者同名不同物,阅读与沟通时务必区分。
> 完整术语表见 [`SPACE-MODEL.md`](./SPACE-MODEL.md)。

## 这个仓库和"按菜单拆模块"有什么不同

原始需求是一份 35 项功能清单,分数据集成 / 数据开发 / 运维监控 / 数据服务四个子系统。
直接按菜单拆模块会得到错误的边界。三个可从需求文本本身证明的反例:

1. **「基础配置」菜单横跨三个 Space**。序号 28-33 同在一个菜单下,但 28/29/30(空间、
   角色、用户)属 Platform,31/32(Flink 执行器、平台级 JAR)属 **Runtime** —— 它们是
   执行器池与制品仓库,不是配置项 —— 33(告警渠道)属 **Governance**。按菜单建模会产出
   一个杂物袋模块,还会把 Runtime 资源管理错放进配置层。
   代码中 `pf_permission.owner_space` 列就是这条结论的落地。

2. **五处「执行记录」必须收敛为一个 Execution 模型**。序号 10/15/19/21/23 分属五个模块,
   但 24(任务监控)、25(告警规则)、27(审计日志)三条需求各自独立地要求跨子系统聚合。
   若每个模块各建一张执行记录表,这三项都做不出来。

3. **规则管理(17)是双栖对象**。清洗与转换规则若硬编码进同步算子,规则管理页面就形同
   虚设。必须是 Rule DSL(Metadata 拥有)+ 运行时解释器(Runtime 拥有)。

完整推导见 [`SPACE-MODEL.md`](./SPACE-MODEL.md),35 项功能的归属见
[`docs/function-space-matrix.md`](./docs/function-space-matrix.md)。

## 模块结构 —— 模块边界即 Space 边界

```
backend/
  dg-common           跨 Space 共享内核:响应封装 / 错误码 / 租户上下文 / 状态机 / 加密
  dg-platform         Platform/Tenancy Space —— 空间(租户)、用户、角色、菜单权限、凭据
  dg-data-spi         Data Space 对外契约 —— 纯接口 + 值对象,零驱动依赖
  dg-data-connectors  Data Space 实现 —— 10 种连接器、类型映射、异常翻译
  dg-metadata         Metadata Space —— 数据源定义、生命周期、目录快照、版本
  dg-app              装配层 —— REST / 安全过滤链 / Flyway / OpenAPI
frontend/             UI Space —— Vue 3 + TypeScript + Element Plus
```

**边界是编译期强制的,不是靠自觉**:`dg-metadata` 的 `pom.xml` 里不存在
`dg-data-connectors` 依赖,所以「Metadata 不得执行任何东西」这条约束由编译器保证。
两个 Space 唯一的接触面是 `DataAccessGateway`,且只暴露 Query 语义 —— 网关上没有
任何写入、建表或提交任务的方法。一旦那里出现 `writeData()`,Metadata 就获得了执行
能力,边界即失守。

## 技术栈

| 层 | 主选(已落地) | 信创替代方向 |
|---|---|---|
| 后端 | Java 21 + Spring Boot 3.3.5 + MyBatis-Plus 3.5.9 | 毕昇 JDK / 东方通 TongWeb |
| 元数据库 | PostgreSQL 16 + Flyway | 达梦 DM8 / 人大金仓 KingbaseES |
| 前端 | Vue 3 + TypeScript + Vite + Element Plus | 同(纯前端无替代压力) |
| 运行时(P2+) | Apache Flink | 同 |

完整选型与替代矩阵见 `SPACE-MODEL.md` 第 L 章。

## 本地运行

### 先决条件
JDK 21、Maven 3.9+、Node 22 + pnpm、一个 PostgreSQL 16 实例。

### 1. 准备数据库

```bash
createdb datagovernance
psql -c "CREATE USER datagov WITH PASSWORD 'datagov'; GRANT ALL ON DATABASE datagovernance TO datagov;"
```

### 2. 配置必需的密钥

应用**不内置任何默认密钥** —— 未配置时直接启动失败,而不是退化成一个内置值加密生产凭据。

```bash
export DG_SECRET_KEY="$(openssl rand -base64 32)"       # 凭据/密钥的 AES-GCM 主密钥
export DG_JWT_SIGNING_KEY="$(openssl rand -base64 48)"  # JWT 签名密钥
# 可选:不设则首次启动生成随机管理员口令并以 WARN 打印一次
export DG_ADMIN_PASSWORD="换成你自己的口令"
```

> `DG_SECRET_KEY` 一旦用于加密过数据就不能更换,否则已存凭据无法解密。
> 密文带 `v1:` 版本前缀,将来换算法(如信创场景改用 SM4-GCM)可平滑共存。

### 3. 启动后端

```bash
mvn -pl backend/dg-app -am spring-boot:run
```

Flyway 会自动建表并灌入权限与内置角色种子。首次启动还会创建默认空间与管理员账号。

- API 文档:http://localhost:8080/swagger-ui.html

### 4. 启动前端

```bash
cd frontend && pnpm install && pnpm run dev
```

## 测试

```bash
./scripts/start-test-postgres.sh            # 起一个本地 PostgreSQL(可反复执行)
mvn test                                    # 全部后端测试
mvn -pl backend/dg-data-connectors test     # 连接器(含真实数据库端到端)
cd frontend && pnpm run build               # 前端类型检查与构建
```

连接器的端到端测试打一个**真实的 PostgreSQL** —— 只有真实的 `DatabaseMetaData`
才能验证结构探测与类型映射,mock 做不到。没起数据库时这些用例**跳过而非失败**,
所以改一行类型映射不必先准备环境。

另有一套 Testcontainers 版本(`PostgreSqlConnectorContainerIT`)供 CI 使用,
在没有 Docker 的环境下同样自动跳过。

## 演进路线

| 阶段 | 内容 | 完成判据 |
|---|---|---|
| **P1** | Metadata Kernel + Data 连接器 + Platform 租户身份 + UI 骨架 | 能创建 5 类数据源、通过连通性测试、浏览库表结构、按空间隔离 |
| P2 | Control(编译+调度)+ Runtime Gateway + 离线同步 | 一条离线同步任务可发布、调度、在 Flink 上跑通并落执行记录 |
| P3 | 实时开发 + 工作流编排 + 整库迁移 + 执行器管理 | 工作流含 Flink/Shell/Python/HTTP/条件节点可编排执行 |
| P4 | Governance:统一监控 / 告警 / 审计 / 血缘 | 功能 24/25/26/27/33 五项跨模块可用 |
| P5 | Intelligence:本体、标注、数据集工厂、模型生命周期 | 独立立项交付 |

最核心的技术资产按顺序形成:**Metadata Kernel → Workflow DSL → Workflow Compiler →
Runtime Contract**。这四项决定系统能否持续扩展。
