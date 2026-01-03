# ShardingSphere 归并引擎 (Merge Engine) 源码学习文档 (修订版)

## 第1章：高屋建瓴——归并引擎的本质与挑战

在分布式数据库环境中，一个逻辑上的查询请求往往被“分而治之”(Scatter-Gather)，即被路由到多个物理数据库或物理表中去执行。

**核心挑战：** 客户端期望得到一个完整的、有序的、逻辑正确的结果集，但从底层数据库返回的是多个独立的、物理上的结果集。

例如，一个分组聚合加排序分页的查询：
```sql
SELECT AVG(score) as avg_score, class_id 
FROM t_score 
GROUP BY class_id 
ORDER BY avg_score DESC 
LIMIT 5, 5;
```
这条SQL在分片后，ShardingSphere会收到多个物理库返回的结果。归并引擎必须完成以下全部工作：
1.  **合并 (Merge)**：将多个物理结果集合并成一个统一的数据视图。
2.  **计算 (Compute)**：对合并后的数据进行再计算，如 `AVG` 需要重新计算（`SUM(score) / SUM(count)`），`COUNT` 需要累加。
3.  **排序 (Sort)**：对计算后的结果集进行全局排序。
4.  **分页 (Paginate)**：在全局有序的结果集上定位到正确的分页位置。

**一句话总结其本质：** **归并引擎是分布式环境下，SQL查询“分治”思想中“合”的过程的实现者，它通过一系列的合并、装饰、计算，将物理结果集还原为逻辑结果集，是保证数据正确性的最后一道关卡。**

## 第2章：新架构设计——“合并”与“装饰”分离

相较于早期版本，当前代码库对归გ合并引擎的设计进行了重构，核心思想是将 **“合并” (Merge)** 和 **“装饰” (Decorate)** 两个核心动作彻底分离，使得职责更清晰，扩展性更强。

- **合并 (Merge)**: 指的是将 **多个** `QueryResult` 合并成 **一个** `MergedResult` 的过程。这通常是归并流程的第一步。例如，将两个有序的结果集通过优先队列合并成一个全局有序的流式结果集。
- **装饰 (Decorate)**: 指的是在 **一个** `MergedResult` (或者原始的 `QueryResult`) 的基础上，附加额外计算、处理逻辑的过程。它接收一个结果集，输出一个“增强”了的结果集。例如，为已经合并排序好的结果集添加分页（LIMIT）处理。

这种分离完美地契合了装饰器模式，形成了更加清晰的调用链。

```mermaid
graph TD
    subgraph ResultProcessEngine (总入口)
        subgraph ResultDecoratorEngine (装饰)
            A(LimitDecorator) --> B(GroupByDecorator)
            B --> C(OrderByDecorator)
        end
        subgraph ResultMergerEngine (合并)
            D(StreamMerger)
        end
    end

    C --> D
    
    subgraph 原始数据来源
        E[QueryResult from DB_0]
        F[QueryResult from DB_1]
    end

    D --> E
    D --> F

    style D fill:#f9f,stroke:#333,stroke-width:2px
    style A fill:#bbf,stroke:#333,stroke-width:2px
    style B fill:#bbf,stroke:#333,stroke-width:2px
    style C fill:#bbf,stroke:#333,stroke-width:2px
```

## 第3章：源码寻踪——新架构下的核心类

归并引擎的核心代码位于 `infra/merge` 模块。

```bash
shardingsphere/infra/merge/src/main/java/org/apache/shardingsphere/infra/merge/
├── engine/
│   ├── MergeEngine.java              # 旧的入口，可能为了兼容性保留
│   ├── ResultProcessEngine.java      # **新的总入口**，协调 Merger 和 Decorator
│   ├── merger/
│   │   ├── ResultMerger.java         # Merger 的顶层 SPI 接口
│   │   └── ResultMergerEngine.java   # **合并引擎**，负责选择并执行 Merger
│   └── decorator/
│       ├── ResultDecorator.java      # Decorator 的顶层 SPI 接口
│       └── ResultDecoratorEngine.java# **装饰引擎**，负责构建 Decorator 链
└── result/
    ├── MergedResult.java             # 归并结果集的统一接口
    └── impl/
        ├── stream/
        │   └── StreamMergedResult.java # 流式归并结果
        ├── memory/
        │   └── MemoryMergedResult.java # 内存归并结果
        └── decorator/
            └── DecoratorMergedResult.java # 装饰器包装后的结果
```

### 核心类职责分析：

1.  **`ResultProcessEngine.java`**:
    - **定位：** 新架构下的**总指挥**。
    - **核心方法：** `process(List<QueryResult> queryResults, ...)`。
    - **职责：**
        1.  调用 `ResultMergerEngine` 将 `List<QueryResult>` **合并**成一个 `MergedResult`。
        2.  调用 `ResultDecoratorEngine` 将上一步得到的 `MergedResult` 进行层层**装饰**。
        3.  返回最终被完全处理的 `MergedResult`。

