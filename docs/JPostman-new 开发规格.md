# JPostman-new 开发规格

本文档供实现 JPostman-new (JavaFX 界面版) 的开发者使用。JPostman 当前版本基于 Swing,兼容 Windows 7;JPostman-new 将采用 JavaFX + JDK 17,提供更现代的交互体验 (多选项卡、拖拽排序、CSS 主题等),核心逻辑完全复用现有 JPostman-core,不重复造轮子。

## 1. 项目定位

| 维度 | JPostman (当前版) | JPostman-new |
|------|-------------------|--------------|
| 界面技术 | Swing + FlatLaf | JavaFX 17+ |
| JDK | JRE 8 / 11 / 17 均可 | JDK 17+ |
| 目标系统 | Windows 7 / Server 2012+ | Windows 10/11 / Linux / macOS |
| 核心逻辑 | 共享 jpostman-core 模块 | 共享 jpostman-core 模块 |
| 选项卡 | 不支持 (单请求模式) | 支持 (TabPane, 可关闭、可拖拽) |
| 拖拽排序 | 不支持 | 支持 (集合树、选项卡) |
| 主题 | FlatLaf 内置 | CSS 自定义 |

## 2. 架构方案: Maven 多模块

```
jpostman/                          (Git 仓库根目录)
├── pom.xml                        (父 POM, 聚合 + 公共依赖管理)
├── jpostman-core/                 (核心模块, 零 UI 依赖)
│   ├── pom.xml
│   └── src/main/java/com/jpostman/
│       ├── service/               HttpEngineService, CookieService, PluginManager,
│       │                          DefaultHeaderProvider, EnvironmentService,
│       │                          VariableResolver, HistoryService, CollectionService
│       ├── model/                 RequestConfig, ResponseData, AuthConfig, KeyValue,
│       │                          RequestNode, CollectionFile, Variable, HistoryRecord...
│       ├── model/dto/             RequestConfig, ResponseData
│       ├── plugin/                JPostmanPlugin, RequestInterceptor, ResponseProcessor,
│       │                          VariableFunction, PluginInfo, PluginManager
│       ├── config/                JPostmanConfig
│       └── service/store/         JSON 文件持久化 (HistoryStore, GlobalVariableStore,
│                                  CollectionStore, SettingsStore)
├── jpostman-ui-swing/             (Swing 界面, 即当前版本)
│   ├── pom.xml                    依赖 jpostman-core
│   └── src/main/java/com/jpostman/ui/
├── jpostman-ui-javafx/            (JavaFX 界面, 即 JPostman-new)
│   ├── pom.xml                    依赖 jpostman-core
│   └── src/main/java/com/jpostman/ui/javafx/
└── jpostman-app/                  (启动器)
    ├── pom.xml
    └── src/main/java/com/jpostman/
        ├── JPostmanApplicationSwing.java   (Win7 启动入口)
        └── JPostmanApplicationJavaFX.java  (Win11 启动入口)
```

### 2.1 模块依赖关系

```
jpostman-ui-swing  ──→  jpostman-core  ←──  jpostman-ui-javafx
jpostman-app       ──→  jpostman-ui-swing   (Win7 打包)
jpostman-app       ──→  jpostman-ui-javafx  (Win11 打包)
```

### 2.2 核心层约束

jpostman-core 的 `service`、`model`、`config`、`plugin` 包零 `javax.swing`/`java.awt`/`javafx` 依赖。当前代码已满足此约束 (已验证零 UI 导入)。JavaFX 版只需调用 core 层的 Service Bean,不需要修改任何核心逻辑。

### 2.3 Spring 上下文共享

两个 UI 版共享同一个 SpringBoot 2.7 上下文。core 层的 Service 全部用 `@Service`/`@Component` 注册,启动时通过 `@ComponentScan("com.jpostman")` 扫描。UI 层的组件也注册为 Spring Bean,通过构造注入获取 Service。

JPostman-new 启动时:

