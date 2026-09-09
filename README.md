# 数据治理平台

按 **Space Model** 推导出模块边界的数据治理平台。**P1–P5 全部完成**,
35 项功能中 34 项已落地,序号 34(高质量数据集制备)按需求确认独立立项,
本仓库锁定它与平台之间的四条契约。

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

**面向使用者的操作手册**在 [`docs/user-manual.html`](./docs/user-manual.html) ——
从建数据源到跑任务、看告警,含三台状态机速查、错误码归因表,以及可直接下载的
公开测试数据集清单(与 `scripts/seed-public-datasets.sh` 对应)。

## 模块结构 —— 模块边界即 Space 边界

```
backend/
  dg-common           跨 Space 共享内核:响应封装 / 错误码 / 租户上下文 / 状态机 / 加密
  dg-platform         Platform/Tenancy Space —— 空间(租户)、用户、角色、菜单权限、凭据
  dg-data-spi         Data Space 对外契约 —— 纯接口 + 值对象,零驱动依赖
  dg-data-connectors  Data Space 实现 —— 10 种连接器、类型映射、异常翻译
  dg-metadata         Metadata Space —— 数据源定义、生命周期、目录快照、版本、注册中心
  dg-runtime          Runtime Space —— 统一 Execution 事实、执行器、制品、规则解释器
  dg-control          Control Space —— 任务定义、编译、DAG、Cron 调度、下发
  dg-governance       Governance Space —— 监控、告警、审计、告警渠道
  dg-app              装配层 —— REST / 安全过滤链 / Flyway / OpenAPI
frontend/             UI Space —— Vue 3 + TypeScript + Element Plus
```

**边界是编译期强制的,不是靠自觉。** 每一条约束都是 pom 里"某个依赖不存在"这个事实:

| 缺失的依赖 | 它保证的约束 |
|---|---|
| `dg-metadata` 看不见 `dg-data-connectors` | Metadata 不得执行任何东西 |
| `dg-runtime` 看不见 `dg-metadata` | Runtime 不得解释业务语义 |
| `dg-runtime` 看不见 `dg-control` | 同步依赖子图无环(H.2.1) |
| `dg-governance` 看不见 `dg-control` | 治理不向任何 Space 发 Command |

最后一条尤其容易被侵蚀:一旦 Governance 能调 Control,「失败三次就自动暂停这个
任务」必然会在三个月内被加进来,而那一行代码会让依赖图变成环
(control → runtime → 事件 → governance → control)。需要干预时它只发告警,
由人来决定。

Metadata 与 Data 唯一的接触面是 `DataAccessGateway`,且只暴露 Query 语义 ——
网关上没有任何写入、建表或提交任务的方法。一旦那里出现 `writeData()`,
Metadata 就获得了执行能力,边界即失守。

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

### 端到端验收(P1–P5)

单元测试之外,有八份打**真实运行中的应用**的端到端验收脚本,逐条核对各阶段的
完成判据。它们可反复执行,连接参数全部从环境变量读:

| 脚本 | 断言数 | 覆盖 |
|---|---|---|
| `verify-p1.py` | 47 | 数据源、连通性、目录、租户隔离、多节点 MPP、空间启停 |
| `verify-p2.py` | 105 | 编译诊断、Cron、真实搬 2500 行、整库迁移、文件/接口解析、规则 |
| `verify-p3.py` | 77 | 实时保活状态机、离线开发跑真 SQL、工作流条件求值与级联取消 |
| `verify-p4.py` | 60 | 五个监控口径、告警抑制窗口、Webhook 真推送、审计不可变 |
| `verify-p5.py` | 50 | Intelligence 四条契约、脱敏在落地前生效 |
| `verify-p6-datasets.py` | 41 | 公开数据集:分区表结构探测、异构类型保真、跨方言整库迁移对照、GBK 中文文件解析 |
| `verify-p7-concurrency.py` | 21 | 并发下执行事实不串、真实数据量取消、线程池过载归因 |
| `verify-p8-retry.py` | 10 | 写到一半失败后重投的幂等性、失败尝试的写入行数 |
| `verify-ui.mjs` | 82 | 真实浏览器驱动的全部页面 |

