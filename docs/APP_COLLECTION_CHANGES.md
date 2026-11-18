# 採集 App 修改（2026-11-02）

本輪支援「LocationData 本機建庫 → 離線比較定位方法 → caller probe 驗證」。尚未執行現場採集。

## Experiment Session 簡化（2026-11-10）

- CellIDTracker 的實驗 session 自動啟用完整 SIP capture，移除開關及 campaign／condition／notes 輸入。
- Campaign ID 在內部自動產生並沿用既有值；condition 使用自動 session ID，notes 保留空字串以相容既有匯出。
- 先按 **Start session**，再按 **Probe**。起始 capture 失敗不會留下正常錄製中的 session；啟動／還原／匯出期間避免重複操作。
- 舊 session 若缺少可恢復的 capture，會提示先匯出再開始新 session，匯出也會保留 capture 不可用的警告。
- 建築物／樓層／點位仍在 LocationData 標記。兩邊以時間及 session 對照配對，沒有自動同步或強制相同的 campaign／condition。
- 驗證：24 項 session 啟動／取消／回復與 capture 匯出相關 unit tests 通過，debug APK 建置成功；未執行實機 probe。

## Smoke test 後的 SIP capture 修正（2026-11-10）

- 修正完整 outgoing SIP request 被標成 `recognized_sip=false`、導致 sanitized export 移除 payload／body 的問題。Capture 以完整第一行辨認 request，再從實際 header 取得 method、Call-ID、Via branch；沿用原本的通話處理 parser。
- 支援 compact header、大小寫及參數等號旁空白。Sanitizer 同步涵蓋 SIPS URI、compact Call-ID、帶空白的 credential header 與 branch／tag；非 SIP payload 仍不匯出。
- 以家中 smoke test 的原始資料離線重播：38 個 payload 中，11 個 outgoing request 全數辨認（REGISTER 2、SUBSCRIBE 1、INVITE 4、CANCEL 4），4 個非空 INVITE body 經 sanitizer 後保留 SDP 媒體／codec 資訊；19 個 response record 保留，8 個無法單獨辨認的 transport 片段被移除。Sanitizer 錯誤為 0。後續實機檢查確認這類片段包含 SIP 訊息續片，不能當成與 SIP 無關的資料。
- Sanitizer 前的 payload／body bytes 與原始 capture 完全相同；原始匯出不覆寫。重播時間與網路位址 metadata 不代表原始量測；sanitized SIP 是分析文字，遮罩後的長度可能不符原始 Content-Length，不能直接當封包重送。
- 驗證：6 項 native host tests、17 項 Android sanitizer／capture health tests 通過，debug APK 建置成功。已重建 bundled ARMv7 executable、更新 checksum，並核對 APK 內 executable 一致；尚未安裝新版或再次實機 probe。

### 第二次實機 smoke test（20261110_015521_216）

- 使用者安裝新版後錄製 634 秒；53 次 probe 全部取得 Cell ID，53 筆結果均與對應的 183 CNI 一致，53 次 geolocation lookup 成功。全程只有一組 Cell tuple，未驗證定位精度。
- 109 個 outgoing request 均被辨認；53 個 INVITE 的 848-byte body 全部完整，且 sanitized export 保留 SDP 媒體／codec 資訊。記錄內與目前手機上的 APK／native executable hash 均符合本機新版。
- 672 筆 native record 序號連續、時間有序，manifest 四個檔案的長度與 SHA256 均符合；回報的丟棄、複製與 sanitizer 錯誤為 0。
- **仍有訊息完整性限制：** capture 記錄的是 transport packet payload，尚未重組 TCP SIP 訊息。58 個 incoming 183（含 5 次相同回應重送）的 SDP body 都分成兩段；raw 可按 TCP sequence 補回完整 body，但 sanitized export 移除後段。兩個 REGISTER 的 header 也跨段。另有一個 incoming UDP record 為不完整 registration XML，尚未確認缺少前段的原因。
- **仍有遮罩缺口：** 該 UDP XML 片段的 `<uri>sip:` 被當作 header name，value 裡有一個 15 位數 SIP user identifier 未遮罩；移除 payload 並未移除這個 structured field。報告不包含該值，但目前 sanitized research export 不宜直接分享。需修正非 SIP 片段的 pseudoheader 處理，並以實際匯出重新驗證。
- `diagnostic.log` 來自最多 600 行的畫面 log，本次只有最後 10 個 probe 的完整結構化 log 事件；session JSON、caller context 與 native capture 仍涵蓋全部 53 次。不能將 diagnostic log 視為全程紀錄。
- 先前將未知 transport 片段稱為「非 SIP」的判斷過強；request 辨認已修好，完整 SIP 匯出仍需訊息重組及完整性檢查。此輪僅讀取與檢查，未修改 App、安裝或啟動 probe。

