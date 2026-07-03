# JPostman 开发规划

> 本地 API 调试工具 · Swing 桌面应用 · 基于 SpringBoot 2.7 · 集成 jaravel-vendor 热加载插件系统
> 最低兼容 Windows 7 / Windows Server 2012

---

## 一、项目定位

JPostman 是一个运行在本地的**桌面 GUI 应用**,对标 Postman / Apifox 的核心操作体验,但去除云同步、团队协作、在线文档等功能,专注于**单人本地使用**的 API 测试与调试场景。

JPostman 不是 B/S 架构,不启动 Web 服务器,不通过浏览器访问。用户双击运行即弹出原生桌面窗口,所有交互在窗口内完成。

核心设计原则:

- **桌面应用**: Swing 原生窗口,FlatLaf 现代外观,不依赖浏览器,不启动 HTTP 服务
- **本地优先**: 所有数据存储在本地 SQLite 文件中,无需联网,无需账号
- **界面对标**: 操作逻辑和界面布局贴近 Postman,降低学习成本
- **插件可扩展**: 借助 jaravel-vendor 的热加载插件系统,支持运行时加载扩展功能
- **Win7 兼容**: JDK 11 开发、`--release 8` 编译、JRE 8+ 运行,最低兼容 Windows 7

---

## 二、技术选型

| 层级 | 技术 | 版本 | 选择理由 |
|------|------|------|----------|
| 开发 JDK | JDK | 11 | `var` 类型推断等现代语法,`--release 8` 编译确保兼容 JRE 8 |
| 编译目标 | Java bytecode | 8 | 兼容 Windows 7 上的 JRE 8 |
| 运行 JRE | JRE | 8+ | JRE 8 是 Win7 上官方支持最可靠的 Java 运行环境 |
| GUI 框架 | Swing | JDK 内置 | 所有 JDK 版本内置,无版本绑定,FlatLaf 提供现代外观 |
| 外观主题 | FlatLaf | 3.x | 现代扁平外观,浅色/深色主题,替代 Swing 默认 Metal 主题 |
| 布局管理 | MigLayout | 11.x | 强大的声明式布局管理器,比 GridBagLayout 更简洁 |
| 容器框架 | SpringBoot | 2.7.x (LTS) | 支持 JDK 8/11,非 Web 模式运行 |
| HTTP 客户端 | OkHttp | 4.x | API 友好,拦截器机制灵活,支持 HTTP/2 |
| 数据库 | SQLite | 3.x | 单文件本地存储,零配置,方便备份迁移 |
| 数据访问 | Spring Data JPA + Hibernate | 随 SpringBoot 2.7 | 简化 CRUD,自动建表 |
| SQLite 驱动 | sqlite-jdbc | 3.x | JDBC 驱动,无需安装数据库服务 |
| 插件系统 | jaravel-vendor plugin-jar-core + plugin-java-core | 降级到 SB2 | JAR 热加载 + Java 源码编译加载 |

### 架构选型说明

**为什么是 Swing 而非 JavaFX**: JavaFX 版本与 JDK 版本硬绑定(JavaFX 8 只在 JDK 8 内置,JavaFX 11+ 的 class 文件需要 JRE 11+),无法同时满足"开发用高版本 JDK"和"运行兼容低版本 JRE"。Swing 内置于所有 JDK 版本,不存在此问题。详见 [ADR-0001](docs/adr/0001-win7兼容性技术栈降级.md)。

**为什么用 SpringBoot 2.7 而非 3.x**: jaravel-vendor 的 plugin-jar-core 和 plugin-java-core 核心技术(URLClassLoader、ClassLoader 隔离、JavaCompiler API)在 JDK 8+ 完全可用,不依赖 JDK 17 新特性。SpringBoot 2.7 支持 JDK 8/11,且使用 `javax.*` 命名空间。SpringBoot 3 强制要求 JDK 17,不支持 Win7。

**为什么用 `--release 8` 而非 `target 1.8`**: `--release 8` 不仅设置 bytecode 版本,还会将 JDK 11 独有的 API 标记为编译错误,防止误用高版本 API 导致运行时 NoSuchMethodError。

### 插件系统说明

jaravel-vendor 的热加载插件系统包含两个模块,都需降级到 SpringBoot 2 兼容版本:

- **plugin-jar-core**: JAR 插件热加载/卸载,三级 ClassLoader 隔离。核心技术 URLClassLoader 在 JDK 8+ 完全可用。
- **plugin-java-core**: Java 源文件直接编译加载。核心技术 `javax.tools.JavaCompiler` 在 JDK 8+ 完全可用。支持"只写一个 Java 类即作为插件"的轻量场景。

两个模块的降级改动主要是 `jakarta.*` → `javax.*` 命名空间适配,核心逻辑不变。jaravel-vendor 源码在 https://github.com/weacsoft/jaravel-vendor,clone 后修改再推送。

> 插件文档参考: https://weacsoft.github.io/jaravel-vendor/#hotreload

---

## 三、整体架构

```
┌──────────────────────────────────────────────────────────┐
│                   Swing 桌面窗口                          │
│                                                           │
│  ┌─────────┬────────────────────────────────────────────┐ │
│  │  左侧栏  │            主内容区                         │ │
│  │         │  ┌─请求构建区──────────────────────────────┐│ │
│  │ 项目树   │  │ [POST ▾] [URL................] [Send]  ││ │
│  │         │  │ [Params] [Headers] [Body] [Auth]       ││ │
│  │         │  └────────────────────────────────────────┘│ │
│  │         │  ┌─响应展示区──────────────────────────────┐│ │
│  │         │  │ Status: 200 OK  Time: 245ms  Size: 1.2KB││ │
│  │         │  │ [Body] [Headers] [Cookies]             ││ │
│  │         │  └────────────────────────────────────────┘│ │
│  └─────────┴────────────────────────────────────────────┘ │
└───────────────────────────┬──────────────────────────────┘
                            │ Spring 依赖注入(直接方法调用)
┌───────────────────────────┴──────────────────────────────┐
│              SpringBoot 2.7 容器 (非 Web 模式)             │
│                                                           │
│  ┌──────────┐  ┌──────────┐  ┌────────────────┐          │
│  │ UI 面板   │  │ HTTP引擎  │  │  插件管理器     │          │
│  │  Panel   │  │  OkHttp  │  │ plugin-jar-core │          │
│  └──────────┘  └──────────┘  │ plugin-java-core│          │
│       │              │       └────────────────┘          │
│  ┌──────────┐  ┌──────────┐  ┌────────────────┐          │
│  │ 业务服务  │  │ 变量解析  │  │  插件执行点     │          │
│  │ Service  │  │ {{var}}  │  │ 前置/后置钩子   │          │
│  └──────────┘  └──────────┘  └────────────────┘          │
│       │                                                   │
│  ┌──────────┐                                            │
│  │ 数据访问  │                                            │
│  │ JPA/Repo │                                            │
│  └──────────┘                                            │
└────────────────────┬─────────────────────────────────────┘
                     │
              ┌──────┴──────┐
              │  SQLite 文件 │
              │  jpostman.db │
              └─────────────┘
```

