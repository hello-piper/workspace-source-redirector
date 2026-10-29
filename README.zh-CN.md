# Workspace Source Redirector

[English](README.md) | **简体中文**

> 一个把 **Go to Declaration** 和 **Go to Implementation** 重定向到工作区源码、而不是反编译 jar 的 IntelliJ IDEA 插件。

解决「IDEA 里 Ctrl+点击总是跳到 jar 里的反编译类，而不是同工作区里的源码」的问题。

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
| `Ctrl+点击` / `Ctrl+B` | 优先跳工作区源码：目标来自 jar 或编译产物时，重定向到源码的对应类 / 方法 / 字段 |
| `Ctrl+Alt+B` / `Ctrl+Alt+点击` / 行号栏图标 | 跨依赖版本直接跳工作区实现方法，不要求调用方与实现方依赖同一个 jar 版本 |

其他行为：

- 成员按「全限定类名 + 擦除后的方法签名」匹配，不依赖 PSI 继承关系，因此 jar 与源码版本不一致也能命中；
- 结果去重，不会出现重复条目；多个实现时弹出平台原生选择框；
- 找不到源码时回退原生行为，不影响正常跳转；
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

> 首次上架仍在审核中。通过审核前请使用下面的本地文件方式安装。

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

插件只做两件事：注册一个扩展点、替换一个 action。

| 机制 | 作用 |
| --- | --- |
| `com.intellij.gotoDeclarationHandler` | 优先级高于标准引用解析，可覆盖「跳 jar」的默认行为 |
| 替换 `GotoImplementation` action | 自己搜、自己导航，保留原生键位与菜单位置 |

**为什么实现跳转不用扩展点**：`definitionsScopedSearch` / `classInheritorsSearch` 作用于全局继承搜索，结构视图、Find Usages、调用层级都会受影响，副作用太大；而且平台还有 `acceptImplementationForReference` 校验，跨版本时会把「父方法来自另一个 jar 版本」的实现直接过滤掉。所以改为在 `plugin.xml` 里用同 id + `overrides="true"` 替换 `GotoImplementation`。

**跨版本匹配**：不依赖 PSI 继承关系。不同 jar 版本里的同名接口在 PSI 里是两个不同对象，插件统一用「FQCN + 方法擦除签名」匹配。

**实现搜索的兜底顺序**：

1. 按 FQCN 找所有同名接口源码副本，逐个以其自身工程的 scope 搜实现；
2. 尝试 `XxxService` → `XxxServiceImpl` 命名约定；
3. 最后用 `PsiSearchHelper.processAllFilesWithWordInText` 按方法名做**工作区源码文本扫描**：跳过接口和注解，用 `InheritanceUtil.isInheritor(class, true, FQCN)` 判定父类型，再用 `WorkspaceSourceMatcher.matchMethod` 比对擦除签名。这一步不经过继承索引，专门兜底跨版本、索引不准、PSI 继承链断裂的情况。

**搜索范围**：只搜**当前工程**。工程自己的 scope 已经覆盖了导入进来的所有 module（多模块工作区正常就是这么打开的），扩到别的工程是负担，不是能力：

| 关系 | 例子 | 是否纳入 |
| --- | --- | --- |
| 当前工程自身（含其全部 module） | `ws`（导入 module-a / module-b） | 是 |
| 嵌套在当前工程下、且在另一个窗口打开 | `ws` 与 `ws/module-a` | 是（同一源码树开了两次） |
| 平级工程 | `ws-a` 与 `ws-b`，同父目录 | 否 |
| 无关工程 | 其他窗口的历史工程 | 否 |

平级工程曾经纳入过，实测有害：`~/IdeaProjects` 下所有开着的项目都会命中，每次跳转都白扫一遍，还可能跳到根本没打算去看的实现。范围收窄后日志里只剩当前工程一行。

**导航**：1 个实现直接跳；多个实现弹平台原生选择框；找不到回退原生 `GotoImplementation`。

声明跳转主流程（`WorkspaceSourceRedirectHandler`，Ctrl+点击 / Ctrl+B）：

1. 从光标元素向上逐层 `resolve()`（最多 15 层），只接受 `PsiClass` / `PsiMethod` / `PsiField`；
2. 目标必须位于 `PsiCompiledFile`（即来自 jar 或编译产物），源码里的元素不处理；
3. 取所在类的全限定名，在工作区里查找同名源码类（当前工程，含其全部 module）；
4. 把成员按擦除签名映射到源码类上，映射失败则退化为跳转类。

实现跳转主流程（`WorkspaceGotoImplementationAction`）：解析光标处的声明（含行号栏图标场景）→ `WorkspaceImplementationFinder` 按上述顺序搜索 → 导航。

两个流程都包在 `ReadAction` 中，索引未就绪时静默放弃，不抛异常。

## 打开的是普通目录时

如果窗口是用 `File → Open` 打开的一个目录（例如 `live/`，下面 9 个各自带 `pom.xml` 的独立工程都没有导入），IDEA 只会把它建成 content root，**不会自动把子目录标成 source root**。此时类索引是空的：`Ctrl+N` 搜不到这些类，`JavaPsiFacade.findClass` 也返回 `null`。

插件对此做了两级处理：

| 查找方式 | 条件 | 代价 |
| --- | --- | --- |
| 类索引 `JavaPsiFacade.findClasses` | 目录已标为 source root / 已导入 module | 无 |
| 文件名索引 + PSI 解析 | 上一步落空时启用，按简单类名找 `Xxx.java`，再逐类比对全限定名 | 一次文件名查询 |

命中兜底时日志会写一条：

```
Workspace Source Redirector: <FQCN> was resolved through the file name index, the project has no source root
```

看到这条说明插件能用，但 `Ctrl+N` 之类的原生功能仍然是残的。一次性修好：右键 `live` 根目录 → **Mark Directory as → Sources Root**（整个目录树一次覆盖，比逐个导入 9 个 Maven 工程快），之后所有查找走类索引的快路径。

注意「跳到实现」依赖继承索引，不是文件名索引能替代的：目录未标 source root 时，跨文件的 `implements` / `extends` 解析本身不可靠，请先按上面的方式把源码标成 source root。

## 已知限制

- **只按全限定名匹配，不校验版本**：这是设计目标（jar 1.0 也能跳源码 1.2），但 API 已删改时会静默退化为跳类；
- 方法签名使用**简单类名**比对，`java.util.Date` 与 `java.sql.Date` 这类同名不同类型理论上会冲突，靠「多候选即放弃」兜底；
- 泛型参数统一按 `T` 处理，重载方法存在错配可能；
- 搜索范围只有当前工程（含其 module）；平级打开的其他工程一律不搜，多窗口共用源码的需求不支持；
- **单窗口打开容器目录、子工程未导入为 module 时**：类索引为空（`Ctrl+N` 同样搜不到），声明跳转会退到文件名索引兜底，实现跳转需要真实的 source root，见上一节；
- 不处理 Kotlin。

## License

[Apache License 2.0](LICENSE)
