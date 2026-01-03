# Apache ShardingSphere 宏观学习图文手册

> 目的：结合官方文档、仓库结构与本地资料，为后续源码学习提供一份“鸟瞰图”。阅读完本手册，应能回答 ShardingSphere 是什么、有哪些产品形态、核心能力如何落地，以及源码目录应该如何切入。

---

## 1. Database Plus 愿景

![Database Plus 设计哲学](../../../docs/document/static/img/design_cn.png)

- **定位**：ShardingSphere 以 Database Plus 为理念，强调在异构数据库之上“连接、增强、可插拔”。通过协议/方言适配连接各种数据库，借助透明流量增强实现分片、读写分离、加密、治理，再以插件化内核支持二次扩展。
- **价值**：不重写数据库，而是最大化复用既有存储与计算能力，构建跨数据源的统一上层标准。在大规模业务系统里，可延续原有技术栈并逐步演进。

---

## 2. 产品矩阵与典型场景

| 维度 | ShardingSphere-JDBC | ShardingSphere-Proxy | ShardingSphere-Sidecar（规划中） |
| --- | --- | --- | --- |
| 连接方式 | 应用内嵌 JAR，直连真实库 | 独立代理，暴露 MySQL/PostgreSQL 协议 | 以 Sidecar 注入 Pod，走本地代理 |
| 适用语言 | 仅 Java（JDBC/ORM） | 任意（MySQL/PG 客户端） | 云原生 Kubernetes 场景 |
| 连接开销 | 高（与应用同生命周期） | 低（统一连接池） | 中（每 Pod 一份 Sidecar） |
| 典型场景 | 高性能 OLTP、老系统平滑扩展 | DBA 运维、跨语言服务、OLAP 管理面 | 数据库 Mesh、云原生治理 |

![ShardingSphere-JDBC 体系](../../../docs/document/static/img/shardingsphere-jdbc_v3.png)

![ShardingSphere-Proxy 体系](../../../docs/document/static/img/shardingsphere-proxy_v2.png)

---

## 3. 三层可插拔架构

![三层插件架构](../../docs/document/static/img/overview_cn.png)

1. **L1 Kernel Layer**：抽象 SQL 处理内核（解析、路由、执行、事务、调度等），可通过 SPI 替换实现。
2. **L2 Feature Layer**：数据分片、读写分离、弹性伸缩、加密、影子库等增强能力，按需组合。
3. **L3 Ecosystem Layer**：协议（MySQL / PostgreSQL / DistSQL）、SQL 方言、存储适配器，用于融入各类数据库生态。

---

## 4. 一条 SQL 的生命周期

ShardingSphere 内核以 **Parse → Route → Rewrite → Execute → Merge** 为主干，配合分片、事务、治理等插件完成请求闭环。

```mermaid
flowchart LR
    A[应用发送 SQL] --> B[Parser<br/>SQL 解析]
    B --> C[Planner & Router<br/>依据规则计算路由]
    C --> D[Rewriter<br/>改写目标 SQL]
    D --> E[Executor<br/>连接池 & 并发执行]
    E --> F[Merger<br/>流式/内存归并]
    F --> G[增强能力<br/>加密/审计/流控]
    G --> H[返回统一结果]
```

- **解析**：`parser`/`sql-parser`/`infra-parser` 模块协同生成 AST。
- **路由&改写**：`infra-route`、`infra-rewrite` 结合 `features` 下的分片等规则，计算真实库表与 SQL 形态。
- **执行&归并**：`infra-executor`、`infra-merge` 负责并发执行和结果处理，`kernel-transaction`、`kernel-schedule` 提供事务、调度支持。

---

## 5. 核心能力地图

![分片架构示意](../../docs/document/static/img/sharding/sharding_architecture_cn_v3.png)

