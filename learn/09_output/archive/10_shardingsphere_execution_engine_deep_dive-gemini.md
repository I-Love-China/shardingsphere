# ShardingSphere SQL 执行引擎深度解析

## 引言

在 ShardingSphere 的核心流程中，SQL 执行引擎扮演着至关重要的角色。它位于 SQL 解析、路由和重写之后，负责将重写后的 SQL 语句在目标数据源上高效、准确地执行。本篇文档将深入探讨 ShardingSphere 执行引擎的设计思想、核心组件以及工作流程，帮助开发者全面理解其底层的运行机制。

## 学习大纲

1.  **执行引擎在 ShardingSphere 中的位置**
    *   回顾 SQL 处理全流程
    *   执行引擎的承上启下作用
2.  **执行入口：`JDBCExecutor`**
    *   统一的 JDBC 执行入口
    *   在 ShardingSphere-JDBC 中的调用
    *   在 ShardingSphere-Proxy 中的调用
3.  **核心调度器：`ExecutorEngine` 详解**
    *   `ExecutorEngine` 的职责与设计
    *   并发模型：并行执行 (`parallelExecute`) vs 串行执行 (`serialExecute`)
    *   线程池管理：`ExecutorServiceManager`
4.  **关键决策：事务与执行策略**
    *   `isInTransaction()` 的决定性作用
    *   事务安全与性能的平衡
5.  **核心模型剖析**
    *   `ExecutionGroupContext` 与 `ExecutionGroup`
    *   `ExecutionUnit` 与 `JDBCExecutionUnit`
    *   回调模式：`ExecutorCallback`
6.  **展望：结果归并 (`infra/merge`)**
    *   为何需要结果归并
    *   与执行引擎的交互
7.  **总结**

---

## 1. 执行引擎在 ShardingSphere 中的位置

为了理解执行引擎，我们首先需要回顾 ShardingSphere 处理 SQL 的完整流程：

`SQL 解析 -> SQL 路由 -> SQL 重写 -> **SQL 执行** -> 结果归并`

执行引擎精确地处在这个流程的第四步。它承接了上游模块（路由和重写）的产物——`ExecutionContext`，这个上下文中包含了需要在哪些数据源上执行哪些具体的 SQL 语句。执行引擎的核心任务就是高效地完成这些 SQL 的实际执行工作，并将执行结果传递给下游的“结果归并”模块。

## 2. 执行入口：`JDBCExecutor`

ShardingSphere 通过 `JDBCExecutor` 提供了一个统一的、标准的 JDBC 操作入口。无论是在 ShardingSphere-JDBC 客户端，还是在 ShardingSphere-Proxy 服务端，最终的数据库交互都是通过这个类来完成的。

在创建 `JDBCExecutor` 实例时，必须注入两个关键对象：

-   `ExecutorEngine`: 核心的执行调度器，负责管理并发和执行流程。
-   `ConnectionContext`: 连接上下文，封装了当前连接的状态，其中最关键的就是**事务状态**。

### 在 ShardingSphere-JDBC 中的调用

当用户通过标准的 JDBC 接口（如 `Statement.execute()` 或 `PreparedStatement.execute()`）执行 SQL 时，ShardingSphere-JDBC 驱动会在 `DriverExecutor` 或 `ShardingSpherePreparedStatement` 中创建 `JDBCExecutor`，并发起调用。

### 在 ShardingSphere-Proxy 中的调用

在 Proxy 模式下，当服务器接收到来自客户端的 SQL 请求时，它会通过 `DatabaseConnector` 等后端组件，最终创建一个 `JDBCExecutor` 实例，用它来执行分发到后端真实数据库的 SQL。

## 3. 核心调度器：`ExecutorEngine` 详解

`ExecutorEngine` 是 ShardingSphere 执行引擎的“大脑”，它不关心具体的 SQL 如何执行，而是专注于**如何调度**这些执行任务。

### 并发模型：并行执行 vs 串行执行

`ExecutorEngine` 的核心 `execute` 方法中，包含一个至关重要的布尔参数 `serial`。这个参数决定了 SQL 将以何种方式执行：

