# 彻底卸载 Sangfor EasyConnect：Windows 完整清理指南

> 环境：Windows 10 / 11。命令在**管理员权限**的 PowerShell 或 cmd 中运行。
> 本文档记录一次真实卸载过程中触及的所有残留点，按优先级组织为可执行步骤。

---

## 0. 为什么需要这篇文章

EasyConnect 的官方卸载器（`EasyConnectUninstaller.exe`）只是冰山一角。实测它即使带 `/S` 参数静默运行完毕，系统里仍会残留：

- 2 个仍在运行的 Windows 服务（`SangforPWEx`、`SangforSP`）
- 1 个已注册的内核驱动（`SangforVnic`，虚拟网卡）
- 约 18 个子组件目录（`ECAgent` / `CSClient` / `DnsDriver` / `TcpDriver` / ...）
- 1 张 **100 年有效期** 的 Sangfor 自签根证书（`NotAfter=2117`）
- 6 条防火墙规则、若干 Prefetch、SysWOW64 DLL、Temp 安装残件
- 3 处非标 Sangfor 目录（`ProgramData`、`Public`、`Roaming`）

这些残留不会主动造成故障，但：

1. **安全面显著增大**：一张你不信任的根 CA 可以在 92 年内对任意 HTTPS 做 MITM。
2. **驱动污染**：虚拟网卡驱动仍在 `pnputil` 驱动库里，将来系统更新或重装 EasyConnect 会把同样的版本拉起来。
3. **洁癖**：系统里没有活的 Sangfor 组件，却一直躺着 20 个目录。

本文按"服务 → 进程 → 驱动 → 文件系统 → 注册表 → 证书 → 次要残留"的顺序清理，每步附验证命令。

---

## 1. 盘点：看看你的机器上到底有什么

先别急着删。下面这组命令不改任何东西，只是盘点。把输出保存下来，方便后续逐项核对。

```powershell
# 1. 运行中的 Sangfor 进程
tasklist | findstr /i "sangfor easyconn ecagent"

# 2. 所有 Sangfor 相关服务（含驱动）
sc query state= all | findstr /i "SERVICE_NAME" | findstr /i "sangfor svpn ecagent"
sc query type= driver state= all | findstr /i "SERVICE_NAME" | findstr /i "sangfor svpn"

# 3. 安装目录
dir "C:\Program Files (x86)\Sangfor" 2>$null
dir "C:\ProgramData\Sangfor" 2>$null
dir "C:\Users\Public\Sangfor" 2>$null
dir "$env:APPDATA\Sangfor" 2>$null

# 4. 注册表
reg query "HKLM\SOFTWARE\Sangfor" /s 2>$null
reg query "HKLM\SOFTWARE\WOW6432Node\Sangfor" /s 2>$null
reg query "HKCU\SOFTWARE\Sangfor" /s 2>$null

# 5. 证书：受信任根证书颁发机构中的 Sangfor
certutil -store Root | findstr /i sangfor
certutil -user -store Root | findstr /i sangfor

# 6. 驱动包
pnputil /enum-drivers | findstr /B /C:"原始名称" /C:"提供程序名称" | findstr /i "sangfor svpn vnic"

# 7. 防火墙
netsh advfirewall firewall show rule name=all | findstr /i "easyconn sangfor"

# 8. 启动项、计划任务
reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" | findstr /i sangfor
reg query "HKLM\Software\Microsoft\Windows\CurrentVersion\Run" | findstr /i sangfor
schtasks /query /fo list | findstr /i "sangfor easyconn"
```

---

## 2. 官方卸载器先跑一遍（可跳过，但推荐）

```cmd
"C:\Program Files (x86)\Sangfor\SSL\EasyConnect\EasyConnectUninstaller.exe" /S
```

`/S` 是 NSIS 的静默参数。实测它会清掉 `ECAgent` / `SangforPromote` 的主进程和部分子组件，但**留下** `SangforPWEx` 服务、虚拟网卡驱动、绝大多数目录。当作"减少一些后续手动清理工作量"即可。

---

## 3. 停进程、停服务、删服务

### 3.1 停用户态服务

服务名可能因版本而异，先按盘点结果来。下面列一组常见名：

```cmd
sc stop SangforPWEx
sc stop SangforSP
sc stop SangforUDProtectEx
sc stop SangforPromoteService
sc stop SangforServiceClient
```

等几秒让 `STOP_PENDING` 完成，再强杀残余进程：

```cmd
taskkill /F /IM SangforPWEx.exe /IM SangforUDProtectEx.exe ^
  /IM SangforPromote.exe /IM SangforPromoteService.exe ^
  /IM ECAgent.exe /IM EasyConnect.exe ^
  /IM SangforCSClient.exe /IM SangforServiceClient.exe
```

然后删除服务注册：

