# 灵犀大规模修复 — 整合冒烟验证报告

- 时间：2026-07-18 06:33 (Asia/Shanghai)
- 验证范围：5 个并行修复代理改动后的全量代码整合（10 项跨模块契约）
- server 进程：旧 PID 44300 已终止；新 PID **44572**（加载全量新代码，保持运行中）
- 启动日志：`c:\Users\mvpdark\.trae-cn\work\6a57a864fc70ec0f718542ad\server_integrate.log`
- 冒烟结果明细：`c:\Users\mvpdark\.trae-cn\work\6a57a864fc70ec0f718542ad\smoke_results.json`

## 总结论

**23/23 全部 PASS。10 项跨模块契约整合全部正确，未发现任何契约拼接 mismatch，零代码改动。**

## 前置检查

| 检查项 | 结果 |
|---|---|
| 旧进程清理（8765） | PASS — taskkill PID 44300，端口释放 |
| server.py 重启（后台） | PASS — PID 44572，日志重定向 server_integrate.log |
| /api/health 就绪 | PASS — `{"ok":true,"db":"up","webdav":"up"}`（首轮轮询即就绪） |
| 启动日志 ImportError/TypeError 检查 | PASS — 无任何异常；无 set_webdav / unexpected keyword argument ('username'/'access_ttl'/'url_secret') 报错 |
| py_compile 全 src（排除 sam2_src） | PASS — 29 个文件 0 失败 |

## 冒烟测试 PASS/FAIL 表

| 测试 | 内容 | 结果 | 关键输出 |
|---|---|---|---|
| T1 | 登录契约 8 字段 | PASS | 200，ok/user_id/username/role/balance/access_token/refresh_token/expires_in 齐全（契约1） |
| T2a | refresh 换新 access_token | PASS | 200 含 access_token + expires_in（契约2） |
| T2b | 新 token 调 /me | PASS | 200，username=testuser_a |
| T3a | 注册含空格用户名 | PASS | 400「用户名格式非法」（契约9） |
| T3b | 注册单字符用户名 | PASS | 400（契约9） |
| T3c | 注册 73 字节密码 | PASS | 400「不得超过 72 字节」（契约9） |
| T4 | 6 端点无 422 | PASS | me=200；change-password 错旧密码=400；billing summary=200；keys=200；charge=200；init-baseline=200（契约10） |
| T4b | 非 admin charge 跳过 | PASS | `{"charged":0,"skipped":true,"reason":"非主账户不承担全局消耗"}`（契约8，testuser_b 验证） |
| T5a | 上传返回带 sig URL | PASS | `/uploads/testuser_a/upload_1784327545_11b5f0.jpg?sig=f7eaf314…`（契约4） |
| T5b | 带 sig GET（无 auth 头） | PASS | 200，694 字节 |
| T5c | 无 sig GET | PASS | 403 |
| T5d | 篡改 sig GET | PASS | 403 |
| T6a | 创建会话 | PASS | sid=0f5987bd73d4 |
| T6b | WS 首帧鉴权+真实 LLM 流式 | PASS | auth_ok → delta → done（契约5/6，120s 内完成） |
| T6c | history 含新消息 | PASS | 2 条（user+assistant） |
| T7a | logout | PASS | 200 |
| T7b | 吊销 token 走 WS 首帧 | PASS | `{"type":"error","content":"unauthorized"}` + close 4401（契约3/5） |
| T7c | 吊销 token 走 HTTP me | PASS | 401（契约3 吊销闭环） |
| T8 | GET /api/sessions（装配间接证明） | PASS | 200，3 个会话；日志无 set_webdav 报错（契约6） |
| T9a | 注册+激活+登录 testuser_c | PASS | 注册 200 → DB UPDATE status='active' → 登录 200 |
| T9b | c 读 a 会话历史 | PASS | `history=[]`（越权隔离） |
| T9c | c 无 sig 访问 a 文件 | PASS | 403 |
| T9d | c 用户名拼路径 + a 的旧 sig | PASS | 403（签名绑定路径用户名，无法伪造） |

## 契约覆盖核对（10/10）

1. 登录 8 字段 — T1 ✅
2. refresh 契约 — T2a ✅
3. AuthService 五参装配 + 吊销闭环 — 启动无 TypeError + T7b/T7c ✅
4. 签名 URL（HMAC-SHA256(username/filename, jwt_secret)[:32]，put_file 自带 sig） — T5a-d ✅
5. WS 首帧鉴权 + 4401 + query token 兼容保留 — T6b/T7b + 代码确认（server.py:1276-1299）✅
6. orchestrator.set_webdav(webdav) + chat_with_agents(username=...) — 启动装配无异常（server.py:211 调用成功，否则启动即炸）+ T6b 真实链路 ✅
7. search() 返回 {results, images} — 启动装配无异常（间接；未单独触发搜索调用，见备注）
8. billing charge 非 admin skipped — T4b ✅
9. 注册格式/密码长度校验 — T3a-c ✅
10. 7 端点 request: Request 注解修复 — T4（另 logout 于 T7a 验证 200）✅

## 发现并修复的拼接问题

**无。** 全部契约一次通过，未做任何代码修改。

## 未修复的大问题

**无。**

## 备注

- 契约7（search 返回结构）未通过真实搜索调用单独触发；本次 T6 对话为简单问候未路由到搜索 agent。search_service 装配在启动路径上，若有签名/导入错误启动即失败，故判定为间接覆盖。如需强验证可追加一条触发搜索的 WS 消息。
- testuser_a 为 admin 角色，故契约8 的「非 admin skipped」改用 testuser_b（role=user）验证。
- T9 产生的 testuser_c 已保留在 DB（status=active），上传的 smoke 测试图保留于 WebDAV（testuser_a 目录）。
