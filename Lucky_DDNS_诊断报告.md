# Lucky DDNS 任务失败诊断报告

**诊断时间**: 2026-07-18
**诊断目标**: unraid (192.168.10.8) 上的 Lucky 容器 DDNS 任务
**域名**: lx.mvpdark.top (Cloudflare, AAAA 记录, 值 {ipv6Addr})

---

## 一、根本原因 (Root Cause)

### 核心结论

**unraid 主机的 DNS 解析器 (192.168.10.2) 运行着 Clash 代理的 fake-ip 模式，该模式对所有 AAAA (IPv6) DNS 查询返回 NODATA (空应答)。这导致 Lucky 无法解析任何 IPv6 公网 IP 检测服务的域名，公网 IPv6 检测失败，{ipv6Addr} 变量为空，最终 DDNS 更新失败。**

### 故障链路

```
Clash (192.168.10.2) fake-ip 模式
  └─> AAAA 查询返回 NODATA (屏蔽 IPv6 DNS)
       └─> Lucky 无法解析 6.ipw.cn / api64.ipify.org 等服务的 IPv6 地址
            └─> Lucky 公网 IPv6 检测失败 → "尚未获取到公网IP"
                 └─> {ipv6Addr} 变量为空
                      └─> DDNS 更新 Cloudflare 失败 → "更新失败"
```

---

## 二、关键证据

### 证据 1: DNS 配置指向 Clash fake-ip DNS

unraid `/etc/resolv.conf`:
```
nameserver 192.168.10.2  # 主 DNS (Clash)
nameserver 192.168.10.1  # 路由器
nameserver fe80::9e9d:7eff:fe7a:fd34%br0
```

容器内 `/etc/resolv.conf` (host 网络模式, 与主机相同):
```
nameserver 192.168.10.2
nameserver 192.168.10.1
nameserver fe80::9e9d:7eff:fe7a:fd34%br0
```

### 证据 2: 192.168.10.2 确认运行 Clash

```
192.168.10.2:7890 -> HTTP 407 (Clash HTTP 代理端口, Proxy Authentication Required)
192.168.10.2:9090 -> HTTP 401 (Clash 外部控制器 API, {"message":"Unauthorized"})
192.168.10.2:80   -> HTTP 200 (Clash 面板/Web 服务)
```

### 证据 3: fake-ip DNS 返回假 IP + 屏蔽 AAAA

A 记录查询 (返回 fake-ip 198.18.x.x, 被 Clash 拦截代理):
```
dig A cloudflare.com @192.168.10.2 -> 198.18.1.122  (fake-ip, 非真实 IP)
dig A google.com    @192.168.10.2 -> 198.18.x.x      (fake-ip)
```

AAAA 记录查询 (全部返回 NODATA, 即 fake-ip 模式屏蔽 IPv6):
```
dig AAAA cloudflare.com @192.168.10.2 -> (空, NODATA)
dig AAAA google.com    @192.168.10.2 -> (空, NODATA)
dig AAAA cloudflare.com @1.1.1.1     -> (空, NODATA, 因 1.1.1.1 也被 Clash 劫持)
dig AAAA cloudflare.com @223.5.5.5   -> (空, NODATA, 同上)
```

对比: 使用真实 IPv6 DNS 服务器时 AAAA 正常:
```
dig AAAA cloudflare.com @2606:4700:4700::1111 -> 2606:4700::6810:85e5, 2606:4700::6810:84e5  (正常!)
dig AAAA cloudflare.com @2400:3200::1         -> 2606:4700::6810:84e5, 2606:4700::6810:85e5  (正常!)
```

### 证据 4: Lucky 容器日志无 DDNS 执行记录

```
2026/07/18 00:32:54 ddns module started
(之后无任何 DDNS 执行/错误日志, 说明任务运行但未记录详细错误到 docker logs)
```
Lucky 版本: 2.20.2, 镜像: gdy666/lucky, 网络模式: host (已确认)

### 证据 5: 主机有公网 IPv6 但检测服务不可达

主机 br0 网卡有公网 IPv6 (中国移动 2408 段):
```
inet6 2408:8240:3254:2ea0::2a2/128 scope global dynamic          (DHCPv6)
inet6 2408:8240:3254:2ea0:2e0:4cff:fed4:3e0/64 scope global      (SLAAC)
inet6 2408:8240:325f:adc0:2e0:4cff:fed4:3e0/64 scope global      (SLAAC)
```

