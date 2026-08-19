# richeditor 模块 — fork 治理说明

## 上游与基线

| 项 | 值 |
|----|----|
| 上游 | https://github.com/MohamedRejeb/compose-rich-editor （Apache 2.0，原 LICENSE 保留在本目录） |
| fork 基线 commit | `01d7f4c919cd49949f57652c77ee23dd82363ac1`（2026-06-05） |
| 上游版本线 | Kotlin 2.3.21 / Compose Multiplatform 1.10.3（与主项目同线，选定原因） |

## 引入原因（为什么不依赖发布版）

1. **JVM 字节码不可控**：三方 Compose 库发布版的字节码目标可能高于本项目运行时（JBR 17 / class 61），
   mikepenz markdown 渲染器曾在运行期 `UnsupportedClassVersionError`（lessons F17）。
2. **IM 场景需要源码级定制**：mention span 的 markdown 序列化、输入区键盘行为（Enter 发送）等
   需要修改编辑器内核，发布版 API 覆盖不到。

## 相对上游的裁剪（非功能性，同步时忽略）

- 目标平台裁剪：仅保留 `commonMain` / `androidMain` / `desktopMain` + `commonTest`；
  删除 `iosMain` / `jsMain` / `wasmJsMain` 及对应 target 注册。
- 构建：去除 `explicitApi()`、binary-compatibility-validator、`module.publication` 发布配置、
  convention-plugins；build.gradle.kts 重写为主项目风格（对齐 app/shared 模块写法）。
- ksoup（HTML 解析）保留为依赖（版本对齐上游 0.6.0）。

## 改动标注规范（**强制**）

对上游源码的**任何功能性修改**必须：

1. 改动处加注释，统一前缀：`// [TT]`（TeamTalk 定制），写明目的；
2. 在下表登记一行（commit hash + 简述），保持可追溯。

## 改动登记表

| 日期 | 上游文件 | 改动 | 目的 |
|------|---------|------|------|
| 2026-08-17 | model/RichTextState.kt | + `insertAtCaret(text)` 公开方法（光标插入，走 onTextFieldValueChange 完整链路含撤销历史） | 表情/@语法插入，上游无公开定点插入 API |
| 2026-08-17 | model/RichTextState.kt | + `replaceRange(start, end, text)` 公开方法（区间替换） | @补全选中替换 query、/指令回填 |
| 2026-08-19 | model/RichTextState.kt | 链接选中态与更新/移除共用边界感知定位，覆盖完整选区和首字符光标 | 避免工具栏状态与实际链接操作分叉 |
| 2026-08-19 | parser/markdown/MarkdownUtils.kt、RichTextStateMarkdownParser.kt | Markdown 普通文本、链接 label/destination 的最小转义与标点反解；普通段落起始块标记按上下文转义 | 保证编辑器文字及含括号、反斜杠链接可无损往返，避免字面 `#`、`>`、列表标记在重载后变成块结构 |

## 上游同步策略

1. 上游仓库单独 clone（`~/git/tk/compose-rich-editor`），需要同步时 fetch 最新；
2. `git diff <基线commit> <目标commit> -- richeditor-compose/src/commonMain` 审查上游变更；
3. 上游变更按文件 cherry-pick / 手工合并到本模块；本模块改动以 `// [TT]` 注释 + 登记表为合并冲突的裁决依据；
4. 裁剪掉的源集（ios/js/wasm）的对应上游变更直接忽略；
5. 大版本升级（Kotlin/CMP 线变更）时重新评估：更新基线 commit 并重放登记表中的定制项。