```cmd
sc delete SangforPWEx
sc delete SangforSP
sc delete SangforUDProtectEx
sc delete SangforPromoteService
sc delete SangforServiceClient
```

不存在的服务会报 `OpenService 失败 1060`，无害，忽略即可。

### 3.2 停内核驱动服务

虚拟网卡由 `SangforVnic` 内核驱动支撑。它不一定在运行（卸载器可能已卸载），但服务注册几乎都还在：

```cmd
sc stop SangforVnic
sc delete SangforVnic
```

若 `sc stop` 返回"服务未启动"，说明驱动未被加载到内核——可直接 `delete`，不需要重启。

---

## 4. 卸载虚拟网卡驱动包

服务删除只是摘除了引用，驱动文件本身还在 `pnputil` 驱动库里。列出找到对应的 `oem##.inf`：

```cmd
pnputil /enum-drivers
```

输出里找提供方为 `Sangfor.inc` 或原始名称为 `sangforvnic.inf` 的条目，记住它的 **发布名称**（形如 `oem13.inf`，编号因机器而异）。然后：

```cmd
pnputil /delete-driver oem13.inf /uninstall /force
```

- `/uninstall`：从使用该驱动的设备上反注册
- `/force`：即使有设备仍在引用也强制删除（虚拟网卡多数时候已拔除，这条是保险）

最后删磁盘上的 `.sys` 文件（`sc delete` 不会删它）：

```cmd
del /F "C:\Windows\System32\drivers\SangforVnic.sys"
```

---

## 5. 删目录

按路径逐个删。用 `rd /s /q`（或 PowerShell 的 `Remove-Item -Recurse -Force`）：

```cmd
rd /s /q "C:\Program Files (x86)\Sangfor"
rd /s /q "C:\ProgramData\Sangfor"
rd /s /q "C:\Users\Public\Sangfor"
rd /s /q "%APPDATA%\Sangfor"
```

注意：`%APPDATA%` 展开的是**当前用户**的 Roaming。多用户环境下每个用户都要清一遍。

### 5.1 Temp 残留

EasyConnect 安装/升级过程会在 `%TEMP%` 留下 CAB 和 Installer，即便卸载也不清：

```cmd
del /F "%TEMP%\1_*Sangfor*.CAB"
del /F "%TEMP%\1_*Sangfor*Installer*.exe"
del /F "%TEMP%\1_EasyConnectUIInstaller.exe"
del /F "%TEMP%\1_ECAgentInstaller.exe"
del /F "%TEMP%\SangforUD.exe"
del /F "%TEMP%\sangfor_*.pem"
del /F "%TEMP%\sangfor_*.der"
del /F "%TEMP%\ecagent_*.pem"
```

### 5.2 SysWOW64 中的 DLL

某些版本会把帮助 DLL 拷贝到 `SysWOW64`（含带下划线的旧版本备份）：

```cmd
del /F "C:\Windows\SysWOW64\SangforInstallHelper.dll*"
del /F "C:\Windows\SysWOW64\SangforVpnLibeay32.dll*"
del /F "C:\Windows\SysWOW64\SangforVpnSsleay32.dll*"
```

### 5.3 Prefetch

```cmd
del /F "C:\Windows\Prefetch\EASYCONNECT*.pf"
del /F "C:\Windows\Prefetch\ECAGENT*.pf"
del /F "C:\Windows\Prefetch\SANGFOR*.pf"
```

这些重启后会自动重建，但只要不再运行对应进程，就不会再生成。

---

## 6. 清注册表

官方卸载器通常会清主键，但为了稳妥再检查一遍：

```cmd
reg delete "HKLM\SOFTWARE\Sangfor" /f
reg delete "HKLM\SOFTWARE\WOW6432Node\Sangfor" /f
reg delete "HKCU\SOFTWARE\Sangfor" /f
```

不存在的键返回 `系统找不到指定的注册表项`，忽略。

检查两个应用卸载入口（某些版本不在标准位置）：

```cmd
reg query "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall" /s /f EasyConnect
reg query "HKLM\SOFTWARE\WOW6432Node\Microsoft\Windows\CurrentVersion\Uninstall" /s /f EasyConnect
```

若仍有条目，记下键名单独 `reg delete` 即可。

---

## 7. 删根证书（**重要安全项**）

Sangfor 会在两个位置安装它的自签根 CA：

- `certlm.msc`：本地计算机 → 受信任的根证书颁发机构（机器级）
- `certmgr.msc`：当前用户 → 受信任的根证书颁发机构（用户级）

这张证书：

- 颁发者：`OU=Sangfor Technologies Inc., L=shenzhen`
- SHA-1 指纹通常为：`a9062c5c1721ff87ebcbd89df03719755560e7a0`
- 序列号示例：`dea4d5fa33cf9e9e`
- **有效期：1997—2117**（100 年！一旦这张根 CA 的私钥泄露或被强制接入，证书寿命内你的所有 HTTPS 流量都可能被 MITM）

