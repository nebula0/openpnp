# 新操作介面（網頁）：計畫與進度

> 開始於 2026-10-29。接手的人先看「進度」和「接手須知」，再往下看細節。

## 目標

在 OpenPnP 旁邊開一個網頁介面，一個畫面看到：

1. **相機視覺**：Top / Bottom 即時畫面。
2. **Task 時間軸**：過去、現在、未來的 task，像歌詞一樣上下展開，可以看全部，現在的 task 高亮並置中。
3. **電路板設計圖**：依 job 畫出板子、焊墊和零件，顯示哪些放了、哪些還沒放。
4. **元件列表**：依零件分組，可收合；點一顆會在板子圖上高亮。
5. **Claude 對話視窗**：參考 Obsidian 外掛 Claudian 的做法，在背景跑 Claude Code，把串流畫成對話（文字逐字出現、工具呼叫可展開、圖片直接顯示、todo 同步到 Task 時間軸、工具權限用按鈕核准）。

OpenPnP 的 Swing 介面不動，機器照樣由 OpenPnP 控制。新介面只透過 AI bridge 讀資料、下指令。

## 架構

```
瀏覽器 http://127.0.0.1:8990（ai-bridge/ui/index.html，單一檔案，不需要編譯）
   │
ai-bridge/ui_server.py（Python 標準函式庫，不加套件）
   ├─ /api/...   轉接 AiBridgeServer（主 port，單執行緒，會動機器的指令都走這裡）
   ├─ /api/view/... 轉接 view server（主 port + 1，多執行緒，只讀：相機畫面、板子 layout、job 檔）
   ├─ /api/actions 讀 <configDir>/ai-bridge/actions.jsonl
   └─ /api/chat/... 在背景跑 claude CLI，用 SSE 把事件推給瀏覽器
```

### 為什麼 Java 要多開一個 view server

AiBridgeServer 用單一執行緒，長時間的機器動作（例如放料 15 分鐘）會擋住其他請求。這是刻意的（避免兩個動作同時跑）。但相機畫面正是要在機器動的時候看，所以另外開一個多執行緒、只讀的 HttpServer：

- 綁 127.0.0.1，port = 主 port + 1，同一個 token。`bridge.json` 會多寫 `viewPort`。
- `GET /camera/frame?camera=Top`：最新一張 JPEG。
- `GET /camera/stream?camera=Top&fps=8`：MJPEG（`multipart/x-mixed-replace`），瀏覽器 `<img>` 可以直接播。
- `GET /job/current`：OpenPnP Job 分頁目前開的 job 檔路徑。
- `GET /job/layout?file=...`：每塊板子的大小、placement（板子座標、角度、零件、封裝、是否已放）、footprint 焊墊。

相機畫面用 `camera.startContinuousCapture(listener)` 拿，跟 OpenPnP 的 CameraView 收到同一批畫面，不會多拍、不影響視覺辨識（相機執行緒預設 5 fps）。

### 對話：claude CLI 的 stream-json 協定（2026-10-29 用 2.1.312 實測）

啟動：

```
claude -p --input-format stream-json --output-format stream-json --verbose
       --include-partial-messages --permission-prompt-tool stdio
       --append-system-prompt "<這個介面連的是真機或模擬機，用哪個 MCP server>"
```

在專案資料夾執行，所以 `.mcp.json`、`CLAUDE.md`、`knowledge/` 都照常生效。

- 送出（stdin，一行一個 JSON）：
  - 先送 `{"type":"control_request","request_id":"init1","request":{"subtype":"initialize","hooks":null}}`
  - 使用者訊息 `{"type":"user","message":{"role":"user","content":"..."},"parent_tool_use_id":null,"session_id":"default"}`
- 收到（stdout）：
  - `system/init`（session_id、工具清單）
  - `stream_event`：`content_block_start`／`content_block_delta`（`text_delta`、`input_json_delta`、`thinking_delta`）／`content_block_stop`，用來逐字顯示
  - `assistant`：完整的一則訊息（text、tool_use、thinking）
  - `user`：`tool_result`（可能含圖片）
  - `control_request` 且 `subtype: can_use_tool`：要用沒被允許的工具。回覆
    `{"type":"control_response","response":{"subtype":"success","request_id":"<同一個>","response":{"behavior":"allow","updatedInput":{...}}}}`
    或 `"behavior":"deny","message":"..."`。實測 deny 之後 Claude 會收到錯誤並停下來說明。
  - `result`：一輪結束（花費、token）
- `TodoWrite` 工具呼叫的 input 就是 todo 清單，拿來當 Task 時間軸的「現在／未來」。

## 已決定的事（使用者 10/29 回「好」，照提議的預設）

| 問題 | 決定 |
|---|---|
| 在哪裡開 | 先只在實驗室電腦本機（127.0.0.1）。之後要從 Mac 開再加登入保護 |
| 會動機器的工具 | 介面跳出「核准／拒絕」按鈕；另有開關可改成自動核准 |
| Python 套件 | 不加。直接跑 claude CLI，自己處理上面的協定 |
| 未來的 task | 有對話時用 Claude 的 todo；沒有時用 job 裡還沒放的零件 |

