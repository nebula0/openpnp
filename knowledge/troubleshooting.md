# 疑難排解：症狀 → 原因 → 先試什麼

> 腳本失敗、AI 接手時先查這裡；問題解決後回來補一筆或修正。
> 每筆格式：**症狀**（錯誤訊息或看到的現象）／原因／先試什麼／相關工具。細節和日期見 worklog。
> 適用這台 LumenPnP（實驗室），參數見 [known-good/](known-good/)。

## 底部視覺

### `ReferenceBottomVision (...): No result found.`
- 原因：Threshold 太高。C0603 的端子在底部相機下大約 200 灰階，原本預設的 240 會把整張圖都切成黑的。
- 先試：吸一顆零件起來（`pick_from_feeder(return_part=False)`），用 `test_bottom_vision(threshold=…)` 由高往低試。
  - 看二值化影像：只有零件的兩個端子是白的、量到的大小接近 footprint、0° 和放置角度都正確。
  - 取可用範圍的中間值，幫該封裝建立專用的視覺設定，不要改預設設定。
- 相關：`test_bottom_vision`、`pick_from_feeder`、`discard_part`。2026-10-14。

### 底部視覺框到的範圍太大、角度錯很多（例如 −33°）
- 原因：Threshold 太低，吸嘴頭邊緣的反光被一起框進去。
- 先試：把門檻調高，直到二值化影像裡吸嘴頭的反光消失。

### `pick offset ... > Max. Pick Tolerance`，零件被拋掉
- 原因：吸偏了，或是吸到的不是正確的零件（10/13 是使用者故意放的假 IC）。
- 先試：`place_from_job` 會自動吸下一顆，連續失敗 5 顆才停。如果一直失敗，查吸嘴頭和 feeder 的位置。

## 量取料高度（`probe_feeder_z`）

### `The vacuum level ... is too close to the contact threshold`
- 原因：吸嘴頭設定的 part on/off 真空範圍是沒用過的預設值，不適合這個吸嘴頭。N045 的空中讀值是 229–230，比設定的「沒零件」範圍 248–255 還低。
- 先試：帶 `contact_drop`（例如 2）。門檻就會變成「空中讀值 − contact_drop」。

### 讀值只降一點點（229 → 226）就不再下降，吸嘴看起來沒碰到零件
- 原因：零件在離吸嘴很近時就被吸起來貼到吸嘴上了；感測器只讀 MSB，有零件和沒零件只差 3–4 階。
- 先試：把讀值開始下降的高度（再壓 0.05 mm）當作取料 Z，然後試吸一顆確認。

## Strip feeder、QR 標籤

### `Found 0 hole candidates but not two 4.0 mm apart in a line`
可能的原因，依常見程度排列：
1. 照片拍的位置不對：標籤貼在料帶上，料帶是從標籤**下方**（−Y）繼續延伸的，標籤上方只有一小截。
2. 料帶剪得太靠近定位孔：孔和料帶外面的深色底板連在一起，不像圓孔（已在 `StripTapeLocator.findHoles` 修正，先用 opening 切開）。
3. 畫面裡只有一個完整的孔。
- 先試：看 `ai-bridge/scan/` 或 `captures/` 存下的照片。

### `No parts found on the strip within ... mm of the label`
- 原因：靠近標籤的口袋都用掉了，空的；或者料帶比 `search_length` 還短。
- 先試：加大 `search_length`，或確認料帶上還有零件。

### `The strip feeds towards its label`
- 原因：料帶放反了（定位孔在零件的另一側）。
- 先試：把料帶轉 180°。程式目前不支援反向料帶，見 ROADMAP 4.5。

### QR 標籤漏讀（有些掃描讀得到、有些讀不到）
- 原因：上相機在 Y 方向只看得到約 10.6 mm，QR 就占了 8 mm。標籤偏離掃描的 Y 超過約 1.3 mm，就會被切掉。
- 先試：加大 `y_tolerance`（預設 4，每排相差 2 mm），或把標籤貼得更整齊。照片有點失焦沒關係。