### 完整訊息重組與遮罩修正（2026-11-10）

- 匯出先以原始 TCP sequence 重組訊息，再遮罩，新增 `native/sip_messages.sanitized.jsonl`。可處理跨檔輪替、順序顛倒、重送、相同 overlap、sequence wrap 與同一段內多個訊息；缺資料、衝突與大小限制不以猜測補齊。
- 訊息以 header 結束位置及 `Content-Length`／compact `l` 驗證完整性；保留 `contributing_sequences`、first-byte 與 completion 時間。輸出順序依 stream finalization，跨 stream 分析須依時間排序。
- 每個 transport record 仍保留；只有獨立驗證完整的 SIP record 才匯出文字。未完成片段的 headers、start line、body 及不受信任的巢狀文字全部排除，修正 XML pseudoheader 的 15 位數 identifier 洩漏。
- 完整訊息的 header 與 XML/body 分開處理。Authorization、authentication-info、WWW-Authenticate 與 Proxy-Authenticate 等 credential／challenge headers 整欄遮罩；sanitized `Content-Length` 重新計算，封包 size metadata 仍描述原始資料。
- Manifest version 2 保存 `sipMessageReassembly`，並對不完整 stream／datagram、gap、conflict、native sequence 缺口及 packet-quality 問題顯示 warning。重試會清除舊 native sanitized 產物，避免較舊的遮罩檔留在資料夾內。
- Native receiver 增大接收 buffer 並偵測 truncation，驗證 IP／TCP／UDP 長度及 ESP 邊界；IP fragmentation 與不支援的 IPv6 extension chain 明確回報。新增 SYN／FIN／RST capture boundary event，避免新資料跨已觀察到的連線邊界拼接。這不等於已支援 IP fragment reassembly。
- 最終驗證：49 項 Android unit tests、7 項 native host tests 通過；debug APK 與 bundled native hash 核對成功。
- 兩次既有 raw capture 經實際 Kotlin exporter 重跑：分別產生 329／30 個完整 SIP messages。第二次試錄的 58 個 183 body、2 個 REGISTER 與 53 個 INVITE 全部保留；無 TCP gap、conflict 或 sanitizer error。對 242 個已知 private values（包含 challenge nonce）的有限檢查，未在新 packet/message 輸出的 structured／decoded views 找到原值。
- 每次試錄仍有 1 個不完整 UDP XML 片段，排除文字並記錄 incompleteness；既有 raw 缺少其前段，無法宣稱已恢復。舊 capture 沒有 TCP boundary event，也不能完全排除快速 tuple reuse。新增 native 診斷及此版 APK 尚待實機 smoke test。
- 原始手機匯出未覆寫。離線驗證產物使用 audit-only key，位於各 session 的 `capture_export_fix_verification/final_native/`，不是原 campaign key 的正式匯出。

### 修正版實機確認（20261110_142320_563）

