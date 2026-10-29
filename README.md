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
| `Ctrl+Click` / `Ctrl+B` | Sources first: if the target resolves into a jar or compiled artifact, redirect to the matching class / method / field in the workspace sources |
| `Ctrl+Alt+B` / `Ctrl+Alt+Click` / gutter icon | Jump straight to the workspace implementation across dependency versions — the caller and the implementation do not need to depend on the same jar version |

Additional behavior:

- Members are matched by **fully qualified class name + erased method signature**, never by PSI inheritance, so a version skew between jar and sources does not matter;
- Results are de-duplicated; several implementations open the platform's native chooser;
- When no source is found, the native behavior runs unchanged;
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

The plugin does two things: it registers one extension point and replaces one action.

| Mechanism | Role |
| --- | --- |
| `com.intellij.gotoDeclarationHandler` | Has higher priority than standard reference resolution, so it can override the default "jump to jar" behavior |
| Replacement of the `GotoImplementation` action | Searches and navigates on its own, keeping the native key bindings and menu placement |

**Why Go to Implementation is not an extension point**: `definitionsScopedSearch` / `classInheritorsSearch` affect inheritance search globally — structure view, Find Usages, call hierarchy — and their results are additionally filtered by the platform's `acceptImplementationForReference` validation, which drops implementations whose super method comes from a different jar version. The plugin therefore declares the same action id with `overrides="true"` in `plugin.xml`.

**Cross-version matching** never relies on PSI inheritance: two copies of the same interface from different jar versions are different PSI objects, so the plugin matches on FQCN plus the erased method signature.

**Implementation search, in fallback order**:

1. every source copy of the interface (same FQCN) is used as a search root, each searched inside its own project scope;
2. the `XxxService -> XxxServiceImpl` naming convention;
3. a **word-in-text scan** over workspace sources via `PsiSearchHelper.processAllFilesWithWordInText`: interfaces and annotations are skipped, `InheritanceUtil.isInheritor(class, true, FQCN)` checks the super type, and `WorkspaceSourceMatcher.matchMethod` compares the erased signature. This step never touches the inheritance index, which is what makes it work when jar versions differ, the index is stale, or the PSI inheritance chain is broken.

**Scope**: the **current project only**. Its own scope already covers every imported module, which is how multi-module workspaces are normally opened. Widening it is a cost, not a capability:

| Relationship | Example | Searched |
| --- | --- | --- |
| The current project (all of its modules) | `ws` with `module-a` / `module-b` imported | yes |
| A project nested below the current one, opened in another window | `ws` and `ws/module-a` | yes (same tree opened twice) |
| Flat siblings | `ws-a` and `ws-b`, same parent | no |
| Unrelated projects | history from other windows | no |

Flat siblings used to be included and turned out to be harmful: every open project under `~/IdeaProjects` matched, so each jump paid for scans in unrelated projects and could land on an implementation nobody meant to reach. With the narrower scope the log shows a single project line.

**Navigation**: one implementation jumps directly, several open the native chooser, none falls back to the native `GotoImplementation`.

Main flow for declarations (`WorkspaceSourceRedirectHandler`, Ctrl+Click / Ctrl+B):

1. Walk up from the element at the caret, calling `resolve()` level by level (at most 15 levels), accepting only `PsiClass` / `PsiMethod` / `PsiField`;
2. The target must live in a `PsiCompiledFile` — that is, it must come from a jar or compiled output. Elements already in source are left alone;
3. Take the fully qualified name of the containing class and look for a source class with the same name in the current project (including all of its modules);
4. Map the member onto the source class using its erased signature; when the mapping fails, fall back to navigating to the class.

Main flow for implementations (`WorkspaceGotoImplementationAction`): resolve the declaration at the caret (including the gutter-icon case) → `WorkspaceImplementationFinder` runs the strategies above → navigate.

Both flows run inside a `ReadAction` and silently give up when the index is not ready — they never throw.

## Known Limitations

- **Matching is by fully qualified name only; versions are not checked.** That is the whole point (a 1.0 jar can jump to 1.2 sources), but when an API has been removed the plugin silently degrades to navigating to the class;
- Method signatures are compared using **simple type names**, so `java.util.Date` and `java.sql.Date` could theoretically collide. The "give up when ambiguous" rule is the only guard;
- Generic parameters are collapsed to `T`, so overloads that differ only in generic positions may be mismatched;
- The search scope is the current project only (all of its modules included); sibling projects opened in other windows are never searched;
- **A container directory opened as a single window**: its sub-projects have no source root, so the class index is empty and native `Ctrl+N` cannot see them either. Declarations still resolve through the file name index fallback, but implementations need real source roots — see the next section;

## When the Window Is a Plain Folder

Opening a directory with `File → Open` (for example `live/`, which holds nine independent Maven projects that were never imported) only creates a content root. IDEA does **not** mark the sub-folders as source roots, so the class index stays empty: `Ctrl+N` finds nothing and `JavaPsiFacade.findClass` returns `null`.

The plugin handles this with two lookups:

| Lookup | Works when | Cost |
| --- | --- | --- |
| Class index, `JavaPsiFacade.findClasses` | the tree has source roots or imported modules | none |
| File name index + PSI parse | the first lookup is empty: finds `Xxx.java` by simple name, then compares fully qualified names per class | one file name query |

When the fallback is used, the log says:

```
Workspace Source Redirector: <FQCN> was resolved through the file name index, the project has no source root
```

That line means navigation works but native features such as `Ctrl+N` are still degraded. The one-step fix is to right-click the root folder → **Mark Directory as → Sources Root** (covers the whole tree at once, much faster than importing nine Maven projects); after that every lookup takes the fast indexed path.

Go to Implementation depends on the inheritance index, which a file name index cannot replace: without source roots, cross-file `implements` / `extends` resolution is unreliable. Mark the sources as a source root first.
- Kotlin is not handled.

## License

[Apache License 2.0](LICENSE)
