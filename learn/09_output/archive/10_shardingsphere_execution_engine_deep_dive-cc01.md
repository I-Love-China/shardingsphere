# ShardingSphere SQL 执行引擎深度源码剖析

## 前言

在 ShardingSphere 的 SQL 处理流程中，执行引擎是连接逻辑层与物理层的关键桥梁。它承接了 SQL 解析、路由和重写的成果，负责将重写后的 SQL 高效地分发到各个真实数据库节点并执行。本文将深入源码层面，剖析执行引擎的核心实现机制、设计模式应用和性能优化策略。

**核心模块位置**: `infra/executor`

**完整处理链路**:
```
SQL解析 → SQL路由 → SQL重写 → [SQL执行] → 结果归并
```

---

## 一、执行引擎架构概览

### 1.1 核心类层次结构

```
ExecutorEngine (核心调度器)
    ├── ExecutorServiceManager (线程池管理器)
    │
JDBCExecutor (JDBC执行入口)
    ├── 依赖 ExecutorEngine
    ├── 依赖 ConnectionContext
    │
JDBCExecutorCallback (执行回调抽象)
    ├── ProxyJDBCExecutorCallback
    │   ├── ProxyStatementExecutorCallback
    │   ├── ProxyPreparedStatementExecutorCallback
    ├── ExecuteQueryCallback
    │   ├── StatementExecuteQueryCallback
    │   ├── PreparedStatementExecuteQueryCallback
    ├── ExecuteUpdateCallback
    └── BatchPreparedStatementExecutorCallback
    │
DriverExecutionPrepareEngine (执行准备引擎)
    ├── 构建 ExecutionGroupContext
    ├── 管理 ExecutorConnectionManager
    └── 管理 ExecutorStatementManager
```

### 1.2 数据流转模型

```
RouteContext + ExecutionContext
    ↓
[DriverExecutionPrepareEngine]
    → 聚合 ExecutionUnit 按数据源分组
    → 计算连接模式 (MEMORY_STRICTLY / CONNECTION_STRICTLY)
    → 分组 (控制连接数)
    → 构建 ExecutionGroupContext
    ↓
ExecutionGroupContext<JDBCExecutionUnit>
    ↓
[JDBCExecutor.execute()]
    → 判断事务状态 (isInTransaction)
    ↓
[ExecutorEngine.execute()]
    ├─ serial=true  → serialExecute (事务中)
    └─ serial=false → parallelExecute (非事务)
    ↓
[JDBCExecutorCallback.execute()]
    → 遍历 JDBCExecutionUnit
    → 执行钩子 (SQLExecutionHook)
    → 调用 executeSQL() 模板方法
    → 异常处理 (Sane Result)
    ↓
List<QueryResult / UpdateResult>
```

---

## 二、ExecutorEngine - 核心调度器深度解析

### 2.1 类定义与初始化

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/kernel/ExecutorEngine.java`

```java
@Getter
public final class ExecutorEngine implements AutoCloseable {

    private static final int CPU_CORES = Runtime.getRuntime().availableProcessors();

    private final ExecutorServiceManager executorServiceManager;

    // 私有构造函数，强制使用工厂方法
    private ExecutorEngine(final int executorSize) {
        executorServiceManager = new ExecutorServiceManager(executorSize);
    }
}
```

**设计要点**:
1. **实现 `AutoCloseable`**: 支持 try-with-resources 自动释放线程池资源
2. **私有构造**: 隐藏实例化细节，确保通过工厂方法创建
3. **静态缓存 CPU_CORES**: 避免重复调用 `Runtime.getRuntime()`

### 2.2 线程池创建策略 (工厂方法模式)

```java
// 1. 指定固定大小
public static ExecutorEngine createExecutorEngineWithSize(final int executorSize) {
    return new ExecutorEngine(executorSize);
}

// 2. 基于 CPU 和资源数量计算
public static ExecutorEngine createExecutorEngineWithCPUAndResources(final int resourceCount) {
    int cpuThreadCount = CPU_CORES * 2 - 1;  // CPU核心数 * 2 - 1
    int resourceThreadCount = Math.max(resourceCount, 1);
    return new ExecutorEngine(Math.min(cpuThreadCount, resourceThreadCount));
}

// 3. 仅基于 CPU 计算
public static ExecutorEngine createExecutorEngineWithCPU() {
    return new ExecutorEngine(CPU_CORES * 2 - 1);
}
```

**线程数计算逻辑**:

| 场景 | 计算公式 | 示例 (4核CPU) |
|-----|---------|--------------|
| 纯 CPU 计算 | `CPU_CORES * 2 - 1` | 7 |
| CPU + 资源限制 | `min(CPU_CORES * 2 - 1, resourceCount)` | min(7, 3) = 3 |
| 固定大小 | 用户指定 | 10 |

**为何是 `* 2 - 1`?**
- I/O 密集型任务适合 CPU 核心数的 2-3 倍线程
- `-1` 是因为主线程会执行第一个任务组 (见后文并行执行分析)

### 2.3 并行执行核心实现 (parallelExecute)

```java
private <I, O> List<O> parallelExecute(final Iterator<ExecutionGroup<I>> executionGroups,
                                       final ExecutorCallback<I, O> firstCallback,
                                       final ExecutorCallback<I, O> callback) throws SQLException {
    // 步骤1: 取出第一个执行组
    ExecutionGroup<I> firstInputs = executionGroups.next();

    // 步骤2: 异步提交其余执行组到线程池
    Collection<Future<Collection<O>>> restResultFutures = asyncExecute(executionGroups, callback);

    // 步骤3: 主线程同步执行第一个组 (避免线程池饥饿)
    Collection<O> firstResults = syncExecute(firstInputs, null == firstCallback ? callback : firstCallback);

    // 步骤4: 合并所有结果
    return getGroupResults(firstResults, restResultFutures);
}
```

**关键设计决策 - 主线程执行第一组**:

这是 ShardingSphere 的一个精妙优化。假设场景:
- 有 4 个数据源，需要执行 4 个 ExecutionGroup
- 线程池大小为 3

如果全部提交到线程池:
```
线程1: Group1 → 阻塞等待 Group4
线程2: Group2 → 阻塞等待 Group4
线程3: Group3 → 阻塞等待 Group4
主线程: 空闲等待
Group4: 无法调度 (线程池已满)
→ 死锁!
```

采用主线程执行第一组:
```
主线程: Group1 (立即执行)
线程1: Group2
线程2: Group3
线程3: Group4
→ 充分利用资源,避免饥饿
```

**异步执行实现**:

```java
private <I, O> Collection<Future<Collection<O>>> asyncExecute(
        final Iterator<ExecutionGroup<I>> executionGroups,
        final ExecutorCallback<I, O> callback) {
    Collection<Future<Collection<O>>> result = new LinkedList<>();
    while (executionGroups.hasNext()) {
        result.add(asyncExecute(executionGroups.next(), callback));
    }
    return result;
}

