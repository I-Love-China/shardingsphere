# Apache ShardingSphere 宏观学习手册 (v2)

> **目标**：本手册旨在通过整合官方文档、核心源码结构与社区资料，为 ShardingSphere 的学习者提供一份高质量的“鸟瞰图”和“代码漫游指南”。阅读完本手册，您应能清晰地理解 ShardingSphere 的设计哲学、核心架构、代码组织，并获得一套行之有效的源码学习路径。

---

## 1. 宏观蓝图：什么是 ShardingSphere？

### 1.1. 设计哲学：Database Plus

ShardingSphere 的核心理念是 **Database Plus**，它定位自身为一个在异构数据库之上的“生态系统”，而非一个新的数据库。其价值在于“连接、增强、可插拔”。

-   **连接 (Connect)**：通过适配多种数据库的协议和 SQL 方言，透明地连接并管理底层多样化的数据源。
-   **增强 (Enhance)**：在不侵入数据库内核的前提下，提供数据分片、读写分离、数据加密、分布式事务等一系列增强能力。
-   **可插拔 (Pluggable)**：整个内核基于高度可扩展的架构设计，所有核心功能（甚至包括内核自身）都可以通过 SPI（Service Provider Interface）进行替换或扩展。

![Database Plus 设计哲学](../../docs/document/static/img/design_cn.png)

### 1.2. 产品形态：JDBC vs. Proxy

ShardingSphere 提供两种核心产品形态，以适应不同场景：

| 维度 | ShardingSphere-JDBC | ShardingSphere-Proxy |
| :--- | :--- | :--- |
| **集成方式** | 以 JAR 包形式嵌入 Java 应用，应用直连数据库。 | 独立的数据库代理服务，应用像连接普通数据库一样连接它。 |
| **适用语言** | 仅 Java | 任何语言（只要有 MySQL/PostgreSQL 客户端）。 |
| **性能损耗** | 低（轻量级，无网络转发） | 略高（增加一次网络跳转） |
| **运维友好** | 对应用透明，但规则管理分散。 | DBA 友好，可通过 DistSQL 集中管理所有规则与资源。 |
| **典型场景** | 高性能 OLTP、Java 单体应用增强。 | 跨语言服务、数据库运维中台、数据安全网关。 |

![ShardingSphere-JDBC 体系](../../docs/document/static/img/shardingsphere-jdbc_v3.png)
![ShardingSphere-Proxy 体系](../../docs/document/static/img/shardingsphere-proxy_v2.png)

### 1.3. 顶层代码结构：模块依赖关系

在深入源码前，理解其顶层模块的依赖关系至关重要。这揭示了项目的静态架构和分层思想。

```mermaid
graph TD
    subgraph 用户端 (User Facing)
        A[sharding-jdbc]
        B[sharding-proxy]
    end

    subgraph 核心能力 (Core Features)
        C[features/sharding]
        D[features/readwrite-splitting]
        E[features/encrypt]
        F[...]
    end

    subgraph 内核 (Kernel)
        G[kernel/transaction]
        H[kernel/data-pipeline]
        I[kernel/sql-federation]
        J[...]
    end

    subgraph 基础设施 (Infrastructure)
        K[infra/parser]
        L[infra/route]
        M[infra/rewrite]
        N[infra/executor]
        O[infra/merge]
        P[infra/spi]
        Q[...]
    end

    A --> C & D & E
    B --> C & D & E

    C & D & E --> G & H & I

    G & H & I --> K & L & M & N & O & P

    subgraph 说明
        direction LR
        S1["<br/>- JDBC/Proxy 依赖 Features<br/>- Features 依赖 Kernel<br/>- Kernel 依赖 Infra"]
    end

    style S1 fill:#fff,stroke:#fff,stroke-width:0px
```
-   `infra`: 项目最底层的“积木库”，提供 SQL 解析、路由、执行、SPI 等与业务无关的通用工具。
-   `kernel`: 面向业务场景的“内核实现”，如事务、数据迁移、元数据管理等，它负责组合 `infra` 的能力。
-   `features`: 用户可直接感知的“功能集”，如分片、读写分离等。它们是 `kernel` 和 `infra` 的具体应用场景。
-   `jdbc` / `proxy`: 面向用户的两种不同“外壳”，共享底层的 `features`, `kernel`, `infra`。

---

## 2. 核心引擎：一条 SQL 的生命周期

ShardingSphere 的核心是对 SQL 的拦截与增强。理解一条 SQL 如何在 ShardingSphere 内部流转，是掌握其脉络的关键。

### 2.1. SQL 生命周期与插件化增强

```mermaid
flowchart LR
    A[SQL 请求] --> B{Parse<br/>infra/parser};
    B --> C{Route<br/>infra/route};
    C --> D{Rewrite<br/>infra/rewrite};
    D --> E{Execute<br/>infra/executor};
    E --> F{Merge<br/>infra/merge};
    F --> G[返回结果];

    subgraph Features (可插拔功能)
        direction TB
        sharding["sharding<br/>(分片)"]
        rw_splitting["readwrite-splitting<br/>(读写分离)"]
        encrypt["encrypt<br/>(加密)"]
        shadow["shadow<br/>(影子库)"]
    end

    sharding -- "影响路由和改写" --> C & D;
    rw_splitting -- "影响路由" --> C;
    encrypt -- "影响改写" --> D;
    shadow -- "影响路由" -- > C;
```