```java
@SpringBootApplication
public class JPostmanApplicationJavaFX extends Application {

    private ConfigurableApplicationContext springContext;

    @Override
    public void init() throws Exception {
        springContext = new SpringApplicationBuilder(JPostmanApplicationJavaFX.class)
                .headless(false)
                .run();
    }

    @Override
    public void start(Stage primaryStage) {
        MainJavaFxView mainView = springContext.getBean(MainJavaFxView.class);
        mainView.show(primaryStage);
    }

    @Override
    public void stop() {
        springContext.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
```

## 3. 核心层服务接口规范

以下为 jpostman-core 中所有 Service 的公开方法签名。JavaFX UI 层通过 Spring 注入这些 Service,直接调用。

### 3.1 HttpEngineService — HTTP 请求执行

```java
@Service
public class HttpEngineService {
    // 执行请求, 返回响应数据 (含状态码/响应头/响应体/性能指标)
    ResponseData execute(RequestConfig config);
    ResponseData execute(RequestConfig config, AuthConfig auth);
    ResponseData execute(RequestConfig config, AuthConfig auth, String collectionId);
}
```

`execute` 内部行为:
- 合并默认头 (DefaultHeaderProvider): 5 个静态头 (Accept, User-Agent, Accept-Encoding, Accept-Language, Cache-Control) + body 推导的 Content-Type; 用户同名头覆盖默认头
- 认证头处理: Basic/Bearer/APIKey, 用户手动设置同名头时不覆盖
- Cookie 自动管理: 从 CookieService 取当前集合的 Cookie 附带; 响应 Set-Cookie 自动存储
- 重定向控制: `config.isFollowRedirects()` (默认 true)
- GET/HEAD 带 body: 通过反射绕过 OkHttp 限制
- gzip/deflate 响应自动解压
- 插件拦截器链: RequestInterceptor → 发送 → ResponseProcessor
- 性能采集: DNS/TCP/TTFB/总时间, 存入 ResponseData
- 记录历史: 调用 HistoryService.record()

### 3.2 DefaultHeaderProvider — 默认头计算与合并

```java
@Component
public class DefaultHeaderProvider {
    // 5 个静态默认头
    List<KeyValue> getStaticDefaults();
    // 根据 body 类型推导 Content-Type
    String getBodyContentType(String bodyType, String rawContentType);
    // 计算发送用的自动头 (静态默认 + body Content-Type, 不含认证头)
    List<KeyValue> computeAutoHeaders(String bodyType, String rawContentType);
    // 计算认证派生头 (Basic/Bearer/APIKey in=header)
    KeyValue getAuthHeader(AuthConfig auth);
    // 计算界面展示用的自动头 (发送自动头 + 认证派生头)
    List<KeyValue> computeDisplayAutoHeaders(String bodyType, String rawContentType, AuthConfig auth);
    // 合并默认头与用户头 (用户同名头覆盖默认头, 键名忽略大小写)
    List<KeyValue> mergeEffective(List<KeyValue> autoHeaders, List<KeyValue> userHeaders);
}
```

### 3.3 CookieService — 按集合隔离的 Cookie 管理

```java
@Service
public class CookieService {
    // 切换当前集合上下文 (从磁盘加载该集合的 Cookie)
    void setCurrentCollection(String collectionId);
    String getCurrentCollectionId();

    // 自动 Cookie 管理 (引擎层调用)
    void storeFromResponse(String url, Map<String, String> responseHeaders);
    String getCookiesForUrl(String url);

    // CRUD (供 UI 树形表格管理)
    Map<String, Map<String, CookieEntry>> getAllCookies();       // domain -> (name -> cookie)
    List<CookieEntry> getAllCookiesFlat();                        // 扁平列表
    void addCookie(CookieEntry cookie);
    void updateCookie(String oldDomain, String oldName, CookieEntry newEntry);
    void deleteCookie(String domain, String name);
    void deleteDomain(String domain);
    void clearAll();
}
```

CookieEntry 内部类:
```java
public static class CookieEntry {
    String name;
    String value;
    String domain;
    String path;       // 默认 "/"
    long expiry;       // 0 = session cookie; >0 = 过期时间戳 (毫秒)
    boolean secure;
    boolean httpOnly;
}
```

Cookie 持久化路径: `~/.api-client/cookies/{collectionId}.json`