- 錄製 175 秒、10 次 attempt，APK／native hash 符合最終修正版。手機實際產生 49 個完整 SIP messages；8 個 incoming 183 body、2 個 REGISTER 與 10 個 INVITE 的 framing、packet references、時間與遮罩長度均通過檢查。5 個 manifest 檔案的長度及 SHA256 正確，已知 identifier／authentication challenge value 檢查無殘留。
- Manifest 實際顯示 4 個 IP fragment 與 2 個不支援 extension 的 warning；全部發生在 startup，早於第一個 INVITE。
- 另有獨立的 probe／signaling 問題：前 6 次成功，後 4 次沒有 provisional response，其中第 9 次稍晚收到 400 bad-header-fields。有效 Cell tuple 有 2 組；失敗列的負值 sentinel 不是第三組 Cell。觀察到 outgoing TCP sequence 回退，尚未證明原因。
- 此次只擷取與分析使用者既有試錄，未新增測試、build、安裝或啟動 probe。完整訊息匯出與有限的遮罩檢查已通過實機確認，長時間 probe 穩定性仍需另查。

## LocationData

實際專案：`/home/ian/AndroidStudioProjects/LocationData`。

### 減少手動輸入（2026-11-10）

- 移除 Campaign／Condition 輸入與必填檢查。Campaign 內部自動產生並保留，新 session 的 condition／scenario 都使用自動 session ID；恢復舊錄製時保留其原始 metadata。
- 建築物名稱輸入一次後可點最近選項；樓層以按鈕選取，點位／路線可按 **New point**／**New route** 產生 P01／R01。自訂樓層、代碼、路線及自由文字事件保留在預設收合區域。
- 點位編號按建築物／樓層分開，路線按建築物分開；數字保留不依賴有上限的 recent history，自訂 P／R 數字代碼也會保留。空白差異會先標準化，避免相同匯出範圍重用代碼。
- 冷啟動只沿用建築物；實際樓層／點位／路線須從選項點選，不把過去位置當目前真值。移動／換樓事件先保存離開標籤，再清除變成未知的欄位；較早的事件與 initial label 不受後續編輯影響。
- 已套用六個檔案至 LocationData 原專案，23 項測試通過，debug APK 建置成功並複製至原專案 build output；套用後檔案 hash 與測試來源相同。尚未安裝或操作手機驗證此 UI。
- 此次修改時尚無 Git，另保存[輸入簡化 patch](../patches/locationdata-session-inputs.patch)與[套用前 checksum](../patches/locationdata-session-inputs-base.sha256)。它以先前 independent-radio 修改後的原專案為基準；原檔備份及驗證報告位於 ignored `captures/locationdata_session_simplify_20261110/`。2026-11-18 已建立 LocationData 獨立 Git 儲存庫。

### 既有採樣功能

- 一個 application-owned recorder，由記錄服務持有獨立計時器與位置／活動訂閱，Activity 重建不新增另一組採樣器。
- 每秒請求一次 radio／環境採樣，最多一項讀取進行中；實際間隔仍依 Android 排程與讀取時間而定。無位置更新也保留 radio 樣本。
- 只選唯一 registered primary，否則唯一 registered；沒有 registered 或無法消除歧義時記未知，保留完整原始 Cell 清單。
- `readStatus` 區分正常、空清單、權限、錯誤與無 telephony。顯示採樣缺口、讀取錯誤與本機資料年齡。
- 可選建築物、樓層、點位、路線標籤，以及到點／開始移動／開始換樓／完成換樓的快捷事件。標籤是人工檢查點，不會自動推測樓層。
- Stop/export 凍結樣本；較晚完成的讀取不會追加進已停止的 session。服務與儲存錯誤保留可匯出的 session。

### Schema 7

