# JPostman 项目交接文档

> 最后更新: 2026-07-03
> 项目路径: `d:\0code\ai\JPostman`

---

## 一、项目定位

JPostman 是一款基于 Java 的轻量级、纯本地、低内存占用的 API 调试工具，对标 Postman 的核心调试功能。数据全部存储在本地 JSON 文件中，天然支持 Git 版本管理。

**技术规格书**: 用户提供的《Java 版轻量级 API 客户端功能规格说明书（第二版）》，包含6大功能模块 + 辅助功能 + 排除清单。

## 二、技术栈

| 组件 | 选型 | 版本 |
|------|------|------|
| GUI 框架 | Swing + FlatLaf | FlatLaf 3.5.1 |
| 布局管理器 | MigLayout | 11.3 |
| IoC 容器 | SpringBoot (非 Web 模式) | 2.7.18 |
| HTTP 客户端 | OkHttp | 4.12.0 |
| JSON 处理 | Jackson + jsr310 | 2.13.5 |
| 编译目标 | `--release 8` (JRE 8 兼容) | JDK 11 开发 |
| 构建工具 | Maven | - |
| 测试 | JUnit 5 + MockWebServer | SpringBoot Test |

### 硬性约束
- **必须兼容 Windows 7 和 Windows Server 2012**
- Swing + FlatLaf（不用 JavaFX，避免 JDK 版本绑定）
- SpringBoot 2.7（不用 3.x，支持 JDK 8/11）
- 使用 `javax.persistence.*` 而非 `jakarta.*`（虽然当前已移除 JPA，但如需引入注意此点）
- `Path.of()` 是 Java 11+，必须用 `Paths.get()`
- `sorter.setSortKey()` 不存在于 Java 8，用 `sorter.setSortKeys(Collections.singletonList(...))`

## 三、项目结构

```
d:\0code\ai\JPostman\
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/com/jpostman/
│   │   │   ├── JPostmanApplication.java          # 启动类
│   │   │   ├── config/
│   │   │   │   └── JPostmanConfig.java           # @SpringBootApplication + ObjectMapper Bean
│   │   │   ├── model/                            # 数据模型 POJO (全部为普通 Java 类，非 JPA)
│   │   │   │   ├── KeyValue.java                 # 键值对 (key/value/description/enabled)
│   │   │   │   ├── Variable.java                 # 变量 (增加 secret 字段)
│   │   │   │   ├── AuthConfig.java               # 认证配置 (none/apikey/bearer/basic)
│   │   │   │   ├── CollectionItem.java           # 集合树节点抽象基类 (@JsonSubTypes 多态)
│   │   │   │   ├── FolderNode.java               # 文件夹节点 (递归嵌套)
│   │   │   │   ├── RequestNode.java              # 请求节点 (完整请求定义)
│   │   │   │   ├── CollectionFile.java           # 集合文件 (对应一个 .json 文件)
│   │   │   │   ├── EnvironmentFile.java          # 环境文件 (对应一个 .json 文件)
│   │   │   │   ├── HistoryRecord.java            # 历史记录
│   │   │   │   ├── Settings.java                 # 用户设置
│   │   │   │   ├── LoadTestConfig.java           # 性能测试配置
│   │   │   │   ├── LoadTestResult.java           # 性能测试结果 (含 MetricPoint 内部类)
│   │   │   │   └── dto/
│   │   │   │       ├── RequestConfig.java        # 请求配置 DTO (HttpEngine 输入)
│   │   │   │       └── ResponseData.java         # 响应数据 DTO (含性能指标)
│   │   │   ├── service/
│   │   │   │   ├── CollectionService.java        # 集合 CRUD + 树操作
│   │   │   │   ├── EnvironmentService.java       # 环境管理 + 变量合并
│   │   │   │   ├── HistoryService.java           # 历史记录管理
│   │   │   │   ├── HttpEngineService.java        # HTTP 引擎 (OkHttp + 性能指标 + Cookie)
│   │   │   │   ├── VariableResolver.java         # 变量解析 (4级作用域 + 动态函数)
│   │   │   │   ├── CookieService.java            # Cookie 自动管理
│   │   │   │   ├── LoadTestService.java          # 性能测试引擎
│   │   │   │   └── store/                        # JSON 文件存储层
│   │   │   │       ├── CollectionStore.java
│   │   │   │       ├── EnvironmentStore.java
│   │   │   │       ├── HistoryStore.java
│   │   │   │       ├── GlobalVariableStore.java
│   │   │   │       └── SettingsStore.java
│   │   │   ├── plugin/                           # 插件系统
│   │   │   │   ├── JPostmanPlugin.java           # 插件主接口
│   │   │   │   ├── RequestInterceptor.java       # 请求拦截器扩展点
│   │   │   │   ├── ResponseProcessor.java        # 响应处理器扩展点
│   │   │   │   ├── VariableFunction.java         # 变量函数扩展点
│   │   │   │   ├── PluginInfo.java               # 插件元数据
│   │   │   │   └── PluginManager.java            # 插件管理器 (SPI + ClassLoader 隔离)
│   │   │   ├── util/                             # 工具类
│   │   │   │   ├── CurlParser.java               # cURL 命令解析器
│   │   │   │   ├── PostmanImporter.java          # Postman v2.1 导入
│   │   │   │   └── CollectionExporter.java       # 集合导出
│   │   │   └── ui/
│   │   │       ├── frame/
│   │   │       │   └── MainFrame.java            # 主窗口
│   │   │       ├── panel/
│   │   │       │   ├── SidebarPanel.java         # 侧边栏 (集合树 + 历史列表)
│   │   │       │   ├── RequestPanel.java         # 请求构建面板
│   │   │       │   ├── ResponsePanel.java        # 响应展示面板
│   │   │       │   ├── KeyValueTablePanel.java   # 键值对表格 (复用组件)
│   │   │       │   └── FormDataPanel.java        # form-data 专用面板
│   │   │       └── dialog/
│   │   │           ├── EnvironmentDialog.java    # 环境管理对话框
│   │   │           ├── GlobalVariableDialog.java # 全局变量对话框
│   │   │           ├── PluginManagerDialog.java  # 插件管理对话框
│   │   │           └── LoadTestDialog.java       # 性能测试对话框
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       ├── java/com/jpostman/service/
│       │   ├── HttpEngineServiceTest.java        # 11个测试
│       │   └── VariableResolverTest.java         # 14个测试
│       └── resources/
│           └── application.yml
```

