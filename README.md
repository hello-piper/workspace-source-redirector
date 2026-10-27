# Workspace Source Redirector

> An IntelliJ IDEA plugin that redirects **Go to Declaration** and **Go to Implementation** to the matching workspace source code instead of decompiled jars.

一个解决「IDEA 里 Ctrl+点击总是跳到 jar 里的反编译类，而不是同工作区里的源码」的 IntelliJ IDEA 插件。

---

## 解决什么问题

同一个 IDEA 窗口里挂着多个 Java 项目 / module，A 依赖 B。按住 `Ctrl` 点击 B 里的类或方法时，IDEA 往往跳到本地仓库里的 **B 的 jar**（反编译后的 class），而不是 B 的源码。

原因有两个：

1. 引用解析按 classpath 顺序进行，jar 排在源码模块前面；
2. 「跳到实现」只在当前 project scope 里搜索。

这个插件在中间插一层：**只要工作区里存在同名全限定类的源码，就重定向过去**，并且不要求依赖的 jar 版本与源码版本一致。

## 功能

| 操作 | 行为 |
| --- | --- |
| `Ctrl+点击` / `Ctrl+B` | 若目标来自 jar 或编译产物，则重定向到项目源码的对应类 / 方法 / 字段 |
| `Ctrl+Alt+B` / `Ctrl+Alt+点击` / 行号栏图标 | 在原生实现搜索结果之外，补充其它已打开工程的源码实现 |

其他行为：

- 成员按「擦除后的参数类型」匹配：方法签名唯一命中就精确跳到该方法，多个候选时退化为跳转所在类；
- 结果去重，不会出现重复条目；
- 找不到源码时返回 `null`，完全交还平台默认逻辑，不影响正常跳转；
- 每次接管都会写日志，便于排查。

## 兼容性

| 项 | 值 |
| --- | --- |
| IDE | IntelliJ IDEA 2023.1（build 231）及以上，无上限 |
| IDE 版本类型 | Community / Ultimate 均可（需要内置 Java 插件） |
| 字节码 | Java 8（class major 52） |
| 语言 | 仅 Java（不处理 Kotlin） |

## 安装

**从 Marketplace（推荐）**

Settings → Plugins → 搜索 `Workspace Source Redirector` → Install。

**从本地文件**

```bash
./gradlew buildPlugin
# 产物：build/distributions/workspace-source-redirector-<version>.zip
```

Settings → Plugins → 齿轮 → Install Plugin from Disk，选择上面的 zip，重启 IDEA。

## 构建

要求 JDK 17 及以上（本项目默认复用 IDEA 自带的 JBR 21，可在 `gradle.properties` 的 `org.gradle.java.home` 中修改）。

```bash
./gradlew buildPlugin        # 构建分发包
./gradlew runIde             # 启动沙箱 IDE 调试
./gradlew verifyPlugin       # 用本机 IDEA 校验二进制兼容性
```

编译 SDK 由 `gradle.properties` 的 `platformVersion` 决定（当前 `2023.1.5`）。目标字节码固定为 Java 8，用 `options.release = 8` 约束，避免误用高版本 JDK API。

## 验证是否生效

接管成功时会写日志，直接 grep 即可：

```bash
grep "Workspace Source Redirector" "$HOME/Library/Logs/JetBrains/IntelliJIdea<version>/idea.log"
```

## 实现原理

插件只注册了两个扩展点，没有后台服务和 UI：

| 扩展点 | 作用 |
| --- | --- |
| `com.intellij.gotoDeclarationHandler` | 优先级高于标准引用解析，可覆盖「跳 jar」的默认行为 |
| `com.intellij.definitionsScopedSearch` | 只被「跳到实现」使用，用于补充跨工程的实现结果 |

主流程（`WorkspaceSourceRedirectHandler`）：

1. 从光标元素向上逐层 `resolve()`（最多 15 层），只接受 `PsiClass` / `PsiMethod` / `PsiField`；
2. 目标必须位于 `PsiCompiledFile`（即来自 jar 或编译产物），源码里的元素不处理；
3. 取所在类的全限定名，用 `JavaPsiFacade.findClass` 在项目源码中查找同名类；
4. 把成员按擦除签名映射到源码类上，映射失败则退化为跳转类。

整个过程包在 `ReadAction` 中，索引未就绪时静默放弃，不抛异常。

## 已知限制

- **只按全限定名匹配，不校验版本**：这是设计目标（jar 1.0 也能跳源码 1.2），但 API 已删改时会静默退化为跳类；
- 方法签名使用**简单类名**比对，`java.util.Date` 与 `java.sql.Date` 这类同名不同类型理论上会冲突，靠「多候选即放弃」兜底；
- 泛型参数统一按 `T` 处理，重载方法存在错配可能；
- 不处理 Kotlin。

## License

[Apache License 2.0](LICENSE)
