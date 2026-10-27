# 疑難排解：症狀 → 原因 → 先試什麼

> 腳本失敗、AI 接手時先查這裡；問題解決後回來補一筆或修正。
> 每筆格式：**症狀**（錯誤訊息或看到的現象）／原因／先試什麼／相關工具。細節和日期見 worklog。
> 標題裡用反引號引用錯誤訊息原文（`...` 代表任意文字）：腳本的報告會依此自動附上相符的條目（`ai-bridge/report.py`）。
> 適用這台 LumenPnP（實驗室），參數見 [known-good/](known-good/)。

## 底部視覺

### `ReferenceBottomVision (...): No result found.`
- 原因：Threshold 太高。C0603 的端子在底部相機下大約 200 灰階，原本預設的 240 會把整張圖都切成黑的。
- 先試：吸一顆零件起來（`pick_from_feeder(return_part=False)`），用 `test_bottom_vision(threshold=…)` 由高往低試。
  - 看二值化影像：只有零件的兩個端子是白的、量到的大小接近 footprint、0° 和放置角度都正確。
  - 取可用範圍的中間值，幫該封裝建立專用的視覺設定，不要改預設設定。
- 相關：`test_bottom_vision`、`pick_from_feeder`、`discard_part`。2026-10-14。

### 底部視覺框到的範圍太大、角度錯很多（例如 −33°）
- 原因：Threshold 太低，吸嘴頭邊緣的反光被一起框進去。**同封裝的不同零件也會不同**：10/16 1uF 用 180 正常，100 nF、10 nF 在 180 都框到反光，要 200 以上。
- 先試：用 `test_bottom_vision(threshold=…)` 往上掃，直到二值化影像只剩零件、大小接近 footprint。取可用範圍中間值，用 `update_package` 或 `update_part(bottom_vision_threshold=…)` 寫入（後者會自動給零件一份專用設定，不影響其他零件）。

### `Part ... width too large: nominal ..., limit ..., measured ...`，連續好幾顆都是，之前同樣的門檻是好的
- 原因（10/16）：**相機曝光變了**，整張畫面變亮，吸嘴口外圈的反光超過門檻、被一起框進去（量到 2.4–3.6 mm）。那次是電腦斷電重開機之後，相機改用設定檔裡的 Exposure −1（最長）。零件其實好好地吸在吸嘴中間，照片裡那條小亮條就是零件（兩個端子），只是比反光圈小很多。
- 先試：
  1. `camera_properties("Bottom")` 和 `camera_properties("Top")` 看 Exposure 和 `freeze`。驗證過的值：Top −4、Bottom −5、freeze true（見 known-good）。
  2. 不對就用 `camera_properties(..., set={"Exposure": {"auto": false, "value": -5}}, freeze=true)` 改回來，再跑 `tune_bottom_vision.py` 確認範圍（C0603 100nF 在 −5 時是 210–250）。
  3. 上相機的驗收照片偏白、量到的零件寬度偏大或角度很怪，也是同一個原因。
- 注意：不要先懷疑真空或補光燈。先拿同一個步驟以前正常的照片來比亮度。

### 底部視覺 `found`，但吸嘴上其實沒有零件
- 原因：沒吸到（例如口袋是空的），而門檻夠低時吸嘴口的反光會被框成一個「零件」（10/16：1.10 × 1.87 mm、−5.9°）。這台沒開吸取後的真空檢查，所以 OpenPnP 也以為有吸到。
- 先試：看底部相機的原始影像，吸嘴口中間有沒有零件；量到的大小和 footprint 差很多時就要懷疑。再到取料位置拍照，看口袋是不是空的。用 `discard_part` 清掉 OpenPnP 記錄的零件狀態。

### `test_bottom_vision`：`Part height unknown and camera Bottom does not support part height sensing`
- 原因：零件高度是 0。
- 先試：查規格書的厚度，用 `update_part(height=…)` 填入（0603 厚度會因容值、耐壓不同，不要一律套 0.8）。

### `pick offset ... > Max. Pick Tolerance`，零件被拋掉
- 原因：吸偏了，或是吸到的不是正確的零件（10/13 是使用者故意放的假 IC）。
- 先試：`place_from_job` 會自動吸下一顆，連續失敗 5 顆才停。如果一直失敗，查吸嘴頭和 feeder 的位置。

## 量取料高度（`probe_feeder_z`）

