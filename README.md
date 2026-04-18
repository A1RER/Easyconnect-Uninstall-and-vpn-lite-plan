# CQUPT VPN（基于 zju-connect）

重庆邮电大学校园 VPN 的轻量化方案。替代原装 Sangfor EasyConnect：
单二进制、零驱动、零开机自启、不导入根证书。

## 组件

| 名称 | 作用 |
|---|---|
| `zju-connect.exe` | Sangfor SSL VPN 协议客户端（Go 开源，需自行下载） |
| `gui/CquptVpn.jar` | Java Swing 图形前端，封装启动 / 断开 / 日志 / 打开 Edge |
| `scripts/start.bat.example` | 命令行启动模板（复制为 `start.bat` 后填入凭据） |

## 暴露端口

- SOCKS5：`127.0.0.1:1180`
- HTTP：`127.0.0.1:1181`

（两个端口功能等价，按客户端支持度二选一使用）

## 安装

### 1. 获取 zju-connect 二进制

```powershell
$URL = 'https://github.com/Mythologyli/zju-connect/releases/download/v1.0.0/zju-connect-windows-amd64.zip'
Invoke-WebRequest -Uri $URL -OutFile zju-connect.zip -UseBasicParsing
certutil -hashfile zju-connect.zip SHA256
# 预期 SHA256: 55f8ddb6f0971676e984ff68bb3476c1b5a07a64dcff0dc0bd402efef1efa68b
Expand-Archive -Path zju-connect.zip -DestinationPath . -Force
```

交叉验证（VirusTotal，60+ 引擎并扫）：
`https://www.virustotal.com/gui/file/55f8ddb6f0971676e984ff68bb3476c1b5a07a64dcff0dc0bd402efef1efa68b`

### 2. 构建 GUI（可选）

```bat
cd gui
build.bat
```

产物：`gui\CquptVpn.jar`。需要系统安装 JDK 11+。不想构建可跳过，直接用命令行。

### 3. 目录结构（示例）

```
<任意目录>\
├── zju-connect.exe
├── scripts\start.bat          # 由 start.bat.example 复制而来
└── gui\CquptVpn.jar
```

## 运行

### GUI

```bat
javaw -jar gui\CquptVpn.jar
```

或双击 jar。输入学号 / 密码 → `连接`。GUI 会在同级或父级目录下自动查找 `zju-connect.exe`。

### 命令行

```bat
copy scripts\start.bat.example start.bat
:: 编辑 start.bat 中的 VPN_USER / VPN_PASS（或改为运行时 set /p 输入）
start.bat
```

看到 `SOCKS5 server listens on 127.0.0.1:1180` 即连接成功。关闭窗口断开。

## 浏览器分流

| 浏览器 | 配置 | 用途 |
|---|---|---|
| 系统默认 | 直连 | 公网 |
| CQUPT Edge | `--proxy-server="socks5://127.0.0.1:1180" --user-data-dir="%LOCALAPPDATA%\Edge-CQUPT"` | 校内资源 |
| 任意浏览器 + SwitchyOmega | 规则 `*.cqupt.edu.cn → SOCKS5 127.0.0.1:1180` | 按域名自动分流 |

生成独立用户数据目录可避免污染日常 Edge 的书签 / 登录态。

## 启动参数说明

| 参数 | 作用 |
|---|---|
| `-disable-zju-config` | 关闭 ZJU 预设配置（非浙大用户必需） |
| `-disable-zju-dns` | 不使用 ZJU 远程 DNS |
| `-skip-domain-resource` | 跳过 ZJU 域名分流规则 |
| `-keep-alive-url` | 定期请求以保活会话 |

## 卸载

删除整个安装目录即可。未安装任何驱动、服务、证书、注册表项。

## 凭据安全

- `start.bat.example` 默认不包含凭据，使用时需手动填入或改为交互输入
- `.gitignore` 已屏蔽 `*.bat`、`*credentials*`、`*password*`，防止误提交
- GUI 不落盘凭据；密码字段使用后立即置零

## 深入阅读

[docs/JOURNEY.md](docs/JOURNEY.md) —— 完整的故障排查、根因分析（PKI / 自签 CA / miniblink / Windows 双层证书库）、方案对比、网络知识点与安全清理流程。

## 参考

- [mythologyli/zju-connect](https://github.com/mythologyli/zju-connect)
- [Hagb/docker-easyconnect](https://github.com/Hagb/docker-easyconnect)
- [SwitchyOmega](https://github.com/FelisCatus/SwitchyOmega)