ping6 到纯 IPv6 地址正常 (ICMP 通):
```
ping6 2400:3200::1          -> 10ms  (中国电信 IPv6 DNS, 正常)
ping6 2606:4700:4700::1111  -> 192ms (Cloudflare IPv6 DNS, 正常)
```

但 curl -6 到域名全部失败 (DNS 解析失败):
```
curl -6 https://6.ipw.cn        -> Could not resolve host: 6.ipw.cn (NODATA)
curl -6 https://api64.ipify.org -> Could not resolve host: api64.ipify.org (NODATA)
curl -6 https://api.cloudflare.com -> Could not resolve host (NODATA)
```

### 证据 6: 临时 DNS 修复验证成功 (决定性证据)

将 `/etc/resolv.conf` 临时改为仅使用 IPv6 DNS 服务器后:
```
nameserver 2606:4700:4700::1111
nameserver 2400:3200::1
```

测试结果:
```
dig AAAA api64.ipify.org -> 2607:f2d8:1:3c::3, 2607:f2d8:4010:51::5  (AAAA 解析成功!)
curl -6 https://api64.ipify.org -> 2408:8240:3254:2ea0::2a2          (成功获取公网 IPv6!)
```

**修复 DNS 后, Lucky 的公网 IPv6 检测逻辑即可正常工作, 能获取到公网 IPv6 地址 `2408:8240:3254:2ea0::2a2`。**

### 证据 7: IPv6 TCP 连通性 (部分可用)

```
FAIL 2606:4700:4700::1111:443 timed out     (Cloudflare IPv6 443 端口超时, 国际线路)
OK   2606:4700:4700::1111:53  connected      (Cloudflare IPv6 53 端口正常)
OK   2400:3200::1:443 connected              (中国电信 IPv6 443 端口正常)
OK   2400:3200::1:53  connected              (中国电信 IPv6 53 端口正常)
```

注: 国际 IPv6 TCP (Cloudflare 443) 不稳定, 但国内 IPv6 (中国电信) 正常。api64.ipify.org 解析到的 IPv6 (2607:f2d8::) 可达, 已验证能返回公网 IP。

### 证据 8: Cloudflare API Token 无法验证

`lucky_ddns.lkcf` 配置文件为 AES 加密二进制格式 (无法直接读取 token):
```
file lucky_ddns.lkcf -> data (非文本, 非 gzip, 加密格式)
strings lucky_ddns.lkcf -> (无可读字符串)
```

但 Cloudflare API 经 IPv4 (经 Clash 代理) 可达:
```
容器内 wget https://api.cloudflare.com/cdn-cgi/trace -> ip=43.207.52.55, colo=NRT (东京节点)
```
说明 Cloudflare API 更新通道本身是通的, 问题不在 API Token, 而在获取不到要更新的 IPv6 地址值。

---

## 三、修复方案

### 方案 A: 修改 Clash DNS 配置 (推荐, 治本)

在 192.168.10.2 的 Clash 配置文件中, 启用 IPv6 DNS 解析:

```yaml
dns:
  enable: true
  ipv6: true                    # 关键: 改为 true, 允许返回 AAAA 记录
  enhanced-mode: fake-ip
  fake-ip-filter:
    - "*.ipw.cn"
    - "api64.ipify.org"
    - "api.ipify.org"
    - "ip.sb"
    - "6.ident.me"
    # 将公网IP检测服务加入 fake-ip-filter, 让它们走真实 DNS
  nameserver:
    - 2606:4700:4700::1111
    - 2400:3200::1
    - 223.5.5.5
```

修改后重启 Clash, Lucky 即可正常解析 AAAA 记录并检测公网 IPv6。

### 方案 B: 修改 unraid 的 DNS 配置 (快速, 但需持久化)

#### 步骤 1: 创建 resolv.conf.head (unraid 持久化方式)

SSH 到 unraid, 创建 `/etc/resolv.conf.head`:
```bash
cat > /etc/resolv.conf.head << 'EOF'
nameserver 2606:4700:4700::1111
nameserver 2400:3200::1
EOF
```

注意: unraid 的 `/etc/resolv.conf` 由 dhcpcd 自动生成, 直接修改会在重启或网络重连时丢失。`resolv.conf.head` 的内容会被自动 prepend 到 resolv.conf 前面。

#### 步骤 2: 重启网络或重新生成 resolv.conf