- **数据分片**：支持垂直/水平分库分表、标准/复合/Hint 分片算法，借助 DistSQL 与注册中心实现集中治理。
- **读写分离**：多主多从拓扑感知，SQL 语义级别路由读请求，支持负载均衡策略。
- **分布式事务**：XA + BASE 混合引擎，内置 Seata AT、自研柔性事务等模式，并可结合全局时钟服务消除写冲突。
- **弹性伸缩与数据迁移**：`kernel-data-pipeline` 模块对接迁移任务，可与 Scaling 组件/DistSQL 协同，实现不停服扩容与数据搬迁。
- **数据安全**：提供加密（列加密、保存/查询双向转换）、数据脱敏（masking）、影子库（全链路压测）、审计/防火墙等。
- **SQL 联邦 & Query Federation**：`kernel-sql-federation` 支持跨库 JOIN/聚合，弥合多数据源分析诉求。
- **可观察与治理**：`infra-logging`、`kernel-traffic`、APM 插件追踪链路，结合 Operator/Cloud 部署方案实现可视化管控。

---

## 6. 源码目录鸟瞰

| 顶层目录 | 作用概述 |
| --- | --- |
| `infra/` | 统一基础设施：算法 SPI、路由/改写/执行/合并框架、连接池、异常体系等，是 SQL 生命周期的积木库。 |
| `kernel/` | 面向业务的内核实现，包含 metadata、transaction、traffic、sql-federation、data-pipeline、logging 等。 |
| `features/` | 具体增强功能实现（sharding、readwrite-splitting、encrypt、mask、shadow、db-discovery 等）。 |
| `jdbc/` | JDBC 接入端源码，封装数据源、连接、语句代理及示例。 |
| `proxy/` | Proxy 服务端实现，涵盖协议解析（MySQL/PostgreSQL）、Netty 通信、会话管理、权限等。 |
| `db-protocol/` & `sql-parser/` | 多协议、多 SQL 方言解析与编译器组件。 |
| `docs/` | 官方文档与站点源码，可直接从 `docs/document/content` 中阅读概念与用户手册。 |
| `examples/` | 场景化示例（分片、读写分离、加密、影子库、分布式事务等），便于 Debug 切入。 |

建议阅读姿势：先结合 `infra` → `kernel` → `features`，再分别深入 `jdbc` 或 `proxy` 路径，理解相同核心在不同接入端的落地差异。

---

## 7. 部署模式与混合架构

![Hybrid Architecture](../../docs/document/static/img/shardingsphere-hybrid-architecture_v2.png)

- **Standalone 模式**：适合本地/小规模环境，配置持久化但不共享，便于单机调试。
- **Cluster 模式**：依赖注册中心（Zookeeper、Etcd、Consul 等）共享元数据，支持节点状态感知、弹性扩缩与 HA。
- **混合部署**：JDBC 负责 OLTP，Proxy 作为集中入口用于 OLAP 与运维管理。共享配置中心后两者可在同一套规则下协同，形成数据库 Mesh。

---

## 8. 演进路线与社区

![Roadmap](../../docs/document/static/img/roadmap_cn.png)

- **版本节奏**：5.3.x 聚焦分布式事务、数据安全、云原生能力；Roadmap 展示了后续在 Database Mesh、云上原生、联邦计算等方向的演进。
- **社区协作**：通过 GitHub Issues/Discussions、邮件列表、Slack 沟通；`CONTRIBUTING.md` 与 `learn/01_meta/shardingsphere_learning_with_ai_discussion.md` 对贡献流程与 AI 协同给出建议。

---

## 9. 推荐资料索引

| 资源 | 说明 |
| --- | --- |
| `README_ZH.md` / `docs/document/content/overview/_index.cn.md` | 官方概述、产品定位、部署对比。 |
| `learn/01_meta/shardingsphere_learning_with_ai_discussion.md` | 面向本地学习的阶段划分与 AI 协作建议。 |
| `learn/02_resource/Apache ShardingSphere 源码解读-张亮.pdf` | 核心作者对内核流程的系统性讲解，适合配合 Debug 阅读。 |
| `learn/02_resource/孟浩然-Apache ShardingSphere 架构解析&应用实践-已压缩.pdf` | 全景架构与典型企业实践，总结关键模块职责。 |
| `learn/02_resource/shardingsphere_docs_cn_5.3.2.pdf` & `shardingsphere_docs_en.pdf` | 线下阅读版官方文档，涵盖用户手册、开发手册、FAQ。 |
| `learn/02_resource/blogs.md` | 社区精选文章索引，适合延展阅读（核心原理专栏、分库分表实践等）。 |

