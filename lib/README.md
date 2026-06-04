# 第三方 JDBC 驱动 JAR 存放目录

将 Maven 中央仓库下载不到的 JDBC 驱动 JAR 文件放入此目录。

## 集成方式

### 方式1: 安装到本地 Maven 仓库（推荐）

在 `config.yaml` 中配置：

```yaml
db:
  type: mydb
  custom_driver:
    load_method: local_repo
    jar_path: lib/my-jdbc-driver.jar
    group_id: com.example
    artifact_id: my-jdbc-driver
    version: 1.0.0
```

Python runner 会自动执行 `mvn install:install-file` 将 JAR 安装到本地仓库。

**注意**: 需要在 `pom.xml` 中添加对应的 `<dependency>`。

### 方式2: 加入 classpath

```yaml
db:
  custom_driver:
    load_method: classpath
    jar_path: lib/my-jdbc-driver.jar
```

Python runner 会将 JAR 加入 Maven Surefire 的附加 classpath。

### 方式3: 手动安装

```bash
mvn install:install-file \
  -Dfile=lib/my-jdbc-driver.jar \
  -DgroupId=com.example \
  -DartifactId=my-jdbc-driver \
  -Dversion=1.0.0 \
  -Dpackaging=jar
```
