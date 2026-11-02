# VoWiFi/IMS 單一 Cell ID 觀測：五個延伸研究方向

本文件整理截至 2026-09-23 的標準、原始研究、本專案已有的結果，以及下一輪**自有或明確授權裝置**實驗。研究問題是：每次新探測最多取得一個 caller 可見的 Cell ID；在這個限制下，還能推斷什麼、能否改善位置估計，以及能否可靠偵測離開某個地方並減少探測量？

## 先固定測量邊界

- [3GPP TS 24.229 §7.2.15](https://www.etsi.org/deliver/etsi_ts/124200_124299/124229/19.03.00_60/ts_124229v190300p.pdf) 將 `Cellular-Network-Info`（CNI）描述為 UE 經非蜂巢式接入 IMS 時，向服務提供者傳送**最近駐留過的蜂巢小區**資訊。LTE 識別資料可拆出 MCC、MNC、TAC、ECI；`cell-info-age` 是 UE 取得小區識別後的秒數，但計算方式依裝置／電信商實作。這些都是同一個識別資料的不同層級，不是數個獨立定位測量。
- 標準允許 UE 將 CNI 放進符合條件的 SIP 訊息；代理在標準程序中不應修改其值，並應在離開信任網域時移除。CNI 被列為敏感資訊；標準亦寫明 UE 不預期收到它。**我們能在 caller 捕獲 CNI，但只有 caller 一端的封包，不能直接證明是哪個 IMS 節點讓它抵達、或界定信任網域，因此暫不定性為特定網路的標準違反。**
- `P-Access-Network-Info`（PANI）描述 SIP UA 使用的接入網路，並受信任網域限制，見 [RFC 7315 §4.4](https://datatracker.ietf.org/doc/html/rfc7315#section-4.4)。本專案已檢視的**incoming** 封包沒有 PANI；不能將標準規定的欄位當成已可用特徵。
- [RFC 3261 §21.1.5](https://www.rfc-editor.org/rfc/rfc3261.html#section-21.1.5) 的 `183 Session Progress` 是一般會話進展，不能單靠它判定目標已響鈴、已接通或位於某處。
- `LocationData` 的 GPS、目標端服務／鄰區 cell、RSRP/RSSI、Wi-Fi、電源狀態是**驗證真值或實驗條件**。若正式應用仍只從 caller 探測，這些資料不能作為推論輸入；否則會高估可用能力。

## 已有證據與限制

| 證據 | 目前能說什麼 | 不能說什麼 |
|---|---|---|
| [SIP 兩日 A-B-A 實驗](SIP_OBSERVABILITY_RESULTS.md) | 第一天熱點延遲較高，第二天未重現；Contact、SDP、Record-Route、來源等路徑欄位也沒有跨日穩定差異。 | 不能把這些欄位或延遲當作已證實的目標 Wi-Fi／位置分類器。 |
| [固定點 cell 序列分析](CELL_SEQUENCE_STUDY.md) | 276 次結構化嘗試中 212 次取得可解析 cell；固定目標仍常切換原始 cell。三連續新 cell 規則有 5 次固定點誤報；同規則用 eNodeB 群組在這些區塊為 0 次誤報。 | 尚未測過「真的離開」的召回率；0 次固定點誤報不能推出能可靠偵測移動。 |
| 新鮮目標端 modem 比對 | 只有 76 次目標端讀值年齡 ≤5 秒；其中原始 cell 相同 45 次、eNodeB 群組相同 73 次。 | CNI 不可直接視為當下目標端已註冊 cell。 |
| 離線跨日固定點定位 | Day 2 有 94 筆取得 Day 1 校準的 cell 估計，中位距離誤差 52.5 m，但 **0/94** 落在訓練推導的「90% 半徑」內。 | 不能宣稱「50 公尺精度」或已校準的信賴區間；缺少移動路線及不同地點驗證。 |
| 歷史外部定位查詢 | 212 次舊查詢都回 HTTP 404；程式已修正請求中的 `radioType` 位置。 | 修正後尚無經授權的實際查詢結果，無法將該 API 列為有效基準。 |

補充的封包盤點：既有 7 個固定點區塊的 incoming INVITE 相關訊息中，100/181/183/487/500 分別有 277/2/399/282/1 份；183 的 329 份、487 的 216 份搭載 CNI。這些是**封包副本數，包含重傳，不能當成獨立探測數或估計欄位出現機率**。觀察到的 CNI `cell-info-age` 均為 0；其值的定義與本地新鮮 modem 比對的落差，說明 0 不能獨立證明是現在註冊的小區。為免透露識別資料，本文件只記錄聚合觀察。

## 五個方向：證據、假設、實驗與判準

| 方向 | 文獻提供的可行線索 | 下一個可驗證假設 | 最重要的反例／失敗條件 |
|---|---|---|---|
| **1. 封包額外資訊** | CNI 自身有 RAT、MCC/MNC、TAC、ECI、age；其他 SIP/SDP 欄位可能隨路徑變化。 | 在只用 caller 可見特徵時，新增欄位能否於**新的一天**改善「位置區域」或「離開事件」判定？ | 變化其實來自 caller、IMS、時間或重傳；前次 A-B-A 已有未重現的警訊。 |
| **2. 更精確的位置** | 3GPP 的 E-CID 依賴額外無線測量；[CAPS](https://www.usenix.org/conference/mobisys-2011/energy-efficient-positioning-smartphones-using-cell-id-sequence-matching) 用已記錄路線的 cell 序列比對；[Cell*](https://conferences.sigcomm.org/co-next/2014/CoNEXT_papers/p121.pdf) 用基地台拓樸及 GIS 推斷路徑。 | 在**同一條授權路線**，配對 GPS 訓練的 cell 覆蓋分布及歷史序列，能否在未見過的日期改善單一 cell 的區域估計？ | 文獻使用連續端側或電信商資料、網路拓樸；我們只有每探測一個 cell，不可搬用其精度數字。 |
| **3. 離開偵測＋少探測** | [Cell*](https://conferences.sigcomm.org/co-next/2014/CoNEXT_papers/p121.pdf) 觀察到靜止時仍可能連到多個小區，短距離移動也可能不發生切換；[Android geofence 文件](https://developer.android.com/develop/sensors-and-location/location/geofencing) 說明停留確認與延遲的權衡。 | 在同樣 10 秒探測資料上，區域機率＋連續確認能否比 raw cell／eNodeB 規則更少誤報且及時抓到真正離開？之後能否以較少探測保住表現？ | 人在同一 cell 覆蓋內越過邊界；或人不動、cell 卻改變。這兩種情況都要刻意採集。 |
| **4. 資訊真正來源** | 3GPP 定義 CNI 的 UE 與代理程序；RFC 7315 也允許接入網資訊經網路處理。 | 將 caller、target、IMS 路徑因素獨立改變，能否定位每個候選欄位對哪一方的變化有穩定反應？ | 只看 caller 封包，即使欄位跟目標變化相關，也無法證明它最初由目標 UE 寫入。 |
| **5. AI 應用** | 序列比對、機率模型與路徑匹配都可處理含雜訊的 cell 記錄。 | 有足夠多天、多地點、多次進出事件後，簡單序列模型能否在隔日測試中超過固定規則並給出可信的不確定性？ | 276 次固定點探測只有 7 個區塊且沒有移動正例；隨機切分會洩漏同一區塊／路線資訊，深度模型目前沒有合理訓練資料。 |

### 1. 封包：先問「有沒有」，再問「增加多少資訊」

| caller 可見候選資訊 | 可能提供的推斷 | 目前的證據地位 |
|---|---|---|
| CNI 的 RAT、MCC/MNC、TAC、ECI 與 eNodeB/sector | 最近駐留的無線制式／網路與小區層級，或較粗的網路區域；需配對地圖才談實際地理位置。 | 有直接觀測；後幾項是同一識別碼的分解，不是新量測。 |
| CNI 的 `cell-info-age` 與是否出現 | 可能協助判斷資料時效或訊息路徑是否改變。 | 既有 CNI age 全為 0，且新鮮 modem 一致率不完整；不能據此判定實際時效。缺失也可能是路徑／實作因素。 |
| SIP 狀態碼、到達順序、延遲 | 可能反映會話進展、可達性或網路路徑。 | 既有 first provisional 都是 183；延遲及額外欄位的 Wi-Fi A/B 關聯未跨日重現；不能推斷目標已響鈴、正在移動或使用某 Wi-Fi。 |
| Contact、SDP、Record-Route、Server、回應來源 | 可能區分代理／媒體路徑與網路實作。 | 可觀測但不等於目標手機 IP 或物理位置；要用來源對照確認是否對目標變因有獨立反應。 |
| PANI、目標 Wi-Fi RSSI、鄰區強度、Timing Advance、電池 | 若可見，可能有助於接入網或精細定位。 | 本次 incoming 封包無 PANI；後四者目前只在目標端／網路端或根本未測得，不能當成 caller 特徵。 |

1. 以每個新 INVITE／Call-ID 為單位，列出第一個 183、後續 183、487、其他回覆的**欄位存在率**；重傳另計但不當作樣本。包括 CNI 存在與否、RAT、MCC/MNC、TAC、ECI、eNodeB/sector、`cell-info-age`，以及 Contact、SDP `o`/`c`、Record-Route、Server、回應來源、狀態碼、單調時鐘延遲。要記錄其訊息類型和出現順序。
2. 分開看「新的物理測量」與「同一 cell 的重新編碼」。TAC、eNodeB、sector 可增加可解釋性，但若它們由 ECI/TAC 解出，不能算獨立資訊。現有 incoming PANI 不存在，只有未來真的出現才納入。
3. 對有候選變化的欄位，做 target 狀態變更、caller 狀態變更、完全不變的控制區塊，順序反轉並跨日重複。第一天見到的模式先凍結，第二天才做正式測試。
4. 比較 `cell alone`、`cell + history`、`cell + validated SIP features`；指標是隔日的定位誤差／區域包含率，或離開事件的誤報、召回、偵測延遲，而非只看某欄位有沒有變。

**判準：**只有特徵在目標變更時重現、在 caller／不變控制下不產生同樣模式，且提高隔日留出測試表現，才稱「新增可用資訊」。`183` 不是響鈴證據；錯誤碼也可能由中間節點產生，需目標端獨立記錄核對。[RFC 3261](https://www.rfc-editor.org/rfc/rfc3261.html)

### 2. 位置：先建立可信的區域，再追求更小的區域

- [3GPP TS 36.305 §4.3.3、§8.3](https://www.etsi.org/deliver/etsi_ts/136300_136399/136305/14.01.00_60/ts_136305v140100p.pdf) 將 Cell ID 定位與 E-CID 區分；E-CID 可用 RSRP/RSRQ、時間差、Timing Advance、AoA 等**額外測量**。目前 caller 可見 CNI 沒有這些數值，不能稱為 E-CID 或多基地台三角定位。目標端記錄了無線訊號也只是驗證／機制研究的輔助，非 caller-only 推論特徵。
- [最近基地台謬誤實測](https://www.sciencedirect.com/science/article/pii/S0198971522000709) 發現多數觀測位置不在服務基地台的 Voronoi 區，故塔台座標或「最近基地台」不能當目標座標。應由配對 GPS 建立每個 cell 的**經驗覆蓋範圍**，並在未參與訓練的日期校準。
- 比較四個預先定義基準：單一 cell 經驗區域；eNodeB 經驗區域；前 k 次 cell 序列／已知路線模板；在外部服務回覆有效時的單 cell API 結果。[Google Geolocation API](https://developers.google.com/maps/documentation/geolocation/requests-geolocation) 要求已知 RAT 時在請求根層放 `radioType`，LTE ECI 是 cell ID；其回傳 `accuracy` 是服務提供的半徑，仍須以 GPS 真值驗證。修正後外部查詢目前**尚未驗證**，任何傳送歷史識別資料到外部服務都須另有明確授權。
- 位置輸出以區域／機率表示，未知 cell 應棄權。主指標：可輸出比例、實際距離誤差中位及 P90、宣稱 90% 區域的實際包含率與區域面積；另依固定點／路線、已見／未見 cell 分組。不能單看點估計平均誤差：若 90% 半徑只包含 0/94，該半徑便無校準意義。

**假設限制：**同一 cell 多次回覆不會自動製造新的幾何量測；cell 序列只在有地圖、已學覆蓋模式、路線限制或轉移先驗時可能縮小不確定性。CAPS 和 Cell* 是方法靈感，資料量與可見度均與本研究不同。[CAPS](https://www.usenix.org/conference/mobisys-2011/energy-efficient-positioning-smartphones-using-cell-id-sequence-matching)、[Cell*](https://conferences.sigcomm.org/co-next/2014/CoNEXT_papers/p121.pdf)

### 3. 離開：把「離開」與「cell 改變」當兩個不同事件

1. 先定義地點邊界。用目標端 GPS 真值與其 accuracy 形成「確定在內」「邊界不確定」「確定在外」；不確定時不強迫貼上內／外標籤。預先選幾種邊界尺度，例如建物周圍、街區、較大生活圈，找出這個方法可用的尺度。
2. 每天採固定點在內、固定點在外、離開、返回、邊界附近短暫移動；特別安排**不換 cell 的離開**與**不移動卻換 cell**。實際路徑在安全範圍內預先固定，不要事後挑最容易的路線。
3. 凍結基準：raw cell 改變、三連續新 raw cell、三連續新 eNodeB、校準的 `P(仍在此區 | caller 可見歷史)` 加連續確認。固定點實驗的 5 次與 0 次誤報只作為先驗，不能替代移動測試。
4. 比較固定 10 秒探測與保守回退排程，例如在不確定時保持 10 秒，穩定時逐步試 30/60/120 秒但設最長間隔。先以 10 秒資料**重播較慢**的排程，計算少了多少探測、誤報／小時、實際離開召回率、從 GPS 越界到警報的延遲，以及同 cell 越界漏報；再做前瞻實驗。10 秒資料不能評估比 10 秒更快的採樣。

**可識別性底線：**若邊界內外產生完全相同的 caller 可見觀測序列，任何規則或 AI 都無法從該序列判定越界。cell 一直相同只可降低變動證據，不能證明目標未移動。[Cell* 的原始論文](https://conferences.sigcomm.org/co-next/2014/CoNEXT_papers/p121.pdf) 也報告其未辨識出的移動路段主要是同一覆蓋範圍內的短途移動，但數字不能直接套用在本探測機制。減少探測必然要報告漏報與延遲代價。Android 的 geofence 是目標裝置本地的不同機制，僅供「停留確認／延遲權衡」的設計參考。[Android 文件](https://developer.android.com/develop/sensors-and-location/location/geofencing)

### 4. 來源：建立因果對照與明確的證據層級

| 可觀測層級 | 例子 | 它能支持的結論 |
|---|---|---|
| caller 收到的位元／封包 | 183 的 CNI、Contact、SDP、回應來源與時間 | 「caller 確實收到」及「與某條件相關」，不能直接指出最初寫入者。 |
| 目標裝置獨立紀錄 | modem cell、GPS、Wi-Fi、手動事件 | 檢查訊號是否跟目標狀態同步、是否過時；正式 caller-only 模型不得讀取。 |
| 目標側／IMS 邊界紀錄（若合法且可取得） | 目標送出前 SIP 或電信商授權節點紀錄 | 才能分清 UE 原始寫入、IMS 中途保留／移除／生成；目前尚無。 |

做 2×2 交叉控制：目標 Wi-Fi A/B 與 caller 網路 A/B 個別切換，並保留 A-A 不變控制；固定兩端位置、時間窗口、裝置版本與探測設定，使用 ABBA/BAAB 反序、跨日重複。另對 cell 來源做「目標移動、caller 固定」與「caller 移動、目標固定」；目標 modem 年齡需記錄並以新鮮讀值比對。每個欄位歸類為「目標相關」「caller／路徑相關」「不穩定／不明」。如果沒有目標側或網路節點的可驗證紀錄，結論限於**統計來源**，不宣稱找到產生 CNI 的確切元件。標準提供程序先驗，但不能替代本電信網實測。[3GPP TS 24.229 §7.2.15](https://www.etsi.org/deliver/etsi_ts/124200_124299/124229/19.03.00_60/ts_124229v190300p.pdf)

特別追查同一次 Call-ID 的首個 183 與後續 183／487：CNI 值、age、欄位存在性何時改變？目標端 modem 何時換 cell，而 caller 何時第一次看見新值？這可區分「像是最近駐留 cell 的快取」與「每次探測重新讀取」兩種行為。既有 incoming 訊息有 CNI 卻未見 PANI，也值得逐訊息覆核及比較不同條件；單靠這個現象不能判定哪個節點移除了 PANI 或是否違反標準。

### 5. AI：先把問題縮小成可評分的任務

| 任務 | 基本模型／規則 | 值得嘗試的 AI | 只有達到什麼條件才升級 |
|---|---|---|---|
| 位置區域 | 每 cell 經驗分布、已知路線模板、未知則棄權 | HMM／轉移模型，或帶校準的輕量分類器 | 在獨立日期／路線提高包含率或縮小區域，且維持校準。 |
| 離開警報 | 三連續 raw cell／eNodeB、固定 dwell | 使用 caller 歷史的機率變點／序列模型 | 相同探測預算下，隔日減少誤報且不犧牲預定召回與延遲上限。 |
| 探測排程 | 固定 10/30/60/120 秒，保守回退 | 依越界風險／不確定性選下次探測時間 | 在相同漏報與延遲上限下，前瞻實驗確實減少探測量。 |

僅用 caller 在實際應用時可見的特徵訓練；目標 GPS、目標 cell、目標 Wi-Fi／RSSI 只能當標籤或診斷。按**整個日期／區塊／地點／路線**分隔訓練與測試，不隨機分割單次探測；[GroupKFold](https://scikit-learn.org/stable/modules/generated/sklearn.model_selection.GroupKFold.html) 的原則是同組樣本不跨訓練與驗證集。報告 `cell`、`cell+history`、`cell+validated SIP` 的消融比較，以及棄權率和校準。先用簡單模型；目前全是少數固定點區塊，尚無支持移動偵測模型的正例。

LLM 可協助整理 SIP 欄位字典、標記待測假設與審閱聚合報告；它不能從缺失的無線測量憑空推回幾何位置，也不應把原始敏感封包送入外部服務。定位與警報應以可重跑、可量化錯誤的模型／規則決策。

## 建議下一輪：一個資料收集計畫，回答三個核心問題

現有 [cell 序列計畫](CELL_SEQUENCE_STUDY.md) 的兩日 S1–M–S2（每天 30/60/30 次、最短 10 秒）可作**第一個移動試行**。若要同時檢驗五個方向，須再收集邊界路線、caller/target 獨立變因與更多跨日重複，避免把少數區塊的多次探測當成多個獨立事件：

1. **路線與地點：**先用目標端資料選並固定一條跨至少兩個 eNodeB 的路線，再另選一段在同 cell／eNodeB 內越過預定地點邊界的路線。兩條都必須納入，不可只報成功路線。
2. **負對照：**各有固定點在內／外、caller 變而 target 不變、target 網路變而位置不變，以及兩端都不變的區塊。延遲／SIP 候選特徵只在跨日複現後評分。
3. **對齊與完整性：**每次 fresh Call-ID 一筆；保留訊息順序與是否重傳；caller 用單調時鐘量延遲。目標端 1 Hz 左右 GPS 與 modem 讀值保留時間戳、accuracy、age；手動標出真正開始移動、越界、返回。不要用不同手機的時鐘相減得延遲。
4. **先凍結、再驗證：**第一天只作試行與訓練／選閾值；第二天不改規則作首次測試，再在額外日期與地點重複，才評估應用可行性。以**離開事件、固定點時段、日期**作統計單位，並呈現不確定性；每 10 秒一次得到的大量點不是同樣數量的獨立證據。至少報表：封包欄位存在率、位置可輸出率與誤差／90% 包含率、每種邊界的離開召回與誤報／小時、警報延遲、探測次數、目標 modem 新鮮度。
5. **正式結論以最弱連結決定：**若同 cell 越界的召回低，不能做可靠的小尺度離開警報；若 90% 區域覆蓋不達標，不能宣稱精確定位；若額外 SIP 欄位跨日失效，AI 不應使用它們。結果可以是「只能判斷較大區域」或「在特定路線與時間上限下可做警報」。

原始封包、座標、電話、IP、Call-ID 與憑證持續存放於私有忽略目錄，Git 只收聚合結果與方法；所有測試限自有或明確授權裝置與網路。

## 主要文獻索引

- [3GPP TS 24.229：CNI 定義、UA／proxy 程序與隱私（§7.2.15）](https://www.etsi.org/deliver/etsi_ts/124200_124299/124229/19.03.00_60/ts_124229v190300p.pdf)
- [3GPP TS 36.305：Cell ID 與 E-CID 的定位測量](https://www.etsi.org/deliver/etsi_ts/136300_136399/136305/14.01.00_60/ts_136305v140100p.pdf)
- [RFC 7315：P-Access-Network-Info](https://datatracker.ietf.org/doc/html/rfc7315#section-4.4)；[RFC 3261：183](https://www.rfc-editor.org/rfc/rfc3261.html#section-21.1.5)
- [CAPS：已學路線上的 cell 序列比對](https://www.usenix.org/conference/mobisys-2011/energy-efficient-positioning-smartphones-using-cell-id-sequence-matching)；[Cell*：電信商資料加網路拓樸／GIS](https://conferences.sigcomm.org/co-next/2014/CoNEXT_papers/p121.pdf)
- [The fallacy of the closest antenna：服務 cell 與 GPS 實測](https://www.sciencedirect.com/science/article/pii/S0198971522000709)
- [Google Geolocation API：請求格式與回應](https://developers.google.com/maps/documentation/geolocation/requests-geolocation)
- [Android geofence：停留、精度與延遲](https://developer.android.com/develop/sensors-and-location/location/geofencing)
- [scikit-learn GroupKFold：群組不重疊驗證](https://scikit-learn.org/stable/modules/generated/sklearn.model_selection.GroupKFold.html)