它们验证的不是"接口通了",而是那些容易在重构中悄悄失效的约束。几个例子:

- 数据源响应体里不存在任何口令字段;跨空间取数据返回 404 而不泄露资源是否存在
- 取消一个工作流会**级联取消正在跑的子执行**,父子都落到已取消
- 条件不成立时下游节点没有执行,而工作流仍然成功 —— 「没跑」不是「失败」
- 抑制窗口内的第二条告警**仍被记录**,但 Webhook 桩收到 0 条新消息
- 搬两行带身份证号的记录后,**目标库里查不到任何一个完整的身份证号**,而源表仍是明文

后三条各自抓到过一个真实缺陷:进度回调整行写回抹掉了取消状态、
被取消的工作流永远停在「取消中」、拼错的配置键被静默忽略导致脱敏失效。

### 公开数据集验收(P6)

前五份脚本的数据是它们自己造的:表是现建的,列是挑好的,编码一律 UTF-8,
没有分区、没有自定义类型、没有中文列名。那样的数据能证明"功能通不通",
证明不了"遇到真实的库会不会塌"。P6 用公开数据集补上这一段:

```bash
./scripts/seed-public-datasets.sh            # 下载 + 灌库 + 备文件素材
./scripts/seed-public-datasets.sh ftp start  # 起本地只读 FTP(文件解析要用)
./scripts/start-test-mysql.sh                # 跨方言迁移要用;不起则该节整段跳过

DG_ADMIN_PASSWORD='换成你的口令' \
DG_SEED_MYSQL_HOST=127.0.0.1 DG_SEED_MYSQL_PASSWORD=mysql \
  python3 scripts/verify-p6-datasets.py
```

| 数据集 | 来源 | 验证什么 |
|---|---|---|
| Pagila | PostgreSQL 官方示例库的社区移植 | 15 张表藏在 55 个分区里;ENUM / DOMAIN / `text[]` / `tsvector` / `vector` / 生成列 |
| Chinook | 官方同时提供 PG / MySQL / Oracle / SQLServer 四套脚本 | 整库迁移唯一有**参照答案**的形态 —— MySQL 版迁进 PostgreSQL,再拿官方 PG 版逐表逐列对答案 |
| 健康样本 | Synthea 官方样本;取不到时本地生成同结构替身 | 中文表头 + GBK 编码 + 合法校验位的合成身份证号,走 FTP 做文件解析与脱敏 |

素材全部落在 `.seed-cache/`(已 gitignore)。身份证号是本地合成的,校验位算对
但号段与生日随机组合,不对应任何真人;哪一份是官方样本、哪一份是替身,
写在生成出来的 `PROVENANCE.txt` 里 —— 两者混在一起而无从分辨,会让后续
所有基于它的结论都失去依据。

**运行至今抓到五个缺陷,均已修复**,断言现已全绿:

1. **分区污染结构树,父表反而不见了。** `AbstractJdbcConnector` 的
   `BROWSABLE_TABLE_TYPES` 不含 `PARTITIONED TABLE`,而 pgjdbc 把分区父表报成
   这个类型、把 55 个子分区报成普通 `TABLE` —— 结果恰好反过来:用户看不到
   `payment`,却要在 55 个月度碎片里找路。
   *修复*:白名单补上 `PARTITIONED TABLE` 并归为 `TableKind.TABLE`;新增方言钩子
   `hiddenTableNames`,PostgreSQL 用 `relispartition` 把子分区滤掉。表清单从
   78 条降到 24 条(15 表 + 1 分区父表 + 8 视图)。
2. **FTP 流被关两次就抛异常。** try-with-resources 同时持有 `BufferedReader`
   和底层流,关闭时 `FilterInputStream.close()` 被调用两次,第二次在已断开的
   连接上调 `completePendingCommand()`。表现最坏:200 行全部写进目标表了,
   执行状态却是 `FAILED` —— 值班的人重跑一次就是双写。
   *修复*:`close()` 用 `AtomicBoolean` 做成幂等。