1.  **Parse (解析)**: 将传入的 SQL 文本解析成抽象语法树（AST）。这是后续所有操作的基础。
2.  **Route (路由)**: 根据分片键（Sharding Key）、事务状态或 Hint 信息，计算出该 SQL 应该发往哪些真实的数据库或表。读写分离、影子库等功能主要在此阶段介入。
3.  **Rewrite (改写)**: 根据路由结果，将原始 SQL 改写成能在目标数据库上正确执行的真实 SQL。例如，会自动补充 schema、修改表名、优化分页查询等。
4.  **Execute (执行)**: 通过底层的数据库连接池，并发地将改写后的 SQL 发往各个目标数据库执行。
5.  **Merge (归并)**: 如果 SQL 被路由到多个数据库或表，此阶段会将多个执行结果集进行流式或内存归并，最终组合成一个对用户透明的、逻辑上完整的结果集。

### 2.2. 生命周期各阶段的代码入口

| 阶段 | 作用 | 核心代码入口 (类/接口) | 源码路径 |
| :--- | :--- | :--- | :--- |
| **Parse** | SQL 解析 | `SQLParserEngine` | `infra/parser/src/main/java/org/apache/shardingsphere/infra/parser/SQLParserEngine.java` |
| **Route** | SQL 路由 | `SQLRouter` | `infra/route/src/main/java/org/apache/shardingsphere/infra/route/SQLRouter.java` |
| **Rewrite**| SQL 改写 | `SQLRewriteEngine`| `infra/rewrite/src/main/java/org/apache/shardingsphere/infra/rewrite/SQLRewriteEngine.java` |
| **Execute**| SQL 执行 | `KernelProcessor` | `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/process/KernelProcessor.java` |
| **Merge** | 结果归并 | `ResultMerger` | `infra/merge/src/main/java/org/apache/shardingsphere/infra/merge/ResultMerger.java` |

---

## 3. 功能地图：ShardingSphere 的核心能力

| 核心能力 | 描述 | 源码目录 |
| :--- | :--- | :--- |
| **数据分片 (Sharding)** | 核心能力。支持水平/垂直分库分表，提供多种分片算法，可通过 DistSQL 在线管理。 | `features/sharding` |
| **读写分离 (Read/Write Splitting)** | 自动路由读请求到从库，写请求到主库。感知主从拓扑，支持多种负载均衡策略。 | `features/readwrite-splitting` |
| **分布式事务 (Transaction)** | 提供 XA（强一致）和 BASE（柔性/最终一致）事务方案，内置 Seata 支持。 | `kernel/transaction` |
| **数据安全 (Security)** | 提供列加密、数据脱敏（Mask）、影子库（压测）、SQL 防火墙等能力。 | `features/encrypt`, `features/mask`, `features/shadow` |
| **弹性伸缩 (Elastic Scaling)** | 支持在线数据迁移与扩缩容，通常与 DistSQL 配合使用，实现平滑扩容。 | `kernel/data-pipeline` |
| **SQL 联邦 (Federation)** | 实验性功能。支持跨不同数据源进行关联查询（JOIN）和聚合，用于 OLAP 场景。 | `kernel/sql-federation` |
| **可观测性 (Observability)** | 通过 Agent 插件体系，将 Trace、Metrics、Log 对接到 SkyWalking、Prometheus 等系统。 | `agent/` |

---

## 4. 源码漫游：关键代码入口汇总

除了生命周期中的核心接口，以下是探索 ShardingSphere 世界版图时的一些关键“传送门”：

| 模块/概念 | 关键代码入口 (类/接口) | 源码路径 |
| :--- | :--- | :--- |
| **JDBC 启动** | `ShardingSphereDriver` | `jdbc/core/src/main/java/org/apache/shardingsphere/driver/ShardingSphereDriver.java` |
| | `ShardingSphereDataSourceFactory` | `jdbc/core/src/main/java/org/apache/shardingsphere/driver/api/ShardingSphereDataSourceFactory.java` |
| **Proxy 启动** | `ShardingSphereProxyBootstrap` | `proxy/bootstrap/src/main/java/org/apache/shardingsphere/proxy/Bootstrap.java` |
| **插件化核心 (SPI)** | `ShardingSphereSPI` | `infra/spi/src/main/java/org/apache/shardingsphere/infra/spi/ShardingSphereSPI.java` |
| **DistSQL 解析** | `DistSQLStatement` (Marker Interface) | `distsql/statement/src/main/java/org/apache/shardingsphere/distsql/statement/DistSQLStatement.java` |
| **DistSQL 执行** | `DistSQLConnectionContext` | `distsql/handler/src/main/java/org/apache/shardingsphere/distsql/handler/connection/DistSQLConnectionContext.java` |
| **模式配置** | `ModeConfiguration` | `mode/core/src/main/java/org/apache/shardingsphere/mode/config/ModeConfiguration.java` |

