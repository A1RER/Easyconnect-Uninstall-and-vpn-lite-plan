# 从 Sangfor EasyConnect 迁移到 zju-connect：故障排查与技术分析

> 环境：Windows 11，某高校校园内网（Sangfor SSL VPN）。
>
> **占位符说明**：下文中的 `vpn.your-school.edu.cn` 是占位符，实际部署请替换为你学校 VPN 网关的真实域名。

---

## 目录

1. [背景](#1-背景)
2. [故障现象](#2-故障现象)
3. [根因分析](#3-根因分析)
4. [涉及的计算机网络知识](#4-涉及的计算机网络知识)
5. [修复演进路径](#5-修复演进路径)
6. [当前使用模式](#6-当前使用模式)
7. [安全考量](#7-安全考量)
8. [后续改进方向](#8-后续改进方向)

---

## 1. 背景

学校使用 Sangfor（深信服）的 SSL VPN 方案，终端通过 EasyConnect 客户端接入 `vpn.your-school.edu.cn`。最早几个月工作正常，后陆续出现无法连接、升级后复发等问题，经两轮修复后最终决定放弃原装客户端，改用开源的 zju-connect。

---

## 2. 故障现象

| 时间 | 症状 |
|---|---|
| 初次 | EasyConnect 启动后点"登录"报 **Request error**，重装无效 |
| 修复后 | 工作数周，学校 VPN 服务端升级后再次出现"异常"提示 |
| 最终 | 客户端被迫升级到新版后完全打不开，放弃原装 |

---

## 3. 根因分析

### 3.1 Sangfor EasyConnect 架构

```
┌─────────────────────────────────────────────────────┐
│ EasyConnect.exe (UI)                                │
│  └─ 内嵌 miniblink (Chromium 裁剪版浏览器)           │
│      │                                              │
│      │ XHR (HTTPS)                                  │
│      ▼                                              │
│  ECAgent.exe (本地守护)                              │
│  └─ 监听 127.0.0.1:54530 / 54533 (HTTPS)             │
│  └─ 处理：SelectLines / rclist / conf 等 API         │
│      │                                              │
│      │ 隧道建立后                                    │
│      ▼                                              │
│  SangforCSClient.exe → vpn.your-school.edu.cn:443     │
└─────────────────────────────────────────────────────┘
```

关键点：**EasyConnect UI 不直接连 VPN 服务器，它先调用本地 ECAgent 的 HTTPS API**。这一步看似多余，实际是为了把复杂的协议逻辑封装到守护进程里。但也正是这一步引入了证书信任的核心问题。

### 3.2 根因一：本地 HTTPS 自签名证书信任断裂

- ECAgent 的 `127.0.0.1:54530` HTTPS 服务使用 **Sangfor 自签名证书**
  - 主体：`CN=127.0.0.1`
  - 颁发者：`OU=Sangfor Technologies Inc., O=Sangfor Technologies Inc., C=CN`
  - 指纹 (SHA1)：`a9062c5c1721ff87ebcbd89df03719755560e7a0`
  - 有效期：**2017-04-27 至 2117-04-03（100 年）**
- miniblink 作为内嵌浏览器，**默认不信任系统根证书库之外的任何自签名 CA**
- miniblink 发起 XHR 调用 `https://127.0.0.1:54533/...` 时，TLS 握手因证书不信任被直接拒绝
- UI 层看到的是网络请求失败 → "Request error"

**诊断关键线索**：ECAgent 自身日志里完全看不到 SelectLines 等请求。说明请求根本没到达 HTTP 层，而是在 TLS 握手阶段就被 miniblink 拒了。

### 3.3 根因二：Windows 两级证书库

Windows 的 **Root 证书库**分两层：

| 层级 | 工具 | 作用域 | 权限 |
|---|---|---|---|
| 用户级 (HKCU) | `certmgr.msc` / `certutil -user` | 当前 Windows 用户 | 普通 |
| 机器级 (HKLM) | `certlm.msc` / `certutil` | 所有用户/所有进程 | **管理员** |

- EasyConnect 旧版（7.6.7.9）的 miniblink 读用户级即可
- 新版（7.6.7.201 及以后）的 miniblink 改成读机器级
- **症状**：用户级修复后，客户端升级一次就失效

### 3.4 根因三：ECAgent 自动导入机制形同虚设

- ECAgent 每 5 分钟尝试把自身根 CA 写入 **Firefox NSS 证书库**（`insert cert failed`）
- 没装 Firefox 的机器永远失败 —— 这个机制对非 Firefox 用户无效

### 3.5 根因四：服务端强制升级客户端

- `vpn.your-school.edu.cn` 服务端升级后会强制客户端升级（7.6.7.9 → 7.6.7.201 → M7.6.8R2）
- 每次升级会重建 ECAgent 安装目录与证书
- **修复过的证书信任被覆盖 → 问题复发 → 又要重新修**

---

## 4. 涉及的计算机网络知识

### 4.1 PKI 与信任链

- **X.509 证书**：包含主体、公钥、颁发者、有效期、签名
- **信任根（Trust Anchor）**：系统/浏览器预置的根 CA 集合，决定哪些证书"天然可信"
- **自签名证书**：主体 = 颁发者，必须显式导入信任根才被接受
- **风险面**：任何被信任的根 CA 理论上都能签发 `*.google.com`、`*.github.com` 等任意域名证书，从而实施 MITM。所以 Sangfor 的 100 年根 CA 一旦导入，其生命周期内都是一个潜在攻击面

### 4.2 TLS / HTTPS

- **证书校验流程**：主体名匹配 → 签名校验 → 信任链回溯到某个根 CA → 有效期/吊销检查
- **本地回环 HTTPS 的特殊性**：`https://127.0.0.1` 需要证书主体名（CN/SAN）能匹配 `127.0.0.1`；但信任仍需走常规流程，miniblink 没放行例外

### 4.3 SSL VPN 的两种数据面

| 模式 | 说明 | 优点 | 缺点 |
|---|---|---|---|
| TUN / 全隧道 | 创建虚拟网卡，L3 包捕获 | 对应用透明 | 需驱动/管理员，冲突可能 |
| 用户态 SOCKS5 | 只在应用层代理 | 轻量、零权限 | 需应用手动指定代理 |

EasyConnect 走 TUN 模式（装驱动），zju-connect 默认走用户态 SOCKS5。

### 4.4 代理协议辨析

| 协议 | 层级 | 特点 |
|---|---|---|
| HTTP 正向代理 | L7 | 只能代理 HTTP |
| HTTP CONNECT | L7 (隧道) | 任意 TCP（HTTPS 常用） |
| SOCKS5 | L5 | 任意 TCP/UDP，支持域名模式 (`socks5h`) |
| SOCKS5h | L5 | **DNS 在代理端解析**（对 VPN 关键） |

注意 `curl -x socks5://` 和 `curl -x socks5h://` 的区别：前者本地解析域名，后者远端解析。访问校内域名必须用 socks5h，否则本地 DNS 查不到内网域名。

### 4.5 DNS 分流

- **本地 DNS**：用户设的 DNS（114.114.114.114 / 系统默认）
- **远程 DNS**：VPN 服务器提供的内网 DNS（能解析 `jwzx.your-school.edu.cn` 这类仅内网暴露的服务）
- `zju-connect -disable-zju-dns` 因为是非浙大用户，不应使用 ZJU 预设的 DNS 劫持规则

### 4.6 进程隔离思路

`docker-easyconnect` 的价值：把闭源、不可信的 VPN 客户端关进容器，容器只对外暴露 SOCKS5 端口。宿主机零侵入。代价是需要 WSL2 + Docker Desktop，对网络栈有影响。

### 4.7 逆向开源的价值

`lyc8503/EasierConnect`（已删库）和 `mythologyli/zju-connect` 对 Sangfor 协议进行了逆向，用 Go 重写了客户端。优点：
- 协议层可审计
- 单二进制，无驱动/服务
- 不依赖 ECAgent，绕开了所有证书信任问题
- 支持 Sangfor EasyConnect + aTrust 双协议

---

## 5. 修复演进路径

### 阶段 1：证书库修复（短期见效，长期失败）

**操作**：从 `ECAgent.exe` 二进制里提取 Sangfor 根 CA（PEM 前缀 `MIIEYDCCA0ig`），转 DER，导入 Windows Root 证书库。

```bash
# 1. 提取根 CA
strings "C:\Program Files (x86)\Sangfor\SSL\ECAgent\ECAgent.exe" \
  | grep -A100 "MIIEYDCCA0ig" | sed '/END CERTIFICATE/q' > sangfor_root.pem
openssl x509 -in sangfor_root.pem -outform DER -out sangfor_root.der

# 2. 同时导入用户级和机器级
certutil -user -addstore "Root" sangfor_root.der
certutil      -addstore "Root" sangfor_root.der   # 管理员

# 3. 重启 EasyConnect
powershell -command "Stop-Process -Name 'EasyConnect','ECAgent','SangforCSClient' -Force; Start-Process 'C:\Program Files (x86)\Sangfor\SSL\EasyConnect\EasyConnect.exe'"
```

**效果**：立即修复，但每次 EasyConnect 自升级都会失效。

### 阶段 2：方案对比

| 方案 | 零侵入 | 无依赖 | 长期稳定 | 对 v2rayN 影响 | 结论 |
|---|---|---|---|---|---|
| 原装 EasyConnect | ❌ | ✅ | ❌ (升级必坏) | 无 | 放弃 |
| docker-easyconnect | ✅ (容器内) | ❌ (需 WSL2+Docker) | ✅ | ⚠ 安装时动网络栈 | 不推荐 |
| lyc8503/EasierConnect | ✅ | ✅ | ❌ (项目 404) | 无 | 不可用 |
| **mythologyli/zju-connect** | ✅ | ✅ | ✅ | 无 | **选定** |

### 阶段 3：安全清理

之前为了让 EasyConnect 工作导入的 Sangfor 根 CA **必须清除**，否则即使不用 EasyConnect，那张 100 年有效期的证书仍在系统信任列表中，是持续 MITM 风险。

```bash
# 用户级
certutil -user -delstore "Root" "a9062c5c1721ff87ebcbd89df03719755560e7a0"

# 机器级（UAC 提权）
powershell -Command "Start-Process certutil -ArgumentList '-delstore','Root','a9062c5c1721ff87ebcbd89df03719755560e7a0' -Verb RunAs -Wait"

# 验证两库均已无该证书
certutil -user -store "Root" "a9062c5c1721ff87ebcbd89df03719755560e7a0"
certutil      -store "Root" "a9062c5c1721ff87ebcbd89df03719755560e7a0"
```

### 阶段 4：部署 zju-connect

**下载 + 校验**

```bash
# 从 GitHub Release 获取
URL="https://github.com/Mythologyli/zju-connect/releases/download/v1.0.0/zju-connect-windows-amd64.zip"
powershell -Command "Invoke-WebRequest -Uri '$URL' -OutFile 'zju-connect-windows-amd64.zip' -UseBasicParsing"

# SHA256 校验（官方 digest，从 GitHub API 获取）
certutil -hashfile zju-connect-windows-amd64.zip SHA256
# 预期：55f8ddb6f0971676e984ff68bb3476c1b5a07a64dcff0dc0bd402efef1efa68b

# 解压
powershell -Command "Expand-Archive -Path 'zju-connect-windows-amd64.zip' -DestinationPath '.' -Force"
```

**交叉验证（VirusTotal）**
```
https://www.virustotal.com/gui/file/55f8ddb6f0971676e984ff68bb3476c1b5a07a64dcff0dc0bd402efef1efa68b
```

**启动脚本**（`start.bat`）关键参数：

```bat
zju-connect.exe ^
  -server vpn.your-school.edu.cn ^
  -username <学号> ^
  -password <密码> ^
  -socks-bind 127.0.0.1:1180 ^
  -http-bind 127.0.0.1:1181 ^
  -disable-zju-config ^
  -disable-zju-dns ^
  -skip-domain-resource ^
  -keep-alive-url http://vpn.your-school.edu.cn/
```

**参数解释**：

| 参数 | 作用 | 为什么需要 |
|---|---|---|
| `-disable-zju-config` | 不加载 ZJU 预设配置 | 非浙大用户必须关 |
| `-disable-zju-dns` | 不用 ZJU 远程 DNS | 改用本地 DNS，避免解析错域名 |
| `-skip-domain-resource` | 不按 ZJU 域名规则分流 | 非浙大用户必须关 |
| `-keep-alive-url` | 定期 ping 该 URL | 防止空闲会话被服务端踢掉 |

---

## 6. 当前使用模式

### 6.1 组件拓扑

```
┌──────────────────────────────────────────────────────────────┐
│                       Windows 11 Host                         │
│                                                               │
│  ┌──────────────┐        ┌────────────────────────┐          │
│  │ v2rayN       │        │ zju-connect            │          │
│  │ 10808 SOCKS5 │        │ 1180 SOCKS5            │          │
│  │ 10809 HTTP   │        │ 1181 HTTP              │          │
│  └──────┬───────┘        └────────────┬───────────┘          │
│         │ (系统代理)                    │                      │
│         │                              │                      │
│  ┌──────┴────────┐  ┌────────────┐  ┌─┴──────────────────┐   │
│  │ Chrome        │  │ 联想浏览器  │  │ 内网 Edge         │   │
│  │ (国外站点)    │  │ (国内直连)  │  │ (校内网 via SOCKS5)│   │
│  └───────────────┘  └────────────┘  └────────────────────┘   │
│                                                               │
│  ┌──────────────┐                                             │
│  │ CLI          │── 系统代理 ──> v2rayN ──> 国外             │
│  │ (Claude Code)│                                             │
│  └──────────────┘                                             │
└──────────────────────────────────────────────────────────────┘
          │                 │                   │
          ▼                 ▼                   ▼
    国外代理节点        国内 CDN           vpn.your-school.edu.cn
                                                │
                                                ▼
                                            校内资源
```

### 6.2 端口与进程分配

| 程序 | 监听 | 用途 | 常开 |
|---|---|---|---|
| v2rayN | 127.0.0.1:10808 / 10809 | 科学上网 | ✅ 开机自启 |
| zju-connect | 127.0.0.1:1180 / 1181 | 校内网接入 | 按需启动 (`start.bat`) |

两者端口不冲突，可同时运行。

### 6.3 浏览器分流

| 浏览器 | 配置 | 用途 |
|---|---|---|
| 联想浏览器 | 直连 | 国内网站 |
| Chrome | 系统代理 (v2rayN 10808) | 国外网站 |
| **内网 Edge** | 命令行参数 `--proxy-server="socks5://127.0.0.1:1180"` | 校内网 |

**内网 Edge 快捷方式**：
```
Target:    C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe
Arguments: --proxy-server="socks5://127.0.0.1:1180" --user-data-dir="%LOCALAPPDATA%\Edge-Intranet"
```

`--user-data-dir` 指定独立的用户数据目录，避免干扰日常 Edge 的书签和登录状态。

### 6.4 卸载清理清单

```
# 当前方案卸载
- 删除 D:\easierconnect\
- 删除 C:\Users\<User>\Desktop\内网 Edge.lnk
- 删除 %LOCALAPPDATA%\Edge-Intranet\

# 旧版 EasyConnect 残留（如还未清理）
- 删除 C:\Program Files (x86)\Sangfor\
- 删除 %APPDATA%\Sangfor\
- 证书已在本次迁移中清除，无需再处理
```

---

## 7. 安全考量

| 维度 | 原装 EasyConnect | 当前 zju-connect |
|---|---|---|
| 代码可见性 | 闭源 | Go 开源，可审计/可自编译 |
| 宿主侵入 | 驱动、注册表、证书库、开机自启 | 单 exe，零侵入 |
| 信任链 | 被迫信任 Sangfor 根 CA (100 年) | 已清除，信任根未被污染 |
| 凭据流向 | → Sangfor 服务器 | → Sangfor 服务器（**相同**） |
| 二进制来源 | 从学校下载，无 hash 校验 | 从 GitHub Release，SHA256 校验 |
| 监听范围 | 本地多端口 + TUN 网卡 | 只 127.0.0.1:1180/1181 |
| 社区审查 | 无 | 4k+ star，多人 fork 对照 |

**剩余风险**：
- VPN 账号密码明文存在 `start.bat` —— 本机单用户环境可接受，共享机器需改进
- zju-connect 作者理论上可能注入恶意代码 —— 缓解措施：固定版本 + SHA256 + VirusTotal 交叉验证 + 可自编译

---

## 8. 后续改进方向

- [ ] 用 TOTP 或启动时手动输入替代明文密码
- [ ] 监听 Windows 网络变化事件，切网后自动重连
- [x] GUI wrapper：任务栏图标 + 一键连断 + 状态展示（`gui/IntranetVpn.jar`，VPN 地址运行时输入，持久化至 `vpn.conf`）
- [ ] 自动化更新：定期检查 zju-connect 新版本 + SHA256 校验

---

## 参考资料

- [mythologyli/zju-connect](https://github.com/mythologyli/zju-connect) —— 当前使用的客户端
- [lyc8503/EasierConnect](https://github.com/lyc8503/EasierConnect) —— 协议逆向原作（已删库）
- [Hagb/docker-easyconnect](https://github.com/Hagb/docker-easyconnect) —— 容器隔离方案
- [SwitchyOmega](https://github.com/FelisCatus/SwitchyOmega) —— 浏览器代理分流扩展