**源文件统计**: 47 个 Java 文件 (main) + 2 个 Java 文件 (test)

## 四、数据存储

### 目录结构
```
~/.api-client/
├── collections/          # 每个集合一个 .json 文件 (如 订单服务.json)
├── environments/         # 每个环境一个 .json 文件 (如 dev.json)
├── plugins/
│   ├── loaded/           # 已加载插件 JAR
│   ├── disabled/         # 已禁用插件
│   └── logs/             # 插件日志
├── reports/              # 性能测试报告
├── globals.json          # 全局变量
├── history.json          # 历史记录 (默认上限500条)
└── settings.json         # 用户设置 (主题/字体/历史上限)
```

### JSON 序列化
- ObjectMapper Bean 在 `JPostmanConfig` 中配置: `INDENT_OUTPUT` + `FAIL_ON_UNKNOWN_PROPERTIES=false` + `JavaTimeModule`
- `CollectionItem` 使用 Jackson 多态序列化: `@JsonTypeInfo(property="type")` + `@JsonSubTypes({folder, request})`
- 文件名安全化: `[^a-zA-Z0-9\u4e00-\u9fa5]` 替换为 `_` (保留字母、数字、汉字)

## 五、核心架构设计

### 5.1 数据模型

**集合树结构** (CollectionFile → FolderNode/RequestNode):
```
CollectionFile (集合)
├── baseUrl: String                    # 集合级公共前置 URL
├── headers: List<KeyValue>            # 集合级公共请求头
├── auth: AuthConfig                   # 集合级公共认证
├── variables: List<Variable>          # 集合级变量
└── items: List<CollectionItem>        # 树形结构 (递归嵌套)
    ├── FolderNode (文件夹)
    │   ├── items: List<CollectionItem>  # 可继续嵌套
    │   └── ...
    └── RequestNode (请求)
        ├── method, url, description
        ├── params: List<KeyValue>
        ├── headers: List<KeyValue>
        ├── bodyType: String            # none/form-data/urlencoded/raw/binary
        ├── bodyContent: String         # raw为文本, form-data/urlencoded为JSON数组, binary为文件路径
        ├── rawContentType: String      # raw模式的Content-Type
        └── auth: AuthConfig
```

### 5.2 变量系统 (4级优先级)

优先级从高到低:
1. **本地变量 (Local)** — 请求执行期间临时生成，不持久化
2. **环境变量 (Environment)** — 绑定当前激活环境
3. **集合变量 (Collection)** — 绑定请求所属集合
4. **全局变量 (Global)** — 跨所有集合和环境生效

