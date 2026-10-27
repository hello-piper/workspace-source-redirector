# Workspace Source Redirector

**English** | [简体中文](README.zh-CN.md)

> An IntelliJ IDEA plugin that redirects **Go to Declaration** and **Go to Implementation** to the matching workspace source code instead of decompiled jars.

---

## The Problem

When several Java projects or modules are open in the same IDEA window and project A depends on project B, `Ctrl+Click` on a class or method from B usually lands in **B's jar** inside the local Maven / Gradle repository — a decompiled class stub — instead of B's actual source code.

There are two reasons for this:

1. Reference resolution follows classpath order, and jars come before source modules.
2. Go to Implementation only searches the current project scope.

This plugin inserts a layer in between: **whenever a source file with the same fully qualified class name exists anywhere in the workspace, it jumps there** — regardless of whether the dependency version matches the source version.

## Features

| Action | Behavior |
| --- | --- |
| `Ctrl+Click` / `Ctrl+B` | If the target resolves into a jar or compiled artifact, redirect to the matching class / method / field in project sources |
| `Ctrl+Alt+B` / `Ctrl+Alt+Click` / gutter icon | Add source implementations from other open projects on top of the native results |

Additional behavior:

- Members are matched by **erased parameter types**: a unique signature match jumps to that exact method, while ambiguous matches fall back to the containing class;
- Results are de-duplicated, so no entry shows up twice;
- When no source is found, the handler returns `null` and leaves the default behavior completely untouched;
- Every takeover is logged, which makes troubleshooting straightforward.

## Compatibility

| Item | Value |
| --- | --- |
| IDE | IntelliJ IDEA 2023.1 (build 231) or later, no upper bound |
| Edition | Community and Ultimate (the bundled Java plugin is required) |
| Bytecode | Java 8 (class major version 52) |
| Language | Java only (Kotlin is not handled) |

## Installation

**From JetBrains Marketplace (recommended)**

Settings → Plugins → search for `Workspace Source Redirector` → Install.

> The first Marketplace release is still pending review. Until it is approved, use the local file method below.

**From a local file**

```bash
./gradlew buildPlugin
# Output: build/distributions/workspace-source-redirector-<version>.zip
```

Then go to Settings → Plugins → ⚙️ → Install Plugin from Disk, pick the zip above and restart the IDE.

## Building

JDK 17 or newer is required. By default the build reuses the JBR 21 bundled with your IDE; change `org.gradle.java.home` in `gradle.properties` to point at another JDK.

```bash
./gradlew buildPlugin        # build the distribution zip
./gradlew runIde             # launch a sandboxed IDE for debugging
./gradlew verifyPlugin       # run the Plugin Verifier against your local IDE
```

The compile SDK is controlled by `platformVersion` in `gradle.properties` (currently `2023.1.5`). Target bytecode is pinned to Java 8 via `options.release = 8`, so JDK APIs newer than 8 are rejected at compile time.

## Verifying It Works

A log entry is written every time the plugin takes over a navigation. Grep for it:

```bash
grep "Workspace Source Redirector" "$HOME/Library/Logs/JetBrains/IntelliJIdea<version>/idea.log"
```

## How It Works

The plugin registers exactly two extension points and ships no background services or UI:

| Extension point | Role |
| --- | --- |
| `com.intellij.gotoDeclarationHandler` | Has higher priority than standard reference resolution, so it can override the default "jump to jar" behavior |
| `com.intellij.definitionsScopedSearch` | Used only by Go to Implementation, to add implementations from other open projects |

Main flow (`WorkspaceSourceRedirectHandler`):

1. Walk up from the element at the caret, calling `resolve()` level by level (at most 15 levels), accepting only `PsiClass` / `PsiMethod` / `PsiField`;
2. The target must live in a `PsiCompiledFile` — that is, it must come from a jar or compiled output. Elements already in source are left alone;
3. Take the fully qualified name of the containing class and look for a class with the same name among project sources via `JavaPsiFacade.findClass`;
4. Map the member onto the source class using its erased signature; when the mapping fails, fall back to navigating to the class.

The whole flow runs inside a `ReadAction` and silently gives up when the index is not ready — it never throws.

## Known Limitations

- **Matching is by fully qualified name only; versions are not checked.** That is the whole point (a 1.0 jar can jump to 1.2 sources), but when an API has been removed the plugin silently degrades to navigating to the class;
- Method signatures are compared using **simple type names**, so `java.util.Date` and `java.sql.Date` could theoretically collide. The "give up when ambiguous" rule is the only guard;
- Generic parameters are collapsed to `T`, so overloads that differ only in generic positions may be mismatched;
- Kotlin is not handled.

## License

[Apache License 2.0](LICENSE)
