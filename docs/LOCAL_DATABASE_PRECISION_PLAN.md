# 百貨公司 Cell 資料庫與定位精度：目前主線

更新：**2026-11-17（Asia/Taipei）**。

**目前已實作：主叫端去重配對資料庫＋最少換樓歷史追蹤。** 每組 **(完整 Cell 身分、樓層)** 只記一次；收到當次 Cell ID 後，輸出資料庫中對應的完整候選樓層集合。資料庫不使用頻率權重；推論使用最近 60 秒 caller Cell 序列，優先選擇換樓次數較少的路徑，仍允許電梯跨層。輸出單一樓層、相鄰兩層或未知；沒收到 Cell ID 就不預測。先前頻率演算法仍退役，Android 即時介面尚未實作。

**目前只使用主叫端資料庫。** 已合併 11 月 10 日兩次錄製與 11 月 16 日到訪，共 **42 組配對／32 個 caller Cell**。LocationData 保留作樓層參考與時間對照；本機 radio Cell 不加入目前關聯或推論，原始本機資料仍保存。各樓層／區域停留時間不會增加既有配對的權重；未走過的區域與樓層參考誤差仍可能造成缺漏或錯配，不能稱為完全無偏差。

**目前階段：完整 caller 建庫＋同資料回放，不切訓練／測試資料。** 三次錄製的可用配對全部合併。依要求在同一批資料上檢查新歷史演算法，488 筆有樓層參考且有回傳 Cell 的觀察中，得到 469 筆正確單層、9 筆正確相鄰兩層、10 筆未知。六個樓層等權的單層辨識率由無歷史的 63.5% 提升至 83.2%。這不是獨立驗證；詳見 [同資料回放結果](CALLER_FLOOR_TRACKER_REPLAY.md)。

**主線：建立已知場域的 Cell／樓層去重配對資料庫，先理解完整資料中的關聯與模糊情況。** 後續再決定如何發展與驗證；[階段規劃](FLOOR_PREDICTION_LIVE_PILOT_PLAN.md) 已將即時測試延後。離開偵測仍為備案。

## 1. 研究問題與成功假設

問題：在已建立資料庫的百貨公司內，單一完整 Cell 身分能把樓層縮小到哪些候選？哪些樓層仍因資訊不足而無法區分？

先評估每筆查表能否保留真實樓層及候選範圍大小。某 Cell 只在一層被觀察到，不代表其他樓層不會出現。已知場域的成果不表示未建庫的建築物也能直接使用。

## 2. 三階段流程

| 階段 | 工作 | 要回答的問題 |
|---|---|---|
| A 建庫 | 錄製多樓層、多水平區域，經品質檢查後保存唯一 Cell／樓層配對。 | 哪些 Cell 對應單一或多個樓層？ |
| B 目前關聯分析 | 使用全部錄製，整理每個 Cell 的樓層集合、各層單層觀察與重疊、來源到訪。 | 哪些配對可能有區分力？哪些樓層資訊模糊或稀少？ |
| C 之後再規劃驗證 | 待理解關聯並決定後續方法，再安排額外資料與實地測試。 | 目前不執行此階段。 |

目前關聯分析與演算法設計只用 CellIDTracker 的實際 CNI；LocationData 提供樓層參考與品質診斷。本機 radio Cell 暫不參與，不直接互換或補入 caller 配對。

## 3. LocationData 輔助參考採集（不作推論輸入）

### 固定點與樓層

- 沿用同一棟樓的不同樓層，每層包含不同水平位置；跨層點盡量水平對齊。
- 以少量建築外／鄰近其他場所作負例；外部資料可用性和受測範圍另列。
- 每個固定點單獨建立 LocationData session，在開始前點選建築物、樓層與點位代碼。到點 Start，原地停留，仍在該點時 Stop & export；整段共用位置／樓層，不逐筆標記。Campaign 自動沿用，condition／scenario 使用自動 session ID，不需輸入。
- 至少保留一個較長原地段，檢查原地的 ID 分布與切換。

### 室內路線

- 沿一條已知內部路線往返，涵蓋同樓走動與上下樓；改變方向／正常步速，保留少量替代路徑或折返。
- 起終點為已知位置；路線中間及上下樓兩端用少量事件錨點。兩錨點之間保留路段／換樓區間，不用均速插值或高度取整數製造精確真值。
- 先小範圍試錄，檢查實際更新與差異；不必先完成舊手冊的全部趟次或進出數量。

## 4. 資料庫保存與主模型輸入

| 資料 | 用途 |
|---|---|
| 場域、實際樓層、區域、點位、路線、方向、日期／到訪、session 對照 | 位置標籤、切分資料與重現實驗 |
| Caller CNI tuple：RAT、MCC、MNC、area、cell ID | 目前唯一的 Cell 推論來源；不只使用 CID 數字。LocationData registered 狀態僅保留為本機品質診斷。 |
| 記錄時間、radio 量測時間／age、實際取樣間隔及缺口 | 品質檢查與模擬稀疏觀察 |
| 少量人工事件與點位／樓層對照表 | 固定點與路線真值 |
| 鄰區、訊號、Timing Advance、Wi-Fi、位置／高度／速度、電源 | 保存原始診斷資料；目前推論輸入僅為當次單一 Cell |