---

## 5. 上手实践：从配置到调试

### 5.1. 运行模式：Standalone vs. Cluster

-   **Standalone（单机模式）**：配置存储在本地文件，简单易用，适合开发和测试。是默认模式。
-   **Cluster（集群模式）**：依赖注册中心（如 ZooKeeper, Etcd）来存储和同步元数据与规则。Proxy 集群的节点可以动态上下线，规则可全局生效，是生产环境的首选。

### 5.2. DistSQL：现代化的治理方式

DistSQL (Distributed SQL) 是 ShardingSphere 5.x 版本的标志性功能，它允许用户像操作数据库一样，通过 SQL 语言来管理和配置 ShardingSphere。

```sql
-- (RDL) 创建一个逻辑数据源
CREATE STORAGE UNIT ds_0 (
  URL='jdbc:mysql://127.0.0.1:3306/demo_ds_0',
  USER='root', PASSWORD='***'
);

-- (RDL) 创建一个分片规则
CREATE SHARDING TABLE RULE t_order (
  DATANODES("ds_0.t_order_0", "ds_0.t_order_1"),
  TABLE_STRATEGY(TYPE="inline", SHARDING_COLUMN=order_id, ALGORITHM_EXPRESSION="t_order_${order_id % 2}")
);

-- (RQL) 查询规则
SHOW SHARDING TABLE RULES;

-- (RAL) 查看集群实例状态
SHOW INSTANCE LIST;
```
**优势**：彻底告别了繁琐的 YAML 文件修改和重启，实现了配置的在线化、动态化和标准化。**强烈建议新用户直接从 DistSQL 入手。**

### 5.3. 调试技巧

-   **开启 SQL 日志**：在 Proxy 的 `server.yaml` 或 JDBC 的 YAML 配置中，将 `props.sql-show` 设置为 `true`。这会打印出 **逻辑 SQL**、**真实 SQL** 和 **执行耗时**，是排查问题的首选。
-   **利用 `EXPLAIN`**：Proxy 支持 `EXPLAIN` 命令，可以查看 SQL 的解析、改写和路由计划，非常适合调试分片规则。
-   **跑通 `examples`**：项目 `examples/` 目录下有针对各种场景的最小化 Demo。通过 Debug 运行这些示例，是理解代码执行流程的最佳方式。

---

## 6. 学习路径与资源推荐

### 6.1. 推荐学习路径

1.  **环境搭建与初体验**：
    -   根据官方 `README.md` 搭建好 Maven 与 JDK 环境。
    -   下载 ShardingSphere-Proxy 的二进制包，参照 `quick-start` 文档，用 DistSQL 跑通一个最简单的分片示例。

2.  **代码调试，跟踪生命周期**：
    -   找到 `examples/shardingsphere-jdbc-example` 项目。
    -   选择一个分片示例（如 `ShardingDatabasesAndTablesExample`），在 `main` 方法入口打上断点。
    -   以 `ShardingSphereDataSource.getConnection()` 为起点，单步调试，亲身跟踪一条 SQL 从 `Parse` 到 `Merge` 的完整过程。对照本手册第 2 节的流程图和代码入口。

3.  **专项突破，深挖一个 Feature**：
    -   选择一个你最感兴趣的 `feature`（推荐从 `sharding` 或 `readwrite-splitting` 开始）。
    -   阅读 `docs/document/content/features` 下对应的官方文档，理解其设计原理和配置方法。
    -   回到源码，深入 `features/{your-feature}` 和 `infra` 中对应的模块，理解其实现细节。例如，阅读 `ShardingSphereRoutingEngine` 如何处理分片逻辑。

4.  **参与社区，贡献代码**：
    -   关注 GitHub Issues 中的 `good first issue` 或 `volunteer wanted` 标签。
    -   从修复一个 bug、补充一个测试用例或改进一段文档开始，提交你的第一个 Pull Request。这是检验学习成果和获得社区认可的最佳途径。

### 6.2. 核心资源索引

| 资源 | 说明 |
| :--- | :--- |
| `README_ZH.md` / `docs/document/content/overview/` | 官方概述、产品定位、部署对比。 |
| `docs/document/content/quick-start/` | JDBC 与 Proxy 的最短路径上手指南。 |
| `docs/document/content/user-manual/` | 最权威的用户手册，包含 DistSQL 语法、YAML 配置等。 |
| `docs/document/content/dev-manual/` | 开发者手册，介绍如何扩展 SPI。 |
| `learn/02_resource/` 目录下的 PDF | 核心作者的演讲材料，提供了高度浓缩的架构解读。 |
| `examples/` 目录 | 覆盖各类场景的可运行、可调试的官方示例代码。 |

---

> **下一步**：现在，您可以从“学习路径”的第一步开始，立即动手实践。当遇到具体代码疑问时，再回到本手册的“代码入口”部分进行精确定位。祝您在 ShardingSphere 的世界中探索愉快！
