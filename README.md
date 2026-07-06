<div align="center">

# Jcurl

**A lightweight, cross-platform HTTP API client with CLI, JavaFX GUI, and Swing GUI**

**轻量级跨平台 HTTP API 客户端 — 支持 CLI 命令行、JavaFX 图形界面、Swing 图形界面**

[![Java](https://img.shields.io/badge/Java-17%2B-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-green.svg)](https://spring.io/projects/spring-boot)
[![JavaFX](https://img.shields.io/badge/JavaFX-21-blue.svg)](https://openjfx.io/)
[![License](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

</div>

---

## English | [中文](#中文)

## English

### Overview

Jcurl is a desktop HTTP API client inspired by Postman, with a curl-compatible CLI mode. It is built as a multi-module Maven project:

| Module | Description | Tech Stack |
|--------|-------------|------------|
| `jcurl-core` | Core backend + CLI | SpringBoot 3.2, JDK 17, OkHttp, Jackson |
| `jcurl-javafx` | Modern GUI (Win11+) | JavaFX 21, RichTextFX, Win11 Fluent design |
| `jcurl-swing` | Legacy GUI (Win7+) | Swing, FlatLaf, SpringBoot 2.7, JDK 8 |

### Features

- **curl-compatible CLI** — 30+ curl options (`-X`, `-H`, `-d`, `-F`, `-u`, `-b`, `-o`, `-i`, `-s`, `-v`, `-w`, `-L`, etc.)
- **Dual GUI** — JavaFX (modern) + Swing (legacy Win7-compatible)
- **Syntax highlighting** — JSON / XML / HTML via RichTextFX
- **Cookie management** — Per-collection cookie isolation, auto Set-Cookie parsing
- **Environment variables** — `{{variable}}` with auto-completion, built-in `$timestamp`, `$uuid`, `$randomInt`
- **Plugin system** — Java source plugins compiled at runtime, 4 extension points
- **Performance testing** — Concurrent requests, percentile stats (p50/p90/p99)
- **Collection management** — Drag-and-drop reordering, import/export (Postman v2.1, OpenAPI 3.0, cURL)
- **Auto headers** — 6 default headers with per-header checkbox disable

### Quick Start

```bash
# Build all modules
mvn clean package -DskipTests

# CLI mode (curl-compatible)
java --add-modules jdk.compiler -jar jcurl-core/target/jcurl-core-0.1.0-SNAPSHOT-exec.jar https://httpbin.org/get

# JavaFX GUI
java --add-modules jdk.compiler -jar jcurl-javafx/target/jcurl-javafx-0.1.0-SNAPSHOT.jar

# Swing GUI
java -jar jcurl-swing/target/jcurl-swing-0.1.0-SNAPSHOT.jar
```

Or use the launch scripts:
```bash
run-jcurl-cli.bat    -i https://httpbin.org/get
run-jcurl-javafx.bat
run-jcurl-swing.bat
```

### CLI Examples

```bash
# GET with response headers
java -jar jcurl-core.jar -i https://httpbin.org/get

# POST JSON
java -jar jcurl-core.jar -X POST -d '{"key":"value"}' -H "Content-Type: application/json" https://httpbin.org/post

# Basic auth
java -jar jcurl-core.jar -u user:pass https://httpbin.org/basic-auth/user/pass

# Custom output format
java -jar jcurl-core.jar -s -o response.json -w "%{http_code} %{time_total}s\n" https://httpbin.org/get

# Multipart form upload
java -jar jcurl-core.jar -F "file=@photo.jpg" -F "name=hello" https://httpbin.org/post
```

### Plugin Development

Create a `.java` file implementing any of the 4 extension points:

```java
@JcurlPlugin(name = "My Plugin", version = "1.0.0")
public class MyPlugin implements RequestInterceptor, ResponseInterceptor,
        VariableFunctionExtension, MetricsCollectorExtension {

    @Override
    public RequestConfig beforeRequest(RequestConfig config, PluginContext ctx) {
        ctx.log("info", "Sending: " + config.getUrl());
        return config;
    }
    // ... implement other extension points
}
```

Install via the Plugin Manager dialog or copy to `~/.api-client/plugins/`.

See [DemoPlugin.java](plugins/DemoPlugin.java) for a complete example.

### Project Structure

```
jcurl/
├── pom.xml                  # Parent POM (aggregates 3 modules)
├── jcurl-core/              # Core + CLI
│   └── src/main/java/com/jcurl2/
│       ├── cli/             # CurlArgParser, CliLauncher
│       ├── config/          # AppConfig
│       ├── model/           # DTOs, components
│       ├── plugin/          # Plugin system, 4 extension points
│       ├── service/         # HttpEngineService, CookieService, etc.
│       └── store/           # JSON file storage
├── jcurl-javafx/            # JavaFX GUI
│   └── src/main/java/com/jcurl2/ui/
├── jcurl-swing/             # Swing GUI
│   └── src/main/java/com/jcurl/
├── plugins/                 # Demo plugin
└── run-jcurl-*.bat          # Launch scripts
```

### Requirements

- **CLI + JavaFX**: JDK 17+ (set `JAVA_HOME`)
- **Swing**: JDK 8+ (Win7 compatible)
- **Build**: Maven 3.6+

### License

MIT

---

## 中文

### 项目简介

Jcurl 是一个受 Postman 启发的桌面 HTTP API 客户端，同时提供 curl 兼容的命令行模式。采用多模块 Maven 架构：

| 模块 | 说明 | 技术栈 |
|------|------|--------|
| `jcurl-core` | 核心后端 + CLI 命令行 | SpringBoot 3.2, JDK 17, OkHttp, Jackson |
| `jcurl-javafx` | 新版图形界面 (Win11+) | JavaFX 21, RichTextFX, Win11 Fluent 风格 |
| `jcurl-swing` | 旧版图形界面 (Win7+) | Swing, FlatLaf, SpringBoot 2.7, JDK 8 |

### 功能特性

- **curl 兼容 CLI** — 支持 30+ curl 选项（`-X`、`-H`、`-d`、`-F`、`-u`、`-b`、`-o`、`-i`、`-s`、`-v`、`-w`、`-L` 等）
- **双图形界面** — JavaFX（现代版）+ Swing（旧版 Win7 兼容）
- **语法高亮** — RichTextFX 实现 JSON / XML / HTML 语法高亮
- **Cookie 管理** — 按集合隔离，自动解析 Set-Cookie 并在后续请求中携带
- **环境变量** — `{{variable}}` 自动补全，内置 `$timestamp`、`$uuid`、`$randomInt` 动态函数
- **插件系统** — 运行时编译 Java 源码插件，支持 4 大扩展点
- **性能测试** — 并发请求，百分位统计（p50/p90/p99）
- **集合管理** — 拖拽排序，支持导入/导出（Postman v2.1、OpenAPI 3.0、cURL）
- **自动请求头** — 6 个默认头自动合并，每个头可单独 CheckBox 取消

### 快速开始

```bash
# 编译所有模块
mvn clean package -DskipTests

# CLI 模式 (curl 兼容)
java --add-modules jdk.compiler -jar jcurl-core/target/jcurl-core-0.1.0-SNAPSHOT-exec.jar https://httpbin.org/get

# JavaFX 图形界面
java --add-modules jdk.compiler -jar jcurl-javafx/target/jcurl-javafx-0.1.0-SNAPSHOT.jar

# Swing 图形界面
java -jar jcurl-swing/target/jcurl-swing-0.1.0-SNAPSHOT.jar
```

或使用启动脚本：
```bash
run-jcurl-cli.bat    -i https://httpbin.org/get
run-jcurl-javafx.bat
run-jcurl-swing.bat
```

### CLI 示例

```bash
# GET 请求并显示响应头
java -jar jcurl-core.jar -i https://httpbin.org/get

# POST JSON
java -jar jcurl-core.jar -X POST -d '{"key":"value"}' -H "Content-Type: application/json" https://httpbin.org/post

# Basic 认证
java -jar jcurl-core.jar -u user:pass https://httpbin.org/basic-auth/user/pass

# 自定义输出格式
java -jar jcurl-core.jar -s -o response.json -w "%{http_code} %{time_total}s\n" https://httpbin.org/get

# Multipart 表单上传
java -jar jcurl-core.jar -F "file=@photo.jpg" -F "name=hello" https://httpbin.org/post
```

### 插件开发

创建一个 `.java` 文件，实现 4 大扩展点中的任意一个：

```java
@JcurlPlugin(name = "我的插件", version = "1.0.0")
public class MyPlugin implements RequestInterceptor, ResponseInterceptor,
        VariableFunctionExtension, MetricsCollectorExtension {

    @Override
    public RequestConfig beforeRequest(RequestConfig config, PluginContext ctx) {
        ctx.log("info", "发送请求: " + config.getUrl());
        return config;
    }
    // ... 实现其他扩展点
}
```

通过插件管理对话框安装，或复制到 `~/.api-client/plugins/` 目录。

完整示例见 [DemoPlugin.java](plugins/DemoPlugin.java)。

### 环境要求

- **CLI + JavaFX**：JDK 17+（需设置 `JAVA_HOME`）
- **Swing**：JDK 8+（兼容 Win7）
- **编译**：Maven 3.6+

### 开源协议

MIT
