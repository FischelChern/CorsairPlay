# DiPlay 实现原理分析（面向二次开发）

> 分析对象：`https://github.com/shihabal3amri/DiPlay`，commit `8010333`
> （v0.2.11 公共预览版，2026-10-04）。这是一个**跑在 BYD 安卓车机上的 CarPlay 接收端**，
> 不是手机端 App。应用包名 `com.shihab.diplay`，源码命名空间仍沿用上游 `com.shilapi.xcertplay`。

## 1. 这个项目到底是什么

普通 CarPlay 车机需要一颗 Apple 授权的 **MFi 认证芯片**（做 Apple 的挑战/签名握手）。
DiPlay 做的事是：**用软件 + 一个被"借用"的配件身份，把车机伪装成合法的 CarPlay 配件**，
从而让 iPhone 通过 USB 或 Wi-Fi 把 CarPlay 画面、音频、触摸投到车机上。

- 上游基础：[shilapi/xcertplay](https://github.com/shilapi/xcertplay)（GPL-3.0），
  协议研究参考 [f-io/LIVI](https://github.com/f-io/LIVI) 与 [amineross/showcase](https://github.com/amineross/showcase)。
- UI/网站视觉改自作者的 [DiAuto](https://github.com/shihabal3amri/DiAuto)（AGPL-3.0，
  所以 `DiPlayActivity.kt` 是 AGPL-only）。
- **未通过 Apple 认证**。APK 里打包了一份从 Carlinkit 固件里提取的配件证书/私钥，
  这是它能离线工作的关键，也是它无法合规大规模分发的原因。

## 2. 仓库结构（Gradle 多模块）

| 模块 | 作用 |
| --- | --- |
| `shared/` | **协议核心**。iAP2、Lockdown、NCM、VPN、AirPlay/CarPlay、MFi、媒体、HUD、网络全部在这里。纯 Kotlin + 一个 NDK `Android.mk`（CH341 I2C 与热点广播 JNI）。 |
| `common/` | 共享的 CarPlay 宿主 Activity、设置界面、持久化、诊断导出。`CarPlayHostActivity.kt`(4.6k 行) 与 `DiPlayActivity.kt`(2.9k 行) 是两个最大的 UI 文件。 |
| `mobile/` | 普通 Android 目标，applicationId `com.shihab.diplay`，`assembleStandaloneDebug` 产出可装车机的 APK。 |
| `automotive/` | Android Automotive OS 目标（AAOS），额外做多声道音频映射。 |
| `samples/maphost`、`samples/home` | 给开发者看的示例：地图宿主、DiPlay Home 桌面。 |

代码量：约 66k 行 Kotlin（含测试），其中 main 约 46k 行；437 个 `.kt/.java` 文件；
测试文件 189 个（JVM/Robolectric 单测为主）。

## 3. 核心实现原理

### 3.1 一条 CarPlay 会话的完整链路

有线（USB）路径，从下到上：

```
iPhone
  │ USB (Apple VID 0x05ac)
  ▼
IphoneUsbHost        打开 USB、发 Apple vendor request 0x52 让 iPhone 重新枚举为 CarPlay 配置 6
  ▼
Iap2UsbMuxHost       USBMUX v2 帧（类似 TCP over USB），连到 Lockdown 端口
  ▼
LockdownPlistChannel / LockdownTlsEngineFactory / LockdownPairingClient
                     Apple Lockdown 配对（plist over TLS），拿到配对记录
  ▼
Iap2LinkEngine       iAP2 链路层：检测→协商(SYN/ACK)→NORMAL，分片/重传/流控
  ▼
Iap2CsmChannel / Iap2Session   iAP2 CSM 会话层，按 endpoint id 收发消息体
  ▼
Iap2IdentificationClient       发 Identification（0x1d00/0x1d01…），声明自己是 CarPlay 配件
  ▼
AirPlaySession       TCP :7000 上的 CarPlay/AirPlay 控制通道（RTSP 风格）
  ├── PairSetup  (SRP-6a，固定 PIN 3939)
  ├── PairVerify (X25519 + Ed25519，派生控制通道密钥)
  ├── /auth-setup (MFiSapAuthSetup，向手机证明 MFi 授权)
  ├── /info       (AirPlayInfoPlist，声明屏幕/音频/HID/资源能力)
  ├── SETUP/RECORD/TEARDOWN，开各条媒体流
  └── 加密事件通道（HID 触摸/旋钮/媒体键下发）
  ▼
ScreenStream / AudioStream / CarPlayMediaEngine → AndroidMediaSink → MediaCodec/AudioTrack
```

无线路径多两步：

1. `CarPlayController.startWireless()` → `WirelessHotspotManager` 起热点
   （Wi-Fi Direct / 车机自带热点 / LocalOnlyHotspot / 现有 Wi-Fi 四种后端，
   枚举为 `WirelessHotspotBackend`；注意 `LOCAL_ONLY_HOTSPOT` 仍存在于底层与旧设置迁移，
   但 0.2.11 的 UI 已把它移除，`WirelessHotspotMode` 只暴露后三种），
   拿 SSID/密码/BSSID/频道/本机地址。
2. 通过 **蓝牙 RFCOMM**（`BluetoothRfcommDuplexStream`）先跑一遍 iAP2 Identification，
   用 `0x5703` 把 Wi-Fi 凭据发给 iPhone，用 `0x4301` 告诉 iPhone 连接地址/端口；
   iPhone 自动加入网络后，再回到上面的 AirPlay 控制通道。

### 3.2 三种"身份/认证"机制（最容易踩坑的地方）

CarPlay 有三层互不相同的认证，DiPlay 分别实现：

| 层 | 类 | 算法 | 说明 |
| --- | --- | --- | --- |
| AirPlay 配对 | `PairSetup` | SRP-6a（固定 PIN `3939`）+ HKDF-SHA512 + ChaCha20-Poly1305 | 用固定 PIN 做"无认证"配对，交换长期 Ed25519 公钥并持久化到 `PairingStore`。 |
| AirPlay 验证 | `PairVerify` | X25519 临时密钥 + Ed25519 签名 | 每次连接验证控制器，派生控制通道读写密钥（`ControlCipher`）。 |
| MFi 授权 | `MfiSapAuthSetup` | X25519 共享密钥 → AES-128-CTR，对挑战做 ECDSA 签名 | 向手机证明"我是合法 MFi 配件"。这是**必须**的一步。 |

MFi 层代码里有 4 种后端（`MfiTarget`）；车机设置界面
（`CarPlayHostActivity.buildMfiTargetSection()`）默认只给出前两项，
`I2C`/`REMOTE` 的高级字段仍在代码中（`mfiI2cFields`/`mfiRemoteFields`）：

- `LOCAL`：**DiPlay 默认**。从 APK assets 里的 `offline-mfi/identity.pk8` +
  `certificate.p7b` 加载一份 P-256 私钥/证书（`LocalMfiAuthenticationClient`），
  在软件里直接签名，完全不需要硬件。首次选择 LOCAL 时由 `DiPlayBootstrap.ensure()`
  把 assets 解包到 `noBackupFilesDir/offline-mfi` 并校验私钥与证书匹配。
- `USB_CH341`：通过 CH341 USB-I2C 桥接芯片接真实 MFi 协处理器
  （`Ch341UsbHost` / `Ch341I2cTransport` / `MfiAuthenticationClient`）。
- `I2C`：直接走板载 `/dev/i2c-N`（`LinuxI2cTransport`，NDK）。
- `REMOTE`：把签名外包给远端 HTTP 服务（`RemoteMfiAuthenticationClient`）。

`MfiAuthenticationClient` 里的寄存器序列（0x02 版本 / 0x10 控制 / 0x20 挑战 /
0x30 证书）来自 LIVI，是标准 MFi 协处理器 I2C 协议。

### 3.3 AirPlay/CarPlay 协议关键点

- **控制通道**：TCP :7000，RTSP 风格文本帧（`RtspMessage`），body 是 Apple 二进制 plist
  （自研 `BplistCodec`）。pair-verify 之后整条通道用 `ControlCipher` 加密。
- **`/info`（`AirPlayInfoPlist`）**：必须完整声明显示能力（主屏 + 仪表屏）、音频格式/时延、
  CarPlay 资源模式（screen/audio）、HID 设备（触摸/旋钮/媒体/电话），否则 iPhone 直接断开。
  这是"能连上"和"连不上"的分水岭。
- **视频流**：`ScreenStream` 监听一个 TCP 数据口，每帧 128 字节头 + body；
  body 是 ChaCha20-Poly1305 加密的帧，密钥是 DataStream 输出密钥，nonce 是 8 字节小端计数器；
  解密后把长度前缀 NAL 转成 Annex B，交给 MediaCodec。
- **音频流**：`AudioStream` 接收 RTP，按 `AudioStreamId(type, audioType)` 分流
  （媒体/导航/电话/语音识别等映射到不同 Android AudioChannel）。
- **HID 输入**：触摸坐标归一化后由 `AirPlayHid` 编码成报告，经加密事件通道下发。
  旋钮/方向盘键走 `AirPlayKnobState`。
- **仪表屏（cluster）**：CarPlay 支持第二块屏（stream type 111）。DiPlay 在 `/info` 里
  声明一块仪表尺寸的屏，`initialURL=maps:/car/instrumentcluster/map`，让 iPhone 自己渲染
  地图，再通过 `showUI`/`stopUI`/`forceKeyFrame` 控制（见 `docs/BYD_NAVIGATION.md`）。
- **Video in car**：iOS 27 的"停车时车机播视频"（`VideoInCar`），需要先在 SETUP 里
  协商 feature，再通过事件通道发 `setVideoPlaybackAllowed`。

### 3.4 媒体落地

`CarPlayMediaEngine`（`AirPlayMediaHandler` 实现）把解密后的视频/音频/iAP2 数据流
交给 `MediaSink`；`AndroidMediaSink` 用 `MediaCodec` 硬解 H.264/H.265 上屏，
用 `AudioTrack` 播放，并做音频焦点协调（`AudioFocusCoordinator`）、
声道映射（`AudioChannelMapping`）、Opus 编码回传麦克风（`OpusEncoder`/`MicrophoneUplink`）。

## 4. 构建与运行要点（二次开发前必读）

```sh
# 纯源码/CI 构建（不含配件身份，不能真正连 iPhone）
./gradlew :shared:testDebugUnitTest :common:testDebugUnitTest :mobile:assembleDebug

# 可上车的独立测试包（需要显式提供配件身份目录）
DIPLAY_AUTH_ASSETS_DIR=/abs/path/to/assets ./gradlew :mobile:assembleStandaloneDebug
```

- 工具链：JDK 25、Android SDK 37、NDK `28.2.13676358`、AGP 9.3.0、Kotlin 2.2.10。
- **签名/身份都不在仓库里**。`DIPLAY_AUTH_ASSETS_DIR` 目录必须恰好包含
  `offline-mfi/identity.pk8` 与 `offline-mfi/certificate.p7b`，
  `mobile/build.gradle.kts` 里有 `rejectBundledCredentials` 校验任务会拒绝其它凭证文件。
- Release 签名走环境变量 `ANDROID_KEYSTORE_PATH/PASSWORD`、`ANDROID_KEY_ALIAS/KEY_PASSWORD`。
- 发布 APK 里的配件身份是可被提取的，仅适合实验/社区测试。

## 5. 给二次开发的建议切入点

1. **先确定你的目标**：只改 UI/设置 → 动 `common/DiPlayActivity.kt`；
   改连接逻辑 → `orchestration/CarPlayController.kt`（2505 行，是总装配点）；
   改协议 → `shared/.../airplay/` 与 `iap2/`。
2. **不要在 main thread 做 USB/I2C**：所有阻塞 I/O 都跑在
   `CarPlayController` 的单线程 executor 上，新代码沿用这个约定。
3. **加测试**：`shared/src/test` 有 189 个用例覆盖编解码、协议、状态机。
   协议改动务必补 `*CodecTest`/`*Test`。
4. **身份是可替换的**：`MfiTarget` 抽象让你可以换成自己合法的 MFi 芯片
   （`USB_CH341`/`I2C`），或自建远程签名服务（`REMOTE`），避免依赖那份提取的证书。
5. **关注 `CarPlayRuntimeConfig`**：所有部署常量（USB 设备 ID、MFi 目标、
   热点模式、识别信息）都从这里注入，没有硬编码的 Apple/CH341 产品 ID。

## 6. 法律与合规提示

- 代码：GPL-3.0（`shared`/`mobile`/`automotive`）+ AGPL-3.0（UI/网站）。修改分发需保留声明。
- 打包在预览 APK 里的配件身份来自 Carlinkit 固件，**不是** Apple 为 DiPlay 签发的，
  且私钥可提取——不适合正式产品分发。
- `shared/src/main/assets/byd-hud-icons` 来自 BYDMate，是 PolyForm 非商业授权。
- CarPlay 及相关图标是 Apple 商标。项目明确声明与 Apple/BYD 无关联、无背书。