private <I, O> Future<Collection<O>> asyncExecute(
        final ExecutionGroup<I> executionGroup,
        final ExecutorCallback<I, O> callback) {
    return executorServiceManager.getExecutorService().submit(() ->
        callback.execute(executionGroup.getInputs(), false)  // isTrunkThread=false
    );
}
```

**关键参数 `isTrunkThread`**:
- `true`: 主线程执行 (Trunk Thread)
- `false`: 工作线程执行 (Worker Thread)
- 作用: 钩子 (Hook) 可根据线程类型做不同处理 (如 APM 追踪)

**同步执行实现**:

```java
private <I, O> Collection<O> syncExecute(
        final ExecutionGroup<I> executionGroup,
        final ExecutorCallback<I, O> callback) throws SQLException {
    return callback.execute(executionGroup.getInputs(), true);  // isTrunkThread=true
}
```

**结果聚合 (等待所有 Future 完成)**:

```java
private <O> List<O> getGroupResults(
        final Collection<O> firstResults,
        final Collection<Future<Collection<O>>> restFutures) throws SQLException {
    List<O> result = new LinkedList<>(firstResults);
    for (Future<Collection<O>> each : restFutures) {
        try {
            result.addAll(each.get());  // 阻塞等待
        } catch (final InterruptedException | ExecutionException ex) {
            return throwException(ex);
        }
    }
    return result;
}
```

### 2.4 串行执行核心实现 (serialExecute)

```java
private <I, O> List<O> serialExecute(
        final Iterator<ExecutionGroup<I>> executionGroups,
        final ExecutorCallback<I, O> firstCallback,
        final ExecutorCallback<I, O> callback) throws SQLException {
    ExecutionGroup<I> firstInputs = executionGroups.next();
    List<O> result = new LinkedList<>(
        syncExecute(firstInputs, null == firstCallback ? callback : firstCallback)
    );

    // 顺序执行其余组
    while (executionGroups.hasNext()) {
        result.addAll(syncExecute(executionGroups.next(), callback));
    }
    return result;
}
```

**与并行执行的对比**:

| 特性 | 并行执行 (parallelExecute) | 串行执行 (serialExecute) |
|-----|---------------------------|-------------------------|
| 线程池使用 | ✅ 使用 (除第一组) | ❌ 不使用 |
| 主线程执行 | 第一组 | 所有组 |
| 执行顺序 | 无序 (并发) | 有序 (顺序) |
| 适用场景 | 非事务查询 | 事务操作 |
| 性能 | 高 (并发) | 低 (串行) |

### 2.5 统一执行入口 (策略模式)

```java
public <I, O> List<O> execute(
        final ExecutionGroupContext<I> executionGroupContext,
        final ExecutorCallback<I, O> firstCallback,
        final ExecutorCallback<I, O> callback,
        final boolean serial) throws SQLException {
    if (executionGroupContext.getInputGroups().isEmpty()) {
        return Collections.emptyList();
    }
    // 策略选择: serial 参数决定执行策略
    return serial
        ? serialExecute(executionGroupContext.getInputGroups().iterator(), firstCallback, callback)
        : parallelExecute(executionGroupContext.getInputGroups().iterator(), firstCallback, callback);
}
```

**设计模式应用 - 策略模式**:
- 上下文: `ExecutorEngine.execute()`
- 策略接口: `ExecutorCallback<I, O>`
- 具体策略: `serialExecute` / `parallelExecute`
- 策略选择器: `serial` 参数

---

## 三、ExecutorServiceManager - 线程池管理器

### 3.1 类定义与线程池创建

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/kernel/thread/ExecutorServiceManager.java`

```java
@Getter
public final class ExecutorServiceManager {

    private static final String DEFAULT_NAME_FORMAT = "%d";

    // 专用于关闭线程池的单线程执行器
    private static final ExecutorService SHUTDOWN_EXECUTOR =
        Executors.newSingleThreadExecutor(
            ExecutorThreadFactoryBuilder.build("Executor-Engine-Closer")
        );

    private final ExecutorService executorService;

    public ExecutorServiceManager(final int executorSize) {
        this(executorSize, DEFAULT_NAME_FORMAT);
    }

    public ExecutorServiceManager(final int executorSize, final String nameFormat) {
        // 使用阿里巴巴 TTL 包装
        executorService = TtlExecutors.getTtlExecutorService(
            getExecutorService(executorSize, nameFormat)
        );
    }

    private ExecutorService getExecutorService(final int executorSize, final String nameFormat) {
        ThreadFactory threadFactory = ExecutorThreadFactoryBuilder.build(nameFormat);
        // 0=CachedThreadPool, >0=FixedThreadPool
        return 0 == executorSize
            ? Executors.newCachedThreadPool(threadFactory)
            : Executors.newFixedThreadPool(executorSize, threadFactory);
    }
}
```

**线程池选择策略**:

| executorSize 值 | 线程池类型 | 特点 | 适用场景 |
|---------------|----------|------|---------|
| `0` | `CachedThreadPool` | 动态扩容,按需创建 | 任务数不确定 |
| `> 0` | `FixedThreadPool` | 固定大小,复用线程 | 任务数可预测 |

**TTL (TransmittableThreadLocal) 包装**:

ShardingSphere 使用阿里巴巴的 `transmittable-thread-local` 库来解决线程池场景下的上下文传递问题:

```java
// 未使用 TTL 的问题
ThreadLocal<String> context = new ThreadLocal<>();
context.set("user-123");

executorService.submit(() -> {
    context.get();  // null! 线程池线程无法获取父线程的 ThreadLocal
});

// 使用 TTL 后
TransmittableThreadLocal<String> context = new TransmittableThreadLocal<>();
context.set("user-123");

TtlExecutors.getTtlExecutorService(executorService).submit(() -> {
    context.get();  // "user-123" ✅ 自动传递
});
```

在 ShardingSphere 中,这对于传递以下上下文至关重要:
- 当前用户身份 (`Grantee`)
- 追踪 ID (`executionID`)
- 数据库名称 (`databaseName`)

### 3.2 线程工厂实现

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/kernel/thread/ExecutorThreadFactoryBuilder.java`

```java
public final class ExecutorThreadFactoryBuilder {

    private static final String NAME_FORMAT_PREFIX = "ShardingSphere-";