### 与 B/S 架构的根本区别

| 维度 | B/S 架构(已废弃) | Swing 桌面架构(采用) |
|------|-------------------|------------------------|
| 界面载体 | 浏览器渲染 HTML | Swing 原生窗口渲染 |
| 通信方式 | HTTP REST API | Spring 依赖注入,直接方法调用 |
| 启动方式 | 启动 Web 服务器,浏览器访问 | 双击运行,弹出窗口 |
| Web 服务器 | Tomcat | 无(WebApplicationType.NONE) |
| 前端技术 | Vue + Element Plus | Swing + FlatLaf + MigLayout |
| 打包产物 | 可执行 JAR(含 Web 服务) | 桌面应用(含原生启动器) |

### 请求执行流程

```
用户点击"Send"按钮
    │
    ▼
Swing Panel 收集请求配置(method, url, headers, body, auth)
    │
    ▼
SwingWorker 异步调用 HttpEngineService.execute(requestConfig)
    │
    ▼
变量解析: 替换 URL/Headers/Body 中的 {{var}}
    │
    ▼
执行前置插件钩子(可选签名/加密等)
    │
    ▼
OkHttp 构建并发送请求
    │
    ▼
接收响应,执行后置插件钩子(可选提取/断言)
    │
    ▼
写入历史记录(HistoryService)
    │
    ▼
返回 ResponseData,SwingUtilities.invokeLater() 回到 EDT 更新界面
    │
    ▼
Swing 渲染响应(状态码/Headers/Body/耗时/大小)
```

---

## 四、功能规划

### 功能总览

| 模块 | 功能点 | 说明 |
|------|--------|------|
| 项目管理 | 项目 CRUD | 创建、重命名、删除项目 |
| | 集合管理 | 项目下创建集合,集合下组织请求 |
| | 请求组织 | 请求归属于集合,支持排序 |
| 请求构建 | HTTP 方法 | GET / POST / PUT / DELETE / PATCH / HEAD / OPTIONS |
| | URL 与参数 | URL 输入,支持 {{变量}},Query Params 键值对 |
| | 请求头 | Headers 键值对编辑,常用头快选 |
| | 请求体 | none / form-data / x-www-form-urlencoded / raw(JSON/XML/Text/HTML) / binary |
| | 认证 | No Auth / Basic Auth / Bearer Token / API Key |
| | 超时设置 | 连接超时、读取超时可配置 |
| 响应展示 | 响应概要 | 状态码、状态文本、耗时、响应大小 |
| | 响应体 | Pretty(JSON/XML 格式化缩进) / Raw / Preview(HTML 渲染) |
| | 响应头 | Headers 键值对展示 |
| | 响应 Cookie | Set-Cookie 列表 |
| 历史记录 | 自动记录 | 每次请求自动存入历史 |
| | 历史浏览 | 按时间倒序列表,支持搜索过滤 |
| | 恢复请求 | 从历史记录恢复请求配置到构建区 |
| 环境变量 | 多环境管理 | 创建多套环境(如开发/测试/生产) |
| | 变量引用 | 请求 URL/Headers/Body 中用 {{key}} 引用变量 |
| | 环境切换 | 顶部下拉切换当前环境 |
| | 全局变量 | 跨环境共享的全局变量 |
| 插件系统 | JAR 插件加载 | 将 JAR 放入插件目录,运行时热加载 |
| | 源码插件加载 | 将 .java 文件放入插件目录,直接编译加载 |
| | 插件卸载 | 运行时卸载插件,释放资源 |
| | 执行钩子 | 请求前置钩子、响应后置钩子 |
| | 插件管理 | 界面查看已加载插件列表与状态 |

### 界面布局

参考 Postman 的经典三栏布局,使用 Swing 组件实现:

```
JFrame (主窗口, BorderLayout)
├── NORTH: JToolBar
│   ├── JComboBox (环境选择)
│   ├── JSeparator
│   ├── JLabel ("JPostman")
│   │
│   ├── JButton (历史记录)
│   └── JButton (插件管理)
│
├── CENTER: JSplitPane (水平,可拖拽调整左右宽度)
│   ├── left: JScrollPane (固定宽度 260px)
│   │   └── JTree (项目→集合→请求三级树)
│   │       └── 自定义 TreeCellRenderer (方法标签颜色 + 操作按钮)
│   │
│   └── right: JSplitPane (垂直,可拖拽调整上下高度)
│       ├── top: JPanel (请求构建区, BorderLayout)
│       │   ├── NORTH: JPanel (Flow Layout)
│       │   │   ├── JComboBox (HTTP 方法)
│       │   │   ├── JTextField (URL 输入)
│       │   │   ├── JButton (Send)
│       │   │   └── JButton (Save)
│       │   └── CENTER: JTabbedPane
│       │       ├── "Params"  → JTable (key/value/description/enabled)
│       │       ├── "Headers" → JTable (key/value/description/enabled)
│       │       ├── "Body"    → JPanel (类型选择 + 编辑区)
│       │       └── "Auth"    → JPanel (类型选择 + 认证字段)
│       │
│       └── bottom: JPanel (响应展示区, BorderLayout)
│           ├── NORTH: JPanel (状态栏: 状态码标签 + 耗时 + 大小)
│           └── CENTER: JTabbedPane
│               ├── "Body"    → JTextArea (等宽字体,格式化显示)
│               ├── "Headers" → JTable
│               └── "Cookies" → JTable
```

---

## 五、数据模型

使用 SQLite 存储,通过 JPA 实体自动建表。

### ER 关系

```
Project 1───n Collection 1───n RequestItem
                                          (请求定义)
Environment 1───n EnvironmentVariable
History (独立表,记录每次执行)
GlobalVariable (独立表)
```

### 表结构

**projects** — 项目

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| name | VARCHAR(100) | 项目名称 |
| description | VARCHAR(500) | 项目描述 |
| sort_order | INT | 排序序号 |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