### 3.4 EnvironmentService — 环境变量与全局变量

```java
@Service
public class EnvironmentService {
    // 获取所有环境
    List<Environment> getAllEnvironments();
    Environment getEnvironment(String id);
    Environment getActiveEnvironment();
    void setActiveEnvironment(String envId);
    void createEnvironment(String name);
    void updateEnvironment(Environment env);
    void deleteEnvironment(String envId);

    // 变量解析用的合并视图
    // 优先级: 本地 > 环境 > 集合 > 全局
    Map<String, String> getEffectiveVariables();
    Map<String, String> getEffectiveVariables(List<Variable> collectionVariables);

    // 全局变量
    List<Variable> getGlobalVariables();
    void setGlobalVariables(List<Variable> variables);

    // 集合变量 (存储在 CollectionFile 中, 由 CollectionService 管理)
    // EnvironmentService 负责合并
}
```

Variable 模型:
```java
public class Variable {
    String key;
    String value;
    String description;
    boolean secret;    // 敏感变量: 界面掩码显示 ******
}
```

### 3.5 VariableResolver — 变量解析

```java
@Service
public class VariableResolver {
    // 解析 {{key}} 占位符, 支持 4 级作用域
    String resolve(String raw);
    String resolve(String raw, List<Variable> collectionVariables, Map<String, String> localVariables);

    // 批量解析 KeyValue 列表
    List<KeyValue> resolveKeyValues(List<KeyValue> kvs);
    List<KeyValue> resolveKeyValues(List<KeyValue> kvs, List<Variable> collectionVariables, Map<String, String> localVariables);

    // 解析 Map
    Map<String, String> resolveMap(Map<String, String> input);

    // 获取可用变量名 (用于自动补全)
    List<String> getAvailableVariableNames();
    List<String> getAvailableVariableNames(List<Variable> collectionVariables);
}
```

动态变量函数 ({{$funcName}} 语法):
- `{{$timestamp}}` — Unix 时间戳 (秒)
- `{{$uuid}}` — 随机 UUID
- `{{$randomInt}}` — 0-10000 随机整数
- `{{$datetime}}` — ISO 日期时间

### 3.6 CollectionService — 集合管理

```java
@Service
public class CollectionService {
    List<CollectionFile> getAllCollections();
    CollectionFile getCollection(String collectionId);
    CollectionFile createCollection(String name, String description);
    void saveCollection(CollectionFile collection);
    void deleteCollection(String collectionId);
    void renameCollection(String collectionId, String newName);

    // 文件夹/请求操作
    void createFolder(String collectionId, String parentId, String name);
    void createRequest(String collectionId, String parentId, String name);
    void renameItem(String collectionId, String itemId, String newName);
    void deleteItem(String collectionId, String itemId);
    void duplicateItem(String collectionId, String itemId);

    // 请求节点读写
    RequestNode getRequest(String collectionId, String requestId);
    void saveRequest(String collectionId, RequestNode request);
}
```

CollectionFile 模型:
```java
public class CollectionFile {
    String id;
    String name;
    String description;
    String baseUrl;                    // 集合级 Base URL
    List<KeyValue> headers;            // 集合级默认头
    List<Variable> variables;          // 集合变量
    AuthConfig auth;                   // 集合级认证
    List<CollectionItem> items;        // 递归嵌套 (文件夹/请求)
}
```

CollectionItem 是接口,实现类:
- `FolderNode`: name, description, List<CollectionItem> items
- `RequestNode`: name, method, url, description, params, headers, bodyType, bodyContent, rawContentType, auth

### 3.7 HistoryService — 历史记录

```java
@Service
public class HistoryService {
    HistoryRecord record(String requestName, RequestConfig config, ResponseData response);
    HistoryRecord record(RequestConfig config, ResponseData response);
    List<HistoryRecord> getAllHistory();
    List<HistoryRecord> search(String keyword, String method);
    void deleteHistory(String id);
    void clearAllHistory();
}
```