### `No part vacuum-detected after pick`，零件還留在口袋裡
- 原因（10/22，1uF）：取料 Z 太高 0.05 mm。feeder 存的是 8.25，`probe_feeder_z` 在 8.25 量到 −6635（只是碰到，沒有密合，門檻是 −6900），8.2 才跳到 −7600。差 0.05 mm 就吸不起來。
- 先試：先拍取料點，確認零件還在、XY 有對準。再跑 `probe_feeder_z`（不用 apply），用 `update_feeder` 把 Z 設成 **contactZ − 0.15**。0.05 不夠：直接放在平台上的料帶不平，沿料帶每 8 mm 會差約 0.1 mm（10/22 的 330ohm：Y 300 在 7.45，Y 292 在 7.35）。吸嘴有彈簧，多壓 0.15 沒問題（10/22 用 −0.15 後，11 顆連續成功）。上膜沒撕也是同樣的症狀，兩個都要檢查。
- 注意：`scan_qr_feeders` 會沿用 feeder 原本的 Z，不會重量。料帶重新放過之後，最好每條都跑一次 `probe_feeder_z`。10/22 料帶架上的三條，Z 都和存的值差 0.05–0.2 mm。

### 第一顆放得好好的，第二顆 `No part vacuum-detected after pick`
- 原因（10/22，100nF）：掃描找到的「第一顆」其實是之前 `pick_from_feeder` 試吸後放回去的那顆，它後面都是空口袋。掃描只要看到有零件就停，不會管後面是不是空的。
- 先試：拍取料點附近的照片確認。重跑 `scan_qr_feeders`（apply），它會跳過已經空掉的口袋，找到下一段有料的地方。試吸時用 `return_part=false` 再丟掉，就不會留下單獨一顆。

### 取料或放料時 `Can't move x to 433.5mm, higher than soft limit 433`
- 原因：N1 在上相機左邊約 24.4 mm。相機能拍到 X 433，但 N1 只能取到 **X ≈ 408.6** 以內的零件。
- 先試：料帶放在 X ≤ 405 以內。


### `The vacuum level ... is too close to the contact threshold`
- 10/16 起真空讀兩個位元組，數值是負的、約 256 倍大（空中約 −6560）。舊的 `contact_drop=2` 現在只是雜訊；N045 不給 `contact_drop` 就會用吸嘴頭的 part-on 上限（−6900）。其他吸嘴頭要先用 `read_nozzle_vacuum` 量空的和有零件的讀值，設好 part-on 範圍。
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

### 第一顆離標籤很遠，中間明明有零件（10/16，100 nF 從 Y 184.9 跳到 144.7）
- 原因：照片每次走 8 mm（兩個孔距），孔在每張照片裡的位置都一樣。第一顆零件後面那個孔剛好被畫面邊緣切掉時，每張都會以 `The first part is at the far end of the holes in view` 失敗。
- 已修正：遇到這種失敗時，相機會沿料帶多走 2 mm、4 mm 再拍（只走 2 mm 不夠時 10 nF 跳過了一顆）。照片在 `scan/`；要離線重跑定位，可以寫一個小程式呼叫 `StripTapeLocator.locate`（classpath：`target/classes` 和 `~/.m2/.../opencv-4.5.5-1.jar`，記得先呼叫 `nu.pattern.OpenCV.loadLocally()`）。

### 掃描回報有零件，但吸不到（空口袋被當成零件）
- 原因：`StripTapeLocator.findBlobs` 只找比紙帶暗的長方形，空口袋（看到底下的檯面）也符合。10/16 1uF 料帶靠標籤那段全是空口袋，掃描還是回報 partsFound 2。
- 已修正（10/16）：零件內部要有至少 2% 的像素接近紙帶亮度（端子），空口袋是 0%，會被略過。若之後有端子不亮的零件被略過，調 `StripTapeLocator.MIN_BRIGHT_FRACTION`。
- 先試：看 `scan/` 的照片，零件兩端應該有很亮的端子；沒有就是空的。

### 掃描找到零件、試吸卻是空的，照片裡零件上方有反光的膜
- 原因：透明上膜（cover tape）只撕了一段。相機看得穿膜，所以掃描照樣「找到」零件，但吸嘴隔著膜吸不起來（10/16，1uF 料帶 Y 152 以下）。膜的邊緣還會讓孔反光變形，邊緣附近那顆零件也認不出來。
- 先試：請使用者把膜撕到料帶末端，再重掃。`scan_feeders.py --verify` 的試吸會抓到這種情況（底部視覺 NOT found）。