3. **`path` 指向文件本身时拼出不存在的路径。** FTP 对一个文件执行 `LIST`
   会返回该文件自己那一条,与目录清单在结构上分不出来,于是给每条拼路径时
   多拼了一层:`/health/x.csv/x.csv`。编译器的配置说明写的是「path 文件或目录路径」。
   *修复*:FTP 用 `CWD` 试探(只在清单只有一项且非目录时问一次),SFTP 用
   `stat().isDir()`;`resolveFiles` 在 path 已指名道姓时不再套用 `filePattern`。
4. **`FileParseCompiler` 没有接 `warnUnknownKeys`。** P5 给离线同步补的那道防线
   没有覆盖文件解析这条路径 —— 同一个拼错键名的错误在这里依然静默通过,
   而这恰恰是脱敏最要紧的一条链路。
   *修复*:检查后发现十个编译器里只有离线同步接了这道防线。给其余全部补上
   `KNOWN_KEYS`;开发类作业(实时/离线/工作流)共用的键收在 `DevJobCompiler`,
   子类用 `extraKnownKeys()` 追加自己的。
5. **`lowercaseNames` 只转了表名,没转列名。** 把官方 MySQL 版 Chinook(表名列名
   都是 PascalCase)迁进 PostgreSQL,表建成了 `genre`,列却是 `"GenreId"`、`"Name"`
   —— PostgreSQL 里不加引号的标识符会折成小写,于是 `SELECT name FROM genre`
   直接报错。表名转了、列名没转是最难受的一种半套:用户连约定都猜不出来,
   而平台自己的字段映射、清洗规则也全都要跟着写引号。
   *修复*:命名规则收进 `TargetNaming`,由执行期的 `DbMigrationRunner` 与
   「预览建表语句」的 `TableDdlController` **共用同一份实现** —— 两边各写一份
   必然漂移,而漂移的表现是预览给你看的语句和实际建出来的表不一样。建表与
   插入也必须共用这个开关:建成 `name` 却插入 `"Name"`,整张表一行都进不去。
   顺手把 `toLowerCase()` 锁到 `Locale.ROOT`:土耳其语环境下 `"ID"` 会变成
   `"ıd"`(无点 i),同一份任务换台机器就建出不同的列名。

缺陷 1 与 5 另有不依赖公开数据集的回归测试(`PostgreSqlConnectorLiveIT` 的
分区夹具、`TargetNamingTest`)—— 把修复撤掉就变红,验证过。

跨方言那一节还顺带确认了**类型映射是保真的**:`varchar(200)` 的长度、
`decimal(10,2)` 的精度与标度、`int` → `integer`,`track` 表全部 9 列与官方
PG 版逐列一致。这是风险 R7 至今唯一一次有标准答案的验证。

### 并发与规模验收(P7)

前六份脚本每个任务都是提交一个、等它跑完、再提交下一个。那样证明不了两件事:
并发下执行事实还对不对,以及取消在真实数据量下是否真的生效。P7 补这一段。

```bash
# 默认池子:并发与取消两节
DG_ADMIN_PASSWORD='换成你的口令' python3 scripts/verify-p7-concurrency.py

# 小池子:过载那一节(它与并发那一节要的池子配置正好相反,故二选一)
mvn -pl backend/dg-app -am spring-boot:run \
  -Dspring-boot.run.arguments="--dg.runtime.max-pool-size=1 --dg.runtime.queue-capacity=1"
DG_ADMIN_PASSWORD='换成你的口令' DG_VERIFY_POOL_LIMIT=2 \
  python3 scripts/verify-p7-concurrency.py
```

两条设计上的讲究:

- **"指标写串"怎么测**:12 个任务分别搬 2000、4000…24000 行。于是"第 i 条执行
  记录的 rowsWritten 是不是 i×2000"有唯一答案 —— 串了立刻看得见。行数还不能
  太小:几十行的任务几十毫秒就跑完,12 个串着跑也不会重叠,那验的是"跑得快"
  而不是"并发对"。
- **并发上限怎么算**:不靠轮询采样(任务跑得快时可能一次都采不到 RUNNING),
  而是把每条执行记录的 `startedAt`/`finishedAt` 摊成事件点扫一遍,得到区间
  重叠的确切上界。这是执行事实表自己的证词,跑一万次结果都一样。