HistoryRecord 模型:
```java
public class HistoryRecord {
    String id;
    String requestName;
    String method;
    String url;
    List<KeyValue> requestHeaders;
    String requestBody;
    String bodyType;
    int statusCode;
    String statusText;
    List<KeyValue> responseHeaders;
    String responseBody;
    long responseTime;     // 毫秒
    long responseSize;     // 字节
    long timestamp;        // 记录时间戳
}
```

### 3.8 PluginManager — 插件管理

```java
@Service
public class PluginManager {
    void loadPlugin(File jarFile) throws Exception;
    void unloadPlugin(String pluginId);
    List<PluginInfo> getLoadedPlugins();
    List<RequestInterceptor> getRequestInterceptors();
    List<ResponseProcessor> getResponseProcessors();
    List<VariableFunction> getVariableFunctions();
}
```

PluginInfo:
```java
public class PluginInfo {
    String name;
    String version;
    String description;
    String filePath;      // JAR 文件路径
    String status;        // "loaded" / "error"
    String pluginId;      // "name-version"
}
```

### 3.9 数据存储层

所有数据存储在 `~/.api-client/` 目录:

```
~/.api-client/
├── collections/
│   ├── {collectionId}.json       (集合文件, 含请求树)
│   └── ...
├── environments.json             (环境列表 + 激活环境)
├── global-variables.json         (全局变量)
├── history.json                  (历史记录, 默认保留 500 条)
├── settings.json                 (应用设置: 主题、历史数量限制等)
├── cookies/
│   ├── {collectionId}.json       (按集合隔离的 Cookie)
│   └── __global__.json           (无集合上下文时的全局 Cookie)
└── plugins/
    └── loaded/                   (已加载的插件 JAR)
```

## 4. 数据模型定义

### 4.1 RequestConfig (请求配置 DTO)

```java
public class RequestConfig {
    String method = "GET";              // GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS
    String url;
    List<KeyValue> params;              // URL 查询参数
    List<KeyValue> headers;             // 用户自定义头 (不含默认头)
    String bodyType = "none";           // none/form-data/urlencoded/raw/binary
    String bodyContent;                 // body 内容 (raw=文本, form-data=JSON, binary=文件路径)
    String rawContentType;              // raw 模式下的 Content-Type
    AuthConfig auth = new AuthConfig();
    boolean followRedirects = true;     // 自动重定向 (默认 true)
}
```

### 4.2 ResponseData (响应数据 DTO)

```java
public class ResponseData {
    boolean success;
    int statusCode;
    String statusText;
    Map<String, String> responseHeaders;
    String responseBody;
    byte[] responseBytes;               // 原始字节 (用于图片预览)
    long responseSize;
    long responseTime;                  // 毫秒
    String errorMessage;                // 网络错误时非 null
    String requestUrl;                  // 请求 URL (供 HTML <base href> 注入)
    // 性能指标
    long dnsTime;                       // DNS 解析时间
    long connectTime;                   // TCP 连接时间
    long ttfbTime;                      // 首字节时间
    long totalTime;                     // 总时间
}
```

### 4.3 AuthConfig

```java
public class AuthConfig {
    String type = "none";               // none/basic/bearer/apikey
    // Basic
    String basicUsername;
    String basicPassword;
    // Bearer
    String bearerToken;
    // API Key
    String apiKeyName;
    String apiKeyValue;
    String apiKeyIn;                    // "header" / "query"

    boolean isEmpty();                  // type 为 none 或关键字段为空时返回 true
}
```

### 4.4 KeyValue

```java
public class KeyValue {
    String key;
    String value;
    String description;
    boolean enabled = true;
}
```

## 5. 功能详情

### 5.1 请求构建