## 階段

1. **只看不動**：Java view server（相機、job layout）、ui_server.py、介面上的相機、板子圖、元件列表、過去的動作。
2. **Claude 對話**：串流、工具卡片、圖片、核准按鈕、todo → Task 時間軸。
3. **互相連動**：點板子上的元件 → 相機移過去（要核准）、問 Claude 這顆的狀況；驗收結果（`job/inspect`）在板子圖上標顏色。

## 進度

- [x] 確認需求和預設（10/29）
- [x] 實測 claude CLI stream-json 和權限協定（10/29，結果寫在上面）
- [x] Java：`AiBridgeViewServer.java`（`/status`（含吸嘴）、`/cameras`、`/camera/frame`、`/camera/stream`、`/job/layout`）。沒帶 `file` 時 `/job/layout` 讀 Job 分頁開著的 job，所以 GUI 跑 job 時已放狀態會即時更新（取代原本計畫的 `/job/current`）。`AiBridgeServer` 只改了建構子（啟動 view server、`bridge.json` 寫 `viewPort`）和幾個 helper 從 private 改成 package-private
- [x] javac 編譯通過（10/29，編到 scratchpad，沒打包）
- [x] 在模擬機上測 view server（10/29：用 port 8997 另開一台，classpath 最前面放新編的 class）。`/status`、`/cameras`、`/camera/frame`、`/camera/stream`、`/job/layout` 都正常
- [x] `ai-bridge/ui_server.py`：靜態檔、轉接、actions.jsonl、對話（claude CLI、SSE、核准、中斷、新對話、附圖）
- [x] `ai-bridge/ui/index.html`：版面、相機、板子圖（縮放拖曳、hover、點選）、元件列表（可收合、篩選、點選連動板子、雙擊帶入對話）、Task 時間軸、對話視窗
- [x] 接模擬機跑過介面（10/29，Edge 無頭截圖）：狀態列、板子圖、Task 時間軸置中、對話串流、工具卡片、核准按鈕 → 允許 → 工具結果 → 回覆，都正常
- [ ] **打包**：OpenPnP 關閉時 `mvn package`，之後用 `start-openpnp.bat` 開的 OpenPnP 才有 view server。10/29 寫完時真機 OpenPnP 正在被另一個工作階段使用，所以還沒打包
- [ ] 在一般瀏覽器確認 MJPEG 相機串流（無頭 Edge 不畫 MJPEG；`?poll=1` 改抓單張的模式已確認正常）
- [ ] todo 同步到 Task 時間軸（程式寫了，還沒有實際讓 Claude 用 TodoWrite 測過）
- [ ] 真機測試
- [ ] 第三階段

### 已知問題與注意事項

- 板子的已放狀態：Job 分頁開著 job 時，跟 GUI 同步。Job 分頁沒開 job 時，`/api/layout` 改用動作紀錄裡最近用過的 job 檔，狀態以檔案為準。`place_from_job` 不會改檔案，所以這時 Claude 放的零件不會變成「已放」。之後可以從 `job/place`、`job/inspect` 的動作紀錄推算。
- 動作紀錄裡有幾筆舊紀錄本身就是亂碼（寫入時就壞了），介面照實顯示。
- 模擬機的零件庫沒有 fm_pnp 的零件，所以模擬機上看 fm_pnp job 時 partId 是空的。

## 接手須知

- 啟動：先用 `ai-bridge\start-openpnp.bat sim`（或真機）開 OpenPnP，再跑 `python ai-bridge\ui_server.py`（模擬機加 `--sim`），打開 http://127.0.0.1:8990。網址加 `?poll=1` 會改用單張畫面輪詢相機。
- 改 Java 要重新打包，打包前先正常關閉 OpenPnP；見 CLAUDE.md 的「建置與啟動」。
- 不想打包就先測 Java：`javac --release 11` 編到別的資料夾，再用 `java <start-openpnp.bat 裡的 --add-opens> -DconfigDir=%USERPROFILE%\openpnp-sim -DaiBridge.port=8997 -cp "<那個資料夾>;target\openpnp-gui-0.0.1-alpha-SNAPSHOT.jar;target\lib\*" org.openpnp.Main` 另開一台模擬機（view server 在 8998）。不會動到正在用的 jar。
- 無頭 Edge 截圖：`msedge --headless=new --force-prefers-reduced-motion --window-size=1920,1080 --timeout=10000 --screenshot=...`。MJPEG 串流會讓無頭模式的時間停住，截圖時加 `?poll=1`。
- 對話視窗裡的 Claude 是在專案資料夾跑的 Claude Code，CLAUDE.md 的操作規則（會動的工具先問）照樣適用；介面的核准按鈕是在那之上多一層。
- 實測用的腳本在對話的 scratchpad，沒有進 repo；協定細節以上面這段為準。