### 料帶上的零件比 footprint 大很多
- 原因：拿錯料（10/14：100 nF 那捲是 0805，板子上是 0603 的焊墊）。
- 先試：從照片量零件本體的尺寸，跟空板照片裡的焊墊比較（`inspect/bare/`），再看料捲上的料號（三星 CL10 是 0603，CL21 是 0805）。

## 放料與驗收

### 放料時把旁邊已經放好的高零件撞歪
- 原因：放料順序反了。吸嘴頭越往上越粗，放矮零件時會擦到旁邊比較高的零件（10/14：1uF 撞到 AD608）。
- 先試：**先放矮的，再放高的**（電阻、電容 → IC）。

### 驗收判定為 `check`，偏移或旋轉很大，可是剛放完時是正的
- 原因：歸零撞到限位開關時，機器會震動，沒有錫膏的零件會跳開。不一定每次都會發生。
- 先試：放好零件後不要重啟或歸零。真的重啟了，就重新驗收，必要時手動扶正。

### 驗收判定為 `missing`，但零件其實在
- 原因：比對用的「放置前照片」裡已經有零件了（之前重複放置、或之前嘗試時留下的舊照片）。
- 先試：看 `inspect/before/` 裡那張照片；刪掉後會改用空板照片比對（`inspect/bare/`）。

### 0603 量到的寬度偏大、X 偏 0.1 mm，或角度有幾度誤差
- 原因：零件旁邊的陰影、邊緣的零碎雜點（已修：opening 改成寬度的 20%、改用主軸方向、旋轉改成用末端位移判斷）。
- 先試：如果又出現，看驗收照片裡紅色變化區域的形狀。

### 搞不清楚 Brd1–4 是哪一片
- 拼板 `fm_pnp`：Brd1、Brd2 是 Y ≈ 130 的那一排，Brd3、Brd4 是 Y ≈ 155 的那一排；Brd1、Brd3 在左邊（X ≈ 316），Brd2、Brd4 在右邊（X ≈ 333）。

## 機器與 OpenPnP

### `FIDUCIAL-HOME no matches found`，歸零失敗
- 原因：底部相機被蓋住了（10/13 是一個塑膠袋）。
- 先試：看底部相機有沒有被遮住。

### `Effective Safe Z coordinate ... is outside Safe Z Zone`，吸嘴卡在下面
- 原因：N2 開了 dynamic safe Z，吸著有高度的零件時 Safe Z 會超出 Safe Zone。
- 先試：這時 bridge 也抬不起來，要在介面上手動 jog Z。N2 的 dynamic safe Z 已經關掉了。

### 散料 feeder `No AD608 rectangle found`，之後 feeder 被停用
- 原因：格子裡沒有零件，或零件偏到格子邊緣。OpenPnP 的 job processor 在 feed 失敗時，會自動停用 feeder。
- 先試：把零件推回格子中間，再用 `update_feeder` 重新啟用。

### OpenPnP 關不掉（log 有 `Shutting down...`，但程序沒有結束）
- 原因：還沒查到。`quit()` 中途丟出例外，所以沒有呼叫到 `System.exit`。
- 先試：看 `<configDir>/log/stderr.log`。等 90 秒還沒結束就強制結束；設定通常已經存好了。

### 重啟後 Job 分頁是空的
- 原因：`auto-load-most-recent-job` 是 false。
- 先試：從「文件」選單打開 job。job 檔本身沒有遺失。

### 工具被 Claude Code 的 auto mode 擋下（Real-World Transactions、Sensitive Remote Exec）
- 先試：使用者用 `/permissions` 為該工具加 allow 規則，或自己用 `!` 執行指令。不要用其他方法繞過去。

### MCP 工具沒有新參數或新工具
- 原因：`server.py` 改過之後，要在 Claude Code 裡用 `/mcp` 重新連線，才會載入新版。
- 先試：重連之前，可以先用舊的參數；Java 端的預設值通常就夠用。

### 全景圖接縫有重影、孔被切斷
- 原因：視差。底板比 feeder 和電路板低，單一比例只能讓一個高度對齊。
- 先試：用 `get_bed_map(z=…)` 指定要對齊的高度重新拼。