| 功能 | 行为 |
|------|------|
| 方法选择 | GET/POST/PUT/DELETE/PATCH/HEAD/OPTIONS |
| URL 输入 | 支持 `{{variable}}` 变量占位符, 输入时自动补全可用变量 |
| Params 面板 | 键值表格 (启用/键/值/描述), 修改后实时同步到 URL query string; 支持文本模式切换 |
| Headers 面板 | 键值表格, 用户只输入自定义头; "显示默认头"按钮展示自动头 (只读, 灰色); 用户同名头覆盖默认头 |
| Body 面板 | 5 种类型: none / form-data / urlencoded / raw / binary; 类型切换时刷新默认 Content-Type |
| form-data | 表格 (启用/键/值/类型/描述), 类型=文本/文件, 文件类型弹出文件选择器; 空表单允许提交 |
| urlencoded | 键值表格 |
| raw | 文本编辑器 + Content-Type 下拉 (Raw/JSON/XML/HTML/Text/JavaScript); "格式化"按钮格式化 JSON/XML |
| binary | 文件路径输入 + 浏览按钮 |
| Auth 面板 | 4 种: none/basic/bearer/apikey; apikey 的 in 可选 header/query; 输入内容时实时刷新默认头展示 |
| 自动重定向 | 复选框 (默认勾选), 取消后返回 301/302 原始响应 |
| GET 带 body | 允许 GET/HEAD 携带 body (通过反射绕过 OkHttp 限制) |
| 发送按钮 | 点击后在后台线程执行, 按钮禁用 + 进度提示; 发送完成后展示响应 |

### 5.2 默认头管理

默认头由 `DefaultHeaderProvider` (核心层) 计算, UI 层只负责展示:

- 5 个静态默认头 (始终自动发送):
  - `Accept: */*`
  - `User-Agent: JPostman/0.1`
  - `Accept-Encoding: gzip, deflate`
  - `Accept-Language: zh-CN,zh;q=0.9`
  - `Cache-Control: no-cache`
- body 推导的 Content-Type (根据 body 类型自动设置):
  - urlencoded → `application/x-www-form-urlencoded`
  - form-data → `multipart/form-data` (含 boundary, 由 OkHttp 自动生成)
  - raw → 用户选择的 Content-Type
  - binary → `application/octet-stream`
- 认证派生头 (界面展示用, 实际由引擎层在发送时添加):
  - Basic → `Authorization: Basic <base64(user:pass)>`
  - Bearer → `Authorization: Bearer <token>`
  - APIKey in=header → `<keyName>: <keyValue>`

合并规则: 用户同名头覆盖默认头 (键名忽略大小写); 用户禁用同名头等价于不发送该头。合并发生在引擎层 `HttpEngineService.buildRequest()`, UI 层的 headers 数据只包含用户输入。

### 5.3 集合管理

树形结构: 集合 → 文件夹 (递归嵌套) → 请求

| 操作 | 说明 |
|------|------|
| 新建集合 | 输入名称, 创建在 collections/ 目录 |
| 新建文件夹 | 在集合或父文件夹下创建 |
| 新建请求 | 在集合或文件夹下创建, 默认 GET |
| 重命名 | 集合/文件夹/请求均可 |
| 删除 | 集合删除会级联删除所有子项; 确认对话框 |
| 复制 | 深拷贝, 重新生成 ID, 名称追加 "(副本)" |
| 拖拽排序 | JPostman-new 应支持拖拽调整节点顺序和层级 |
| 集合配置 | 集合级 Base URL、默认头、变量、认证 (在集合属性面板编辑) |

选中请求时加载到请求构建区; 选中集合时切换 Cookie 上下文 (`cookieService.setCurrentCollection(collectionId)`)。

### 5.4 环境变量

| 功能 | 说明 |
|------|------|
| 环境列表 | 多个环境 (如 dev/staging/prod), 每个含一组变量 |
| 激活环境 | 同时只有一个激活环境; 切换环境后变量立即生效 |
| 变量编辑 | 键/值/描述/敏感(掩码); 敏感变量值在界面显示为 ******, 可切换显示 |
| 右键菜单 | 复制键/值/键值对, 插入行, 删除行 |
| 变量作用域 | 本地 > 环境 > 集合 > 全局 (高优先级覆盖低优先级) |
| 变量引用 | URL/Headers/Body 中使用 `{{varName}}` 语法 |
| 动态函数 | `{{$timestamp}}` / `{{$uuid}}` / `{{$randomInt}}` / `{{$datetime}}` |

### 5.5 全局变量

全局变量在所有环境中生效, 优先级最低。界面与操作同环境变量 (键/值/描述/敏感 + 右键菜单)。

