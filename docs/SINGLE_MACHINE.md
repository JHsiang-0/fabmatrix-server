# 单机运行模式

Windows Server 使用 SQLite、本地文件和进程内缓存，不需要 MySQL、Redis、RustFS 或 Docker。一个 SQLite 数据库只能由一个 Server 实例使用，多个桌面客户端可通过局域网连接该实例；启动时会对数据库路径加文件锁。

IDEA 以 Java 25 选择项目共享的 `FabMatrix Server` 运行配置，默认 `dev` 自动启用 `local`，可用环境变量 `FARM_DATA_DIR` 指定开发数据目录、`SERVER_PORT` 指定端口。该运行配置、`mvn spring-boot:run` 和 Windows 启动器显式允许类路径原生访问，供 SQLite JDBC 加载本地库。IDEA 原有个人运行配置不自动修改，使用原配置时需在 VM options 中添加 `--enable-native-access=ALL-UNNAMED`。请与已安装服务使用不同数据目录和端口。Local 通过 SQLite 初始化 SQL 与 `LocalSchemaMigration` 初始化或升级数据库，首次管理员按受控初始化流程创建。

Windows 安装版沿用 `local` 配置，文件与数据库位置由安装器确定。已有 MySQL 或对象存储数据不会自动迁移；内存缓存、临时锁与设备状态在进程结束时清除，持久业务数据保存在 SQLite 和本地文件中。