2.  **`ResultMergerEngine.java`**:
    - **定位：** **合并引擎**，负责 "多变一"。
    - **核心逻辑：**
        - 它通过 ShardingSphere 的 SPI 机制查找所有 `ResultMerger` 的实现。
        - 根据当前的 `SQLStatementContext` 和规则，选择一个最合适的 `ResultMerger`（例如，如果SQL包含 `ORDER BY`，可能会选择 `StreamMerger` 来创建一个 `StreamMergedResult`）。
        - 如果没有特定的 Merger，会使用默认的 `TransparentResultMerger`，它只是简单地将多个结果集封装起来，并不做任何处理。

3.  **`ResultDecoratorEngine.java`**:
    - **定位：** **装饰引擎**，负责 "一变一 (增强版)"。
    - **核心逻辑：**
        - 与 Merger 类似，它通过 SPI 机制查找所有 `ResultDecorator` 的实现。
        - **它会构建一个装饰链**。根据 `SQLStatementContext` 中的信息（如 `LIMIT`, `GROUP BY` 等），它会依次创建 `LimitDecorator`, `GroupByDecorator`, `OrderByDecorator` 等，并将它们像洋葱一样层层包裹起来。
        - 创建的顺序至关重要，通常是 `OrderBy -> GroupBy -> Limit` 的逆向顺序来包装。

4.  **`ResultMerger` & `ResultDecorator` SPI 接口**:
    - **定位：** 扩展性的基石。
    - **实现：** ShardingSphere 为各种场景提供了内置实现，例如：
        - `dql/orderby/OrderByStreamMerger.java`: 用于处理带 `ORDER BY` 的流式合并。
        - `dql/groupby/GroupByMemoryMerger.java`: 用于处理 `GROUP BY` 的内存计算合并。
        - `dql/limit/LimitDecorator.java`: 用于实现分页逻辑的装饰器。
    - 用户也可以通过实现这些接口来注入自定义的归并或装饰逻辑。

## 第4章：实战演练——一条SQL的归并之旅 (新架构版)

再次分析这条SQL：
`SELECT AVG(score) as avg_score, class_id FROM t_score GROUP BY class_id ORDER BY avg_score DESC LIMIT 5, 5;`

1.  **入口: `ResultProcessEngine.process(...)`**
    - `ResultProcessEngine` 接收到多个 `QueryResult` 和 `SQLStatementContext`。

2.  **第一步：调用 `ResultMergerEngine` 进行合并**
    - `ResultMergerEngine` 启动，扫描所有 `ResultMerger` 实现。
    - 它发现SQL中包含 `ORDER BY`，因此匹配到 `OrderByStreamMerger`。
    - `OrderByStreamMerger` 创建一个 `StreamMergedResult` 实例。这个实例内部维护了一个**优先队列**，能够从多个有序的 `QueryResult` 中流式地输出全局有序的数据行。
    - `ResultMergerEngine` 返回这个 `StreamMergedResult`。

3.  **第二步：调用 `ResultDecoratorEngine` 进行装饰**
    - `ResultDecoratorEngine` 接收到上一步返回的 `StreamMergedResult`。
    - 它开始构建装饰链，检查 `SQLStatementContext`:
        - **发现 `GROUP BY` 和 `AVG` 聚合函数**：创建一个 `GroupByDecorator` 来包装 `StreamMergedResult`。`GroupByDecorator` 会从下层的流式结果中拉取数据，在内存中进行分组和聚合计算。
        - **发现 `ORDER BY`**：(这一步可能与 `GroupByDecorator` 结合处理，或者由一个独立的 `OrderByDecorator` 负责)。`OrderByDecorator` 会确保从 `GroupByDecorator` 出来的数据是全局有序的。
        - **发现 `LIMIT`**：创建一个 `LimitDecorator` 来包装 `OrderByDecorator`。
    - `ResultDecoratorEngine` 返回最终被层层包裹的 `LimitDecorator` 实例。

4.  **返回与消费**
    - `ResultProcessEngine` 返回这个 `LimitDecorator`。
    - 上层代码调用 `limitDecorator.next()`：
        - 请求会穿透 `LimitDecorator` -> `OrderByDecorator` -> `GroupByDecorator` -> `StreamMergedResult`。
        - 数据从 `StreamMergedResult` 流出，经过 `GroupByDecorator` 的内存计算，再经过 `OrderByDecorator` 的排序，最后被 `LimitDecorator` 进行计数和分页处理，最终返回给客户端。

## 第5章：总结与展望

- **架构演进：** 新架构将“合并”和“装饰”的职责分离，使得逻辑更清晰，代码结构更合理，更符合设计模式的最佳实践。
- **核心驱动：** 整个归并过程由 SPI 驱动，提供了极佳的扩展性。无论是内置功能还是用户自定义逻辑，都可以无缝集成。
- **性能考量：** 归并的代价依然存在。内存归并（如 `GROUP BY`）和需要将大量数据拉到内存中进行排序的场景，仍然是需要重点关注的性能点。理解归并机制有助于在业务层面写出更“分布式友好”的SQL。

### 下一步学习建议
在你理解了新架构后，调试的入口应该放在 `ResultProcessEngine` 的 `process` 方法。通过单步调试，观察 `ResultMergerEngine` 如何选择 Merger，以及 `ResultDecoratorEngine` 如何一步步构建装饰链，这将使你对当前版本归并引擎的理解更加深刻。