### 5.6 历史记录

| 功能 | 说明 |
|------|------|
| 记录 | 每次请求自动记录 (请求+响应), 默认保留 500 条 |
| 列表显示 | `[METHOD] url - statusCode statusText - responseTime ms` |
| 搜索 | 按 URL 关键字模糊搜索 |
| 重新发送 | 选中历史记录可加载到请求构建区 |
| 删除 | 单条删除 / 清空全部 |

### 5.7 Cookie 管理

| 功能 | 说明 |
|------|------|
| 自动管理 | 响应 Set-Cookie 自动存储; 请求时按域名/路径匹配自动附带 |
| 按集合隔离 | Cookie 跟随项目 (集合) 走, 切换集合切换 Cookie 上下文 |
| 持久化 | 存储到 `cookies/{collectionId}.json` |
| 树形管理 | 左侧域名树 (集合→域名→Cookie), 右侧明细表格 |
| 增删改查 | 新增 Cookie、编辑后保存、删除单条/整域/全部 |

### 5.8 响应展示

| 功能 | 说明 |
|------|------|
| 状态行 | `200 OK · 234ms · 12.3KB` + 性能指标 (DNS/TCP/TTFB) |
| 响应头 | 键值表格, 支持文本模式切换, 右键复制 |
| 响应体 | 自动检测格式: JSON/XML/HTML/Image/Text |
| JSON | 语法高亮 + 折叠 + 格式化 |
| XML | 语法高亮 + 格式化 |
| HTML | 预览 (注入 `<base href>`) + 源码切换 + "在浏览器中打开" |
| Image | 图片预览 (从响应字节渲染) |
| 复制 | 一键复制响应体到剪贴板 |
| 选项卡 | JPostman-new 应支持多请求选项卡, 每个选项卡独立展示请求+响应 |

### 5.9 插件系统

| 功能 | 说明 |
|------|------|
| 插件接口 | `JPostmanPlugin` (基础) + `RequestInterceptor` / `ResponseProcessor` / `VariableFunction` (可选扩展) |
| 加载方式 | SPI (ServiceLoader), 每个 JAR 独立 ClassLoader |
| 安装 | 文件选择器选 .jar, 后台线程加载 (SwingWorker/Task), 进度提示 |
| 卸载 | 选中插件确认后卸载, 后台线程执行 |
| 管理界面 | 表格: 名称/版本/状态/描述/ID/文件路径 + 右键卸载 |

### 5.10 性能测试

| 功能 | 说明 |
|------|------|
| 配置 | 并发数、请求次数、间隔 |
| 执行 | 后台线程池并发执行, 实时显示进度 |
| 结果 | 总请求数、成功/失败数、平均/最小/最大响应时间、QPS |
| 导出 | JPostman-new 可增加 HTML/CSV 报告导出 |

### 5.11 主题与设置

| 功能 | 说明 |
|------|------|
| 主题 | Swing 版用 FlatLaf (浅色/深色); JavaFX 版用 CSS 自定义 |
| 设置 | 历史记录数量限制、默认超时、代理配置 |
| 快捷键 | Ctrl+Enter 发送, Ctrl+S 保存请求, Ctrl+T 新建选项卡 (JavaFX 版) |

## 6. UI 层需要实现的内容

JPostman-new (JavaFX) 需要实现的 UI 组件, 对应 Swing 版的每个 Panel/Dialog:

### 6.1 主界面布局

```
┌─────────────────────────────────────────────────┐
│ MenuBar: 文件 / 编辑 / 视图 / 插件 / 帮助           │
├──────────┬──────────────────────────────────────┤
│          │ TabPane (多请求选项卡)                  │
│ Sidebar  │ ┌──────────────────────────────────┐ │
│          │ │ Request Panel                     │ │
│ - 集合树   │ │   (URL + Method + Send)           │ │
│ - 历史列表 │ │   (TabPane: 参数/请求头/请求体/认证)│ │
│          │ ├──────────────────────────────────┤ │
│          │ │ Response Panel                    │ │
│          │ │   (状态行 + TabPane: 响应体/响应头)│ │
│          │ └──────────────────────────────────┘ │
├──────────┴──────────────────────────────────────┤
│ StatusBar: 当前集合 · Cookie数量 · 环境变量         │
└─────────────────────────────────────────────────┘
```