| 欄位 | 使用方式 |
|---|---|
| `recordedAtMillis` | 採樣時間；跨手機對時使用。 |
| `capturedElapsedRealtimeNanos` | 本機採樣計時；計算 cadence。 |
| `locationFixTimeMillis`、`locationElapsedRealtimeNanos` | 位置 fix 的原始時間；不改成採樣時間。 |
| `elapsedRealtimeNanos` | 保留舊名稱，仍是位置 fix 的時間；無位置時為 null。 |
| `locationStatus` | missing／available／stale；位置逾 10 秒為品質提醒，舊格式另標 legacy。 |
| `lat`、`lon`、位置屬性 | 可以是 null；不能因缺少 GPS 就刪除整筆 radio 樣本。 |
| `servingCellCallerKey` | RAT/MCC/MNC/area/Cell ID，排除 PCI；主要 caller-compatible 模型使用。 |
| `servingCellSelectionStatus` | 選到 primary／唯一 registered，或沒有／多個候選。 |
| `radio.readStatus`、`readErrorReason` | 區分無資料與讀取失敗；保留原始 measurements 及 age。 |
| `initialIndoorLabel`、`manualEvents[].indoorLabel` | 起始標籤及每個事件當時的標籤快照。 |

舊 unfinished journal 可以包含舊格式與新格式樣本；只有新樣本帶 `sampleSchemaVersion=7`。舊資料缺少的新欄位保持未知。`servingCellStableKey` 是去抖後歷史，不作當下新觀察的替代。

Android 本機 API 量測可能有快取；獨立 timer 不保證每次取得新 modem 量測。本輪沿用成功 caller CNI 最新的前提。首次場勘建議保持畫面開啟，檢查實際 cadence；尚未驗證長時間熄屏行為。

## CellIDTracker

- 實驗 session 自動啟用 research capture，匯出會對缺少／空的 native capture、複製失敗、context 寫入失敗、無法解析的 JSONL 及 native 回報丟棄事件顯示警告。
- `manifest.json` 保存 `captureWarnings` 與相關計數；沒有警告不代表已證明封包完整。
- 區分 session JSON 成功與 research export 失敗。一般 session 以外的 probe 不要求 SIP capture。
- 一般 JSON 匯出失敗時可在同一次 App 執行中重試，保留先前 research export 結果；重試狀態目前在記憶體中。

## 驗證範圍

- LocationData：19 項 host 測試通過（含 5 項 Robolectric 整合案例）；App 與裝置測試 APK 建置通過。整合案例涵蓋無位置採樣、fix 時間與 age、鄰近 Cell、null-location 還原、舊 session 服務停止事件。
- CellIDTracker：15 項匯出健康／重試及 sanitizer 測試通過；debug APK 建置通過。
- LocationData 實際套用的 13 個檔案已與測試來源逐一核對 hash；debug APK 也複製至其正常 build output。Patch 已在修改前的原目錄通過 dry run。

手機上的權限、實際 modem 更新速度、Activity／系統背景管理及百貨公司現場資料品質，仍須安裝後短試錄確認。此輪沒有啟動 probe 或採集現場資料。

LocationData 已於 2026-11-18 在原目錄建立獨立 Git 儲存庫；後續 App 原始碼在該儲存庫追蹤。本專案保留[既有修改 patch 與說明](../patches/README.md)作歷史紀錄；兩份 patch 在暫存副本反向套用後重新套用，14 個受影響檔案均與目前來源完全一致。原始封包、log、本機設定與建置產物不納入版本。

## 版本保存與驗證（2026-11-18）

- LocationData 初始版本：`main` 分支的 `94e92e1`，保存 51 個來源與設定檔案。
- CellIDTracker：7 項 native host tests、43 項 Python analysis tests、88 項 Android unit tests 通過，debug APK 建置成功。
- LocationData：23 項 Android unit tests 通過，debug APK 建置成功。
- LocationData 的 ignore 規則已涵蓋既有封包、log、IDE／Kotlin 快取、建置產物與本機憑證設定；這些檔案保留於本機。