**collections** — 集合

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| project_id | BIGINT FK | 所属项目 |
| name | VARCHAR(100) | 集合名称 |
| description | VARCHAR(500) | 集合描述 |
| sort_order | INT | 排序序号 |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

**request_items** — 请求定义

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| collection_id | BIGINT FK | 所属集合 |
| name | VARCHAR(200) | 请求名称 |
| method | VARCHAR(10) | HTTP 方法 |
| url | VARCHAR(2000) | 请求 URL |
| params | TEXT | Query 参数(JSON) |
| headers | TEXT | 请求头(JSON) |
| body_type | VARCHAR(20) | body 类型: none/form-data/urlencoded/raw/binary |
| body_content | TEXT | body 内容(JSON 或原始文本) |
| auth_type | VARCHAR(20) | 认证类型: none/basic/bearer/apikey |
| auth_data | TEXT | 认证数据(JSON) |
| sort_order | INT | 排序序号 |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

**history** — 历史记录

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| request_name | VARCHAR(200) | 请求名称 |
| method | VARCHAR(10) | HTTP 方法 |
| url | VARCHAR(2000) | 请求 URL |
| request_headers | TEXT | 请求头(JSON) |
| request_body | TEXT | 请求体 |
| status_code | INT | 响应状态码 |
| status_text | VARCHAR(100) | 状态文本 |
| response_headers | TEXT | 响应头(JSON) |
| response_body | TEXT | 响应体 |
| response_time | BIGINT | 耗时(ms) |
| response_size | BIGINT | 响应大小(bytes) |
| created_at | TIMESTAMP | 执行时间 |

**environments** — 环境

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| name | VARCHAR(100) | 环境名称(如"开发环境") |
| variables | TEXT | 变量键值对(JSON) |
| created_at | TIMESTAMP | 创建时间 |
| updated_at | TIMESTAMP | 更新时间 |

**global_variables** — 全局变量

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT PK | 自增主键 |
| key | VARCHAR(100) | 变量名 |
| value | VARCHAR(2000) | 变量值 |
| description | VARCHAR(500) | 描述 |

---

## 六、Service 接口设计

桌面应用中,UI 面板通过 Spring 注入直接调用 Service 方法,不经过 HTTP。以下是核心 Service 接口。

### ProjectService / CollectionService / RequestItemService

```java
// 项目管理
List<Project> getProjectTree();           // 返回项目→集合→请求三级树
Project createProject(String name, String description);
Project updateProject(Long id, String name, String description);
void deleteProject(Long id);              // 级联删除集合和请求

// 集合管理
Collection createCollection(Long projectId, String name, String description);
Collection updateCollection(Long id, String name, String description);
void deleteCollection(Long id);

// 请求定义管理
RequestItem createRequest(Long collectionId, String name, String method, String url);
RequestItem updateRequest(Long id, RequestItem config);
RequestItem getRequest(Long id);
void deleteRequest(Long id);
```

### HttpEngineService

```java
ResponseData execute(RequestConfig config);
// 输入: 请求配置(已做变量替换)
// 输出: 响应数据(状态码/状态文本/响应头/响应体/耗时/大小)
// 内部: 执行前置钩子 → OkHttp 发送 → 执行后置钩子 → 写历史记录
```

### HistoryService

```java
Page<History> getHistoryList(int page, int size, String keyword);
History getHistoryDetail(Long id);
void deleteHistory(Long id);
void clearAllHistory();
```

### EnvironmentService

```java
List<Environment> getAllEnvironments();
Environment createEnvironment(String name, Map<String, String> variables);
Environment updateEnvironment(Long id, String name, Map<String, String> variables);
void deleteEnvironment(Long id);
void setActiveEnvironment(Long id);        // 设置激活环境
Environment getActiveEnvironment();        // 获取当前激活环境

List<GlobalVariable> getGlobalVariables();
void saveGlobalVariables(List<GlobalVariable> variables);
Map<String, String> getEffectiveVariables(); // 合并激活环境变量 + 全局变量
```

### PluginManagerService

```java
List<PluginInfo> getLoadedPlugins();       // 已加载插件列表
void loadPlugin(String jarPath);           // 加载指定 JAR (plugin-jar-core)
void loadJavaSource(String javaFilePath);  // 编译加载 Java 源文件 (plugin-java-core)
void unloadPlugin(String pluginName);      // 卸载插件
void scanPlugins();                        // 扫描 plugins/ 目录(含 .jar 和 .java)
```

---

## 七、插件系统集成方案

### 集成方式

引入 jaravel-vendor 降级后的插件模块:

```xml
<!-- JAR 插件热加载 -->
<dependency>
    <groupId>io.github.lijialong1313</groupId>
    <artifactId>plugin-jar-core</artifactId>
    <version>0.1.0-sb2</version>  <!-- SpringBoot 2 兼容版 -->
</dependency>

<!-- Java 源码插件编译加载 -->
<dependency>
    <groupId>io.github.lijialong1313</groupId>
    <artifactId>plugin-java-core</artifactId>
    <version>0.1.0-sb2</version>  <!-- SpringBoot 2 兼容版 -->
</dependency>
```

两个模块均仅依赖 SpringBoot(降级到 2.7),在桌面应用中以非 Web 模式运行。

### 双模式插件加载

JPostman 支持两种插件形式,满足不同复杂度的扩展需求:

**JAR 插件 (plugin-jar-core)** — 完整的 JAR 包,适合复杂插件:
- 三级 ClassLoader 隔离,插件间互不干扰
- 可包含多个类、资源文件、第三方依赖
- 热加载/卸载,无需重启

**源码插件 (plugin-java-core)** — 单个 .java 文件,适合轻量插件:
- 有时只需要一个 Java 类就能完成扩展(如简单的签名算法)
- 直接编译 .java 文件加载,无需打包 JAR
- `javax.tools.JavaCompiler` 在 JDK 8+ 可用(JRE 运行时需确保 tools.jar 或 jrt.fs 在 classpath)

### 插件接口设计

```java
package com.jpostman.plugin;

/**
 * 请求前置钩子: 在请求发送前执行,可修改请求配置
 * 用途: 签名计算、参数加密、动态 Token 注入等
 */
public interface PreRequestHook {
    void beforeRequest(RequestContext context);
}

/**
 * 响应后置钩子: 在收到响应后执行,可读取/处理响应
 * 用途: 数据提取、断言校验、响应转换等
 */
public interface PostResponseHook {
    void afterResponse(ResponseContext context);
}
```

### 源码插件示例

一个 .java 文件即为一个完整插件,适合简单场景:

```java
// plugins/HmacSignPlugin.java
@JPostmanPlugin(name = "HMAC签名", version = "1.0", author = "dev")
public class HmacSignPlugin implements PreRequestHook {
    @Override
    public void beforeRequest(RequestContext context) {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String sign = hmacSha256("secret", context.getUrl() + timestamp);
        context.addHeader("X-Timestamp", timestamp);
        context.addHeader("X-Signature", sign);
    }

    private String hmacSha256(String key, String data) {
        // HMAC-SHA256 实现
        return ...;
    }
}
```

### 插件加载流程

```
启动 JPostman
    │
    ▼
扫描 plugins/ 目录
    │
    ├── *.jar 文件 → plugin-jar-core 加载,隔离 ClassLoader
    │
    └── *.java 文件 → plugin-java-core 编译加载
    │
    ▼
扫描插件中带 @JPostmanPlugin 注解的类
    │
    ▼
实例化插件,注册到钩子管理器
    │
    ▼
请求执行时,按注册顺序调用 PreRequestHook / PostResponseHook
```

运行时新增插件: 将 JAR 或 .java 文件放入 `plugins/` 目录,在界面点击"重新扫描"或调用 PluginManagerService,热加载新插件,无需重启。

---

## 八、分步实现计划

以下将整个项目拆分为 13 个步骤,每个步骤可独立完成并验证。建议按顺序执行,因为后续步骤依赖前序步骤的产出。

---

### 步骤 1: 项目初始化与基础架构

**目标**: 搭建 SpringBoot 2.7 + Swing 项目骨架,能启动并显示空白窗口。

**具体内容**:

1. 在 `d:\0code\ai\JPostman` 下创建 Maven 项目,`pom.xml` 配置:
   - SpringBoot 2.7.x parent
   - 依赖: spring-boot-starter(非 web)、spring-boot-starter-data-jpa、sqlite-jdbc、okhttp、flatlaf、miglayout
   - Java 11 编译,`--release 8` 目标
   - maven-shade-plugin 或 maven-assembly-plugin 打包

2. 应用配置 `application.yml`:
   - `spring.main.web-application-type: none`(非 Web 模式)
   - SQLite 数据源配置(JDBC URL: `jdbc:sqlite:jpostman.db`)
   - JPA 配置: `ddl-auto: update`(自动建表)
   - Hibernate SQLite 方言配置

3. 主启动类 `JPostmanApplication.java`:
   - `main()` 方法: 以 `WebApplicationType.NONE` 启动 SpringBoot,获取 `ConfigurableApplicationContext`
   - 在 EDT 中创建 JFrame: `SwingUtilities.invokeLater()`
   - 设置 FlatLaf 外观: `FlatIntelliJLaf.setup()`
   - 窗口关闭时: `context.close()` 优雅关闭 SpringBoot

4. Spring Bean 与 Swing 集成:
   - UI 面板类标注 `@Component`,通过 `@Autowired` 注入 Service
   - 主窗口从 Spring 容器获取主面板: `context.getBean(MainFrame.class)`

5. 目录结构:

```
JPostman/
├── pom.xml
├── src/main/java/com/jpostman/
│   ├── JPostmanApplication.java         # 启动类 (SpringBoot + Swing)
│   ├── config/                          # 配置类
│   ├── service/                         # 业务逻辑
│   ├── model/                           # JPA 实体 + DTO
│   ├── repository/                      # 数据访问
│   ├── http/                            # HTTP 请求引擎
│   ├── plugin/                          # 插件接口与管理
│   ├── ui/
│   │   ├── frame/                       # JFrame 主窗口
│   │   ├── panel/                       # JPanel 面板(Spring Bean)
│   │   ├── dialog/                      # JDialog 对话框
│   │   ├── renderer/                    # Swing 渲染器
│   │   └── util/                        # UI 工具
│   └── common/                          # 通用工具
├── src/main/resources/
│   └── application.yml
└── plugins/                             # 插件目录(.jar 和 .java)
```

6. 主窗口: 一个带标题的空 `JFrame`,使用 `BorderLayout`,确认窗口能弹出

**验证标准**: `mvn package && java -jar jpostman.jar` 启动后,弹出 Swing 桌面窗口,标题显示"JPostman",FlatLaf 外观生效。无 Web 服务器启动,无浏览器参与。

---

### 步骤 2: 数据库与实体层

**目标**: 建立全部数据表,实现 JPA 实体与 Repository。

**具体内容**:

1. 创建 6 个 JPA 实体类,对应第五章的表结构:
   - `Project`, `Collection`, `RequestItem`, `History`, `Environment`, `GlobalVariable`
   - 使用 `@Entity`, `@Table`, `@Id`, `@GeneratedValue` 等注解
   - 时间字段使用 `LocalDateTime`

2. 创建对应的 Spring Data JPA Repository 接口:
   - `ProjectRepository`, `CollectionRepository`, `RequestItemRepository`
   - `HistoryRepository`, `EnvironmentRepository`, `GlobalVariableRepository`

3. 项目删除时级联删除集合和请求(在 Service 层处理,或使用 `@OneToMany(cascade = CascadeType.ALL)`)

4. Hibernate SQLite 方言: 配置 `hibernate.dialect` 为 SQLite 方言(SB2.7 + Hibernate 5.6 需要自定义或社区方言)

**验证标准**: 启动应用后,SQLite 数据库文件自动生成,表结构与设计一致。可写一个临时的 `ApplicationRunner` 测试插入和查询。

---

### 步骤 3: 项目与集合管理 Service

**目标**: 实现项目、集合、请求定义的完整 Service 层业务逻辑。

**具体内容**:

1. `ProjectService`: 项目树查询(含集合与请求)、创建、更新、删除
   - 树查询返回项目 → 集合 → 请求的三级结构,供 UI 直接渲染 JTree

2. `CollectionService`: 集合的创建、更新、删除

3. `RequestItemService`: 请求定义的创建、更新、删除、查详情

4. Service 层业务逻辑:
   - 删除项目时级联删除其下所有集合和请求
   - 排序序号管理
   - 输入校验(名称非空等)

5. DTO 定义: `RequestConfig`(请求配置)、`ResponseData`(响应数据)

**验证标准**: 通过单元测试或临时 ApplicationRunner 验证每个 Service 方法能正确创建、查询、更新、删除数据。

---

### 步骤 4: HTTP 请求引擎

**目标**: 实现 OkHttp 封装,能根据请求配置发送 HTTP 请求并返回结构化响应。

**具体内容**:

1. `HttpEngineService`: 核心请求执行类(深度模块)
   - 接口: `ResponseData execute(RequestConfig config)`
   - 内部处理: 变量替换、URL 拼接、Header 设置、Body 构建、Auth 注入、超时控制、SSL 信任、异常捕获、前置/后置插件钩子调用、历史记录写入

2. 请求构建逻辑:
   - URL 拼接 Query Params
   - Headers 设置
   - Body 构建:
     - form-data: MultipartBody
     - x-www-form-urlencoded: FormBody
     - raw: RequestBody + Content-Type
     - binary: 文件上传 RequestBody
   - Auth 处理:
     - Basic Auth: 添加 Authorization 头
     - Bearer Token: 添加 Authorization: Bearer xxx 头
     - API Key: 添加到 Header 或 Query Param

3. 超时与异常处理:
   - 连接超时、读取超时、写入超时分别配置
   - 网络异常、SSL 证书错误、DNS 解析失败等捕获并返回友好错误信息

4. 支持 HTTPS(默认信任所有证书,因为本地调试工具常面对自签名证书)

**验证标准**: 单元测试中用 MockWebServer 模拟目标服务器,验证 GET/POST 请求能正确发送并返回结构化响应。不依赖外部 API。

---

### 步骤 5: 环境变量与变量解析

**目标**: 实现环境变量管理,请求中的 {{key}} 能被正确替换。

**具体内容**:

1. `EnvironmentService`: 环境 CRUD + 激活环境管理
2. `GlobalVariableService`: 全局变量管理

3. `VariableResolver`: 变量解析服务(深度模块)
   - 接口: `String resolve(String raw)`
   - 解析逻辑: 查找 `{{key}}` 模式,依次从以下来源替换:
     1. 当前激活环境的变量
     2. 全局变量
   - 未找到的变量保留原样 `{{key}}`,并收集警告列表

4. 请求执行时,在构建 OkHttp 请求前,由 HttpEngineService 调用 VariableResolver 对 URL、Headers 值、Body 内容执行变量替换

**验证标准**: 单元测试验证变量替换逻辑。创建环境变量 `base_url = http://httpbin.org`,请求 URL 填写 `{{base_url}}/get`,执行后能正确解析。

---

### 步骤 6: 历史记录

**目标**: 每次请求自动记录历史,支持查询和恢复。

**具体内容**:

1. 在 `HttpEngineService` 执行请求后,调用 HistoryService 写入 `history` 表
   - 记录完整的请求和响应信息
   - 请求体和响应体可能较大,设置合理上限(如响应体超过 1MB 则截断存储)

2. `HistoryService`:
   - 分页查询历史(按时间倒序)
   - 支持按 URL / 方法 / 状态码搜索
   - 获取单条详情
   - 删除单条 / 清空全部

3. 历史记录列表只返回概要(名称、方法、URL、状态码、时间),详情方法返回完整数据

**验证标准**: 单元测试验证历史记录写入和查询。发送几次请求后,历史列表能正确显示;清空功能正常。

---

### 步骤 7: Swing 基础框架与主界面布局

**目标**: 搭建 Swing 界面骨架,实现三栏布局。

**具体内容**:

1. `MainFrame`(JFrame):
   - 设置 FlatLaf 外观(`FlatIntelliJLaf.setup()`)
   - `BorderLayout`: NORTH 工具栏,CENTER 主分割面板
   - 窗口大小 1200x800,居中显示
   - 关闭操作: 优雅关闭 SpringBoot 容器

2. 工具栏(`JToolBar`):
   - 环境选择 `JComboBox`(绑定 EnvironmentService)
   - 标题 `JLabel`
   - 历史按钮、插件按钮

3. 主分割面板(`JSplitPane` 水平):
   - left: 左侧导航(占位)
   - right: `JSplitPane` 垂直(请求构建区 + 响应展示区)
   - 分割比例可拖拽调整,比例持久化到配置文件

4. UI 面板类结构:
   ```
   ui/
   ├── frame/
   │   └── MainFrame.java              # 主窗口 JFrame
   ├── panel/
   │   ├── SidebarPanel.java           # 左侧导航
   │   ├── RequestBuilderPanel.java    # 请求构建区
   │   ├── ResponseViewerPanel.java    # 响应展示区
   │   └── ...
   ├── dialog/
   │   ├── HistoryDialog.java          # 历史记录对话框
   │   ├── EnvManagerDialog.java       # 环境变量管理对话框
   │   └── PluginManagerDialog.java    # 插件管理对话框
   └── util/
       └── UiUtils.java                # UI 工具方法
   ```

5. 所有面板类标注 `@Component`,通过 `@Autowired` 注入 Service

**验证标准**: 启动后看到完整的三栏布局窗口,顶栏环境选择有数据,各区域有占位内容,JSplitPane 分割线可拖拽,FlatLaf 外观生效。

---

### 步骤 8: Swing - 项目与集合管理界面

**目标**: 实现左侧导航树,支持项目/集合/请求的增删改查。

**具体内容**:

1. `SidebarPanel`(JPanel):
   - `JTree` 渲染三级树(项目 → 集合 → 请求)
   - 自定义 `DefaultTreeCellRenderer`: 方法标签带颜色(GET 绿色, POST 黄色, DELETE 红色等)
   - `MouseListener` 右键菜单: 项目节点(新建集合/编辑/删除)、集合节点(新建请求/编辑/删除)、请求节点(编辑/删除)
   - `TreeSelectionListener`: 点击请求节点,加载该请求配置到右侧构建区

2. 新建/编辑对话框(`JDialog`):
   - 项目: 名称(JTextField)、描述(JTextField)
   - 集合: 名称、描述
   - 请求: 名称 + HTTP 方法选择(JComboBox) + URL 预览

3. 删除操作需二次确认(`JOptionPane.showConfirmDialog`)

4. 使用 MigLayout 布局对话框表单

**验证标准**: 能在界面上创建项目、集合、请求;树形结构正确显示;删除操作有确认提示;点击请求节点能触发加载事件。

---

### 步骤 9: Swing - 请求构建与发送

**目标**: 实现请求构建区,能配置并发送请求。

**具体内容**:

1. `RequestBuilderPanel`(JPanel, BorderLayout):
   - NORTH: JPanel(MigLayout)
     - HTTP 方法 `JComboBox` + URL `JTextField` + Send `JButton` + Save `JButton`
   - CENTER: `JTabbedPane`
     - Params / Headers / Body / Auth