### 6.2 组件清单

| JavaFX 组件 | 对应 Swing 组件 | 说明 |
|-------------|----------------|------|
| MainJavaFxView | MainFrame | 主窗口, BorderPane 布局 |
| SidebarView | SidebarPanel | 左侧边栏, TabPane (集合树 + 历史列表) |
| CollectionTreeView | SidebarPanel 集合树 | TreeView, 支持拖拽排序 |
| HistoryListView | SidebarPanel 历史列表 | ListView, 搜索框 |
| RequestView | RequestPanel | 请求构建区 |
| ResponseView | ResponsePanel | 响应展示区 |
| KeyValueTableView | KeyValueTablePanel | 键值表格 (TableView) |
| FormDataView | FormDataPanel | form-data 表格 |
| EnvironmentDialog | EnvironmentDialog | 环境变量对话框 |
| GlobalVariableDialog | GlobalVariableDialog | 全局变量对话框 |
| CookieManagerDialog | CookieManagerDialog | Cookie 管理对话框 |
| PluginManagerDialog | PluginManagerDialog | 插件管理对话框 |
| LoadTestDialog | LoadTestDialog | 性能测试对话框 |

### 6.3 JavaFX 版增强功能 (相对 Swing 版)

| 功能 | 实现方式 |
|------|---------|
| 多请求选项卡 | `TabPane` + 可关闭 Tab + Ctrl+T 新建 |
| 拖拽排序 | 集合树 `TreeView` 支持拖拽调整层级和顺序 |
| CSS 主题 | `.root` 类切换 light/dark CSS 文件 |
| 变量自动补全 | `Popup` + `ListView`, 输入 `{{` 时弹出可用变量列表 |
| 响应体语法高亮 | `RichTextFX` 库或 `WebView` + highlight.js |
| JSON 树形浏览 | `TreeView` 渲染 JSON 节点, 可折叠 |
| HTML 预览 | `WebView` (JavaFX 内置, 比 Swing JEditorPane 强得多) |

## 7. 打包方式

### 7.1 Win7 版 (Swing)

```bash
mvn -pl jpostman-app -am package -Pswing
# 产物: jpostman-app/target/jpostman-swing-{version}.jar
# 运行: java -jar jpostman-swing-{version}.jar (JRE 8+)
```

### 7.2 Win11 版 (JavaFX)

```bash
mvn -pl jpostman-app -am package -Pjavafx
# 产物: jpostman-app/target/jpostman-javafx-{version}.jar
# 运行: java -jar jpostman-javafx-{version}.jar (JDK 17+)
```

### 7.3 Profile 配置 (父 POM)

```xml
<profiles>
    <profile>
        <id>swing</id>
        <dependencies>
            <dependency>
                <groupId>com.jpostman</groupId>
                <artifactId>jpostman-ui-swing</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </profile>
    <profile>
        <id>javafx</id>
        <dependencies>
            <dependency>
                <groupId>com.jpostman</groupId>
                <artifactId>jpostman-ui-javafx</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </profile>
</profiles>
```

## 8. 迁移步骤

从当前单体项目迁移到多模块的推荐步骤:

1. 创建父 POM, 设置 `<packaging>pom</packaging>` 和 `<modules>`
2. 创建 `jpostman-core` 模块, 将 `service/`、`model/`、`config/`、`plugin/` 包移入
3. 创建 `jpostman-ui-swing` 模块, 将 `ui/` 包移入, 依赖 `jpostman-core`
4. 验证 Swing 版功能不回归 (运行现有测试 + 手动启动)
5. 创建 `jpostman-ui-javafx` 模块, 由另一个 AI 实现 JavaFX 界面
6. 创建 `jpostman-app` 启动器, 用 Profile 区分两个版本
7. 配置 CI 分别打包两个版本

当前代码已满足核心层零 UI 依赖的约束, 迁移时只需调整目录结构和 POM, 无需修改核心逻辑代码。
