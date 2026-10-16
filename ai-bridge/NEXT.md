# 下一個對話的交接（2026-10-16）

> 給接手的 Claude：這份是上一個對話結束時的狀態和接下來要做的事。`CLAUDE.md` 會自動載入，裡面有操作規則（會移動機器的工具要先拿 plan 再問、每次 commit 後都要 push 等），一定要遵守。
> 背景資料：[ROADMAP.md](ROADMAP.md)（進度、依流程分類的功能表、已知問題）、[../knowledge/](../knowledge/)（疑難排解、驗證過的數值）、[../worklog/](../worklog/)（每天的細節，需要時再查，不用全部讀）。
> 這個任務做完後，更新或刪除這份檔案。

## 1. 目前的狀態

- 程式：分支 `ai-bridge`，已推到 GitHub。`target/` 裡的 jar 包含所有改動（QR 掃描分 5 排、定位孔 opening 修正等）。
- 真機設定資料夾 `~/.openpnp2` 已經用本機 git 管理；OpenPnP 關閉時，`start-openpnp.bat` 會自動 commit。
- OpenPnP 現在可能沒有在跑，或者在跑但還沒歸零：**先 `get_status`**，需要時重新啟動（`ai-bridge\start-openpnp.bat`）。
- 相機找不到（紅叉、`FIDUCIAL-HOME no matches found`）：USB 路徑可能又變了，照 `knowledge/troubleshooting.md` 跑 `ai-bridge/fix_cameras.py`。歸零後一定要拍上相機確認看到的是平台（10/16 接反時歸零照樣「成功」）。
- 板子 `fm_pnp`（`C:\Users\Acer\Desktop\fm_pnp_test\fm_pnp.job.xml`，4 片拼板）：最後的狀態是 Brd3 的 C6 有放；Brd1 的 C6 被歸零震到焊墊外；Brd2、Brd4 是空的。AD608 都被使用者拿掉了。**實際狀況要先問使用者。** 空板參考照（`ai-bridge/inspect/bare/`）是在全部清空時拍的，還能用。
- 料帶架：一排三條 8 mm 紙帶，QR 標籤貼在料帶上，料帶從標籤底下往 −Y 延伸。
  - 標籤那一排：Y ≈ 197–199.5、X 115–165。
  - 由左到右：`C0603-100nF`（X ≈ 124）、`C0603-1uF`（≈ 140）、`C0603-10nF`（≈ 153）。
  - 100 nF 那條原本放反了，**使用者已經轉 180°，但還沒重新掃描**。
- feeder：`C0603-1uF strip` 已啟用，Z 8.15。10 nF、100 nF 還沒有 feeder。
- 底部視覺：封裝 C0603 用專用設定 `C0603`（門檻 180），還沒在 10 nF、100 nF 上測過。
- 零件高度：只有 C0603-1uF 設了（0.8 mm），其他都是 0。

## 2. 接下來要做的事（依序）

### 2.1 把三條料帶設定好（用現有的工具）

1. 歸零（先問使用者，提醒路徑淨空）。
2. `scan_qr_feeders(y=199.5, x_from=115, x_to=165)`，先不帶 apply 試跑。預期 3 條都成功、1uF 的第一顆大約在 Y 188.5。結果有問題時看 `ai-bridge/scan/` 的照片，並查 `knowledge/troubleshooting.md`。
3. 結果正確就帶 `apply=True` 再跑一次。10 nF、100 nF 會新增 feeder，預設停用。
4. 新的 feeder 用 `probe_feeder_z` 量 Z（`contact_drop=2`，下限不要比 7.0 低）→ 用 `update_feeder` 寫入 Z → 試吸一顆 → `test_bottom_vision` 確認門檻 180 也適用 → `discard_part` → 用 `update_feeder` 啟用。
5. 請使用者提供 10 nF、100 nF 的厚度（看規格書；0603 陶瓷電容常見是 0.8 mm），把零件高度補進 `parts.xml`：先正常關閉 OpenPnP，修改後在 `~/.openpnp2` 裡 commit。

### 2.2 寫 `ai-bridge/run_job.py`（最優先的腳本）

目標：跑一批板子不需要 AI。只用 Python 標準函式庫，透過 bridge 的 HTTP API 呼叫，可以參考 `scan_feeders.py` 的寫法：從 `bridge.json` 讀 token，先拿 plan，印出來後問 y/N，確認後才帶 confirm。

1. `GET /status`。沒有歸零的話，提醒使用者確認路徑淨空，按 Enter 才 `POST /machine/home`。
2. 參數：`--job`、`--boards`（預設全部啟用的板子）、`--placements`（預設全部啟用的 placement）、`--survey`（新板子時先拍空板參考照，`POST /job/survey`）、`--yes`、`--sim`。
3. 依零件高度分組，**由矮到高**。高度是 0 的零件列出警告，排在最前面；高度可以從 `GET /job/placements` 拿到 partId，再查零件，**可能要在 Java 端加一個查零件高度的 API，或是讓 `/job/placements` 直接回傳高度**。
4. 每一組依序執行 `POST /job/place`（預設會先拍放置前的照片），**放完立刻** `POST /job/inspect`（`images: "none"`，或只存失敗的照片路徑）。
5. 報告寫到 `ai-bridge/reports/<時間>.json`：每個 placement 的判定（`ok`／`check`／`missing`）和原因、照片路徑、`partsRejected`、`feedersDisabled`、`skippedOccupied`。終端機另外印出一份摘要。
6. 出錯就停，把 `nozzlesAfterFailure` 和錯誤訊息寫進報告，並提示「把報告交給 AI」。
7. 先在模擬機上測流程（`--sim`），再用真機放一組 C0603 測試。
8. 用 `ai-bridge/token_usage.py` 比較：同樣一批板子，由 AI 一步一步呼叫和由腳本跑，各花多少 token。

### 2.3 之後（見 ROADMAP 4.2）

- `scan_feeders.py` 擴充：寫入 feeder 之後，接著自動量 Z、試吸、測底部視覺，全部通過才啟用。
- Java 端新增設定封裝視覺參數、設定零件高度的 API，然後寫 `tune_bottom_vision.py`。

## 3. 要記得的事

- 歸零時機器會震動，**放好零件之後就不要再重啟或歸零**。要改程式的話，先把程式改完、重啟好，再開始放零件（見記憶 `homing-shakes-dry-parts`）。
- 板號對應：Brd1、Brd2 是 Y ≈ 130 的那一排，Brd3、Brd4 是 Y ≈ 155 的那一排；Brd1、Brd3 在左邊。
- 先放矮的，再放高的（AD608 最後放）。
- strip feeder 試吸後不能放回料帶，只能 `discard_part`。
- 改了 `server.py` 之後，要請使用者打 `/mcp` 重新連線。
- 使用者希望：能用腳本做的都用腳本，腳本失敗時才由 AI 接手。新功能要輸出結構化的結果、存下照片。