-   **`parallelExecute` (并行执行, `serial=false`)**: 这是默认的行为。`ExecutorEngine` 会利用其内部管理的线程池，将发往不同数据源的 SQL 执行请求（`ExecutionGroup`）作为独立的任务并发执行。它会首先同步执行第一个任务组，然后将剩余的任务组异步提交到线程池，最后等待所有任务完成并汇总结果。这种方式能最大限度地利用多核 CPU 和网络 I/O，显著提升查询性能。

-   **`serialExecute` (串行执行, `serial=true`)**: 在此模式下，`ExecutorEngine` 会在一个单独的线程中，按顺序依次执行每一个 `ExecutionGroup`。它会遍历所有的任务组，并同步地、一个接一个地执行它们。

### 线程池管理：`ExecutorServiceManager`

`ExecutorEngine` 通过 `ExecutorServiceManager` 来创建和管理其内部的线程池。线程池的大小是动态计算的，ShardingSphere 会根据当前的 CPU 核心数和需要访问的数据库资源数来确定一个合理的线程数量，以达到资源利用率和性能的最佳平衡。

## 4. 关键决策：事务与执行策略

那么，ShardingSphere 是如何决定何时使用并行执行，何时又必须使用串行执行呢？答案就在 `JDBCExecutor` 的 `execute` 方法中：

```java
// In JDBCExecutor.java
return executorEngine.execute(..., connectionContext.getTransactionConnectionContext().isInTransaction());
```

`serial` 参数的值，直接取决于 `isInTransaction()` 方法的返回值。

-   **当不在事务中 (`isInTransaction()` 返回 `false`)**: ShardingSphere 会选择**并行执行**，以追求极致的性能。
-   **当处于事务中 (`isInTransaction()` 返回 `true`)**: ShardingSphere 会强制切换到**串行执行**。这是为了保证事务的 ACID 特性。在分布式环境中，并行执行事务操作可能会导致数据不一致或死锁等问题。通过串行执行，ShardingSphere 确保了在一个事务内的所有 SQL 操作都会在同一个连接上、按照预期的顺序依次执行。

这种动态切换策略，体现了 ShardingSphere 在性能和数据一致性之间所做的精妙平衡。

## 5. 核心模型剖析

-   **`ExecutionGroupContext` / `ExecutionGroup`**: 这是执行任务的分组。通常，发往同一个数据源的多个 SQL 操作会被分到同一个 `ExecutionGroup` 中，以便在同一个数据库连接上执行。
-   **`ExecutionUnit` / `JDBCExecutionUnit`**: 这是最小的执行单元，它封装了需要执行的 SQL 语句以及其对应的数据源。
-   **`ExecutorCallback`**: 这是一个回调接口，`ExecutorEngine` 通过它来执行具体的数据库操作。这种设计将调度逻辑与执行逻辑解耦，`JDBCExecutorCallback` 就是其针对 JDBC 场景的具体实现。

## 6. 展望：结果归并 (`infra/merge`)

当 `ExecutorEngine` 在多个数据源上执行完 SQL 后（特别是 `SELECT` 查询），我们会得到多个独立的 `QueryResult` 结果集。然而，对于上层应用来说，它需要的是一个统一的、逻辑上完整的结果集。

这就是**结果归并 (`infra/merge`)** 模块的职责。它会作为执行引擎的下游模块，负责消费多个 `QueryResult`，并通过排序、聚合、分页等计算，将它们合并成一个单一的、连续的数据流，最终返回给调用方。

对结果归并的深入学习，将是我们理解 ShardingSphere 分布式查询能力的关键。

## 7. 总结

ShardingSphere 的执行引擎是一个设计精巧、高度优化的组件。它通过 `ExecutorEngine` 实现了灵活的串行/并行执行调度，通过 `JDBCExecutor` 提供了统一的执行入口，并巧妙地利用事务状态来动态调整执行策略，从而在保证数据一致性的同时，最大化地提升了系统的执行性能。