    public static ThreadFactory build(final String nameFormat) {
        return new ThreadFactoryBuilder()
            .setDaemon(true)  // 守护线程
            .setNameFormat(NAME_FORMAT_PREFIX + nameFormat)
            .build();
    }
}
```

**生成的线程命名示例**:
```
ShardingSphere-0
ShardingSphere-1
ShardingSphere-2
...
```

**为何使用守护线程?**
- JVM 在所有非守护线程结束后退出
- 执行引擎的工作线程应随主应用退出,不应阻止 JVM 关闭
- 如果设置为非守护线程,可能导致应用无法正常退出

### 3.3 优雅关闭机制

```java
public void close() {
    SHUTDOWN_EXECUTOR.execute(() -> {
        try {
            executorService.shutdown();  // 停止接受新任务
            // 等待已提交任务完成,每 5 秒检查一次
            while (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();  // 超时强制中断
            }
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    });
}
```

**关闭流程图**:

```
调用 close()
    ↓
提交到 SHUTDOWN_EXECUTOR (单独线程)
    ↓
executorService.shutdown() (拒绝新任务)
    ↓
awaitTermination(5s) (等待任务完成)
    ├─ 超时 → shutdownNow() (强制中断) → 继续等待
    └─ 完成 → 退出
```

**为何在单独线程中关闭?**

如果在主线程中执行 `awaitTermination(5s)`,会阻塞主线程 5 秒:
```java
// 阻塞主线程
executorEngine.close();
doSomethingElse();  // 需要等待关闭完成
```

使用 `SHUTDOWN_EXECUTOR`:
```java
// 不阻塞主线程
executorEngine.close();
doSomethingElse();  // 立即执行
```

---

## 四、JDBCExecutor - JDBC 执行入口

### 4.1 类定义与核心依赖

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/execute/engine/driver/jdbc/JDBCExecutor.java`

```java
@RequiredArgsConstructor
public final class JDBCExecutor {

    private final ExecutorEngine executorEngine;
    private final ConnectionContext connectionContext;
}
```

**两个核心依赖**:

1. **ExecutorEngine**: 提供调度能力
2. **ConnectionContext**: 提供上下文信息

**ConnectionContext 结构**:

```java
// infra/common/src/main/java/org/apache/shardingsphere/infra/context/ConnectionContext.java
@RequiredArgsConstructor
@Getter
public final class ConnectionContext implements AutoCloseable {

    private final CursorConnectionContext cursorConnectionContext = new CursorConnectionContext();
    private final TransactionConnectionContext transactionConnectionContext = new TransactionConnectionContext();

    @Setter
    private String trafficInstanceId;

    // ...
}
```

### 4.2 核心执行方法

```java
public <T> List<T> execute(
        final ExecutionGroupContext<JDBCExecutionUnit> executionGroupContext,
        final JDBCExecutorCallback<T> firstCallback,
        final JDBCExecutorCallback<T> callback) throws SQLException {
    try {
        // 核心决策: 根据事务状态决定串行/并行
        return executorEngine.execute(
            executionGroupContext,
            firstCallback,
            callback,
            connectionContext.getTransactionConnectionContext().isInTransaction()
        );
    } catch (final SQLException ex) {
        SQLExecutorExceptionHandler.handleException(ex);
        return Collections.emptyList();
    }
}
```

**关键决策逻辑**:

```java
boolean serial = connectionContext.getTransactionConnectionContext().isInTransaction();
```

| 场景 | isInTransaction() | serial | 执行策略 | 原因 |
|-----|------------------|--------|---------|------|
| 普通查询 | false | false | 并行执行 | 追求性能 |
| 事务操作 | true | true | 串行执行 | 保证隔离性 |

**为何事务中必须串行?**

考虑以下场景 (分布式事务):
```sql
BEGIN;
UPDATE db1.user SET balance = balance - 100 WHERE id = 1;
UPDATE db2.order SET status = 'paid' WHERE user_id = 1;
COMMIT;
```

如果并行执行:
```
线程1 (db1): UPDATE user ... (获取行锁)
线程2 (db2): UPDATE order ... (获取行锁)

如果 COMMIT 失败,需要 ROLLBACK:
线程1: ROLLBACK
线程2: ROLLBACK (可能已经 COMMIT!)
→ 数据不一致
```

串行执行保证:
- 操作按顺序执行
- 在同一连接上执行 (同一事务)
- COMMIT/ROLLBACK 原子性

### 4.3 异常处理机制

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/execute/engine/SQLExecutorExceptionHandler.java`

```java
public final class SQLExecutorExceptionHandler {

    private static final ThreadLocal<Boolean> IS_EXCEPTION_THROWN =
        ThreadLocal.withInitial(() -> true);

    public static void setExceptionThrown(final boolean isExceptionThrown) {
        IS_EXCEPTION_THROWN.set(isExceptionThrown);
    }

    public static void handleException(final Exception exception) throws SQLException {
        if (isExceptionThrown()) {
            if (exception instanceof SQLException) {
                throw (SQLException) exception;
            }
            throw new UnknownSQLException(exception).toSQLException();
        }
        // 不抛出异常,仅记录日志
        log.error("exception occur: ", exception);
    }

    private static boolean isExceptionThrown() {
        return IS_EXCEPTION_THROWN.get();
    }
}
```

**应用场景**:

某些场景下,ShardingSphere 需要"容忍"部分数据源的失败:
```java
// Sane Result 场景: 查询不存在的表
SELECT * FROM non_exist_table;

// MySQL 返回: Table 'non_exist_table' doesn't exist
// PostgreSQL 返回: relation "non_exist_table" does not exist

// 如果数据库类型不一致,ShardingSphere 可以返回空结果集,而不是抛异常
SQLExecutorExceptionHandler.setExceptionThrown(false);
```

---

## 五、JDBCExecutorCallback - 回调机制深度解析

### 5.1 回调接口层次

```
ExecutorCallback<I, O>  (顶层接口)
    ↓
JDBCExecutorCallback<T>  (JDBC 抽象回调)
    ↓
    ├── ProxyJDBCExecutorCallback  (Proxy 模式基类)
    │   ├── ProxyStatementExecutorCallback
    │   └── ProxyPreparedStatementExecutorCallback
    │
    ├── ExecuteQueryCallback  (查询回调)
    │   ├── StatementExecuteQueryCallback
    │   └── PreparedStatementExecuteQueryCallback
    │
    ├── ExecuteUpdateCallback  (更新回调)
    │
    └── BatchPreparedStatementExecutorCallback  (批量回调)
```

### 5.2 顶层回调接口

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/kernel/ExecutorCallback.java`

```java
public interface ExecutorCallback<I, O> {
    /**
     * @param inputs 执行单元集合
     * @param isTrunkThread 是否主线程
     * @return 执行结果
     */
    Collection<O> execute(Collection<I> inputs, boolean isTrunkThread) throws SQLException;
}
```

### 5.3 JDBC 回调抽象实现

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/execute/engine/driver/jdbc/JDBCExecutorCallback.java`

```java
@RequiredArgsConstructor
public abstract class JDBCExecutorCallback<T> implements ExecutorCallback<JDBCExecutionUnit, T> {

    // 缓存数据源元数据,避免重复查询
    private static final Map<String, DataSourceMetaData> CACHED_DATASOURCE_METADATA =
        new ConcurrentHashMap<>();

    private final DatabaseType protocolType;  // 协议类型 (如 MySQL)
    private final Map<String, DatabaseType> storageTypes;  // 存储类型映射
    private final SQLStatement sqlStatement;
    private final boolean isExceptionThrown;

    @Override
    public final Collection<T> execute(
            final Collection<JDBCExecutionUnit> executionUnits,
            final boolean isTrunkThread) throws SQLException {
        Collection<T> result = new LinkedList<>();
        for (JDBCExecutionUnit each : executionUnits) {
            T executeResult = execute(each, isTrunkThread);
            if (null != executeResult) {
                result.add(executeResult);
            }
        }
        return result;
    }
}
```

### 5.4 单个执行单元的执行逻辑 (模板方法核心)

```java
private T execute(final JDBCExecutionUnit jdbcExecutionUnit, final boolean isTrunkThread)
        throws SQLException {
    // ========== 第一步: 设置异常处理策略 ==========
    SQLExecutorExceptionHandler.setExceptionThrown(isExceptionThrown);

    // ========== 第二步: 获取存储层数据库类型 ==========
    DatabaseType storageType = storageTypes.get(
        jdbcExecutionUnit.getExecutionUnit().getDataSourceName()
    );
    DataSourceMetaData dataSourceMetaData = getDataSourceMetaData(
        jdbcExecutionUnit.getStorageResource().getConnection().getMetaData(),
        storageType
    );

    // ========== 第三步: 创建 SQL 执行钩子 ==========
    SQLExecutionHook sqlExecutionHook = new SPISQLExecutionHook();

    try {
        SQLUnit sqlUnit = jdbcExecutionUnit.getExecutionUnit().getSqlUnit();

        // ========== 第四步: 钩子开始 ==========
        sqlExecutionHook.start(
            jdbcExecutionUnit.getExecutionUnit().getDataSourceName(),
            sqlUnit.getSql(),
            sqlUnit.getParameters(),
            dataSourceMetaData,
            isTrunkThread
        );

        // ========== 第五步: 执行 SQL (模板方法) ==========
        T result = executeSQL(
            sqlUnit.getSql(),
            jdbcExecutionUnit.getStorageResource(),
            jdbcExecutionUnit.getConnectionMode(),
            storageType
        );

        // ========== 第六步: 钩子完成 (成功) ==========
        sqlExecutionHook.finishSuccess();

        // ========== 第七步: 上报执行进度 ==========
        finishReport(jdbcExecutionUnit);

        return result;

    } catch (final SQLException ex) {
        // ========== 第八步: Sane Result 机制 ==========
        if (!storageType.equals(protocolType)) {
            Optional<T> saneResult = getSaneResult(sqlStatement, ex);
            if (saneResult.isPresent()) {
                return isTrunkThread ? saneResult.get() : null;
            }
        }

        // ========== 第九步: 钩子完成 (失败) ==========
        sqlExecutionHook.finishFailure(ex);

        // ========== 第十步: 异常处理 ==========
        SQLExecutorExceptionHandler.handleException(ex);
        return null;
    }
}
```

**关键实现细节**:

#### 1) 数据源元数据缓存

```java
private DataSourceMetaData getDataSourceMetaData(
        final DatabaseMetaData databaseMetaData,
        final DatabaseType storageType) throws SQLException {
    String url = databaseMetaData.getURL();
    if (CACHED_DATASOURCE_METADATA.containsKey(url)) {
        return CACHED_DATASOURCE_METADATA.get(url);  // 缓存命中
    }
    DataSourceMetaData result = storageType.getDataSourceMetaData(
        url, databaseMetaData.getUserName()
    );
    CACHED_DATASOURCE_METADATA.put(url, result);
    return result;
}
```

**性能优化**:
- 首次访问数据源时查询元数据 (JDBC URL, 用户名等)
- 后续访问直接从缓存获取
- 使用 `ConcurrentHashMap` 保证线程安全

#### 2) 模板方法 (由子类实现)

```java
// 模板方法1: 执行 SQL
protected abstract T executeSQL(
    String sql,
    Statement statement,
    ConnectionMode connectionMode,
    DatabaseType storageType
) throws SQLException;

// 模板方法2: 获取兜底结果 (Sane Result)
protected abstract Optional<T> getSaneResult(
    SQLStatement sqlStatement,
    SQLException ex
);
```

#### 3) SQL 执行钩子 (SPI 扩展点)

**钩子接口定义**:

```java
// infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/hook/SQLExecutionHook.java
public interface SQLExecutionHook {

    void start(String dataSourceName, String sql, List<Object> params,
               DataSourceMetaData dataSourceMetaData, boolean isTrunkThread);

    void finishSuccess();

    void finishFailure(Exception cause);
}
```

**SPI 实现**:

```java
// infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/hook/SPISQLExecutionHook.java
public final class SPISQLExecutionHook implements SQLExecutionHook {

    private final Collection<SQLExecutionHook> sqlExecutionHooks =
        ShardingSphereServiceLoader.getServiceInstances(SQLExecutionHook.class);

    @Override
    public void start(final String dataSourceName, final String sql,
                     final List<Object> params, final DataSourceMetaData dataSourceMetaData,
                     final boolean isTrunkThread) {
        for (SQLExecutionHook each : sqlExecutionHooks) {
            each.start(dataSourceName, sql, params, dataSourceMetaData, isTrunkThread);
        }
    }

    @Override
    public void finishSuccess() {
        for (SQLExecutionHook each : sqlExecutionHooks) {
            each.finishSuccess();
        }
    }

    @Override
    public void finishFailure(final Exception cause) {
        for (SQLExecutionHook each : sqlExecutionHooks) {
            each.finishFailure(cause);
        }
    }
}
```

**应用场景**:
- **APM 追踪**: SkyWalking, Zipkin 等通过钩子收集 SQL 执行信息
- **慢查询日志**: 记录超过阈值的 SQL
- **审计日志**: 记录所有 SQL 操作

**自定义钩子示例**:

```java
// 1. 实现接口
public class SlowQueryHook implements SQLExecutionHook {
    private long startTime;

    @Override
    public void start(String dataSourceName, String sql, List<Object> params,
                     DataSourceMetaData dataSourceMetaData, boolean isTrunkThread) {
        startTime = System.currentTimeMillis();
    }

    @Override
    public void finishSuccess() {
        long duration = System.currentTimeMillis() - startTime;
        if (duration > 1000) {  // 超过 1 秒
            log.warn("Slow query detected: {}ms", duration);
        }
    }

    @Override
    public void finishFailure(Exception cause) {
        log.error("Query failed", cause);
    }
}

// 2. 注册 SPI
// META-INF/services/org.apache.shardingsphere.infra.executor.sql.hook.SQLExecutionHook
com.example.SlowQueryHook
```

#### 4) Sane Result 机制

**核心逻辑**:

```java
if (!storageType.equals(protocolType)) {
    Optional<T> saneResult = getSaneResult(sqlStatement, ex);
    if (saneResult.isPresent()) {
        return isTrunkThread ? saneResult.get() : null;
    }
}
```

**场景说明**:

ShardingSphere 支持**异构数据库**场景:
```
应用 (MySQL 协议)
    ↓
ShardingSphere-Proxy (protocolType=MySQL)
    ├─ db1: MySQL (storageType=MySQL)
    ├─ db2: PostgreSQL (storageType=PostgreSQL)
    └─ db3: Oracle (storageType=Oracle)
```

当执行跨数据库的 SQL 时:
```sql
-- 查询系统表 (MySQL 特有)
SELECT * FROM information_schema.tables;

-- PostgreSQL 没有这个表,会报错
-- 但 ShardingSphere 可以返回空结果,而不是抛异常
```

**实现示例**:

```java
@Override
protected Optional<QueryResult> getSaneResult(final SQLStatement sqlStatement,
                                             final SQLException ex) {
    if (sqlStatement instanceof ShowTablesStatement && isTableNotExistException(ex)) {
        // 返回空结果集
        return Optional.of(new EmptyQueryResult());
    }
    return Optional.empty();
}
```

### 5.5 具体回调实现示例

#### 1) Statement 查询回调

```java
// StatementExecuteQueryCallback.java
public final class StatementExecuteQueryCallback extends ExecuteQueryCallback {

    @Override
    protected QueryResult executeSQL(final String sql, final Statement statement,
                                    final ConnectionMode connectionMode,
                                    final DatabaseType storageType) throws SQLException {
        ResultSet resultSet = statement.executeQuery(sql);

        // 根据连接模式选择结果集类型
        return ConnectionMode.MEMORY_STRICTLY == connectionMode
            ? new JDBCStreamQueryResult(resultSet)        // 流式: 节省内存
            : new JDBCMemoryQueryResult(resultSet, storageType);  // 内存: 快速访问
    }

    @Override
    protected Optional<QueryResult> getSaneResult(final SQLStatement sqlStatement,
                                                 final SQLException ex) {
        return Optional.empty();  // 不提供兜底结果
    }
}
```

**ConnectionMode 对结果集的影响**:

| ConnectionMode | 结果集类型 | 特点 | 适用场景 |
|---------------|----------|------|---------|
| `MEMORY_STRICTLY` | `JDBCMemoryQueryResult` | 一次性加载到内存 | 结果集小,需要快速访问 |
| `CONNECTION_STRICTLY` | `JDBCStreamQueryResult` | 流式读取,逐行加载 | 结果集大,节省内存 |

#### 2) PreparedStatement 查询回调

```java
// PreparedStatementExecuteQueryCallback.java
public final class PreparedStatementExecuteQueryCallback extends ExecuteQueryCallback {

    @Override
    protected QueryResult executeSQL(final String sql, final Statement statement,
                                    final ConnectionMode connectionMode,
                                    final DatabaseType storageType) throws SQLException {
        // PreparedStatement 已绑定参数,无需传 SQL
        ResultSet resultSet = ((PreparedStatement) statement).executeQuery();

        return ConnectionMode.MEMORY_STRICTLY == connectionMode
            ? new JDBCStreamQueryResult(resultSet)
            : new JDBCMemoryQueryResult(resultSet, storageType);
    }
}
```

#### 3) 更新回调

```java
// ExecuteUpdateCallback.java
public abstract class ExecuteUpdateCallback extends JDBCExecutorCallback<Integer> {

    @Override
    protected final Integer executeSQL(final String sql, final Statement statement,
                                      final ConnectionMode connectionMode,
                                      final DatabaseType storageType) throws SQLException {
        return executeUpdate(sql, statement);
    }

    protected abstract int executeUpdate(String sql, Statement statement) throws SQLException;

    @Override
    protected final Optional<Integer> getSaneResult(final SQLStatement sqlStatement,
                                                   final SQLException ex) {
        return Optional.empty();
    }
}
```

#### 4) 批量执行回调

```java
// BatchPreparedStatementExecutor.java 中的匿名回调
JDBCExecutorCallback<int[]> callback = new JDBCExecutorCallback<int[]>(
        protocolType, storageTypes, sqlStatement, isExceptionThrown) {

    @Override
    protected int[] executeSQL(final String sql, final Statement statement,
                              final ConnectionMode connectionMode,
                              final DatabaseType storageType) throws SQLException {
        return statement.executeBatch();
    }

    @Override
    protected Optional<int[]> getSaneResult(final SQLStatement sqlStatement,
                                           final SQLException ex) {
        return Optional.empty();
    }
};
```

---

## 六、执行单元构建流程

### 6.1 执行单元数据结构

#### 1) ExecutionUnit - 最小执行单元

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/context/ExecutionUnit.java`

```java
@RequiredArgsConstructor
@Getter
@EqualsAndHashCode
@ToString
public final class ExecutionUnit {

    private final String dataSourceName;  // 数据源名称 (如 "ds0")
    private final SQLUnit sqlUnit;        // SQL 单元
}
```

#### 2) SQLUnit - SQL 信息

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/context/SQLUnit.java`

```java
@RequiredArgsConstructor
@Getter
@EqualsAndHashCode(of = "sql")  // 仅根据 SQL 判断相等性
@ToString
public final class SQLUnit {

    private final String sql;                          // 实际 SQL
    private final List<Object> parameters;             // 参数列表
    private final List<RouteMapper> tableRouteMappers; // 表路由映射

    public SQLUnit(final String sql, final List<Object> params) {
        this(sql, params, Collections.emptyList());
    }
}
```

**示例**:

```java
// 原始 SQL
String logicSQL = "SELECT * FROM t_order WHERE user_id = ?";

// 重写后的 SQL 单元
SQLUnit sqlUnit1 = new SQLUnit(
    "SELECT * FROM t_order_0 WHERE user_id = ?",
    Arrays.asList(123),
    Arrays.asList(new RouteMapper("t_order", "t_order_0"))
);

SQLUnit sqlUnit2 = new SQLUnit(
    "SELECT * FROM t_order_1 WHERE user_id = ?",
    Arrays.asList(123),
    Arrays.asList(new RouteMapper("t_order", "t_order_1"))
);
```

#### 3) JDBCExecutionUnit - JDBC 执行单元

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/execute/engine/driver/jdbc/JDBCExecutionUnit.java`

```java
@RequiredArgsConstructor
@Getter
public final class JDBCExecutionUnit implements DriverExecutionUnit<Statement> {

    private final ExecutionUnit executionUnit;       // 执行单元
    private final ConnectionMode connectionMode;     // 连接模式
    private final Statement storageResource;         // Statement 对象
}
```

#### 4) ExecutionGroup - 执行组

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/kernel/model/ExecutionGroup.java`

```java
@RequiredArgsConstructor
@Getter
public final class ExecutionGroup<T> {

    private final List<T> inputs;  // 一组执行单元 (通常为同一数据源的多个 SQL)
}
```

#### 5) ExecutionGroupContext - 执行组上下文

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/kernel/model/ExecutionGroupContext.java`

```java
@RequiredArgsConstructor
@Getter
public final class ExecutionGroupContext<T> {

    private final Collection<ExecutionGroup<T>> inputGroups;  // 多个执行组
    private final ExecutionGroupReportContext reportContext;  // 报告上下文
}
```

#### 6) ConnectionMode - 连接模式

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/execute/engine/ConnectionMode.java`

```java
public enum ConnectionMode {

    MEMORY_STRICTLY,      // 内存优先 (一次性加载结果集)
    CONNECTION_STRICTLY   // 连接优先 (流式读取,复用连接)
}
```

### 6.2 执行准备引擎

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/prepare/AbstractExecutionPrepareEngine.java`

```java
public abstract class AbstractExecutionPrepareEngine<T> implements ExecutionPrepareEngine<T> {

    private final int maxConnectionsSizePerQuery;  // 单次查询最大连接数
    private final Map<ShardingSphereRule, ExecutionPrepareDecorator> decorators;

    @Override
    public final ExecutionGroupContext<T> prepare(
            final RouteContext routeContext,
            final Collection<ExecutionUnit> executionUnits,
            final ExecutionGroupReportContext reportContext) throws SQLException {
        Collection<ExecutionGroup<T>> result = new LinkedList<>();

        // ========== 第一步: 按数据源聚合 SQL 单元 ==========
        for (Entry<String, List<SQLUnit>> entry : aggregateSQLUnitGroups(executionUnits).entrySet()) {
            String dataSourceName = entry.getKey();
            List<SQLUnit> sqlUnits = entry.getValue();

            // ========== 第二步: 分组 (控制连接数) ==========
            List<List<SQLUnit>> sqlUnitGroups = group(sqlUnits);

            // ========== 第三步: 确定连接模式 ==========
            ConnectionMode connectionMode = maxConnectionsSizePerQuery < sqlUnits.size()
                ? ConnectionMode.CONNECTION_STRICTLY  // SQL 数量超限,连接优先
                : ConnectionMode.MEMORY_STRICTLY;     // SQL 数量合理,内存优先

            // ========== 第四步: 创建执行组 ==========
            result.addAll(group(dataSourceName, sqlUnitGroups, connectionMode));
        }

        // ========== 第五步: 应用装饰器 ==========
        return decorate(routeContext, result, reportContext);
    }
}
```

**关键逻辑分析**:

#### 1) 按数据源聚合

```java
private Map<String, List<SQLUnit>> aggregateSQLUnitGroups(
        final Collection<ExecutionUnit> executionUnits) {
    Map<String, List<SQLUnit>> result = new LinkedHashMap<>(executionUnits.size(), 1);
    for (ExecutionUnit each : executionUnits) {
        if (!result.containsKey(each.getDataSourceName())) {
            result.put(each.getDataSourceName(), new LinkedList<>());
        }
        result.get(each.getDataSourceName()).add(each.getSqlUnit());
    }
    return result;
}
```

**示例**:

```java
// 输入: ExecutionUnit 集合
[
    ExecutionUnit(ds0, "SELECT * FROM t_order_0"),
    ExecutionUnit(ds1, "SELECT * FROM t_order_1"),
    ExecutionUnit(ds0, "SELECT * FROM t_order_2"),
    ExecutionUnit(ds1, "SELECT * FROM t_order_3")
]

// 输出: 按数据源分组
{
    "ds0": ["SELECT * FROM t_order_0", "SELECT * FROM t_order_2"],
    "ds1": ["SELECT * FROM t_order_1", "SELECT * FROM t_order_3"]
}
```

#### 2) 分组策略 (控制连接数)

```java
private List<List<SQLUnit>> group(final List<SQLUnit> sqlUnits) {
    // 计算分区大小: 确保每个分区的 SQL 数量不超过 maxConnectionsSizePerQuery
    int desiredPartitionSize = Math.max(
        0 == sqlUnits.size() % maxConnectionsSizePerQuery
            ? sqlUnits.size() / maxConnectionsSizePerQuery
            : sqlUnits.size() / maxConnectionsSizePerQuery + 1,
        1
    );
    return Lists.partition(sqlUnits, desiredPartitionSize);
}
```

**示例**:

```java
// maxConnectionsSizePerQuery = 3
// sqlUnits.size() = 10

// 计算:
desiredPartitionSize = 10 / 3 + 1 = 4

// 分组结果:
[
    [sql1, sql2, sql3, sql4],
    [sql5, sql6, sql7, sql8],
    [sql9, sql10]
]
// 需要 3 个连接
```

**为何要限制连接数?**

假设有 100 个分片,不限制连接数会导致:
```
并发查询 100 个分片 → 打开 100 个连接 → 数据库连接池耗尽
```

限制后:
```
maxConnectionsSizePerQuery = 10
→ 分 10 组,每组 10 个 SQL
→ 最多使用 10 个连接
→ 连接复用,避免耗尽
```

#### 3) 连接模式选择

```java
ConnectionMode connectionMode = maxConnectionsSizePerQuery < sqlUnits.size()
    ? ConnectionMode.CONNECTION_STRICTLY
    : ConnectionMode.MEMORY_STRICTLY;
```

**决策逻辑**:

| sqlUnits.size() | maxConnections | ConnectionMode | 原因 |
|----------------|---------------|----------------|------|
| 5 | 10 | `MEMORY_STRICTLY` | 连接充足,内存模式提升性能 |
| 15 | 10 | `CONNECTION_STRICTLY` | 连接不足,流式模式节省连接 |

### 6.3 JDBC 执行准备引擎

**源码位置**: `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/prepare/driver/DriverExecutionPrepareEngine.java`

```java
public final class DriverExecutionPrepareEngine<T extends DriverExecutionUnit<?>, C>
        extends AbstractExecutionPrepareEngine<T> {

    private final ExecutorConnectionManager<C> connectionManager;
    private final ExecutorStatementManager<C, ?, ?> statementManager;
    private final SQLExecutionUnitBuilder sqlExecutionUnitBuilder;

    @Override
    protected List<ExecutionGroup<T>> group(
            final String dataSourceName,
            final List<List<SQLUnit>> sqlUnitGroups,
            final ConnectionMode connectionMode) throws SQLException {
        List<ExecutionGroup<T>> result = new LinkedList<>();

        // ========== 第一步: 获取连接 (连接数 = 分组数) ==========
        List<C> connections = connectionManager.getConnections(
            dataSourceName,
            sqlUnitGroups.size(),
            connectionMode
        );

        int count = 0;
        for (List<SQLUnit> each : sqlUnitGroups) {
            // ========== 第二步: 为每个分组创建执行组 ==========
            result.add(createExecutionGroup(
                dataSourceName,
                each,
                connections.get(count++),
                connectionMode
            ));
        }
        return result;
    }

    private ExecutionGroup<T> createExecutionGroup(
            final String dataSourceName,
            final List<SQLUnit> sqlUnits,
            final C connection,
            final ConnectionMode connectionMode) throws SQLException {
        List<T> result = new LinkedList<>();
        for (SQLUnit each : sqlUnits) {
            // ========== 第三步: 为每个 SQLUnit 构建 JDBCExecutionUnit ==========
            result.add((T) sqlExecutionUnitBuilder.build(
                new ExecutionUnit(dataSourceName, each),
                statementManager,
                connection,
                connectionMode,
                option,
                databaseTypes.get(dataSourceName)
            ));
        }
        return new ExecutionGroup<>(result);
    }
}
```

**构建流程图**:

```
ExecutionUnit 集合
    ↓
[按数据源聚合]
    ↓
{ds0: [sql1, sql2, sql3, sql4, sql5]}
{ds1: [sql6, sql7, sql8]}
    ↓
[分组] (maxConnections=3)
    ↓
{ds0: [[sql1, sql2], [sql3, sql4], [sql5]]}  → 需要 3 个连接
{ds1: [[sql6, sql7, sql8]]}                   → 需要 1 个连接
    ↓
[获取连接]
    ↓
ds0: [conn1, conn2, conn3]
ds1: [conn4]
    ↓
[构建 JDBCExecutionUnit]
    ↓
ExecutionGroup 1: [
    JDBCExecutionUnit(sql1, conn1),
    JDBCExecutionUnit(sql2, conn1)
]
ExecutionGroup 2: [
    JDBCExecutionUnit(sql3, conn2),
    JDBCExecutionUnit(sql4, conn2)
]
ExecutionGroup 3: [
    JDBCExecutionUnit(sql5, conn3)
]
ExecutionGroup 4: [
    JDBCExecutionUnit(sql6, conn4),
    JDBCExecutionUnit(sql7, conn4),
    JDBCExecutionUnit(sql8, conn4)
]
    ↓
ExecutionGroupContext
```

---

## 七、完整执行流程串联

### 7.1 查询场景

**用户调用**:
```java
ResultSet rs = preparedStatement.executeQuery();
```

**内部流程**:

```
[1] ShardingSpherePreparedStatement.executeQuery()
    ↓
[2] DriverJDBCExecutor.executeQuery()
    ↓
    创建 ExecutionGroupContext
        ↓
    DriverExecutionPrepareEngine.prepare()
        → 聚合 ExecutionUnit 按数据源
        → 分组 (控制连接数)
        → 获取连接
        → 构建 JDBCExecutionUnit
    ↓
[3] JDBCExecutor.execute()
    ↓
    判断: isInTransaction()?
        ├─ false → serial=false (并行)
        └─ true  → serial=true (串行)
    ↓
[4] ExecutorEngine.execute()
    ↓
    serial?
        ├─ false → parallelExecute()
        │            ├─ 主线程执行 Group1
        │            ├─ 线程池异步执行 Group2, Group3, ...
        │            └─ 等待所有 Future 完成
        │
        └─ true  → serialExecute()
                     └─ 主线程顺序执行所有 Group
    ↓
[5] JDBCExecutorCallback.execute()
    ↓
    for each JDBCExecutionUnit:
        ├─ sqlExecutionHook.start()
        ├─ executeSQL() (模板方法)
        │   └─ statement.executeQuery()
        ├─ sqlExecutionHook.finishSuccess()
        └─ 返回 QueryResult
    ↓
[6] 聚合所有 QueryResult
    ↓
[7] 传递给结果归并引擎
```

### 7.2 更新场景

```
[1] ShardingSpherePreparedStatement.executeUpdate()
    ↓
[2] DriverJDBCExecutor.executeUpdate()
    ↓
    (同查询场景的步骤 2-4)
    ↓
[5] ExecuteUpdateCallback.execute()
    ↓
    for each JDBCExecutionUnit:
        ├─ sqlExecutionHook.start()
        ├─ executeUpdate()
        │   └─ statement.executeUpdate()
        ├─ sqlExecutionHook.finishSuccess()
        └─ 返回 updateCount
    ↓
[6] 聚合所有 updateCount (求和)
    ↓
[7] 返回总更新行数
```

### 7.3 批量执行场景

```
[1] ShardingSpherePreparedStatement.addBatch()
    ↓
    累积批量参数
    ↓
[2] ShardingSpherePreparedStatement.executeBatch()
    ↓
[3] BatchPreparedStatementExecutor.executeBatch()
    ↓
    构建 ExecutionGroupContext (每个参数组对应一个 ExecutionUnit)
    ↓
[4] JDBCExecutor.execute()
    ↓
[5] BatchPreparedStatementExecutorCallback.execute()
    ↓
    for each JDBCExecutionUnit:
        ├─ 绑定所有批量参数
        ├─ statement.executeBatch()
        └─ 返回 int[] (每行更新数)
    ↓
[6] 合并所有 int[] 数组
    ↓
[7] 返回总批量结果
```

---

## 八、设计模式深度解析

### 8.1 策略模式 (Strategy Pattern)

**应用位置**: `ExecutorEngine.execute()`

**核心代码**:
```java
public <I, O> List<O> execute(..., final boolean serial) {
    return serial
        ? serialExecute(...)  // 策略1: 串行执行
        : parallelExecute(...);  // 策略2: 并行执行
}
```

**UML 类图**:
```
<<Context>>
ExecutorEngine
    ├─ execute(serial: boolean)
    │
    ↓
<<Strategy>>
ExecutionStrategy
    ├─ serialExecute()  ← ConcreteStrategyA
    └─ parallelExecute()  ← ConcreteStrategyB
```

### 8.2 模板方法模式 (Template Method Pattern)

**应用位置**: `JDBCExecutorCallback`

**模板方法**:
```java
public final Collection<T> execute(Collection<JDBCExecutionUnit> units, boolean isTrunk) {
    Collection<T> result = new LinkedList<>();
    for (JDBCExecutionUnit each : units) {
        // ========== 模板流程 ==========
        SQLExecutorExceptionHandler.setExceptionThrown(...);
        DataSourceMetaData metadata = getDataSourceMetaData(...);
        SQLExecutionHook hook = new SPISQLExecutionHook();

        try {
            hook.start(...);
            T result = executeSQL(...);  // ← 钩子方法 (由子类实现)
            hook.finishSuccess();
            return result;
        } catch (SQLException ex) {
            Optional<T> sane = getSaneResult(..., ex);  // ← 钩子方法 (由子类实现)
            hook.finishFailure(ex);
            handleException(ex);
        }
    }
    return result;
}
```

**钩子方法**:
```java
protected abstract T executeSQL(String sql, Statement stmt,
                               ConnectionMode mode, DatabaseType type) throws SQLException;

protected abstract Optional<T> getSaneResult(SQLStatement stmt, SQLException ex);
```

**子类实现**:
```java
// 查询回调
class ExecuteQueryCallback extends JDBCExecutorCallback<QueryResult> {
    @Override
    protected QueryResult executeSQL(...) {
        return new JDBCMemoryQueryResult(statement.executeQuery(sql));
    }
}

// 更新回调
class ExecuteUpdateCallback extends JDBCExecutorCallback<Integer> {
    @Override
    protected Integer executeSQL(...) {
        return statement.executeUpdate(sql);
    }
}
```

### 8.3 回调模式 (Callback Pattern)

**应用位置**: `ExecutorEngine` 接收 `ExecutorCallback`

**核心设计**:
```java
// ExecutorEngine 定义执行框架
public <I, O> List<O> execute(
        ExecutionGroupContext<I> context,
        ExecutorCallback<I, O> callback) {  // ← 回调接口
    // 框架代码
    return callback.execute(inputs, isTrunkThread);  // ← 回调
}

// 调用方提供具体回调实现
JDBCExecutorCallback<QueryResult> callback = new ExecuteQueryCallback(...);
List<QueryResult> results = executorEngine.execute(context, callback);
```

**好处**:
- 解耦执行框架与业务逻辑
- 支持多种执行类型 (查询/更新/批量)
- 易于扩展新的执行类型

### 8.4 工厂方法模式 (Factory Method Pattern)

**应用位置**: `ExecutorEngine` 创建

```java
// 抽象工厂方法
public static ExecutorEngine createExecutorEngineWithSize(int size);
public static ExecutorEngine createExecutorEngineWithCPU();
public static ExecutorEngine createExecutorEngineWithCPUAndResources(int resources);

// 隐藏构造函数
private ExecutorEngine(int executorSize) {
    this.executorServiceManager = new ExecutorServiceManager(executorSize);
}
```

### 8.5 装饰器模式 (Decorator Pattern)

**应用位置**: `TtlExecutors.getTtlExecutorService()`

```java
// 原始线程池
ExecutorService original = Executors.newFixedThreadPool(10);

// 装饰后的线程池 (添加 ThreadLocal 传递能力)
ExecutorService decorated = TtlExecutors.getTtlExecutorService(original);

// 接口不变,功能增强
decorated.submit(() -> {
    // 可以访问父线程的 TransmittableThreadLocal
});
```

### 8.6 SPI 模式 (Service Provider Interface)

**应用位置**: `SQLExecutionHook`

```java
// 1. 定义接口
public interface SQLExecutionHook {
    void start(...);
    void finishSuccess();
    void finishFailure(Exception cause);
}

// 2. SPI 加载器
public final class SPISQLExecutionHook implements SQLExecutionHook {
    private final Collection<SQLExecutionHook> hooks =
        ShardingSphereServiceLoader.getServiceInstances(SQLExecutionHook.class);

    @Override
    public void start(...) {
        for (SQLExecutionHook each : hooks) {
            each.start(...);
        }
    }
}

// 3. 第三方实现
public class MyCustomHook implements SQLExecutionHook { ... }

// 4. 注册 SPI
// META-INF/services/org.apache.shardingsphere.infra.executor.sql.hook.SQLExecutionHook
com.example.MyCustomHook
```

---

## 九、性能优化要点

### 9.1 线程池优化

**1. 主线程执行第一组 (避免饥饿)**

```java
// 不优化 (可能死锁)
for (ExecutionGroup group : groups) {
    futures.add(threadPool.submit(() -> execute(group)));
}
// 如果 groups.size() > threadPool.size(),最后一组无法调度

// 优化后
ExecutionGroup first = groups.removeFirst();
execute(first);  // 主线程执行
for (ExecutionGroup group : groups) {
    futures.add(threadPool.submit(() -> execute(group)));
}
```

**2. 线程数动态计算**

```java
// 不同场景使用不同策略
int threads = Math.min(CPU_CORES * 2 - 1, dataSourceCount);
```

### 9.2 连接管理优化

**1. 连接模式动态选择**

```java
// SQL 少 → 内存模式 (性能优先)
// SQL 多 → 连接模式 (资源优先)
ConnectionMode mode = maxConnections < sqlCount
    ? CONNECTION_STRICTLY
    : MEMORY_STRICTLY;
```

**2. 连接复用 (分组)**

```java
// 不优化: 100 个 SQL = 100 个连接
for (SQLUnit sql : sqlUnits) {
    Connection conn = getConnection();
    execute(sql, conn);
    conn.close();
}

// 优化后: 100 个 SQL = 10 个连接 (每个连接执行 10 个 SQL)
List<List<SQLUnit>> groups = Lists.partition(sqlUnits, 10);
for (List<SQLUnit> group : groups) {
    Connection conn = getConnection();
    for (SQLUnit sql : group) {
        execute(sql, conn);
    }
    conn.close();
}
```

### 9.3 缓存优化

**1. 数据源元数据缓存**

```java
private static final Map<String, DataSourceMetaData> CACHE = new ConcurrentHashMap<>();

private DataSourceMetaData getMetaData(String url) {
    return CACHE.computeIfAbsent(url, k -> queryMetaData(k));
}
```

**2. ExecutorEngine 实例复用**

```java
// 不优化: 每次查询创建新的 ExecutorEngine
ExecutorEngine engine = new ExecutorEngine(10);
engine.execute(...);
engine.close();

// 优化后: 复用 ExecutorEngine 实例
private static final ExecutorEngine ENGINE = ExecutorEngine.createExecutorEngineWithCPU();

public void execute() {
    ENGINE.execute(...);
}
```

### 9.4 事务场景优化

**串行执行避免分布式锁**:

```java
// 事务中强制串行执行
if (isInTransaction()) {
    return serialExecute(...);  // 同一连接,避免死锁
}
```

---

## 十、总结与展望

### 10.1 核心设计思想

1. **调度与执行分离**: `ExecutorEngine` 负责调度,`Callback` 负责执行
2. **策略动态切换**: 根据事务状态自动选择串行/并行
3. **资源精细控制**: 连接数、线程数、内存模式的精确管理
4. **扩展性优先**: SPI、模板方法、回调等模式支持灵活扩展

### 10.2 关键技术点

| 技术点 | 实现方式 | 作用 |
|-------|---------|------|
| 并发执行 | 主线程 + 线程池 | 提升性能,避免饥饿 |
| 串行执行 | 主线程顺序执行 | 保证事务一致性 |
| 连接复用 | 分组 + 连接模式 | 节省连接资源 |
| 结果集优化 | 流式 vs 内存 | 平衡性能与资源 |
| 监控扩展 | SQLExecutionHook | 支持 APM/审计 |
| 异构数据库 | Sane Result | 兼容性处理 |

### 10.3 下一步学习

执行引擎完成 SQL 执行后,会产生多个 `QueryResult`。下一步应学习:

**结果归并模块 (`infra/merge`)**:
- `MergeEngine` 的设计与实现
- 不同归并策略: 流式归并、内存归并、排序归并、分组归并、聚合归并、分页归并
- 装饰器模式在归并中的应用
- 分布式查询的性能优化

完成结果归并的学习后,你将掌握 ShardingSphere SQL 处理的完整链路!

---

## 附录: 关键源码文件清单

| 模块 | 文件路径 |
|-----|---------|
| 核心调度器 | `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/kernel/ExecutorEngine.java` |
| 线程池管理 | `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/kernel/thread/ExecutorServiceManager.java` |
| JDBC 执行器 | `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/execute/engine/driver/jdbc/JDBCExecutor.java` |
| 回调抽象 | `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/execute/engine/driver/jdbc/JDBCExecutorCallback.java` |
| 执行准备 | `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/prepare/driver/DriverExecutionPrepareEngine.java` |
| 执行钩子 | `infra/executor/src/main/java/org/apache/shardingsphere/infra/executor/sql/hook/SQLExecutionHook.java` |
| 连接上下文 | `infra/common/src/main/java/org/apache/shardingsphere/infra/context/ConnectionContext.java` |
