# NetworkInfo — 网络信息查看器

_✨ 查看本机各类网络信息，每条都能一键复制 ✨_

![release](https://img.shields.io/github/v/release/qwqZYLqwq/NetworkInfo) ![downloads](https://img.shields.io/github/downloads/qwqZYLqwq/NetworkInfo/total) ![commit activity](https://img.shields.io/github/commit-activity/m/qwqZYLqwq/NetworkInfo) ![last commit](https://img.shields.io/github/last-commit/qwqZYLqwq/NetworkInfo)
![platform](https://img.shields.io/badge/platform-Android-3DDC84) ![minSdk](https://img.shields.io/badge/minSdk-26%20(Android%208.0)-blue) ![实测](https://img.shields.io/badge/%E5%AE%9E%E6%B5%8B-Android%209-brightgreen) ![APK](https://img.shields.io/badge/APK-%E7%BA%A625KB-orange)

一个轻量的安卓应用，快捷查看本机的各类网络信息，每条信息均可单独一键复制。

## 功能

### 本机地址（置顶展示）

- IPv4 内网地址 / IPv4 公网地址 分组显示
- IPv6 内网地址 / IPv6 公网地址 分组显示
- IPv6 地址自动标注 临时（隐私扩展）/ 非临时（EUI-64 或稳定隐私）/ 已弃用
  - 通过解析 `/proc/net/if_inet6` 内核标志位（`IFA_F_PERMANENT` / `IFA_F_DEPRECATED`）实现

### 外网地址（公网出口）

- 公网 IPv4 出口地址（多源自动回退：ipify / icanhazip / ipw.cn，国内网络可用）
- 公网 IPv6 出口地址

### 当前网络

- 联网状态、网络类型（Wi-Fi / 移动数据 / 以太网 / VPN / 蓝牙，支持组合显示）
- 默认网关（IPv4 / IPv6）、DNS 服务器、搜索域名、计费网络状态

### 所有网卡

- 枚举全部网络接口（wlan / eth / rmnet / tun 等），含已停用接口
- 每个接口的 IPv4 / IPv6 地址、前缀长度、MAC 地址、MTU
- 地址自动分类：内网（私有）/ 公网 / 链路本地 / 回环 / ULA / 组播

### 复制

- 每条信息右侧均有独立「复制」按钮，点击整行也可复制
- 顶部「复制全部」一键复制所有网络信息

## 界面

仿 MIUI 浅色风格：白色圆角卡片 + 蓝色强调色，Material Design。

## 安装

直接下载 [NetworkInfo.apk](../../releases) 安装即可（或自行构建，见下文）。

- 最低支持 Android 8.0（API 26），已在 Android 9 实机验证
- 仅需两个权限：`INTERNET`（查询公网出口）、`ACCESS_NETWORK_STATE`（读取网络信息）
- 无任何第三方依赖，不需要 Gradle

## 构建

项目附带 `build.ps1`（Windows PowerShell），直接调用 Android SDK 的 `aapt2` / `d8` / `zipalign` / `apksigner`
完成构建，无需安装 Gradle 和完整 JDK（仅需 JRE + 自动下载的 Eclipse 编译器 ecj）。

前置要求：

- Android SDK（`build-tools 36.0.0`、`platforms/android-36`）
- PATH 中有 `java`（JRE 即可）

```powershell
./build.ps1
```

构建产物为项目根目录的 `NetworkInfo.apk`（debug 签名）。

## 技术说明

- 纯 Java + 系统 API，单个 Activity，无第三方库，APK 约 25KB
- 公网地址查询使用多源回退策略，兼容国内网络环境
- IPv6 临时 / 非临时判断：解析 `/proc/net/if_inet6` 的内核地址标志位，`IFA_F_PERMANENT` 未置位即为隐私扩展临时地址
- 支持安卓 9（实机验证）至最新版本

## 项目结构

```
app/src/main/
├── AndroidManifest.xml
├── java/net/tools/netinfo/
│   └── MainActivity.java      # 全部逻辑：采集、分类、UI 构建
└── res/
    ├── drawable/              # 卡片背景、图标
    ├── layout/                # 主界面、卡片、信息行布局
    ├── mipmap-anydpi-v26/     # 自适应启动图标
    └── values/                # 颜色、主题
build.ps1                      # 无 Gradle 构建脚本
```

## License

MIT