2. Params 标签页:
   - `JTable`(key, value, description, enabled 复选框列)
   - 底部 Bulk Edit 按钮(弹出 `JDialog` 含 `JTextArea` 批量编辑)
   - URL 输入框中的 Query 参数与 Params 表格双向同步

3. Headers 标签页:
   - `JTable`(同 Params 结构)
   - 常用 Header 快捷选择(`JPopupMenu`: Content-Type, Accept, Authorization 等)

4. Body 标签页:
   - 类型选择 `JComboBox`: none / form-data / x-www-form-urlencoded / raw / binary
   - form-data: `JTable`(value 列可选文本或文件选择按钮 `JFileChooser`)
   - x-www-form-urlencoded: `JTable`
   - raw: `JTextArea`(等宽字体 `Font.MONOSPACED`) + 语言选择 `JComboBox`
   - binary: 文件选择按钮(`JFileChooser`)

5. Auth 标签页:
   - 类型选择 `JComboBox`: No Auth / Basic Auth / Bearer Token / API Key
   - Basic Auth: 用户名 `JTextField` + 密码 `JPasswordField`
   - Bearer Token: Token `JTextField`
   - API Key: Key + Value + 位置 `JComboBox`(Header / Query Param)

6. Send 按钮:
   - 收集配置 → `SwingWorker` 异步调用 `HttpEngineService.execute()` → 传递响应给响应展示区
   - 发送过程中 Button 禁用 + 文字变为"Sending..."
   - `SwingWorker.done()` 中在 EDT 更新 UI

7. Save 按钮: 调用 RequestItemService 保存当前请求配置

**验证标准**: 能配置各种类型的请求并成功发送;请求参数正确传递;Body 各类型均能工作;认证信息正确附加;发送过程 UI 不卡顿。

---

### 步骤 10: Swing - 响应展示

**目标**: 实现响应展示区,格式化显示响应数据。

**具体内容**:

1. `ResponseViewerPanel`(JPanel, BorderLayout):
   - NORTH: 状态栏 JPanel
     - 状态码 `JLabel`(带颜色: 2xx 绿色, 3xx 蓝色, 4xx 橙色, 5xx 红色)
     - 状态文本 + 耗时 + 大小
   - CENTER: `JTabbedPane`
     - Body / Headers / Cookies

2. Body 标签页:
   - 展示模式切换 `JRadioButton`: Pretty / Raw / Preview
   - Pretty: `JTextArea`(只读,等宽字体),JSON 自动格式化缩进,XML 格式化
   - Raw: `JTextArea`(只读纯文本)
   - Preview: 如果 Content-Type 是 text/html,用 `JEditorPane`(HTMLEditorKit)渲染

3. Headers 标签页:
   - `JTable` 展示响应头
   - 支持搜索过滤(`JTextField` + `TableRowSorter`)

4. Cookies 标签页:
   - 从响应头的 Set-Cookie 解析 Cookie 列表
   - `JTable` 展示 name, value, domain, path, expires

5. 错误响应处理:
   - 网络错误: 状态栏显示错误信息,Body 区显示错误详情
   - 超时: 提示请求超时

**验证标准**: 发送请求后,响应区正确显示状态码、耗时、大小;JSON 响应能格式化缩进;响应头和 Cookie 正确展示。

---

### 步骤 11: Swing - 历史记录与环境变量

**目标**: 实现历史记录对话框和环境变量管理界面。

**具体内容**:

1. 历史记录对话框 `HistoryDialog`(JDialog):
   - 点击顶部"历史"按钮,弹出模态/非模态对话框
   - 历史列表: `JList` 或 `JTable`,每条显示方法标签(带颜色) + URL + 状态码 + 时间
   - 搜索框: `JTextField` 按 URL / 方法过滤
   - 双击某条历史: 加载到主窗口请求构建区(可编辑重发)
   - 删除单条 / 清空全部按钮

2. 环境变量管理对话框 `EnvManagerDialog`(JDialog):
   - `JSplitPane`: 左侧环境列表(`JList`),右侧变量编辑(`JTable`)
   - 环境列表: 新增 / 编辑名称 / 删除
   - 变量编辑: `JTable`(key, value, description)
   - 全局变量: 独立 `JTabbedPane` Tab
   - 当前生效变量预览: 合并激活环境变量 + 全局变量

3. 顶部环境选择 `JComboBox` 切换后,后续请求使用新环境的变量

**验证标准**: 历史记录正确展示并可搜索;双击历史能恢复请求到构建区;环境变量能增删改查;切换环境后变量替换生效。

---

### 步骤 12: 插件系统集成

**目标**: 集成 jaravel-vendor 插件系统(plugin-jar-core + plugin-java-core),实现双模式插件加载和执行钩子。

**具体内容**:

1. 降级 jaravel-vendor 插件模块:
   - clone https://github.com/weacsoft/jaravel-vendor
   - 将 plugin-jar-core 和 plugin-java-core 改为 SpringBoot 2.7 依赖,`jakarta.*` → `javax.*`
   - 编译发布 SB2 兼容版本

2. 引入降级后的依赖,配置插件目录(`./plugins/`)

3. 定义插件接口与上下文:
   - `PreRequestHook`: 请求前置钩子
   - `PostResponseHook`: 响应后置钩子
   - `RequestContext`: 请求上下文(method, url, headers, body, 可修改)
   - `ResponseContext`: 响应上下文(statusCode, headers, body, 只读)
   - `@JPostmanPlugin`: 插件声明注解(name, version, author, description)

4. `PluginManagerService`:
   - 应用启动时扫描 plugins/ 目录
   - `.jar` 文件 → plugin-jar-core 加载
   - `.java` 文件 → plugin-java-core 编译加载
   - 扫描带 @JPostmanPlugin 注解的类,实例化并注册钩子
   - 运行时加载/卸载插件

5. 在 `HttpEngineService` 中集成钩子调用:
   - 构建请求前: 遍历 PreRequestHook
   - 收到响应后: 遍历 PostResponseHook

6. 插件管理对话框 `PluginManagerDialog`(JDialog):
   - `JTable` 展示已加载插件(名称、版本、作者、类型[JAR/Java源码]、状态、钩子类型)
   - 加载按钮: `JFileChooser` 选择 .jar 或 .java 文件
   - 卸载按钮: 每行一个

7. 开发示例插件:
   - JAR 插件示例: HMAC 签名插件(打包为 JAR)
   - 源码插件示例: 简单日志插件(单个 .java 文件)

**验证标准**: 两种形式的插件都能被正确加载;请求发送时前置钩子被执行;插件管理界面能查看、加载、卸载插件;卸载后钩子不再执行;源码插件(单 .java 文件)能直接编译加载。