其他欄位用於品質診斷與建立樓層參考，不參與目前查表推論。位置／樓層真值、已知起點與行程都不能成為隱藏輸入。

### LocationData schema 7 的品質檢查

- 記錄服務以獨立計時器請求 radio 採樣，不再依賴 location callback。時間間隔仍為 best effort；用 `capturedElapsedRealtimeNanos`、radio 量測時間／age 檢查實際 cadence，不能把每次讀取都算成新 modem 量測。
- 只選唯一 registered primary，否則選唯一 registered；沒有 registered 或仍有多個候選時保留未知。原始 `radio.cells` 不刪除，`servingCellSelectionStatus` 說明選取結果。
- 若日後恢復本機比較，可用 `servingCellCallerKey`（完整 RAT/MCC/MNC/area/cell，排除 PCI）與時間；目前主叫端來源仍為實際 CNI。`servingCellObservedKey` 仍保存原本含 PCI 的診斷 key。`stableCell`／confirmed transition 已含去抖，不作每筆原始 Cell 的替代。
- 沒有位置 fix 的樣本也保存。`recordedAtMillis` 是採樣記錄時間；`locationFixTimeMillis` 與 `locationElapsedRealtimeNanos` 是位置 fix 自己的時間；不要把重用的 fix 當成新位置。
- `readStatus` 區分成功、空清單、權限不足、讀取錯誤與無 telephony。UI 的缺口／資料年齡提示協助現場發現問題，不能取代匯出後的品質檢查。
- Android 本機讀值可能是快取，這是本機採集品質問題；caller 成功收到 CNI 最新的前提沿用。
- 舊 schema 6 或更早資料仍可能有鄰近 Cell fallback 與 location-callback 耦合，離線必須檢查 registered 與缺口；不能直接套用新版保證。恢復中的舊 journal 可混有舊格式 rows，新採樣另有 `sampleSchemaVersion=7`。

快捷標記會在 `manualEvents[].indoorLabel` 保存當時填寫的建築物、樓層、點位與路線。固定點仍可一點一 session；移動路線只在 checkpoint／換樓兩端按鍵。標籤不會自動補到每筆樣本，不依高度推定樓層。

LocationData 記住最近使用的建築物／樓層／點位／路線；常見樓層可直接點選，**New point**／**New route** 產生 P01／R01 等代碼。首次建築物名稱輸入後可重用，特殊樓層與自訂代碼仍可在展開選項輸入。重新開啟 App 時，樓層／點位／路線保持未知，從最近選項點回；開始 session 才確認目前選取的起點標籤。開始移動會先存離開點再清除目前點位；開始換樓也清除樓層，抵達後點選實際樓層再標記結束。自動 session ID 不會同步兩台手機，仍以時間與 session 記錄配對。

程式依據：LocationData 的 `GroundTruthForegroundService.kt`、`GroundTruthViewModel.kt` 與 `TelemetrySnapshot.kt`。版本與相容性見 [採集程式修改說明](APP_COLLECTION_CHANGES.md)；本機 API 行為見 [Android TelephonyManager](https://developer.android.com/reference/android/telephony/TelephonyManager#getAllCellInfo())。

## 5. 目前資料分析

1. 合併所有錄製，經既有量測與樓層參考品質檢查後，建立主叫端的唯一配對；本機 radio 配對暫不分析。
2. 列出每個 Cell 的完整樓層集合；不使用出現次數、比例、時間歷史或交集推論。
3. 各樓層分別統計不同 Cell 數、只在該層觀察的 Cell，以及與其他樓層共用的 Cell。
4. 整理各樓層兩兩共享多少 Cell，並列出每個配對來自哪些到訪，協助找缺漏與模糊情況。
5. 所有這些都是現有資料描述，關聯統計本身不切資料；另以同資料回放檢查歷史演算法，不能當作新到訪準確率。單層觀察不是證實只會出現在該層。

## 6. 之後的 Probe 驗證（目前暫緩）

先挑少量代表固定點與一條已知路線，同步記錄 LocationData 與 caller。核對兩端 Cell 對應、時間差與實際 probe 節奏；不因 CNI 最新就預設 Android API 同時一定回傳相同 Cell。

方法凍結後，用另一日期的 CNI 流直接評估，保留沒 CNI、未見過 ID、重啟與記錄缺口的分類。若必須重新訓練／調整對應，使用獨立校準資料，再另留測試資料。

本機在無 Wi-Fi 的外部仍可能記到 Cell；該段在 caller 可能不可觀察。目前只以實際 caller CNI 作推論資訊，不能用本機 Cell 補足。

## 7. 備案與目前下一步

**備案保留：** 實體離開偵測、原地 Wi-Fi-off 與出門對照、SIP 延遲／回應流程分類、固定與動態省 probe。舊手冊的進出 episode 與排程實驗不再是本輪必要採集。

**下一步：** 先看完整資料的 Cell／樓層對應表、跨層重疊與到訪來源，釐清可區分與模糊的樓層。新資料可繼續加入同一資料庫，不指定任何一趟作測試集。需要補資料時，優先增加水平區域與稀少樓層的到訪；每段停留選一次樓層即可。