### `scan_qr_feeders` 往標籤上方（+Y）找料帶
- 原因：MCP server 還在跑舊版，`tape_offset` 的預設值是 +15（`server.py` 已經改成 −14）。
- 先試：明確傳 `tape_offset=-14`，或請使用者打 `/mcp` 重新連線。

### `The strip feeds towards its label`
- 原因：料帶放反了（定位孔在零件的另一側）。
- 先試：把料帶轉 180°。程式目前不支援反向料帶，見 ROADMAP 4.5。

### QR 標籤漏讀（有些掃描讀得到、有些讀不到）
- 原因：上相機在 Y 方向只看得到約 10.6 mm，QR 就占了 8 mm。標籤偏離掃描的 Y 超過約 1.3 mm，就會被切掉。
- 先試：加大 `y_tolerance`（預設 4，每排相差 2 mm），或把標籤貼得更整齊。照片有點失焦沒關係。

### `scan_qr_feeders` 讀到 0 個標籤，但照片裡 QR 清清楚楚整個都在
- 原因（10/22）：新的一批標籤 QR 比較小（約 6.5 mm，之前是 8 mm），格子小，失焦後糊掉。OpenCV 偵測得到 QR，但怎樣都解不出內容；zxing 在縮放到 600–1000 px 寬時讀得到。已修改：`QrLabelLocator` 會同時用兩種解碼器。
- 先試：用 scratchpad 的小程式，拿 `ai-bridge/scan/` 的照片離線跑 `QrLabelLocator.find`，不用動機器就能試參數。標籤也可以印回 8 mm。
- **不要用 `camera_properties` 改上相機的 Focus**：10/22 設成 `auto=false, value=5` 之後，Top 立刻斷線（紅色大叉），只能重啟 OpenPnP，而且 freeze 會把這個值存起來，下次開相機時又套用。發生時要先正常關閉 OpenPnP，把 `machine.xml` 裡的 Focus 改回 `auto="true"`、commit，再啟動。

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

### IC 放反 180°（pin 1 在錯的那一端）
- 原因：散料盤的方向程式 `scripts/AD608_Polarity.bsh` 會找 pin 1 圓點，但「pin 1 在哪一端算 0°」要跟板子一致。fm_pnp 的 U1 絲印 pin 1 在 −X 端（rotation 0 時是左下角）；10/27 之前程式用 +X 端（跟 SOP-16 footprint 一樣），所以每顆都反了。已修。
- 先試：比對空板照片（`inspect/bare/…-U1.png`）裡的絲印圓點，和放好之後 IC 上凹點的位置。`inspect_feeder` 看散料盤：凹點在 −X 時 rotation 應該是 0。
- `inspect_placements` 分不出 pin 1，IC 放完都要看一次照片。換新板子或新 IC 時，先確認約定。
- 10/27 也發現找圓的步驟（直徑 24–50 px）根本抓不到凹點（約 90 px），抓到的是雜點，方向只是碰巧對。現在 script 會擋下不在本體端點上的圓（報 `AD608 Pin-1 mark not found on an end of the body`）。看到這個錯誤時，表示 pipeline 的圓直徑還沒調好。

### 驗收時每一顆都判定 `check`（「something else changed」、尺寸不符）
- 原因：一次放完一整片時，每顆的「放置前照片」拍完之後，旁邊的零件才陸續放上去（尤其最後放的 IC），所以照片裡到處都是變化。
- 先試：直接拍整片板子用眼睛看。待改：整批放料前先統一拍一次，或改用空板照片比對。

### 搞不清楚 Brd1–4 是哪一片
- 拼板 `fm_pnp`：Brd1、Brd2 是 Y ≈ 130 的那一排，Brd3、Brd4 是 Y ≈ 155 的那一排；Brd1、Brd3 在左邊（X ≈ 316），Brd2、Brd4 在右邊（X ≈ 333）。

## 機器與 OpenPnP

### `FIDUCIAL-HOME no matches found`，歸零失敗
可能的原因，依常見程度排列：
1. **OpenPnP 找不到相機**（10/16）：`capture_camera` 拍到的是灰底加紅色大叉，不是真的照片；log 有 `No camera found with ID ...` 或 `Camera Top failed to return an image`。見下一條。
2. 底部相機被蓋住了（10/13 是一個塑膠袋）。先看底部相機有沒有被遮住。