---

### 步骤 13: 完善与打包

**目标**: 完善细节体验,打包为桌面应用,确保 Win7 兼容。

**具体内容**:

1. 数据导入导出:
   - 导出: 将项目、集合、请求定义导出为 JSON 文件(Postman Collection 格式兼容)
   - 导入: 从 JSON 文件导入,支持 Postman Collection v2.1 格式

2. 快捷键支持:
   - Ctrl+Enter: 发送请求
   - Ctrl+S: 保存请求
   - Ctrl+H: 打开历史
   - 使用 `KeyStroke` + `InputMap` + `ActionMap` 绑定

3. 全局异常处理:
   - 后端: SpringBoot 全局异常处理
   - 前端: `SwingWorker` 失败时 `JOptionPane` 提示

4. 界面细节:
   - 请求构建区与响应区可拖拽调整分割高度(JSplitPane)
   - 深色 / 浅色主题切换(FlatIntelliJLaf ↔ FlatDarkLaf)
   - 请求发送中的 Loading 动画(覆盖式 `JProgressBar` 不确定模式)
   - 窗口大小和分割比例记忆(写入 SQLite 或配置文件)

5. 打包:
   - maven-shade-plugin 生成 fat JAR
   - 确保编译目标为 Java 8(`--release 8`)
   - Windows: 可选使用 launch4j 生成 .exe 启动器
   - 首次运行自动在 JAR 同级目录创建 `jpostman.db` 和 `plugins/` 目录

6. Win7 兼容性测试:
   - 在 Windows 7 + JRE 8 环境实测
   - 确认 FlatLaf、Swing、SQLite、OkHttp 均正常工作
   - 确认 plugin-java-core 的 JavaCompiler 在 JRE 8 上可用(需确认 tools.jar 在 classpath)

**验证标准**: 在 Windows 7 + JRE 8 上双击运行,能完整使用所有功能;数据持久化到 SQLite;插件目录功能正常(JAR 和 .java 两种);导入导出功能正常;窗口关闭再打开后布局状态保持。

---

## 九、目录结构总览

```
JPostman/
├── pom.xml
├── src/main/java/com/jpostman/
│   ├── JPostmanApplication.java            # SpringBoot + Swing 启动
│   ├── config/
│   │   └── JPostmanConfig.java             # SpringBoot 配置
│   ├── service/
│   │   ├── ProjectService.java
│   │   ├── CollectionService.java
│   │   ├── RequestItemService.java
│   │   ├── HttpEngineService.java          # HTTP 请求引擎(深度模块)
│   │   ├── HistoryService.java
│   │   ├── EnvironmentService.java
│   │   ├── VariableResolver.java           # 变量解析(深度模块)
│   │   └── PluginManagerService.java       # 插件管理
│   ├── model/
│   │   ├── entity/                         # JPA 实体
│   │   │   ├── Project.java
│   │   │   ├── Collection.java
│   │   │   ├── RequestItem.java
│   │   │   ├── History.java
│   │   │   ├── Environment.java
│   │   │   └── GlobalVariable.java
│   │   └── dto/                            # 数据传输对象
│   │       ├── RequestConfig.java
│   │       ├── ResponseData.java
│   │       └── PluginInfo.java
│   ├── repository/
│   │   ├── ProjectRepository.java
│   │   ├── CollectionRepository.java
│   │   ├── RequestItemRepository.java
│   │   ├── HistoryRepository.java
│   │   ├── EnvironmentRepository.java
│   │   └── GlobalVariableRepository.java
│   ├── http/
│   │   └── HttpClientWrapper.java          # OkHttp 封装
│   ├── plugin/
│   │   ├── PreRequestHook.java             # 前置钩子接口
│   │   ├── PostResponseHook.java           # 后置钩子接口
│   │   ├── RequestContext.java
│   │   ├── ResponseContext.java
│   │   └── JPostmanPlugin.java             # 插件注解
│   ├── ui/
│   │   ├── frame/
│   │   │   └── MainFrame.java              # 主窗口 JFrame
│   │   ├── panel/                          # JPanel 面板(Spring Bean)
│   │   │   ├── SidebarPanel.java
│   │   │   ├── RequestBuilderPanel.java
│   │   │   └── ResponseViewerPanel.java
│   │   ├── dialog/                         # JDialog 对话框
│   │   │   ├── HistoryDialog.java
│   │   │   ├── EnvManagerDialog.java
│   │   │   ├── PluginManagerDialog.java
│   │   │   ├── ProjectDialog.java
│   │   │   └── CollectionDialog.java
│   │   ├── renderer/                       # Swing 渲染器
│   │   │   └── MethodTreeCellRenderer.java
│   │   └── util/
│   │       └── UiUtils.java               # UI 工具方法
│   └── common/
│       └── GlobalExceptionHandler.java
├── src/main/resources/
│   └── application.yml
├── src/test/java/com/jpostman/             # 测试
└── plugins/                                # 插件目录(.jar 和 .java)
```

---

## 十、依赖清单

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>2.7.18</version>
</parent>

<properties>
    <java.version>11</java.version>
    <maven.compiler.release>8</maven.compiler.release>
</properties>

<dependencies>
    <!-- SpringBoot (非 Web,仅容器) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter</artifactId>
    </dependency>

    <!-- JPA -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>

    <!-- SQLite 驱动 -->
    <dependency>
        <groupId>org.xerial</groupId>
        <artifactId>sqlite-jdbc</artifactId>
        <version>3.45.1.0</version>
    </dependency>

    <!-- SQLite Hibernate 方言(SB2.7 + Hibernate 5.6) -->
    <dependency>
        <groupId>com.github.gwenn</groupId>
        <artifactId>sqlite-dialect</artifactId>
        <version>0.1.4</version>
    </dependency>

    <!-- OkHttp -->
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>okhttp</artifactId>
        <version>4.12.0</version>
    </dependency>

    <!-- FlatLaf (Swing 现代外观) -->
    <dependency>
        <groupId>com.formdev</groupId>
        <artifactId>flatlaf</artifactId>
        <version>3.5.1</version>
    </dependency>

    <!-- MigLayout (Swing 布局管理器) -->
    <dependency>
        <groupId>com.miglayout</groupId>
        <artifactId>miglayout-swing</artifactId>
        <version>11.3</version>
    </dependency>

    <!-- 插件热加载 (降级到 SB2) -->
    <dependency>
        <groupId>io.github.lijialong1313</groupId>
        <artifactId>plugin-jar-core</artifactId>
        <version>0.1.0-sb2</version>
    </dependency>
    <dependency>
        <groupId>io.github.lijialong1313</groupId>
        <artifactId>plugin-java-core</artifactId>
        <version>0.1.0-sb2</version>
    </dependency>

    <!-- 测试 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>com.squareup.okhttp3</groupId>
        <artifactId>mockwebserver</artifactId>
        <version>4.12.0</version>
        <scope>test</scope>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <release>8</release>
            </configuration>
        </plugin>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-shade-plugin</artifactId>
            <configuration>
                <transformers>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                        <mainClass>com.jpostman.JPostmanApplication</mainClass>
                    </transformer>
                    <transformer implementation="org.apache.maven.plugins.shade.resource.ServicesResourceTransformer"/>
                </transformers>
            </configuration>
        </plugin>
    </plugins>
