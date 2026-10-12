# OpenPnP（實驗室 fork）

這是 OpenPnP 原始碼，外加讓 Claude 透過 MCP 操作 OpenPnP 的 AI bridge。
完整背景見 [ai-bridge/AI_BRIDGE_PLAN.md](ai-bridge/AI_BRIDGE_PLAN.md)，每天做了什麼見 [worklog/](worklog/)。

- 使用者溝通用繁體中文。
- 機器：Opulo LumenPnP，Marlin 韌體，GcodeDriver，上下各一台 USB 相機（`OpenPnpCaptureCamera`）。
- 真機設定：`%USERPROFILE%\.openpnp2`。模擬設定：`%USERPROFILE%\openpnp-sim`。

## 建置與啟動

```
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot
%USERPROFILE%\tools\apache-maven-3.9.9\bin\mvn -q -DskipTests package
ai-bridge\start-openpnp.bat sim     # 模擬機
ai-bridge\start-openpnp.bat         # 真機
```

專案編譯目標是 Java 11，不要升級，也不要加新依賴。Java 程式碼照 `OpenPnP_Eclipse_Formatter.xml` 的格式寫（之後要回饋給 upstream）。

## AI bridge

- Java 端：`src/main/java/org/openpnp/ai/AiBridgeServer.java`。啟動參數 `-DaiBridge.port=8999` 才會開，只綁 127.0.0.1，token 寫在 `<configDir>/ai-bridge/bridge.json`。
- MCP 端：`ai-bridge/server.py`。`.mcp.json` 裡有兩個 server：`openpnp-sim`（模擬）和 `openpnp`（真機）。
- 動作紀錄：`<configDir>/ai-bridge/actions.jsonl`。

## 操作規則（必須遵守）

- **會讓機器移動的工具**（`inspect_feeder`、`pick_from_feeder`）：先不帶 `confirm` 呼叫，拿到 plan 後用一句話問使用者，使用者同意後才帶 `confirm=true` 再呼叫一次。每次都要問，前一次的同意不算數。
- 用 `openpnp`（真機）之前先跑 `get_status`，確認連到的是哪台機器、`motionAllowed` 是否為 true。
- `reason` 要寫清楚為什麼做這個動作，它會進入動作紀錄。
- 操作失敗時，先看 `nozzlesAfterFailure`，吸嘴上可能還留著零件，要告訴使用者。
- 不要直接修改執行中 OpenPnP 的 XML 設定檔，OpenPnP 結束時會覆寫它。
- 只有重要改動才在 `worklog/YYYY-MM-DD.md` 補紀錄：程式碼或設定的修改、新功能、新發現的問題或異常結果。例行的成功操作（例如照常 pick 再放回）不用寫，動作紀錄 `actions.jsonl` 已經有了。