**变量语法**: `{{variableName}}`
**动态函数**: `{{$timestamp}}`, `{{$uuid}}`, `{{$randomInt}}`, `{{$datetime}}`

`VariableResolver.resolve(raw, collectionVariables, localVariables)` 方法合并全部作用域后替换。

### 5.3 HTTP 引擎 (HttpEngineService)

- 使用 OkHttp 4.12.0，SSL trust-all
- **性能指标**: 通过 EventListener 捕获 DNS时间、TCP连接时间、TTFB
- **请求取消**: `volatile Call currentCall` + `cancelCurrentRequest()`
- **Cookie 自动管理**: 请求前自动添加 Cookie 头，响应后自动存储 Set-Cookie
- **Content-Type 自动管理**: 在 `buildConfig()` 中计算，仅当用户未手动设置时添加，不修改 headers 面板数据
- **空 body 处理**: POST/PUT/PATCH 使用 `RequestBody.create("", null)`
- **插件集成**: 请求前应用 `RequestInterceptor`，响应后应用 `ResponseProcessor`

### 5.4 插件系统

- **发现机制**: Java SPI (`ServiceLoader.load(JPostmanPlugin.class, classLoader)`)
- **类隔离**: 每个 JAR 使用独立 `URLClassLoader`
- **扩展点**:
  - `RequestInterceptor` — 请求发送前修改请求
  - `ResponseProcessor` — 收到响应后处理数据
  - `VariableFunction` — 扩展动态变量函数
- **生命周期**: `onLoad()` / `onUnload()`

### 5.5 性能测试 (LoadTestService)

- 使用 `ExecutorService` 线程池模拟虚拟用户
- 每个 VU 循环执行请求序列
- **负载模型**: fixed / rampup / spike / peak (目前 fixed 已完整实现)
- **实时监控**: 每秒采集 `MetricPoint` (VU数/平均RT/错误率/吞吐量)
- **结果统计**: P50/P90/P95/P99 百分位计算
- **断言**: 平均RT上限、错误率上限

## 六、UI 架构

### MainFrame (主窗口)
- **NORTH**: 工具栏 (method + url + 发送 + 取消 + 保存请求 + 环境)
- **CENTER**: JSplitPane 水平分割 (SidebarPanel | JSplitPane垂直分割(RequestPanel / ResponsePanel))
- **SOUTH**: 状态栏 (状态文本 + Cookie计数)
- **菜单栏**: 文件(导入cURL/导入Postman/导出集合) + 编辑(环境管理/全局变量/Cookie管理/插件管理) + 视图(亮色/暗色主题/字体大小) + 工具(性能测试) + 帮助

### 关键 UI 组件

| 组件 | 功能 |
|------|------|
| KeyValueTablePanel | 可复用键值对表格，支持表格/文本双模式切换、右键菜单(复制键/值/键值对等)、表头排序、常见Header下拉建议 |
| FormDataPanel | form-data专用，支持Text/File类型，文本模式路径自动识别 |
| RequestPanel | 请求构建，含Params/Headers/Body/Auth标签页，Params↔URL双向同步 |
| ResponsePanel | 响应展示，含Body/Headers/Cookie标签页，性能指标面板，JSON/XML美化，HTML预览，图片预览 |
| SidebarPanel | 集合树(右键菜单CRUD) + 历史列表(搜索/过滤) |

### 快捷键
- `Ctrl+Enter`: 发送请求
- `Ctrl+S`: 保存请求
- `Ctrl+N`: 新建请求

## 七、当前状态

### 编译与测试
```
mvn clean test
→ Tests run: 25, Failures: 0, Errors: 0, Skipped: 0
→ BUILD SUCCESS
```

### 启动
```
mvn spring-boot:run
→ Started JPostmanApplication in ~2.1 seconds (冷启动 < 3秒，达标)
→ 无异常
```

### 打包
```
mvn package
→ target/jpostman-0.1.0-SNAPSHOT.jar
→ java -jar target/jpostman-0.1.0-SNAPSHOT.jar
```

## 八、已实现 vs 待实现

### 已完成
- [x] 模块一: HTTP 请求构建与发送 (7种方法/Params/Headers/Body 5种类型/Auth 4种类型/取消/性能指标)
- [x] 模块二: 集合与项目管理 (树形CRUD/集合继承配置/Postman导入/cURL导入/JSON导出)
- [x] 模块三: 环境与变量管理 (4级作用域/动态变量/Secret变量/环境切换)
- [x] 模块四: 请求历史 (自动记录/搜索/过滤/删除/清空)
- [x] 模块五: 插件系统 (接口/SPI发现/3个扩展点/管理界面)
- [x] 模块六: 性能测试 (VU配置/fixed负载模型/实时监控/百分位/断言)
- [x] 辅助: 主题切换/快捷键/字体调节/Cookie管理/右键菜单/表头排序
- [x] v2架构迁移: SQLite→JSON文件存储

