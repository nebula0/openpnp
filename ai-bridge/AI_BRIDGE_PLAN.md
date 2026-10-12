# OpenPnP AI Bridge 開發計劃

> 給接手的 Claude：這份計劃是在另一台電腦上和使用者討論後寫的。那台電腦的對話記憶不會跟過來，所以需要的背景都寫在這裡。開始寫程式前，先讀完整份文件，再向使用者確認「§0 開工前要問使用者的事」。
> 寫於 2026-10-12，對應 OpenPnP commit `5bd404cf`。下面提到的檔案行號都是當時確認過的，版本不同時請重新確認。

## 目標

使用者在終端機用自然語言描述問題，例如「底部視覺抓不到這顆 IC 的外框」，Claude 就能直接：

1. **讀取和修改 OpenPnP 的參數**：所有參數都能讀，寫入依風險分級，見 §4。
2. **跑視覺 pipeline 並看到每個 stage 的圖片**，自己反覆調整，直到辨識成功。
3. **留下人看得懂的變更紀錄**，並且可以還原。
4. **累積經驗**：記住常見問題和有效參數，下次遇到類似情況先查。

使用者是終端使用者，也是開發者。這套工具會在實驗室連著實體 PnP 機器的電腦上開發和使用。溝通語言用繁體中文。

## 架構（建議）

```
Claude Code ──MCP(stdio)──> bridge/ (Python MCP server, 薄薄一層)
                                │ HTTP JSON, 只綁 127.0.0.1
                                ▼
                     OpenPnP 內嵌的 AiBridgeServer (Java)
                                │
                  Configuration / Machine / CvPipeline / Scripting
```

為什麼拆成兩層：

- 專案編譯目標是 **Java 11**（`pom.xml:292`），官方 MCP Java SDK 需要 Java 17 以上，不要為了這個升級整個專案。
- Java 端用 JDK 內建的 `com.sun.net.httpserver.HttpServer`，JSON 用專案已有的 **gson**，**不需要新增依賴**。這樣 OpenPnP 端的修改小，也容易維護，之後要回饋給 upstream 也比較有機會。
- MCP 端用 Python 官方的 `mcp` SDK，工具定義、圖片回傳（image content）都很簡單。只負責轉發 HTTP 請求。

## 已確認的程式碼位置

| 用途 | 位置 |
|---|---|
| 程式進入點、設定資料夾 `~/.openpnp2`（可用 `-DconfigDir=` 覆寫） | `src/main/java/org/openpnp/Main.java:173-184` |
| 全域設定、儲存 | `org.openpnp.model.Configuration`：`get()`、`save()`（`:592`）、`addListener()`（`:395`） |
| 機器執行緒（所有會動機器的操作都要從這裡送） | `org.openpnp.spi.Machine#submit(...)`（`Machine.java:225-261`） |
| 腳本引擎（JS/Python/BeanShell，已注入 `config`、`machine`、`gui`） | `org.openpnp.scripting.Scripting`：`execute(String)`（`:129`），bindings 在 `:177-189` |
| 視覺 pipeline 序列化 | `CvPipeline#toXmlString()` / `fromXmlString()`（`CvPipeline.java:384,398`） |
| 個別 stage 的結果和圖片 | `CvPipeline#getResult(name)`（`:169`），`CvStage.Result#getImage()`（`CvStage.java:281`） |
| 底部視覺 | `machine/reference/vision/ReferenceBottomVision.java`，設定繼承用 `getInheritedVisionSettings(part)`，`preparePipeline(...)`（`:464`） |
| 視覺設定的 model | `model/BottomVisionSettings.java`、`FiducialVisionSettings.java`、`AbstractVisionSettings.java` |
| Stage 種類 | `vision/pipeline/stages/`（Threshold、FindContours、MinAreaRect、FilterRects、MaskCircle… 共約 60 種） |
| 現有 TCP server 範例 | `util/GcodeServer.java` |
| 通用屬性讀寫可用的依賴 | `commons-beanutils`（pom 已有） |
| Model 物件的屬性變更通知 | `model/AbstractModelObject`（`firePropertyChange`） |

## 分階段實作

### Phase 0：環境
- 在實驗室電腦裝 JDK 11 以上和 Maven，執行 `mvn -q -DskipTests package` 確認能編譯，再確認 OpenPnP 能啟動。
- 實驗室的實際設定在那台電腦的 `~/.openpnp2`。**先備份**，再進行 §3 的 git 化。
- 開發時先用一份**模擬機器的設定**（`-DconfigDir=<另一個資料夾>`，machine 設為 SimulationModeMachine），不要直接對真機開發。

### Phase 1：Java 端 AiBridgeServer（最小可用版）
新增 package `org.openpnp.ai`（名稱可再討論）：

- `AiBridgeServer`：在 Configuration 載入完成後啟動，綁 `127.0.0.1`，port 可設定，預設關閉，用系統屬性或偏好設定開啟。啟動時產生一組 token 寫進設定資料夾，請求要帶這組 token。
- 端點（JSON）：
  - `GET /objects?path=` 列出物件樹，例如 machine → heads → nozzles / cameras / feeders / actuators、parts、packages、vision settings。每個物件要有穩定的 id 路徑（盡量用 OpenPnP 物件的 `id`，不要用 index）。
  - `GET /property?path=&name=`、`POST /property`（set）。用 beanutils 反射，只處理 getter/setter 成對、型別單純的屬性（數字、字串、boolean、enum、`Length`、`Location`）。
  - `GET /vision/pipeline?settingsId=` 回傳 pipeline XML，和每個 stage 的名稱、類型、可調屬性。
  - `POST /vision/stage-property` 修改某個 stage 的參數。
  - `POST /vision/run`：輸入是 settingsId 或 partId，加上影像來源（目前相機畫面，或指定的圖檔路徑）。輸出是每個 stage 的狀態、錯誤、結果摘要，以及 PNG 圖片（base64，縮圖到合理大小，可指定只回傳某幾個 stage）。
  - `POST /config/save` 呼叫 `Configuration.get().save()`。
