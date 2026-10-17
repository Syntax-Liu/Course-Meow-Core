# CourseMeow Core（拾课喵 · 核心层）

「拾课喵 / CourseMeow」的跨平台核心层源码。这里放的是**与平台无关**的课表逻辑，
Android 与鸿蒙两端共用同一份：不含界面、不含 Android 平台 API，删除应用代码后本仓库
仍可独立编译并跑通全部测试。

## 为什么公开

应用里「从教务系统导入」需要用户填写一卡通号和密码登录教务系统，并对返回的课表页面做解析。
为了让这件事**实现公开可查**，解析模块连同它的全部测试一起放在这里 —— 代码就在这里，
任何人都可以自己看它是怎么读取课表的。

应用本身（界面、桌面小组件、云端备份等）不在本仓库内。

## 目录

| 路径 | 内容 |
| --- | --- |
| `core/src/main/kotlin/com/nexoraism/coursemeow/domain/sheet/` | **教务课表解析模块**：教务系统（正方）课表解析、班级课表与个人课表合并、PDF / Excel 课表导入 |
| `core/src/main/kotlin/com/nexoraism/coursemeow/domain/` | 时间计算、教学周、节假日判定、冲突检查、课程颜色分配 |
| `core/src/main/kotlin/com/nexoraism/coursemeow/data/` | 课表与课程的数据模型、编解码、界面文案表 |
| `core/src/test/kotlin/` | 与上面各模块对应的测试（含教务课表解析的样例数据） |

## 构建与测试

需要 JDK 17 或更高版本：

```bash
./gradlew :core:test
```

## 依赖约定

本模块是纯 Kotlin/JVM 模块（`kotlin-jvm` 插件，Java 17）。为了将来接 Kotlin Multiplatform
与鸿蒙，依赖有严格约定：

- 允许：纯 Kotlin、JDK 标准库；
- 允许：**仅注解用途**的 `compileOnly` 依赖（Room 的 `@Entity`、Compose 的 `@Immutable`）；
- 禁止：`android.*`、需要打包进产物的 `androidx.*`、以及对本应用代码的引用。

`org.json` 也是如此：Android 运行时自带同名类，本模块只在编译期取一个实现、不打包。

## 许可

MIT，见 [LICENSE](LICENSE)。