**使用建议**：先浏览官方 Overview，随后交替阅读张亮/孟浩然两份讲义与 `examples` 代码；遇到疑问时回溯官方 PDF、博客或 `docs/*` 中的专题章节。

---

## 10. 入门实践路径（可与 AI 协作）

1. **搭建环境**：按 README 准备 JDK 17+ 与 Maven，执行 `mvn -T1C clean install -DskipTests` 熟悉构建日志。
2. **跑通示例**：从 `examples/shardingsphere-jdbc-example` 启动最小 Demo，结合 Debug 理解 `ShardingSphereDataSource` 如何代理 JDBC。
3. **绘制流程图**：参考本手册的 SQL 生命周期，结合调试堆栈输出属于自己的调用图（可使用 Mermaid/PlantUML）。
4. **专项突破**：选择 `features/sharding` 或 `features/readwrite-splitting`，查阅对应 `docs/document/content/features/*`，把“概念 → 配置 → 源码”串联。
5. **社区共建**：关注 `good first issue`，完成一次测试或文档贡献，把学习成果回馈社区。

借助 AI Agent，可在上述每一步请求：文档梳理、目录索引、代码讲解、流程图生成、提交信息润色等支持，以保证探索过程持续高效。

---

## 11. docs/ 文档地图

- **站点结构**：`docs/document/content` 采用 Hugo 多语言站点，依序覆盖 Overview、Quick Start、Features、User Manual、Reference、Dev Manual、Test Manual、FAQ 等章节；`docs/blog`、`docs/community` 则存放社区故事与贡献指南源码。
- **内容定位**：
  - `quick-start/` 提供 JDBC 与 Proxy 的最短路径配置；
  - `features/` 为 3.x 节，聚焦能力背景/挑战/目标/场景；
  - `user-manual/` 拆分 common config、JDBC、Proxy、错误码等，是查找 YAML/DistSQL 语法的权威；
  - `reference/` 汇总事务、兼容性、影子库等深度原理；
  - `dev-manual/`、`test-manual/` 方便后续做二开或补测试。
- **阅读顺序建议**：先用 Overview / Quick Start 建立认知，再按需要跳转到 Features 和 User Manual，遇到实现疑问回查 Reference 与 Dev Manual，可通过站点的 pre/weight 快速定位序号。

---

## 12. Quick Start 关键步骤

### ShardingSphere-JDBC

- **场景与限制**：目前只支持 Java 语境，可通过 Java API 或 YAML 两种形式配置（`docs/document/content/quick-start/shardingsphere-jdbc-quick-start.en.md`）。
- **依赖与步骤**：
  1. 在 `pom.xml` 引入 `shardingsphere-jdbc-core`；
  2. 编写 YAML（`databaseName`、`mode`、`dataSources`、`rules`、`props`）或 Java 配置对象；
  3. 例如 Spring Boot 中仅需将 `driver-class-name` 替换为 `org.apache.shardingsphere.driver.ShardingSphereDriver` 并指向 YAML。
- **后续**：按照 User Manual 中的规则配置章节，完善 sharding、readwrite-splitting 等 `rules` 数组项。

### ShardingSphere-Proxy

- **获取方式**：支持 Binary、Docker、Helm 三种分发途径；本地运行需 JRE8+ (`docs/document/content/quick-start/shardingsphere-proxy-quick-start.en.md`)。
- **核心步骤**：
  1. 在 `%SHARDINGSPHERE_PROXY_HOME%/conf/server.yaml` 设置权限、模式等全局项，在 `config-*.yaml` 中定义逻辑库；
  2. 使用 MySQL 时需将 `mysql-connector-java` 拷贝到 `ext-lib`，PG/openGauss 则内置；
  3. 通过 `bin/start.sh [port] [conf]` 启动，`-f` 参数可强制忽略异常数据源；
  4. 使用 `mysql`/`psql`/`gsql` 客户端登录，后续通过 DistSQL 管理资源与规则。
- **注意**：Proxy 对系统库支持有限，图形化客户端异常时可优先使用命令行。

---

## 13. 配置基石：Mode / Props / 插件