- 執行緒規則：
  - 讀寫 model 屬性時用 `SwingUtilities.invokeAndWait`，避免和 GUI 綁定（beansbinding）衝突。
  - 跑 pipeline 或任何會碰硬體的操作，用 `machine.submit(callable).get(timeout)`。
- 修改屬性後，確認已開啟的 GUI wizard 有沒有跟著更新。沒有的話，至少回傳提示，請使用者重新開那個分頁。

**驗收**：在模擬機器上，用 curl 能讀到 bottom vision 的 pipeline，修改 Threshold 後跑 `/vision/run`，能拿到每個 stage 的圖。

### Phase 2：MCP bridge（Python）
在 repo 裡新增 `ai-bridge/`（或另開 repo，問使用者）：

- 工具：`list_objects`、`get_property`、`set_property`、`get_vision_pipeline`、`set_stage_property`、`run_vision`（回傳 MCP image content）、`save_config`、`undo_last_change`、`get_change_log`。
- 每個寫入類工具都要有 `reason` 參數，原因會寫進變更紀錄。
- 提供 `.mcp.json` 範例，讓 Claude Code 在這個 repo 下自動接上。

**驗收**：在 Claude Code 對它說「這顆 IC 外框抓不到」，它能自己讀 pipeline、看圖、改參數、重跑，最後回報結果。

### Phase 3：可追溯性（人看得到 AI 改了什麼）
1. **變更紀錄**：Java 端每次寫入都 append 一行到 `~/.openpnp2/ai-bridge/changes.jsonl`，內容包含時間、物件路徑、屬性、舊值、新值、reason、來源（ai/human）。
2. **Undo**：用上面的 jsonl 反向套用。
3. **設定資料夾 git 化**：在 `~/.openpnp2` 執行 `git init`，用 `.gitignore` 排除 `log/`、快取和圖片類的大檔。bridge 在 `save_config` 之後自動 commit，commit message 帶上 reason。使用者用 `git log -p` 就能看到所有變更，也能還原。
4. （可選）OpenPnP GUI 裡加一個簡單的「AI Changes」面板，顯示最近的變更。

### Phase 4：經驗累積（記憶）
這一步幾乎不用寫程式，主要是訂規則，寫進 repo 的 `CLAUDE.md`：

- **參數本身**存在 OpenPnP 的設定裡，再加上 git 歷史，不用另外存一份。
- **經驗**存在 `~/.openpnp2/ai-bridge/knowledge/`。這個資料夾跟著機器走，也一起納入 git：
  - `troubleshooting.md`：症狀 → 原因 → 先試什麼。例如「QFN 外框抓不到：通常是背光反光，或 Threshold 太高」。
  - `known-good/<package或part>.md`：哪組參數有效、當時的光線和相機條件、日期。
- `CLAUDE.md` 要寫清楚流程：遇到問題**先查 knowledge**；調整成功後**更新 knowledge**；失敗的嘗試也要簡短記下。
- Claude Code 自己的專案記憶也可以用，但它只存在那台電腦的使用者目錄下。所以和機器有關的知識，以 knowledge 資料夾為準。

## §4 安全分級（必須做）

寫入權限依類型分級，在 Java 端實作，不要只靠 prompt 約束：

- 🟢 **直接寫入**：視覺 pipeline 和 stage 參數、vision settings、容差、part/package 的視覺相關屬性。
- 🟡 **需要使用者確認**：軸限位、Safe Z、nozzle/camera offset、feeder 位置、driver 的 G-code、速度和加速度。
  - 做法：bridge 回傳「待確認」，Claude 在終端機問使用者，使用者同意後帶 `confirm=true` 再送一次。
- 🔴 **會讓機器移動的指令**：每次都要確認。調視覺時優先用「目前畫面」或存下來的圖檔，不需要移動機器。
- 機器沒有 home 過、或正在跑 job 時，拒絕所有 🟡/🔴 操作。
- 預設不開放 `Scripting.execute()` 執行任意腳本。真的要開，就歸到 🔴。

## §0 開工前要問使用者的事

1. 實驗室機器的型號和控制板（driver 類型），以及底部和頂部相機的配置。
2. 要先從哪個實際問題開始，例如某顆 IC 的外框辨識。請使用者準備幾張失敗的圖。
3. `ai-bridge/` 放在這個 repo，還是另開 repo？
4. 這個功能之後要不要回饋給 OpenPnP upstream？如果要，Java 端要照 `OpenPnP_Eclipse_Formatter.xml` 的格式，並遵守 upstream 的貢獻規範。
5. 🟡 類別的確認方式，是在終端機問就好，還是要在 OpenPnP GUI 跳出確認視窗？

## 不要做的事

- 不要升級專案的 Java 版本，也不要引入大型依賴。
- 不要直接改執行中 OpenPnP 的 XML 設定檔，它會被覆寫。所有修改都要透過 bridge。
- 沒有使用者確認，不要對真機做任何移動。