**抓到第六个缺陷:线程池打满被归错了因。**

`TriggerType.DISPATCH_REJECTED`(「下发被拒」)是一个**用户可配的告警规则类型**,
而 `reject()` 此前只在"没有支持该类型的执行引擎"时被调用。线程池打满抛的
`RejectedExecutionException` 走的是另一条 catch,落成 `SYS_INTERNAL_ERROR`,
消息是 `提交执行失败: Task java.util.concurrent.FutureTask@36198d13[Not completed...]`。

后果有两层。值班的人看到"系统内部错误"会去找开发查 bug,而真相是"队列满了,
加机器或调大池子" —— 这正是本项目一贯区分的「环境缺口」与「平台故障」。
更要紧的是**告警不会发**:用户配了"执行器满了就通知我",过载真发生时一条都
收不到,因为 `DispatchRejected` 事件根本没有被发布。

*修复*:在提交的 catch 里把 `RejectedExecutionException` 单独接住,走
`rejectSubmission()` → `reject()`,落 `RTM_DISPATCH_REJECTED` 并发出事件;
消息换成人话("执行器已满,下发被拒。当前并发已达上限…"),不再泄露 FutureTask
的 toString。

### 重试幂等性验收(P8)

前七份脚本里的任务要么整个成功、要么整个失败。真实世界最常见的第三种形态没被
碰过:**写到一半失败**。它要紧,是因为两条各自都合理的设计合在一起会出事 ——
写入每攒满一批就 commit 一次(几百万行不可能攒在一个事务里),而重试重投的是
**整个任务**(runner 从源端第一行重新读起,它没有断点)。

```bash
DG_ADMIN_PASSWORD='换成你的口令' python3 scripts/verify-p8-retry.py
```

怎么让它确定性地"写到一半失败":目标表加一条 CHECK 拒绝某一个特定的 id。
批次 1000、坏行在 3777,于是每次尝试都提交前三批(3000 行)、第四批撞上约束
整批回滚 —— 行为可复现。判据不看行数而看重复:`count(*)` 与
`count(DISTINCT id)` 一比就知道有没有写重,这个判据不依赖"每次尝试恰好写了
多少行",源端返回顺序变了也不会误报。

三节里有两节是**对照组**,它们决定修法是"精准"还是"一刀切":一行都没写就失败
(目标表不存在)必须照旧重试三次;OVERWRITE 写到一半失败也必须照旧重试 ——
它开写前先清表,重投天然幂等,禁掉它的重试是白白的损失。

**抓到第七个缺陷:重试从来没有成功过一次。**

`startAttempt()` 无条件执行 `PENDING → DISPATCHED` 那段迁移,而重投时执行早已
不在 `PENDING` 上。`retryNow()` 还先把 `RUNNING` 按回 `DISPATCHED`,于是两条路
都变成 `DISPATCHED → DISPATCHED` —— 一个状态机不认的自环,`checkTransition`
抛 409。这个异常发生在调度线程里,被 `RetryScheduler` 的 catch 吞掉,只留下
一行日志。

表面上什么都看不出来,后果却有三层:配了重试的任务**一次都没重试过**;执行
既没落终态也没人在跑,一直挂到 `timeoutMs` 到点被超时清扫工扫成 `TIMEOUT` ——
默认超时是小时级的;而那句"执行超时,已强制结束"**盖掉了真正的失败原因**
(实际是 CHECK 约束违例)。

*修复*:`startAttempt()` 只在 `PENDING` 时迁状态 —— 这本就是
`ExecutionLifecycle.retryable` 那段说明写明的语义(重试不改变 Execution 状态,
它只新增一次 attempt);`retryNow()` 里那个把状态按回去的补丁一并删掉。
另外,重投确实送不出去时不再只记一行日志,而是把执行落到 `FAILED` 并保留上一次
尝试的报错 —— 不然它还是会挂到超时。

**抓到第八个缺陷:修好重试之后,APPEND 会把已落盘的行再写一遍。**

第七个缺陷一直**掩盖**着这一个:重试根本没发生,自然也没重复写。修好之后
P8 立刻红了 —— 目标表 9000 行,却只有 3000 个不同 id,多出的 6000 行是重投
写重的。任务最终报 `FAILED`,用户看到的是"任务失败了",不会想到失败的任务
还顺手往目标表塞了三份数据。

