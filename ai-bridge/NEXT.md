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
  - 由左到右：`C0603-100nF`（X ≈ 127）、`C0603-1uF`（≈ 142）、`C0603-10nF`（≈ 157）。
- feeder（10/16 用 `scan_qr_feeders` 設好）：
  - `C0603-100nF strip`：啟用，Z 8.20，已試吸 3 顆。
  - `C0603-10nF strip`：啟用，Z 8.30，已試吸 1 顆。
  - `C0603-1uF strip`：**停用**（10/16 18:10）。Y 152 以下還有透明上膜，掃描指到膜下的 138.0。**請使用者把膜撕完，再跑 `scan_feeders.py ... --apply --verify-all`。** Y 157.8、153.8 兩顆沒有膜，可以取。
- 底部視覺：三種 C0603 都用門檻 215，並開啟零件大小檢查 BodySize 25%。
- 零件高度：1uF、10 nF、100 nF 都是 0.8 mm（料號已寫進零件說明）；C0603-18nF、-100pF 還是 0。
- 新工具：`list_parts`、`update_part`（高度、說明、底部視覺門檻）、`update_package`。**改了 `server.py`，使用者要打 `/mcp` 重連才看得到。**

## 2. 接下來要做的事（依序）

### 2.1 料帶：已完成（見 worklog 10/16）

### 2.2 `run_job.py`：已完成（10/16，真機 4 顆 C6 一次通過）

- 用法見檔案開頭。之後可以補：`--stop-on-check`、`--sim` 實測、AD608 那組（要先把 AD608 放回散料 feeder）。
- **板上有 4 顆乾放的 C6（10/16 18:05）**：不要重啟或歸零，先問使用者要不要拿掉。

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