- **Mode（运行模式）**：`user-manual/shardingsphere-jdbc/yaml-config/mode.en.md` 将 `Standalone` 与 `Cluster` 作为顶级模式，统一以 `mode.type` + `repository` 的形式声明；Cluster 推荐配合 ZooKeeper/Etcd/Nacos/Consul，YAML 示例直接指定 `server-lists`、`namespace` 等属性，并需在 `pom` 中引入对应 repository 依赖。
- **Props（系统级属性）**：`user-manual/common-config/props.en.md` 中列出 `sql-show`、`sql-simple`、`kernel-executor-size`、`max-connections-size-per-query`、`check-table-metadata-enabled`、`sql-federation-type` 等全局开关，可在 YAML `props` 下配置，结合日志主题 `ShardingSphere-SQL` 观察逻辑/真实 SQL。
- **可插拔 SPI**：`user-manual/shardingsphere-jdbc/optional-plugins/_index.en.md` 列出 Authority、DB Discovery、Encrypt/Mask、Shadow、Traffic、SQL Federation、事务、模式仓库等核心/可选插件（`groupId:artifactId` 形式）；理解这些插件的拆分，可帮助在源码中定位 SPI 接口与具体实现（例如 `shardingsphere-cluster-mode-repository-zookeeper`、`shardingsphere-transaction-xa-narayana`、`shardingsphere-sql-translator-jooq-provider`）。

---

## 14. DistSQL 治理闭环

![DistSQL Before](../../docs/document/static/img/distsql/before.png)

![DistSQL After](../../docs/document/static/img/distsql/after.png)

- **定义**：DistSQL（Distributed SQL）是 Proxy 独享的治理语言，分为 RDL（定义资源/规则）、RQL（查询）、RAL（管理，如导入导出、Hint、熔断、Scaling）、RUL（实用工具，如 SQL 解析/格式化/执行计划）（`user-manual/shardingsphere-proxy/distsql/_index.en.md`）。
- **体验升级**：从 5.x 起，DBA 可用同一个客户端（MySQL/PG）同时操作业务数据与配置，无需修改 YAML、无需文件写权限、规则秒级生效；适合生产滚动调整。
- **实现原理**：DistSQL 复用了 SQL Parser，将语句转换为 AST 和 `Statement` 并交由对应 Handler，形成“SQL → Handler → Metadata/Registry”闭环（详见 `overview_v2.png` 流程图）。
- **使用建议**：搭建 Proxy 后优先通过 DistSQL 管理 data source、sharding/readwrite/影子库规则、导入导出配置，以减少手工配置错误。

---

## 15. Observability & 治理面板

![Observability Overview](../../docs/document/static/img/apm/overview_v3.png)

- **定位**：ShardingSphere-Agent 专注于生成可观测数据，采集/存储/展示交由 OTEL、SkyWalking、Prometheus 等系统；内核通过插件方式输出 Trace/Metrics/Log，避免侵入 (`docs/document/content/features/observability/_index.en.md`)。
- **生态互通**：支持 OpenTelemetry Auto Configure、SkyWalking SDK 或自动探针，Metrics 默认兼容 Prometheus；用户可自研插件接入其他 APM。
- **应用场景**：1) 监控面板——输出版本、线程、SQL 处理指标；2) 性能诊断——Trace 标注 Parse→Route→Rewrite→Execute→Merge 各阶段延迟；3) 链路追踪——穿透分布式服务/多数据源，绘制 SQL 拓扑定位异常节点。
- **实践提示**：Proxy/JDBC Agent 都可按需开启 tracing；在大规模集群中结合 DistSQL 读数与 APM 指标，可快速评估规则变更的影响。

---

## 16. 数据迁移与高可用协同

![DB Discovery Overview](../../docs/document/static/img/discovery/overview.en.png)

- **迁移目标**：`features/migration/_index.en.md` 强调以不断服、保证数据正确性为核心，借助 `kernel-data-pipeline` + DistSQL Scaling，帮助单体数据库平滑切换到分片架构，解决百亿级数据量、TPS 持续增长的瓶颈。
- **高可用策略**：`features/ha/_index.en.md` 描述 Heterogeneous HA 场景，ShardingSphere 复用存储节点原生 HA（主备/复制），在计算层自动感知主从元数据并与读写分离联动，实现 7*24 不中断；当二级节点下线时可动态调整读流量路由。
- **实战建议**：在执行迁移或扩容时，可先通过 DistSQL 抽象数据节点，再配合 HA 模块实时刷新拓扑；一旦主备切换，计算节点会自动刷新路由表，减轻 DBA 人工干预。

