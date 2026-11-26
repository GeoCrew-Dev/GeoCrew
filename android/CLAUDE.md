# android/：工作规则

先读根目录的 [CLAUDE.md](../CLAUDE.md)，再读 [ADR 0016](../docs/adr/0016-android-stack.md)（技术栈与模块）、[ADR 0010](../docs/adr/0010-action-registry.md)（动作注册表）和 [ADR 0002](../docs/adr/0002-android-baseline.md)（平台基线）。旧版的代码在标签 `android-legacy`，只作参考，不沿用。

## 规则

- **技术栈与模块**按 ADR 0016：Kotlin、Jetpack Compose、Material 3，一个 Activity。`core:*` 是不用安卓 API 的纯 Kotlin 模块，规则和协议放在那里，在电脑上测。
- **所有界面操作都走动作注册表**（ADR 0010），界面只读状态、发起动作，不直接改数据。
- **界面文字放进资源文件**（`res/values/strings.xml`），不写死在代码里。
- **新依赖**：先写 ADR，更新 [docs/licenses.md](../docs/licenses.md)，版本写进 `gradle/libs.versions.toml`。Gradle、AGP、Kotlin 的版本和 GeoCrew-Native 一起升。
- **合并和权限规则**要通过 `protocol/testdata/` 下和服务端共用的样例。
- **不提交** `local.properties`、签名密钥和本机的代理设置。

## 怎么构建和测试

- 需要 JDK 17 或以上，以及装了 platform 36 的 Android SDK（设好 `ANDROID_HOME`）。
- 先取 GeoCrew-Native 的 AAR：`android/scripts/fetch-native.sh`（要装好并登录 `gh`）。它按 `android/native.properties` 里写的 CI 运行编号下载，并核对 SHA-256。要试 GeoCrew-Native 的改动，把环境变量 `GEOCREW_NATIVE_AAR` 设成本地编出的 AAR 再运行。换版本就改 `native.properties`。
- `cd android && ./gradlew assembleDebug test lintDebug`
- CI 是根目录的 `.github/workflows/android.yml`，改了 `android/` 或 `protocol/testdata/` 时运行。