### 相機只有紅色大叉、`No camera found with ID ...`，或上下相機對調
- 原因：兩台相機（Alcor `058F:0363`）沒有序號，Windows 用它們在 USB 樹裡的位置產生裝置路徑（例如 `8&1654aaea`），`machine.xml` 的 `unique-id` 記的就是這個路徑。**沒有換孔也可能改變**：重開機、拔插、hub 重新列舉之後都可能變（9/8、10/16 都發生過）。另外 Windows 相機 App 之類的程式占著相機時，OpenPnP 也打不開，而且之後不會自己重開，要重啟 OpenPnP。Windows 給的名字（LumenPnP Top／Bottom）不可靠，不能拿來判斷哪台是哪台。
- 先試：
  1. 關掉 Windows 相機 App 等可能占用相機的程式，**正常關閉 OpenPnP**。
  2. `python ai-bridge/fix_cameras.py`：比對設定和 Windows 現在看到的相機。印 OK 就不是路徑的問題，檢查 USB 線。
  3. 路徑對不上就加 `--apply`：寫入新路徑並在 `~/.openpnp2` commit。兩台都換了路徑時，哪台是 Top 是猜的。
  4. 啟動 OpenPnP、歸零之後，**一定要拍上相機確認**：在歸零位置要看到十字靶 fiducial，而且噴頭移動時畫面要跟著變。如果看到的是失焦、不會動、往上看噴頭的畫面，或 GUI 的 Bottom 視窗是上下顛倒的平台，就是接反了：關閉 OpenPnP，執行 `fix_cameras.py --swap`，再啟動、**重新歸零**。
- 注意：**歸零成功不代表上相機接對了。** 10/16 上下接反時，fiducial 歸零在底部相機的畫面裡誤判成功，座標因此可能偏掉。接反時做過的歸零都不算數，改好之後要重新歸零。
- 10/16 起 OpenPnP 會自己處理一部分：找不到記錄的路徑時，改用同型號（VID/PID）、還沒被另一台相機用掉的裝置，log 寫 `No camera found with ID ... using ... of the same model instead`，新路徑在關閉時存進 `machine.xml`。兩台都換了路徑時一樣是猜的，所以下一條的檢查還是要看。

### `Top camera picture did not follow the head: ...`（`home_machine`）
- 原因：bridge 歸零後把噴頭沿 X 移 3 mm，上相機的畫面應該跟著平移約 3 mm 的像素數（phase correlation）。沒移、移錯方向或移太多，就代表「Top」其實是固定往上看的底部相機（上下對調），或上相機沒看到平台。
- 先試：看結果裡 `topCameraCheck.photos` 的兩張照片。是往上看噴頭的失焦畫面 → 關閉 OpenPnP，`fix_cameras.py --swap`，再啟動、重新歸零。是平台但幾乎沒有紋理（整片同色）→ 檢查可能誤判，換個位置用 `move_camera` 拍照確認。

### `Effective Safe Z coordinate ... is outside Safe Z Zone`，吸嘴卡在下面
- 原因：N2 開了 dynamic safe Z，吸著有高度的零件時 Safe Z 會超出 Safe Zone。
- 先試：這時 bridge 也抬不起來，要在介面上手動 jog Z。N2 的 dynamic safe Z 已經關掉了。

### 散料 feeder `No AD608 rectangle found`，之後 feeder 被停用
- 原因：格子裡沒有零件，或零件偏到格子邊緣。OpenPnP 的 job processor 在 feed 失敗時，會自動停用 feeder。
- 先試：把零件推回格子中間，再用 `update_feeder` 重新啟用。

### OpenPnP 關不掉（log 有 `Shutting down...`，但程序沒有結束）
- 原因（10/16 查到）：**OpenPnP 在跑的時候重新建置了 jar**。關閉時要載入還沒用過的 class，jar 已經被換掉，`stderr.log` 出現 `NoClassDefFoundError thrown from the UncaughtExceptionHandler in thread "AWT-EventQueue-0"`，`quit()` 卡在中途，沒有呼叫到 `System.exit`。
- 先試：強制結束（設定可能沒存，先看 `~/.openpnp2` 的 `git status`）。以後要先關閉 OpenPnP，再 `mvn package`。

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