---

## 17. 分布式事务执行链路

![XA Transaction](../../docs/document/static/img/transaction/2pc-xa-transaction-design.png)

![Seata AT](../../docs/document/static/img/transaction/sharding-transaciton-base-seata-at-design.png)

- **二阶段提交（XA）**：`reference/transaction/2pc-xa-transaction.en.md` 揭示 `XAShardingSphereTransactionManager` 在收到 `SET autocommit=0` 后，以 XA Manager（Narayana、Bitronix 等）统筹全局事务；执行顺序为 `XAResource.start → SQL 执行 → XAResource.end → prepare → commit/rollback`，确保跨库强一致。
- **柔性事务（Seata BASE）**：`reference/transaction/base-transaction-seata.en.md` 说明 ShardingSphere 通过包裹数据源为 `Seata DataSourceProxy`，并在 TM/RM/TC 间转发上下文，实现 undo 日志、分支事务注册与全局上下文透传；适合对延迟敏感但可接受最终一致性的业务。
- **工程实践**：在 YAML/DistSQL 中启用 XA/BASE 时需配置事务管理器实现与日志仓库；调试时可借助 Observability 中的 Trace，洞察事务边界与 Prepare/Commit 耗时。

---

## 18. Feature Spotlight：读写分离 & 数据安全

- **读写分离（features/readwrite-splitting）**：强调“把一组主从数据库当成一个库来用”，通过 SQL 语义识别读写请求、感知主从拓扑，支持多主多从与分片混用。官方图 `readwrite-splitting/background.png` 展示了将查询压力分摊到多副本的方式，而 `challenges.png` 显示复杂拓扑的可视化难点，指引使用 ShardingSphere 屏蔽细节。
- **加密（features/encrypt）**：面向“新业务一开始就合规存储”和“老业务无侵入改造”两类场景，提供逻辑列 → cipher/plain/assisted 列的映射模型。文档强调三大挑战（历史数据清洗、无痛接入、平滑迁移），以及在业务 SQL 不变的情况下完成加解密。
- **Mask / Shadow / 数据脱敏**：虽然不在本节详述，但 docs/document/static/img/mask 与 shadow 相关图示展示了对测试/压测与行级脱敏的支持，可配合加密一起形成“数据安全”域。

> 结合 User Manual 中的规则定义，可在 YAML `rules` 数组内为 readwrite-splitting/encrypt/影子库配置 `dataSources`、`load-balancer`、`encryptors` 等元素；对应 DistSQL 语法在 `/user-manual/shardingsphere-proxy/distsql/syntax/rdl/` 下有完整样例。

---

## 19. DistSQL 速查示例

```sql
-- 注册数据源（摘自 docs/document/content/user-manual/shardingsphere-proxy/distsql/syntax/rdl/storage-unit-definition）
CREATE STORAGE UNIT ds_0 (
  URL='jdbc:mysql://127.0.0.1:3306/demo_ds_0',
  USER='root', PASSWORD='***',
  PROPERTIES('maximumPoolSize'=50,'idleTimeout'=60000)
);

-- 定义读写分离规则
CREATE READWRITE_SPLITTING RULE pr_ds (
  WRITE_STORAGE_UNIT=ds_primary,
  READ_STORAGE_UNITS(ds_replica0,ds_replica1),
  TYPE(NAME='Static', PROPERTIES('auto-aware-data-nodes'='false')),
  LOAD_BALANCER(TYPE='ROUND_ROBIN')
);

-- 查看规则与节点
SHOW READWRITE_SPLITTING RULES;
SHOW STORAGE UNITS;

-- 注册/执行 Scaling 任务
CREATE MIGRATION JOB ...;
SHOW MIGRATION LIST;
STOP MIGRATION 'jobId';
```

- DistSQL 把 RDL/RQL/RAL/RUL 拆分清晰，驱动 Proxy 实时生效；配套的错误码（如 `Storage unit [...] is still used by [...]`）在 `user-manual/error-code/sql-error-code.en.md` 中列出，便于排查。
- 多数 DistSQL 语句都能通过 `SHOW ...` 和 `EXPLAIN` 核对当前元数据状态，调试时可配合 `props.sql-show = true` 查看实际改写 SQL。