*修复*:runner 在**第一次真正 commit 之后**调 `context.markUnsafeToRetry()`,
Runtime 据此不再重投,并在失败原因里说明为什么("本次尝试已向目标端提交了
部分数据…请先清理目标端已写入的数据再手工重跑,或把写入模式改为 OVERWRITE")。

两个位置的讲究:

- **标在提交点,不标在抛异常的地方**。后者要求每个 runner 的每条失败路径都记得
  包一层,总会漏一条,而漏掉的后果是静默写重。
- **判断由 runner 做,不由 Runtime 做**。只有 runner 看得见 `writeMode`,而
  Runtime 不该知道什么是 `writeMode`(Space 边界)。Runtime 收到的只是一位事实:
  "这次尝试重投会不会写重"。

顺带补上的:失败的尝试此前不记 `rowsWritten`(`onFailed` 传的 metric 是 null),
于是"失败的任务到底写进去多少行"永远是个问号 —— 而出事后第一个要问的正是
"目标端现在有多少脏数据要清"。现在 `TableCopier` 在失败前把已提交的行数报上去。

**抓到第九个缺陷:UPSERT 跑出来其实是 APPEND。**

判断"哪种写入模式重投是安全的"时发现的:`OfflineSyncCompiler` 接受 `UPSERT`
并认真校验主键字段,而 `TableCopier.buildInsert()` 生成的是一条普通 `INSERT` ——
没有 `ON CONFLICT` / `ON DUPLICATE KEY` / `MERGE`。编译期校验主键、执行期不用它,
于是用户配的是「按主键更新」,拿到的是一张越跑越大的表,**没有任何提示**。

真正实现它要逐方言写(PostgreSQL 的 `ON CONFLICT`、MySQL/Doris 的
`ON DUPLICATE KEY`、Oracle/SQLServer/达梦的 `MERGE`,后者与批量提交配合起来
不轻松),涉及 10 种数据源。在那之前,**让失败发生在配置时而不是数据里**:

- `OfflineSyncCompiler` 把 `UPSERT` 单独拦下,报「本版本尚未实现 UPSERT 写入模式」
  并给出替代方案。**不是**混进"写入模式无效"里 —— 那句话会让用户以为自己拼错了,
  而真相是平台没实现。这两句话指向完全不同的下一步动作,P2 各有一条断言钉住。
- `TableCopier` / `RowWriter` 在执行期再拦一道。这道不能省:**已发布的任务跑的是
  存下来的物理计划,不会再过编译器** —— 少了它,改动之前建的 UPSERT 任务会照旧
  静默跑成 APPEND。
- 界面上该选项置灰;打开一个老的 UPSERT 任务会看到一条红色提示说明怎么改。
  **不替用户改成 APPEND** —— 那等于代他做了一个会改变数据的决定。
- 计划里不再写 `primaryKeys`:离线同步的物理计划中它没有任何消费者,留着只会让
  下一个读代码的人以为 UPSERT 是通的(顺带治好了每个同步任务都收到一条
  「未知配置键 primaryKeys」警告的老毛病 —— 前端一直无条件提交这个字段)。

### P1 验收(示例)

其余阶段同理,把脚本名换掉即可:

```bash
./scripts/start-test-postgres.sh                # 1. 起测试数据库
mvn -q install -DskipTests                      # 2. 构建
DG_DB_URL=jdbc:postgresql://127.0.0.1:55432/datagovernance \
DG_DB_USER=postgres DG_DB_PASSWORD=postgres \
DG_SECRET_KEY="$(openssl rand -base64 32)" \
DG_JWT_SIGNING_KEY="$(openssl rand -base64 48)" \
DG_ADMIN_PASSWORD='换成你的口令' \
  java -jar backend/dg-app/target/dg-app-0.1.0-SNAPSHOT.jar &   # 3. 启动

DG_ADMIN_PASSWORD='换成你的口令' python3 scripts/verify-p1.py    # 4. 验收
```

它验证的不只是"接口通了",还包括几条容易在重构中悄悄失效的约束:数据源响应体里
不存在任何口令字段、手工测试失败回到 `DRAFT` 而非 `UNREACHABLE`、未验证的数据源
不允许浏览结构、跨空间取数据返回 404 而不泄露资源是否存在。

### 前端验收

另有一份用真实浏览器(Playwright)驱动的 UI 验收:

```bash
cd frontend
DG_API_TARGET=http://127.0.0.1:8080 pnpm run preview &     # 起前端并代理到后端
cd .. && DG_PLAYWRIGHT_ROOT=$(npm root -g) \
  DG_ADMIN_PASSWORD='换成你的口令' node scripts/verify-ui.mjs
```

**装不上 playwright 或浏览器版本对不上时**,两个环境变量各解一个问题:

```bash
# ① 别在 frontend 目录里装。npm 会连带解析整棵既有依赖树,
#    里面任何一个 workspace: 协议的依赖都会让安装失败(报 Unsupported URL Type)。
#    单开一个空目录装,再用 DG_PLAYWRIGHT_ROOT 指过去。
mkdir -p /tmp/pw && cd /tmp/pw && npm init -y >/dev/null
PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 npm install playwright

# ② playwright 把浏览器 build 号钉死在库版本上,镜像里预装的往往是别的号,
#    于是报 Executable doesn't exist 并让你去 install —— 不允许联网下载时那条路是死的。
#    DG_CHROMIUM_PATH 指到一个现成的 Chromium 上直接用。
cd - && DG_PLAYWRIGHT_ROOT=/tmp/pw/node_modules \
  DG_CHROMIUM_PATH=/opt/pw-browsers/chromium-1194/chrome-linux/chrome \
  DG_ADMIN_PASSWORD='换成你的口令' node scripts/verify-ui.mjs
```

它覆盖登录跳转、后端下发的菜单渲染、数据源列表与目录树、结构浏览抽屉的逐层下钻,
以及一条重要的架构约束在界面上的体现:**表单结构由 `capabilities` 决定而非类型判断**
—— 选 RestAPI 出现「接口地址」、选 MySQL 出现「主机/端口/库名」并自动填 3306。

## 演进路线

| 阶段 | 内容 | 状态 |
|---|---|---|
| **P1** | Metadata Kernel + Data 连接器 + Platform 租户身份 + UI 骨架 | ✅ 序号 1-8、28-30 |
| **P2** | Control(编译+调度)+ Runtime Gateway + 集成任务 | ✅ 序号 9-17 |
| **P3** | 实时/离线开发 + 工作流编排 + 执行器 + 制品仓库 | ✅ 序号 18-23、31、32 |
| **P4** | Governance:监控 / 告警 / 审计 / 告警渠道 | ✅ 序号 24-27、33 |
| **P5** | Intelligence 契约 + 敏感数据脱敏 | ✅ 序号 34(契约)、35(合规底座) |

最核心的技术资产按顺序形成:**Metadata Kernel → Workflow DSL → Workflow Compiler →
Runtime Contract**。这四项决定系统能否持续扩展。

### 两处诚实的缺口

平台不假装自己能做没有接入的事。以下两类作业在当前部署下**明确失败并说明缺什么**,
而不是返回"成功、处理 0 行":

- **实时开发作业**需要 Flink 集群 —— 失败码是 `RTM_EXECUTOR_UNAVAILABLE`
  (环境缺件),不是内部错误。值班的人据此判断该找运维还是找开发。
- **Python 训练作业**需要 K8s。

假成功会让编译、下发、执行记录、状态机整条链路都"通过",而实际什么都没发生 ——
对一个靠"跑没跑"来判断任务好坏的平台,那比缺一个功能更糟。接入时各新增一个
`ExecutionEngine` 实现即可,上层的编译、状态机与监控都不必改。

序号 34「高质量数据集制备」按需求确认**独立立项**(工程量与序号 1-33 之和相当)。
本仓库锁定它与平台之间的四条契约并写成可执行的代码:取数不得直连业务数据源
(编译期拦截)、算力走 Control 复用统一 Execution、产物注册进 Metadata Registry、
字段到医学概念的映射写入关系边。