</build>
```

---

## 十一、SpringBoot + Swing 启动模式

JPostman 的启动类同时承担 SpringBoot 容器引导和 Swing 窗口创建两个职责:

```java
public class JPostmanApplication {

    public static void main(String[] args) {
        // 1. 设置 FlatLaf 外观(在 EDT 之前设置)
        FlatIntelliJLaf.setup();

        // 2. 启动 SpringBoot 容器(非 Web 模式)
        ConfigurableApplicationContext context =
            new SpringApplicationBuilder(JPostmanConfig.class)
                .web(WebApplicationType.NONE)
                .headless(false)  // Swing 需要非 headless
                .run(args);

        // 3. 在 EDT 中创建并显示主窗口
        SwingUtilities.invokeLater(() -> {
            MainFrame mainFrame = context.getBean(MainFrame.class);
            mainFrame.setVisible(true);
        });

        // 4. 注册关闭钩子
        Runtime.getRuntime().addShutdownHook(new Thread(context::close));
    }
}
```

UI 面板作为 Spring Bean,通过 `@Autowired` 注入 Service:

```java
@Component
public class RequestBuilderPanel extends JPanel {

    @Autowired private HttpEngineService httpEngineService;
    @Autowired private RequestItemService requestItemService;
    @Autowired private VariableResolver variableResolver;

    private JComboBox<String> methodCombo;
    private JTextField urlField;
    private JButton sendButton;

    public RequestBuilderPanel() {
        initLayout();
    }

    private void initLayout() {
        setLayout(new MigLayout("fill, wrap 3", "[][grow][]"));
        methodCombo = new JComboBox<>(new String[]{"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"});
        urlField = new JTextField();
        sendButton = new JButton("Send");
        sendButton.addActionListener(e -> onSend());
        add(methodCombo);
        add(urlField, "growx");
        add(sendButton);
    }

    private void onSend() {
        sendButton.setEnabled(false);
        sendButton.setText("Sending...");

        // SwingWorker 异步发送请求,避免阻塞 EDT
        SwingWorker<ResponseData, Void> worker = new SwingWorker<ResponseData, Void>() {
            @Override
            protected ResponseData doInBackground() {
                RequestConfig config = collectRequestConfig();
                return httpEngineService.execute(config);
            }

            @Override
            protected void done() {
                try {
                    ResponseData response = get();
                    // 通知响应展示区渲染(通过事件总线或直接调用)
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(RequestBuilderPanel.this,
                        "请求失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                } finally {
                    sendButton.setEnabled(true);
                    sendButton.setText("Send");
                }
            }
        };
        worker.execute();
    }
}
```

---

## 十二、Win7 兼容性保障要点

| 组件 | Win7 兼容性 | 注意事项 |
|------|------------|---------|
| JRE 8 | 官方支持 | Oracle JDK 8 最后一个支持 Win7 的 LTS |
| Swing | 完全兼容 | JDK 1.2+ 内置,无 OS 版本依赖 |
| FlatLaf 3.x | 兼容 JRE 8 | 需测试确认,FlatLaf 官方支持 Java 8+ |
| SpringBoot 2.7 | 兼容 JRE 8 | SB 2.7 支持 JDK 8/11/17 |
| SQLite JDBC | 无 OS 限制 | 纯 Java + native lib,Win7 可用 |
| OkHttp 4.x | 无 OS 限制 | 纯 Java |
| plugin-jar-core | 需降级 | URLClassLoader 在 JRE 8 可用 |
| plugin-java-core | 需降级 + 注意 | JavaCompiler 需要 tools.jar(JRE 8)或 jrt-fs.jar(JRE 9+);JRE 8 打包时需包含 tools.jar |

**plugin-java-core 在 JRE 8 上的特殊注意**: `javax.tools.JavaCompiler` 在 JDK 中可用,但在纯 JRE 中不可用。如果目标用户只装了 JRE 8 而非 JDK 8,源码插件编译功能将不可用。两种解法:
1. 打包时将 JDK 8 的 `tools.jar` 包含进 fat JAR
2. 降级到仅支持 JAR 插件,源码插件作为可选功能(检测到 JavaCompiler 不可用时禁用)

---

## 十三、执行节奏建议

| 阶段 | 步骤 | 预估工作量 | 产出 |
|------|------|-----------|------|
| 第一阶段: 基础 | 步骤 1-2 | 较小 | 可启动的 Swing 窗口 + 数据库 |
| 第二阶段: 后端核心 | 步骤 3-6 | 中等 | 完整的 Service 层,可用单元测试验证 |
| 第三阶段: 界面核心 | 步骤 7-10 | 较大 | 可用的桌面界面,能发送请求看响应 |
| 第四阶段: 完善 | 步骤 11-13 | 中等 | 历史记录、环境变量、插件、打包 |

每个步骤完成后,对照验收标准逐项确认,再进入下一步。

---

## 十四、jaravel-vendor 插件降级任务

在步骤 12 之前,需要先完成 jaravel-vendor 的插件模块降级:

1. `git clone https://github.com/weacsoft/jaravel-vendor`
2. 创建 SB2 兼容分支
3. 修改 plugin-jar-core:
   - SpringBoot 3.2 → 2.7
   - `jakarta.*` → `javax.*`
   - 验证 URLClassLoader / ClassLoader 隔离逻辑不变
4. 修改 plugin-java-core:
   - 同上命名空间迁移
   - 验证 `javax.tools.JavaCompiler` 调用逻辑不变
5. 编译发布 `0.1.0-sb2` 版本到 Maven Central
6. 推送分支到 GitHub

降级工作量较小,核心逻辑不需要改动,主要是依赖版本和命名空间适配。
