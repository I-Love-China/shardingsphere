# ShardingSphere 示例启动指南

本指南旨在提供一份清晰、可操作的步骤，帮助您成功启动和运行 Apache ShardingSphere 官方提供的各种示例。

---

## 1. 环境准备 (Prerequisites)

在运行任何示例之前，请务DEI完成以下两个准备步骤：

### 步骤 1：编译并安装 ShardingSphere 主项目

所有示例代码都依赖于 ShardingSphere 的核心模块。您必须先在本地 Maven 仓库中安装这些依赖。

```bash
# 1. 克隆 ShardingSphere 主项目仓库
git clone https://github.com/apache/shardingsphere.git

# 2. 进入项目根目录
cd shardingsphere

# 3. 设置 JDK
# 这里务必使用 11+，否则会有 ANTLR 的问题
sdk use java 11.0.21-kona

# 4. 执行编译和安装
# -Prelease 参数会打包所有必须的依赖，请务必加上
./mvnw clean install -Prelease -T0.8C -Djacoco.skip=true -Dcheckstyle.skip=true -Drat.skip=true -Dmaven.javadoc.skip=true -Dspotless.apply.skip=true -DskipTests 

mvnd clean install -Prelease -Djacoco.skip=true -Dcheckstyle.skip=true -Drat.skip=true -Dmaven.javadoc.skip=true -Dspotless.apply.skip=true -DskipTests
```

### 步骤 2：初始化数据库（可选）

如果您打算手动运行 `jdbc` 示例，而不是通过 Docker 启动数据库，建议先执行官方提供的数据库初始化脚本。这将创建示例所需的库和表。

**脚本位置**: `examples/src/resources/manual_schema.sql`

您可以使用任何 MySQL 客户端执行此脚本，例如：

```bash
mysql -u<your_username> -p < examples/src/resources/manual_schema.sql
```

---

## 2. JDBC 示例 (代码生成方式)

ShardingSphere 的 JDBC 示例采用了一种独特的 **代码生成** 模式。您无需直接寻找示例代码，而是通过配置来生成符合您需求的、可独立运行的示例项目。

### 步骤 1：修改配置文件

-   **配置文件**: `examples/shardingsphere-example-generator/src/main/resources/config.yaml`

打开此文件，您可以看到 `products`, `modes`, `features`, `frameworks` 等列表。通过注释或取消注释列表中的条目，您可以决定要生成哪些组合的示例。

**示例 `config.yaml`**:
```yaml
# ...
# 只生成 jdbc 产品的示例
products:
  - jdbc

# 只生成 standalone 模式的示例
modes: 
  - standalone

# 只生成 sharding 和 readwrite-splitting 功能的示例
features: 
  - sharding
  - readwrite-splitting

# 只生成 raw jdbc 和 spring-boot-jdbc 框架的示例
frameworks:
  - jdbc
  - spring-boot-starter-jdbc
# ...
```
> **建议**：初次使用时，可以保持默认配置，生成所有示例。

### 步骤 2：运行代码生成器

通过执行 `ExampleGeneratorMain` 这个 Java 类来启动代码生成器。最方便的方式是使用 Maven 命令：

```bash
# 在 ShardingSphere 项目根目录下执行
mvn -f examples/shardingsphere-example-generator/pom.xml exec:java -Dexec.mainClass="org.apache.shardingsphere.example.generator.ExampleGeneratorMain"
```

执行成功后，您不会看到太多日志，但示例代码已经生成在目标目录中。

### 步骤 3：查找并运行生成的示例

-   **生成位置**: `examples/shardingsphere-example-generator/target/generated-sources/`

进入该目录，您会看到类似 `shardingsphere-jdbc-sample` 的文件夹，这就是一个完整且可运行的 Maven 项目。

**如何运行具体的示例？**

1.  用您的 IDE（如 IntelliJ IDEA）打开这个生成的项目 (`shardingsphere-jdbc-sample`)。
2.  在 `src/main/java/org/apache/shardingsphere/example` 包下，您会看到根据您配置生成的各种示例，例如 `sharding`、`readwrite-splitting` 等。
3.  每个包下都有一个 `main` 方法入口类，例如 `ShardingDatabasesAndTablesExample.java`。
4.  直接运行这个类的 `main` 方法，即可启动对应的示例。

---

## 3. Proxy 示例 (DistSQL)

Proxy 的示例相对直接，代码是静态的，无需生成。

### 步骤 1：定位示例项目

-   **项目位置**: `examples/shardingsphere-proxy-example/shardingsphere-proxy-distsql-example`

这是一个标准的 Maven 项目。

### 步骤 2：运行主类

该示例的入口是一个名为 `DistSQLFeatureExample` 的 Java 类。您可以通过 IDE 或 Maven 命令来运行它。

**通过 Maven 运行**:
```bash
# 在 ShardingSphere 项目根目录下执行
mvn -f examples/shardingsphere-proxy-example/shardingsphere-proxy-distsql-example/pom.xml exec:java -Dexec.mainClass="org.apache.shardingsphere.example.proxy.distsql.DistSQLFeatureExample"
```

这个程序会自动启动一个嵌入式的 ShardingSphere-Proxy 和一个嵌入式的数据库（如 H2），然后通过代码演示如何使用 Java 客户端执行 DistSQL 来管理资源和规则。

---

## 附录：常用命令速查

-   **编译主项目**:
    ```bash
    ./mvnw clean install -Prelease -T1C -Djacoco.skip=true -Dcheckstyle.skip=true -Drat.skip=true -Dmaven.javadoc.skip=true -Dspotless.apply.skip=true -DskipTests 
    ```

-   **生成 JDBC 示例**:
    ```bash
    mvn -f examples/shardingsphere-example-generator/pom.xml exec:java -Dexec.mainClass="org.apache.shardingsphere.example.generator.ExampleGeneratorMain"
    ```

-   **运行 Proxy (DistSQL) 示例**:
    ```bash
    mvn -f examples/shardingsphere-proxy-example/shardingsphere-proxy-distsql-example/pom.xml exec:java -Dexec.mainClass="org.apache.shardingsphere.example.proxy.distsql.DistSQLFeatureExample"
    ```
