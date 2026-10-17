# Go 跨平台打包

版本 **0.1.3**，以 GoLand 2025.2.3 构建验证。安装后，IDE 右侧工具窗口栏会出现“Go 跨平台打包”，位置与 IntelliJ IDEA 中 Maven 工具窗口相同。

[完整使用说明](docs/USAGE.zh-CN.md)

## 使用

1. 在 GoLand 中打开 Go 项目，并配置 Go SDK。
2. 在右侧打开“Go 跨平台打包”。插件会自动扫描项目内 `package main` 的 `main.go`，并用 `go tool dist list` 读取当前 Go 版本支持的平台。
3. 勾选一个或多个入口以及目标平台，点击“打包选中平台”。点击“全平台打包”会选择列表中的全部平台并立即开始构建。
4. 在项目根目录的 `dist/<入口名称>/<系统>_<架构>/` 查看可执行文件和对应 ZIP 包。Windows 可执行文件使用 `.exe` 后缀；Wasm 使用 `.wasm` 后缀。

平台勾选和 Go 可执行文件路径按项目保存，下次打开同一项目会恢复。`linux/amd64` 和 `linux/arm64` 默认排在前两位。每次点击打包，当前选中的每个平台各记录一次使用；累计使用次数越多，排序越靠前，同次数按默认优先级排列。源文件改变后可点击“重新扫描”。构建在后台执行，可从 GoLand 进度窗口取消；每个目标的结果会显示在插件日志中。

插件优先使用 GoLand 项目设置中的 Go SDK。若没有配置 SDK，可以在顶部选择 `go` 或 `go.exe` 文件。构建时设置 `GOOS`、`GOARCH`、`CGO_ENABLED=0`；依赖 CGO 的项目或需要 CGO 的目标平台可能构建失败，具体错误会显示在日志中。“全平台打包”会尝试当前 Go 工具链列出的全部平台。

## 安装

在 GoLand 打开 **Settings → Plugins → 齿轮图标 → Install Plugin from Disk**，选择项目 `release/goland-cross-build-plugin-0.1.3.zip`，然后重启 GoLand。已安装 `0.1.2` 时可直接安装 `0.1.3` 更新。若仍安装旧的 `1.0.1`，由于版本编号从 `1.x` 改为 `0.1.x`，请先卸载旧版并重启，再安装 `0.1.3`。插件 ID 保持不变，以便保留已有项目设置。后续版本按 `0.1.4`、`0.1.5` 递增。

## 从源码构建

需要 Java 17 运行 Gradle、JDK 21 编译插件，以及 Gradle 8.13（项目包含 Gradle Wrapper）。当前 `gradle.properties` 已配置这台电脑上的 Microsoft JDK 17 和 GoLand 自带的 JBR 21；若移动到其他电脑，请修改其中的两个 JDK 路径。PowerShell 示例：

```powershell
$env:JAVA_HOME = 'D:\SoftWareSpace\microsoft-jdk-17.0.15-windows-x64\jdk-17.0.15+6'
.\gradlew.bat buildPlugin -PgolandHome='D:/SoftWareSpace/GoLand 2025.2.3'
```

插件 ZIP 生成在 `build/distributions/`。项目使用本机 GoLand 作为 IntelliJ Platform SDK，不会下载另一份 IDE。

Java 源码包名为 `com.github.yufuchang.crossbuild`。插件图标采用白色 G 与黄色三向分支，表示 Go 项目向多个平台输出。

