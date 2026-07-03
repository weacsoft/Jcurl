# JPostman 领域术语表

> 本文件是 JPostman 项目的领域语言 glossary。
> 只记录术语定义,不包含实现细节。术语在对话中 resolved 时立即更新。

---

## 组织结构

**Project(项目)** — 顶层组织单元。一个项目代表一组相关的 API 调试工作,包含多个 Collection。

**Collection(集合)** — 项目下的请求分组。用于将相关请求组织在一起,包含多个 RequestItem。

**RequestItem(请求定义)** — 被保存的请求配置,归属于某个 Collection。包含 HTTP 方法、URL、参数、请求体、认证方式等。它是"模板",可以被加载、修改、执行。

## 请求执行

**RequestConfig(请求配置)** — 一次请求的完整配置数据。可以是某个 RequestItem 的快照,也可以是用户临时构建未保存的。它是 HttpEngine 的输入。

**ResponseData(响应数据)** — 一次请求执行返回的结果。包含状态码、状态文本、响应头、响应体、耗时、响应大小。它是 HttpEngine 的输出。

**History(历史记录)** — 一次实际执行的请求及其响应的完整记录。每次执行请求后自动生成。与 RequestItem 的区别:RequestItem 是保存的模板,History 是一次实际发生的执行。

**HttpEngine(HTTP 引擎)** — 接收 RequestConfig,发送 HTTP 请求,返回 ResponseData 的服务。是 JPostman 的核心深度模块。

## 环境与变量

**Environment(环境)** — 一套命名的变量集合(如"开发环境"、"测试环境")。同一时刻只有一个环境处于激活状态。

**GlobalVariable(全局变量)** — 跨所有环境共享的变量。变量查找时,环境变量优先于全局变量。

**VariableResolver(变量解析器)** — 将字符串中的 `{{key}}` 占位符替换为实际值的服务。查找顺序:当前 Environment 的变量 → GlobalVariable。未命中的占位符保留原样并收集警告。

**Active Environment(激活环境)** — 当前被选中用于变量替换的 Environment。用户通过界面下拉切换。

## 插件系统

**Hook(钩子)** — 在请求执行过程中插入的扩展点。插件通过实现 Hook 接口来扩展 JPostman 的行为。

**PreRequestHook(前置钩子)** — 在请求发送前执行的钩子。接收 RequestContext,可以修改请求配置(如注入签名、加密参数、动态 Token)。

**PostResponseHook(后置钩子)** — 在收到响应后执行的钩子。接收 ResponseContext,可以读取和处理响应(如数据提取、断言校验、响应转换)。

**RequestContext(请求上下文)** — 传递给 PreRequestHook 的上下文对象。包含当前请求的方法、URL、Headers、Body,钩子可以读取和修改这些字段。

**ResponseContext(响应上下文)** — 传递给 PostResponseHook 的上下文对象。包含响应的状态码、Headers、Body,钩子可以读取但不应修改。

**Plugin(插件)** — 实现一个或多个 Hook 接口的独立 JAR 包。通过热加载机制在运行时加载和卸载,无需重启 JPostman。用 `@JPostmanPlugin` 注解声明。

## 设计术语

**Seam(接缝)** — 可以不修改原地代码就改变行为的位置。在 JPostman 中,Hook 接口就是接缝 —— 插件在接缝处插入行为,而不修改 HttpEngine 的代码。

**Deep Module(深度模块)** — 小接口藏大实现的模块。HttpEngine 和 VariableResolver 都应是深度模块。

**Tracer Bullet(示踪弹)** — 第一个贯穿所有层的垂直切片实现,用于证明端到端通路打通。

**Vertical Slice(垂直切片)** — 贯穿数据库、Service 层、Swing 界面、测试所有层的功能切片。每个开发步骤应以垂直切片为单位推进。

## 界面层

**Swing Panel(界面面板)** — Swing 的 JPanel 子类,标注 @Component 成为 Spring Bean,通过 @Autowired 注入 Service。负责收集用户输入、调用 Service、将结果渲染到界面组件。不包含业务逻辑。

**FlatLaf** — Swing 的现代扁平外观主题库。JPostman 启动时设置 FlatIntelliJLaf(浅色)或 FlatDarkLaf(深色)作为 LookAndFeel,使 Swing 界面获得现代外观,替代默认的 Metal/Ocean 主题。

**JTextArea(文本编辑区)** — Swing 的多行文本编辑组件,用于请求 Body 编辑和响应 Body 展示。不使用 RichTextFX 语法高亮,保持纯文本编辑。通过等宽字体(Font.MONOSPACED)和合理的 Tab 缩进提供基本的代码编辑体验。

**EDT(Event Dispatch Thread)** — Swing 的事件分发线程。所有 UI 更新必须在此线程执行(SwingUtilities.invokeLater())。耗时操作(如 HTTP 请求)通过 SwingWorker 异步执行,结果通过 invokeLater 回到 EDT 更新界面。