```bash
# 方法1: 重启 br0 接口
rc.inet1 restart

# 方法2: 手动重建 resolv.conf (临时)
cp /etc/resolv.conf /etc/resolv.conf.bak
cat /etc/resolv.conf.head > /etc/resolv.conf.new
cat /etc/resolv.conf.bak >> /etc/resolv.conf.new
mv /etc/resolv.conf.new /etc/resolv.conf
```

#### 步骤 3: 验证

```bash
dig AAAA api64.ipify.org +short    # 应返回 IPv6 地址
curl -6 -sS https://api64.ipify.org  # 应返回 2408:8240:3254:2ea0::2a2
```

#### 步骤 4: 重启 Lucky 容器使 DNS 生效

```bash
docker restart lucky
```

### 方案 C: 修改 Lucky DDNS 任务 IP 获取方式 (绕过公网检测)

在 Lucky WebUI (http://192.168.10.8:16601) 中:

1. 进入 **DDNS** 模块
2. 编辑 lx.mvpdark.top 的 DDNS 任务
3. 将 **IP 获取方式** 从 "公网IP" 改为 **"网卡接口"**
4. 选择接口: **br0**
5. 选择 IPv6 地址类型: 全局 (global) 地址
6. 保存并手动触发一次更新

这样 Lucky 直接从 br0 网卡读取本地公网 IPv6 地址 (2408:8240:3254:2ea0::2a2), 不需要调用外部检测服务, 完全绕过 DNS 问题。

### 方案 D: 在路由器 DHCP 中添加 IPv6 DNS (网络层面)

登录路由器 (192.168.10.1) 管理界面, 在 DHCP/DNS 设置中:
- 将 IPv6 DNS 服务器设为 `2606:4700:4700::1111` 和 `2400:3200::1`
- 或在 IPv6 DNS 分发设置中加入以上地址

这样所有网络设备 (包括 unraid) 都会获得正确的 IPv6 DNS。

---

## 四、推荐操作顺序

1. **立即修复 (方案 C)**: 修改 Lucky DDNS 任务为从 br0 网卡获取 IPv6, 1 分钟内恢复 DDNS 功能
2. **根治问题 (方案 A)**: 修改 Clash 配置启用 IPv6 DNS, 解决全局 IPv6 DNS 问题
3. **持久化 (方案 B 或 D)**: 确保 unraid 重启后 DNS 配置不丢失

---

## 五、补充说明

### 关于 IPv6 TCP 国际线路不稳定

测试发现 Cloudflare IPv6 (2606:4700:4700::1111) 的 443 端口 TCP 连接超时, 但 53 端口正常。这是国内到国际 IPv6 线路的常见问题 (GFW 干扰或 ISP 路由问题)。

- `api64.ipify.org` 解析到的 IPv6 (2607:f2d8::) 是可达的, 已验证能返回公网 IP
- 如果 api64.ipify.org 也不稳定, 可在 Lucky 公网 IP 检测 URL 中改用国内服务:
  - `https://v6.ip.sb` (需验证 AAAA 记录)
  - `https://6.ident.me`
  - 或直接用方案 C (网卡获取) 彻底避免此问题

### 关于 Cloudflare API Token

由于 lucky_ddns.lkcf 文件为加密格式, 无法通过读取配置文件验证 Token 有效性。但基于以下证据判断 Token 大概率有效:
- Cloudflare API 经 IPv4 可达 (trace 返回 200)
- 容器日志中没有 Token 无效/权限不足的错误
- "更新失败" 的原因是值为空, 而非 API 调用被拒

如需验证 Token, 请在 Lucky WebUI 的 DDNS 任务页面查看 Token 配置, 或在 Cloudflare 面板确认 Token 权限包含:
- Zone - DNS - Edit (对 mvpdark.top 区域)

### 诊断脚本位置

本次诊断使用的 Python (paramiko) 脚本:
- `c:\Users\mvpdark\.trae-cn\work\6a560395312bb4fdf8a887b5\diagnose_lucky_ddns.py`
- `c:\Users\mvpdark\.trae-cn\work\6a560395312bb4fdf8a887b5\diagnose_lucky_ddns_2.py`
- `c:\Users\mvpdark\.trae-cn\work\6a560395312bb4fdf8a887b5\diagnose_lucky_ddns_3.py`
- `c:\Users\mvpdark\.trae-cn\work\6a560395312bb4fdf8a887b5\diagnose_lucky_ddns_4.py`
- `c:\Users\mvpdark\.trae-cn\work\6a560395312bb4fdf8a887b5\diagnose_lucky_ddns_5.py`
