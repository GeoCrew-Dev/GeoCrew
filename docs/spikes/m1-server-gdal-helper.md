# M1 技术验证：服务端 GDAL 辅助程序的五平台静态构建

- 日期：2026-11-25
- 对应待办：[M1](../backlog.md)"服务端 GDAL 辅助程序在五个平台上的静态构建"
- 代码：[GeoCrew-Native#5](https://github.com/GeoCrew-Dev/GeoCrew-Native/pull/5) 的 `helper/`、五个三元组和 CI `server.yml`
- 相关：[ADR 0013](../adr/0013-server-gdal-helper.md)

## 结论

能做到。五个平台都编出了单个可执行文件，带上旁边 12 MB 的 `data/`，在各自的系统上跑通了版本、重投影和 GeoPackage 读写，除系统自带的库外没有动态依赖。ADR 0013 的做法可以照用。

还剩一个要解决的问题：Linux 版要求 glibc 2.38，老一些的服务器系统跑不了（见"发现的问题"第 1 条），放进待办。

## 做法

- **程序** `geocrew-gdal-helper`：参数和 `gdal` 命令行一样，内部走 GDAL 3.12 的算法接口；PROJ 和 GDAL 的数据文件放在程序旁边的 `data/`。服务端以后作为子进程调用它，GDAL 出问题不会拖垮同步服务。
- **静态构建**：x64-linux、arm64-linux、x64-osx、arm64-osx 和 x64-windows-static 五个 vcpkg 三元组，全部静态库，只编 Release。Windows 的 C 运行库也静态链接；Linux 静态链接 libstdc++ 和 libgcc；macOS 的最低系统版本是 12。
- **检查**（`scripts/check-helper.sh`）：在 CI 里用五种 GitHub 运行器各编一份，在各自的机器上实际运行：
  - 读出版本；
  - 把一个带中文属性的 GeoJSON 点重投影到 EPSG:4547，写成 GeoPackage 再读回，核对坐标和属性；
  - 列出动态依赖。
- **许可证**：GEOS 和 GNU libiconv 在服务端是静态链接，[许可证清单](../licenses.md)允许这样做，因为本项目完全开源。CI 照样检查不能有 GPL。

## 结果

| 平台 | 运行器 | 程序大小 | 动态依赖 | 结果 |
|---|---|---|---|---|
| x64-linux | ubuntu-24.04 | 55 MB | libc、libm（glibc ≥ 2.38） | 通过 |
| arm64-linux | ubuntu-24.04-arm | 51 MB | libc、libm（glibc ≥ 2.38） | 通过 |
| arm64-osx | macos-15（Apple 芯片） | 42 MB | 系统的 libSystem、libc++、libiconv、libcharset | 通过 |
| x64-osx | macos-15-intel | 44 MB | 同 arm64-osx | 通过 |
| x64-windows-static | windows-2025 | 36 MB | 只有系统 DLL（KERNEL32、ADVAPI32、SHELL32、ole32、WS2_32 等） | 通过 |

- 版本：GDAL 3.12.4、PROJ 9.9.0、GEOS 3.15.1。
- macOS 上 iconv 用的是系统自带的 libiconv，不是 vcpkg 编的 GNU libiconv。系统库每台 Mac 都有，不影响分发。
- 没有缓存时，一个平台从头编完要十几分钟到半小时以上（Intel Mac 和 Windows 最慢）；有了 vcpkg 二进制缓存后，Linux 不到一分钟。

## 发现的问题

1. **Linux 版要求 glibc 2.38**：程序在 Ubuntu 24.04 上编译，只动态依赖 libc 和 libm，但要求 glibc ≥ 2.38，所以在 Debian 12（2.36）、Ubuntu 22.04（2.35）、RHEL 9（2.34）这些常见的服务器系统上跑不起来。要么在 glibc 较老的容器里编，要么改用 musl 完全静态链接，一个文件到哪都能跑。服务端本身（Go，CGO_ENABLED=0）没有这个问题。
2. **macOS 上 GDAL 找不到自己的路径**：GDAL 的 `CPLGetExecPath` 只实现了 Windows 和 Linux，macOS 上要改用 `_NSGetExecutablePath`，否则找不到旁边的 `data/`。评审时发现，已修。
3. **许可证检查漏了一类包**：vcpkg 对没写许可证的 port 记的是 `LicenseRef-vcpkg-null`，不是 `NOASSERTION`，所以 gdal 和 libiconv 一直没对照手工核对的表，libiconv 的"LGPL 必须编成 .so"也就没检查到（安卓上它本来就是 .so，没出事）。另外 Windows 的静态库是 `.lib`。都已修。
4. **程序没有剥离符号**：Linux 版 51 到 55 MB，剥离后能小不少，发布时再做。

## 退路

ADR 0013 本来就留了退路：某个平台编不出来时，同步服务照样发布，只是依赖 GDAL 的功能不可用。

## 后续

已记入[待办](../backlog.md)：

- Linux 版改用 glibc 较老的环境或 musl 完全静态编译，让 Debian 12、Ubuntu 22.04、RHEL 9 也能用。
- 发布时剥离符号；把辅助程序和 `data/` 一起打进服务端的发布包和 Docker 镜像，服务端按 `gdal` 能力声明（协议 3.3）（M3）。