---

## 20. User Manual 重点索引

- **Common Config**：`common-config/props.en.md`、`builtin-algorithm/*.md` 汇总了内置算法（分片、负载均衡、密钥生成、审计、元数据仓库等）及参数说明，是跨 feature 共享的基础。
- **ShardingSphere-JDBC**：`user-manual/shardingsphere-jdbc/yaml-config` 与 `java-api` 分别提供 YAML/编程模式的详尽示例；`optional-plugins` 与 `observability` 小节说明如何引入额外 SPI 或 Agent。
- **ShardingSphere-Proxy**：`startup` 覆盖 bin/docker/helm 启动，`session` & `logging` 解释交互与日志策略，`migration` 与 `observability` 描述 Proxy 专属能力。
- **Error Codes**：`user-manual/error-code/sql-error-code.en.md` 将 SQL 状态、Vendor Code 与常见提示（含 DistSQL 专属）对应；在排错时可结合 FAQ 使用。
- **模式配置**：`user-manual/shardingsphere-jdbc/yaml-config/mode.en.md` + `.../java-api/mode.en.md` 统一说明 Standalone/Cluster 以及 ZooKeeper/Etcd/Nacos/Consul 的属性映射，便于在源码中查找 `ModeConfiguration`、`RepositoryConfiguration` 相关实现。

---

## 21. Dev & Test Manual 一览

- **Dev Manual**：`docs/document/content/dev-manual` 罗列每种 SPI（算法、连接池、执行引擎、协议适配等）的扩展点，配合源码 `infra/spi`、`infra/algorithm` 可迅速定位接口；社区鼓励将自研插件回馈，路径、审核要求可参考 `CONTRIBUTING.md`。
- **Test Manual**：三种测试引擎贯穿 `integration`（端到端对接真实库）、`module`（Parser/Rewriter 等复杂模块）、`performance`（Sysbench/JMH/TPCC）。整个测试框架大量使用 XML 用例 + Docker 镜像，意味着无需写 Java 代码即可新增断言；对学习者而言，可把这些 XML 视为“行为文档”来理解 SQL 改写/路由预期。
- **实践提示**：阅读代码时，可同时打开 `test/it` 与 `docs/document/content/test-manual`，确认对应模块的测试策略。例如 Parser 模块在 Module Test 中定义了 SQL → AST 预期，便于逆向理解语法支持范围。

---

## 22. FAQ 与排障策略

- **JDBC / 事务**：`FAQ#JDBC` 提到在 Spring Boot 中引入 `shardingsphere-transaction-xa-core` 会触发 `JtaAutoConfiguration`，需要 `@SpringBootApplication(exclude = JtaAutoConfiguration.class)`；Oracle 元数据大小写需与 YAML 一致，否则加载失败。
- **Proxy**：常见问题包括 Windows 解压包导致 `Bootstrap` 缺失、未创建逻辑库/Storage Unit 时客户端失败、推荐的客户端（MySQL CLI、DataGrip）。文档也提供 `CREATE/DROP DATABASE` DistSQL 样例支持动态建库。
- **Sharding & KeyGen**：FAQ 解释了 inline 表达式与 Spring 占位符冲突、浮点除法、长整型转换异常、Snowflake 偶数结尾等；建议在 5.x 启用 `allow-range-query-with-inline-sharding` 以放宽范围查询。
- **Encryption**：与 JPA 联用需手工建表（包含 cipher/plain/assisted 列），并关闭自动建模；这是调研现成业务接入时需要注意的文档提示。
- **DistSQL**：列举了注册数据源时缺 JDBC 驱动、删除 Storage Unit 被单表引用、如何设定连接池属性等问题；这些都是真实运维场景中最易踩的坑。
- **调试建议**：无论是 FAQ 还是 Error Codes，文档都反复强调 `props.sql-show=true` 能输出逻辑 SQL、真实 SQL 与解析结果（日志主题 `ShardingSphere-SQL`），是定位问题的首选工具。

---

> **下一步**：选择一个感兴趣的 Feature（如分片或加密），以本手册为导航，结合 `examples` 与 PDF 资料开始纵深阅读；每深入一个环节，补充自己的“源码笔记”，逐渐构建专属知识库。