### 待实现 / 可改进
- [ ] 请求标签页 (多 Tab 同时打开多个请求)
- [ ] OpenAPI 3.0 导入/导出
- [ ] 负载模型 rampup/spike/peak 的完整实现 (目前 fixed 已完成)
- [ ] 数据驱动测试 (CSV/JSON 数据文件映射到变量)
- [ ] 性能测试报告导出 (HTML/JSON/CSV)
- [ ] 变量自动补全弹窗 (输入 `{{` 时弹出可选变量列表)
- [ ] 拖拽排序 (集合树中请求/文件夹拖拽移动)
- [ ] Path Variables (`{{variable}}` 占位符在URL路径中的识别和高亮)
- [ ] 集合级继承配置的 UI 编辑界面 (baseUrl/公共headers/公共auth的编辑入口)
- [ ] 插件的热卸载 UI 操作 (目前代码支持 unloadPlugin，但界面只有刷新)
- [ ] 性能测试 CLI 模式 (规格中标注暂不实现)
- [ ] 响应体搜索 (Ctrl+F 在响应体中搜索)
- [ ] 历史记录保存到集合 (弹窗选择 Collection 和 Folder)

## 九、关键设计决策与教训

1. **存储从 SQLite 迁移到 JSON**: v1 用 SQLite+JPA，v2 改为纯 JSON 文件存储，每个 Collection/Environment 一个文件，天然支持 Git
2. **Content-Type 自动管理**: 不要在 headers 面板中修改数据，而是在 `buildConfig()` 时计算并合并，避免切换 body 类型时 Content-Type 累积
3. **Content-Type 累积 bug 修复**: 彻底移除 `updateAutoContentType()` 方法，改为构建时计算
4. **LazyInitializationException**: v1 用 JPA 时 @OneToMany 默认 LAZY 导致问题，v2 已移除 JPA 不存在此问题
5. **Jackson 多态序列化**: `CollectionItem` 使用 `@JsonTypeInfo` + `@JsonSubTypes` 实现文件夹/请求的多态序列化
6. **JavaTimeModule**: HistoryRecord 含 LocalDateTime，必须注册 `jackson-datatype-jsr310`
7. **KeyValueTablePanel 复用**: Params、Headers、urlencoded Body 都复用此组件，通过 `setKeySuggestions()` 和 `setData()` 定制
8. **BorderLayout 而非 MigLayout**: 按钮区域必须用 BorderLayout.SOUTH 确保可见，MigLayout 的 grow 会隐藏按钮
9. **不要用 JScrollPane 包裹 RequestPanel**: 会破坏 BorderLayout.SOUTH 的按钮可见性

## 十、用户偏好

- 沟通语言: **中文**
- UI 语言: **全中文**
- 技术栈: PHP, Java (proficient)，jblade/jaravel 框架
- 文档: 不要引用未上传到 GitHub 的文件
- 插件分类: 远程执行与热重载应为独立插件组
- 插件依赖: 热重载和远程执行插件应依赖 SpringBoot 但不强依赖 jaravel 相关函数
- jblade 开发: 增强原生表达式编译能力，而非添加 PHP 转换层
- 迁移系统: 参考 https://github.com/weacsoft/database-all-support 的实现方式

## 十一、规格排除清单 (不需要实现)

- ❌ 多协议 (WebSocket/gRPC/MQTT/SSE) — 仅 HTTP/HTTPS
- ❌ 自动化功能测试 (Test Runner/断言驱动功能测试)
- ❌ Mock Server
- ❌ 多人协作/实时同步/权限管理
- ❌ 命令行 CLI 工具 (性能测试 CLI 暂不实现)
- ❌ 数据库直连
- ❌ 代理抓包

## 十二、快速验证命令

```powershell
# 编译
cd d:\0code\ai\JPostman; mvn clean compile

# 测试
cd d:\0code\ai\JPostman; mvn test

# 启动
cd d:\0code\ai\JPostman; mvn spring-boot:run

# 打包
cd d:\0code\ai\JPostman; mvn package

# 检查编译错误
cd d:\0code\ai\JPostman; mvn compile 2>&1 | Select-String "ERROR"
```