### 命令行删除

```cmd
:: 机器级
certutil -delstore Root dea4d5fa33cf9e9e

:: 当前用户级
certutil -user -delstore Root dea4d5fa33cf9e9e
```

如果你的证书序列号不同，可以用指纹：

```cmd
certutil -delstore Root a9062c5c1721ff87ebcbd89df03719755560e7a0
```

验证：

```cmd
certutil -store Root | findstr /i sangfor
certutil -user -store Root | findstr /i sangfor
certutil -store CA | findstr /i sangfor
certutil -user -store CA | findstr /i sangfor
```

四条命令都应无输出。

---

## 8. 删防火墙规则

```cmd
netsh advfirewall firewall delete rule name=EasyConnect
netsh advfirewall firewall delete rule name=SangforCSClient
```

名称可能视版本而异，按盘点结果补齐。

---

## 9. 最终扫残

遍历常见位置，排除你有意保留的目录（比如替代方案的工作目录、相关 VM、文档仓库等）。把下面正则里的关键字换成你要保留的路径特征：

```powershell
$keep = 'replace-with-your-keeplist'   # 例：'myvpn|some-vm|some-repo'
Get-ChildItem -Path C:\, D:\ -Recurse -ErrorAction SilentlyContinue `
  -Include *sangfor*,*easyconn*,ECAgent* |
  Where-Object { $_.FullName -notmatch $keep } |
  Select-Object FullName
```

剩下的挨个判断去留。常见漏网类型：

| 类型 | 示例路径 | 处理 |
|---|---|---|
| 下载目录里的安装包 | `<Downloads>\EasyConnectInstaller*.exe` | 删 |
| OEM 应用商店的图标缓存 | `C:\ProgramData\<OEM>\...\*.ico` | 删 |
| Roaming 根目录下的残留文本 | `%APPDATA%\ECAgent.txt` | 删 |

---

## 10. 是否需要重启

**不需要**，满足以下条件即可：

- `sc stop SangforVnic` 时返回"服务未启动"（驱动未加载到内核）
- 所有 `taskkill` 后没有残存进程
- `pnputil /delete-driver /uninstall /force` 输出"已成功删除驱动程序包"

如果 `sc stop` 曾报告需要重启、或 `pnputil` 提示有设备仍在使用，建议重启一次再做第 9 步扫残。

---

## 11. 完整验收清单

```cmd
:: 应全部无输出或"找不到"
tasklist | findstr /i "sangfor easyconn ecagent"
sc query state= all | findstr /i "sangfor svpn"
pnputil /enum-drivers | findstr /i "sangfor svpn"
dir "C:\Program Files (x86)\Sangfor" 2>nul
dir "C:\ProgramData\Sangfor" 2>nul
reg query "HKLM\SOFTWARE\Sangfor" 2>nul
certutil -store Root | findstr /i sangfor
certutil -user -store Root | findstr /i sangfor
netsh advfirewall firewall show rule name=all | findstr /i "easyconn sangfor"
```

九条命令全部"无输出/未找到" = 清理完成。

---

## 12. 然后呢？

如果你因为 EasyConnect 故障而看到这篇文档，建议直接切换到开源方案：

- 本仓库根目录的 [README.md](../README.md)：基于 [Mythologyli/zju-connect](https://github.com/mythologyli/zju-connect) 的轻量化接入
- [JOURNEY.md](JOURNEY.md)：完整的故障根因（miniblink / 双层证书库 / PKI）与迁移决策过程

zju-connect 是单二进制，没有服务、没有驱动、没有根证书、没有开机自启。这篇卸载文的存在本身就说明"装一个校园 VPN 客户端要付出什么代价"。

---

## 附录 A：为什么不用控制面板卸载

控制面板 → 程序和功能 → 卸载 EasyConnect 等价于第 2 步的 `EasyConnectUninstaller.exe` + 图形确认。它的清理范围**和静默参数相同**，不覆盖服务、驱动、证书、Public/ProgramData 下的残留。

## 附录 B：为什么要强制 `/force` 删驱动

`pnputil /delete-driver` 默认拒绝删除"仍在被设备引用"的驱动包。虚拟网卡虽已逻辑拔出，但设备树里可能还有失效引用（尤其经历过服务端升级后的版本变迁）。`/force` 配合 `/uninstall` 是这种场景的标准组合拳。

## 附录 C：证书指纹会不同吗

会。指纹和序列号取决于 Sangfor 在你机器上导入的那张根 CA 的版本。不同 ECAgent 版本内嵌的 CA 可能不同。以本机 `certutil -store Root` 实际输出为准。

---

*撰写：2026-04-19。一次真实的彻底卸载回放。*
