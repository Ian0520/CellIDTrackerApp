# 四方向文獻審查：可借用的方法與輸入差異

查找／核對日期：**2026-10-15（Asia/Taipei）**。對應 [實驗與資料蒐集方案](FOUR_DIRECTION_EXPERIMENT_PLAN.md)。

範圍：建築物／區域、樓層／垂直區域、Cell 切換地標、離開區域／減少 probe。**成功取得的 CNI 是當次最新 cell**，依使用者確認評估；一筆只有一個 ID，目標端感測器只供校準與真值。已知場域調查可接受。

本次重讀四篇既有核心研究，新增十一篇核心原始研究，並查核近年的相近方法與部署反例。核心篇目已讀原始方法／評估；無法取得全文的另列，不以摘要補成已核實的實驗結果。數值皆屬原研究，不能當成目前 caller CNI 的能力。沒有直接相同論文也不構成新穎性證明。

## 一、最有用的結論

1. **已有 ID-only 場所／序列方法可借。** PlaceSense、Laasonen、CAPS，以及 2015 年走廊研究提供具體做法。它們仍需要實際場域的 ID 模式有區分力。
2. **多筆單一 ID 可以形成指紋；多塔 RSS 指紋提供的是另一種資訊。** HMM、CTrack 等模型可改寫成 categorical ID 模型，但刪除 RSS／neighbor 後的表現需要重測。
3. **樓層可辨識性取決於部署。** 有分樓層的 cell 就可能有機會；多支室內天線也可能屬於同一 cell。先調查 ID 與樓層關係，避免先假設每層有專屬 ID。
4. **平滑、地圖、已知入口都可能提高結果，也可能掩蓋換樓與離開。** 需要因果模型、無 ID 的先驗基準，以及真實通知延遲。
5. **少 probe 有成熟方法可參考。** 新研究價值應在這個觀察管道實測的事件品質／成本取捨；不能只宣稱換了排程或 AI。

## 二、場所、樓層與序列定位：核心原文

### L01. PlaceSense — UbiComp 2009【既有】

[Kim 等原文](https://www.cs.ucf.edu/~lboloni/Teaching/EEL6788_2010/papers/Kim-PlacesRFBeacons.pdf)，Algorithm／Implementation、Tables 3–4、Figure 5。

- **輸入／前提：** 手機每 10 秒讀附近 Wi-Fi AP；GSM 版本每次只讀一個 connected cell，按分鐘累積 ID。穩定集合與代表 ID 消失／新 ID 出現，加容忍機制判斷進出。三人、30 個腳本場所與各四週日常測試。
- **結果／限制：** 92% 召回、89% 精確率是 Wi-Fi 結果；GSM 鄰近場所常混淆。離開時間回填，圖不含實際決策延遲；未量化查詢節省。GSM 表列召回計數無法由 11/30 直接重算，本文不引用其精確比率。
- **借用：** 單一 ID 的時間集合、穩定／離開證據。不同處：其本機在沒有 Wi-Fi 時仍可觀察 GSM；我們失聯後保留未知。

### L02. Floor Detection with Indoor Vertical Positioning in LTE Femtocell Networks — 2015【既有】

[del Peral-Rosado 等原文](https://spcomnav.uab.cat/docs/conferences/DelPeral_GLOBECOM_2015.pdf)，§IV、Table I。

- **輸入／前提：** 兩層樓、四個實驗部署 femtocell，已知高度；本機接收設備辨認實體 cell ID，約 160 秒測試。
- **結果：** ID-only 樓層正確率 **69.88%**，垂直 RMSE **1.79 m**；超過 98% 屬另有感測器的方法。
- **借用：** 校準 ID→樓層／樓層群。不同處：我們是 CNI 全球身分與實際 serving 選擇，沒有可控部署；不能從 ID 的數字解碼高度，也未驗證商場每層有不同 cell。

### L03. CAPS：Energy-Efficient Positioning for Smartphones using Cell-ID Sequence Matching — MobiSys 2011【既有】

[Paek 等原文](https://cseweb.ucsd.edu/~schulman/class/cse291_w17/docs/paek_caps.pdf)，§4–6、Table 4。

- **輸入／前提：** 約 2.5 秒直接讀目前 cell，六趟 GPS 路線校準；近期五個造訪 ID 作容錯序列匹配，再依 cell 內經過時間內插歷史 GPS；失配可重開 GPS。
- **結果：** 四條主要已學習道路路線的定位誤差中位數 **31～72 m**；不同路線若序列相同無法分辨，沒有入口尺度或偏離偵測率。
- **借用：** 有方向的轉換對／近期序列→已知路段。不同處：我們採樣較稀疏、缺口內的轉換時間未知、無即時 GPS 補救；精確時間內插須與序列辨識分開測。

### L04. Enhancing RFID indoor localization with cellular technologies — 2015【新增，最接近 ID 集合的室內依據】

[Aguilar-Garcia 等出版商全文](https://link.springer.com/article/10.1186/s13638-015-0444-9)，§4、§5.3、§6.3、§7.2。

- **輸入／前提：** 單一四樓 **22.5×2 m 走廊**，63 點各五分鐘、1 Hz 校準；兩部手機分別固定 GSM 900／UMTS 2100。之後另走中央線，用五秒視窗的 serving-ID 集合匹配。
- **結果：** 文中 cellular-only 誤差第 95 百分位約 **10 m**；未清楚區分該曲線是 UMTS 單獨或雙 RAT 聯合。RFID 融合的一公尺級結果另需 RFID 標籤。未見跨日／跨場地驗證。
- **借用：** 同一 ID 可共用，但歷史 ID 集合能形成區域候選。不同處：一個 LTE CNI／較慢採樣不同於其兩手機與固定 RAT；小走廊要與中心點等簡單基準比較，不把 10 m 當通用定位精度。

### L05. Adaptive On-Device Location Recognition — Pervasive 2004【新增】

[Laasonen、Raento、Toivonen 作者全文](https://www.cs.helsinki.fi/group/context/pubs/pervasive04.pdf)，§2–5。

- **輸入／前提：** 本機 cell 切換 ID＋時間，沒有塔座標；把反覆切換形成的圖群聚成個人常去的 bases，學到訪與路徑。三人、六個月資料。
- **結果：** 展示個人地點／旅程辨認；沒有建築邊界或樓層正確率，部分目的地評估排除沒去過的下一地點。單一不可區分 cell 內的不同地方無法解開。
- **借用：** 先學同一場所常見的一組 ID，避免每次切換都報移動。不同處：其本機切換事件較完整；群組需校準才能命名為某建築物／區域。

### L06. A Hidden Markov Model for Localization Using Low-End GSM Cell Phones — ICC 2011【新增】

[Ibrahim、Youssef 原始作者稿（2010）](https://arxiv.org/pdf/1010.3411)，§III–IV、Table II；正式會議 DOI：10.1109/ICC.2011.5962993。

- **輸入／前提：** 每秒一筆 **associated Cell ID＋RSSI**；GPS 校準區域發射機率，圖限制位置轉移，歷史序列估位置。訓練與測試另收。
- **結果：** rural／urban 定位誤差中位數 **93.85／50.34 m**；較長歷史另有延遲代價。單一 serving cell 不等於 ID-only。
- **借用：** 改以 `P(CNI ID｜區域)` 和實際經過時間作因果圖推論；刪除 RSS 是未驗證的改寫。不能照搬其採樣、相鄰格限制與精度。

### L07. CTrack：Accurate, Low-Energy Trajectory Mapping for Mobile Devices — NSDI 2011【新增】

[Thiagarajan 等 USENIX 原文](https://www.usenix.org/events/nsdi11/tech/full_papers/Thiagarajan.pdf)，§4、§6。

- **輸入／前提：** 約 1 Hz、serving＋至多六個鄰居 ID／RSSI，GPS 指紋與道路圖；另有感測器版本。先保留位置候選，再做路徑匹配。
- **結果：** 約 126 小時開車資料，序列處理後道路長度 precision 中位約 **75%**，不做該步約 **50%**；不是樓層正確率。
- **借用：** 保留歧義候選，讓後續 ID／動線逐步限縮。不同處：我們沒有其多塔 RSS；完整路徑回溯不能當即時定位或較早通知。

### L08. CellSense：An Accurate Energy-Efficient GSM Positioning System — accepted TVT manuscript 2011【新增】

[Ibrahim、Youssef 擴充原始作者稿](https://arxiv.org/pdf/1110.3425)，§III–IV、Table III。與較早的會議前身分開。

- **輸入／前提：** serving＋六鄰居的 RSS、1 Hz、GPS 網格指紋；多個業者及另收測試資料。
- **結果：** rural／urban 誤差中位數 **42.43／27.86 m**，第 95 百分位仍 **308.56／273.14 m**。
- **借用：** 機率指紋與區域大小／樣本成本取捨。不同處：我們只能測 ID 頻率／順序替代，重複 ID 不可當獨立證據一直相乘；報尾端與未知，不只中位數。

### L09. The SkyLoc Floor Localization System — PerCom 2007【新增】

[Varshavsky 等作者原文](https://www.cs.toronto.edu/~delara/papers/percom2007.pdf)，§3–5.5。

- **輸入／前提：** 三棟 9／12／16 層建築，多來源 GSM RSS 指紋、1 Hz、樓層校準，再以近期投票平滑；含另收測試與部分跨日／同款不同手機測試。
- **結果：** 一棟旅館精確樓層結果約 **56%→73%**；**97% 在兩層誤差內**屬另一容許範圍指標，不是 97% 精確樓層。
- **借用：** 樓層特徵選擇與平滑。不同處：RSS 不可用；我們二十筆觀察至少跨約 190 秒，不能照搬二十筆平滑而忽略換樓延遲。

### L10. Accurate GSM Indoor Localization — UbiComp 2005【新增，輸入與驗證對照】

[Otsason 等作者原文](https://www.cs.toronto.edu/~delara/papers/ubicomp2005/ubicomp.pdf)，§3–4、Tables 4–6。

- **輸入／前提：** 可程式 modem，六強 cell＋至多 29 額外頻道的強度；密集已知點 RSS 比對。其 onecell 基準仍有訊號強度。
- **結果：** 三棟樓的多頻道 RSS 指紋樓層正確率 **89.08%、97.01%、93.69%**；主要驗證不代表長期跨裝置泛化。
- **借用：** 垂直無線差異與採樣對照設計。不同處：我們沒有這些頻道與強度；不能把 onecell 結果改稱單一 ID 的能力。

### L11. Indoor Localisation Based on GSM Signals: Multistorey Building Study — 2016【新增】

[Górak 等出版商全文](https://onlinelibrary.wiley.com/doi/10.1155/2016/2719576)，§2–4、Table 8、§7。

- **輸入／前提：** 六層、約 50×70 m 建築，多個 GSM RSS；三批獨立採集作學習／驗證／測試，歷史估計濾波。
- **結果：** 最終水平誤差中位數 **4.39 m**，樓層正確率約 **64.21%**。序列測試來自靜態點組成的人工路徑，非真實走動；採集另有 GMLC 作業。
- **借用：** 歷史與分層評分。不同處：我們必須實際走路／上下樓驗證，不能由靜態指紋拼出的路徑推論換樓／離開延遲。

## 三、區域事件與降低查詢：核心原文

### L12. Combining GPS and GSM Cell-ID Positioning for Proactive Location-based Services — MobiQuitous 2007【新增細讀】

[Deblauwe、Ruppel 作者原文](https://www.researchgate.net/publication/4318531_Combining_GPS_and_GSM_Cell-ID_positioning_for_Proactive_Location-based_Services)，§I–III、Table I；[出版記錄](https://eudl.eu/doi/10.1109/mobiq.2007.4450985)。

- **輸入／前提：** 合作式目標端偵測 cell／區域變化並推送；已知 cell 幾何，邊界歧義時用 GPS。模擬持續以 50 km/h 移動。
- **結果：** 不同條件 GPS 開啟時間 **20～87%**，不是省下該比例電池。訊息數對某些策略反而增加，不能當普遍省 probe 證據。
- **借用：** 內部強支持區／邊界歧義區分開處理。不同處：我們沒有目標主動通知與 GPS 補救；經驗上偏向室內的 ID，也不等於已知全覆蓋幾何的 safe cell。

### L13. Detecting State Transitions of a Markov Source: Sampling Frequency and Age Trade-off — IEEE TCOM 2022【新增】

[Champati 等機構原文](https://dspace.networks.imdea.org/bitstream/handle/20.500.12761/1570/Detecting%20State%20Transitions%20of%20a%20Markov%20Source%20-%20Sampling%20Frequency%20and%20Age%20Trade-off-TCOM.pdf?sequence=1)，§III–V。

- **輸入／前提：** 已知狀態轉移模型，只在抽樣時知道真實狀態；依先前觀察決定下次抽樣，數值比較週期排程。
- **結果：** 狀態相依策略能在其 age／frequency 指標下改善取捨，但承認可能漏掉多次轉移；沒有實測離開事件召回保證。
- **借用：** 上次狀態＋經過時間決定查詢。不同處：最新 cell 不一定解開區域內／外；需另評短暫離開再返回、失聯與真正警報延遲。

### L14. Data-Efficient Quickest Change Detection in Minimax Settings — IEEE TIT 2013【新增】

[Banerjee、Veeravalli 作者保存的正式原文](https://vvv.ece.illinois.edu/papers/journal/bane-veer-tit-2013.pdf)，§III–VI、Algorithm 3、Figs.5–6。

- **輸入／前提：** 已知變化前後分布、獨立觀察、單次持續變化。DE-CuSum 用實際已取樣資料的累積證據，決定暫停、取樣與警報。
- **結果：** 在其假設下有漸近理論；高斯模擬減少變化前取樣而增加少量延遲，優於隨機跳過。沒有 CNI／場域實驗。
- **借用：** 把「要不要查」與「何時確認改變」一起比較。不同處：ID 相關、內外分布重疊、離開可返回，無法直接套保證；也不能對 ID 數字做距離減法。

### L15. Age of Incorrect Information for Pull-Based State Estimation of General Markov Sources — 2026 預印本【既有】

[Zanni 等原始全文](https://arxiv.org/html/2608.13248v1)，§II、IV–VI、IX／Table II，2026-08-13。

- **輸入／前提：** 已知轉移模型；成功抽樣立即知道真實狀態，失敗與狀態變化獨立。用錯誤持續時間＋查詢成本決策，提出依狀態等待後連續查詢策略。
- **結果：** 數值測試的策略成本接近模型最佳值；成本不是省下的 probe 百分比，也沒有離開實測。
- **借用：** 小型狀態別等待表與錯誤時間成本。不同處：我們知道的是最新 cell，區域狀態仍可能歧義；位置相關 Wi-Fi 失聯及短暫離開需另計。

## 四、近年相近研究與部署：明列存取限制

### UniCellular — SIGSPATIAL 2023／TSAS 正式卷期 2026

[2023 DOI](https://doi.org/10.1145/3589132.3625635)、[期刊 DOI](https://doi.org/10.1145/3708986)、[CMU 原始研究計畫](https://www.qatar.cmu.edu/projects/unicellular-towards-ubiquitous-and-deployable-context-aware-systems-leveraging-cellular-infrastructures/)。

原始摘要、前頁與作者／機構說明可確認：使用 serving-cell 變化與 **RSS 序列**做樓層辨識。單一 serving tower 仍有比我們更多的訊息。ACM 全文無法取回；作者站無公開 PDF，Paperity 僅前兩頁後截斷，因此不引用摘要最佳正確率作獨立核實的結果。作者記錄的 2024 接受年份與 2026 卷期分開，不假定精確先行出版日期。

**對我們的問題：** 去掉 RSS 後，ID 關聯本身還剩多少樓層辨識力？這需要我們的 ID-only 比較，不能直接照搬 CNN／生成資料的成果。

### TransparentLoc — MobiCom 2023／TMC 2024

[作者原始 PDF](https://juderer.github.io/pubs/zhu2023experience.pdf)、[擴充原文](https://juderer.github.io/pubs/gao2024real.pdf)。原始可搜尋的方法節選顯示：使用多個 serving／neighbor、PCI／頻道與 RSRP，並結合大規模校準和模型。

完整 2023 PDF 超過瀏覽工具大小限制，本機下載也因網路限制失敗；此處只用可核對的原文方法節選說明輸入差異，不引用精度成果。它提示較新 cellular 室內應用仍依賴額外量測；我們不能從 target App 有這些欄位，推成 caller 也取得它們。

### 室內多天線可以共用 cell：官方拓樸反例

[Ericsson 技術原文（2020），Case studies](https://www.ericsson.com/en/reports-and-papers/ericsson-technology-review/articles/critical-iot-connectivity) 的工廠模型採一個 cell／八個分散天花板天線；[2025 Radio Dot 官方說明](https://www.ericsson.com/en/news/2025/6/ericsson-named-top-small-cells-vendor-by-abi-research) 也描述 Combined Cell。

這是可能的部署方式，沒有證明我們選定場域採用它。實驗因此先看實際 ID，而不從看見室內天線推論每樓層應有不同 ID。

## 五、由文獻整理成的實驗優先序

| 優先問題 | 借用來源 | 我們要增加的實證 |
|---|---|---|
| 單筆相同，短期集合／分布能否分辨場所？ | L01、L04、L05、L08 | 真實 caller 序列、鄰近外部負例、跨日／抵達路線、相同 probe 成本。 |
| ID／序列能否區分樓層或粗垂直群？ | L02、L09–L11、UniCellular | ID-only 與有 RSS 方法分開；水平／垂直對照、未知初始樓層、真正上下樓及通知延遲。 |
| A→B／近期順序能否縮小 B 的候選位置？ | L03、L06、L07 | 方向／步速／原地／替代路徑；採樣區間而非假定瞬間 handover。 |
| 事件品質固定時，何時查能省 attempts？ | L01、L12–L15 | Wi-Fi 缺口、原地斷網、短暫離開；因果重播後再前瞻驗證，與固定慢速組比較。 |

最終貢獻要由持續可重現的 caller-only 能力與限制支撐；文獻中的方法可借，不能把不同輸入下的成果直接相加。

## 六、搜尋與核對紀錄

- 搜尋詞組：`cell ID indoor floor localization`、`serving cell ID set indoor localization`、`Enhancing RFID indoor localization cellular`、`Adaptive On-device Location Recognition`、`SkyLoc floor GSM`、`Accurate GSM Indoor Localization`、`Indoor Localisation GSM Multistorey`。
- 序列方法：`cell ID transition landmark positioning`、`CAPS cell ID sequence matching`、`CTrack trajectory mapping NSDI`、`A Hidden Markov Model for Localization Using Low-End GSM Cell Phones`、`CellSense TVT`。
- 排程／事件：`Deblauwe Ruppel cell ID zone proactive services`、`Detecting State Transitions of a Markov Source`、`Data-efficient quickest change detection on-off`、`AoII pull based general Markov sources`。
- 近期反例查找：`UniCellular floor identification`、`3708986`、`single serving cell floor identification RSS`、`Experience Large-scale Cellular Localization`、`cellular floor 2024 2025`、室內 Combined Cell 官方說明。追查作者、出版商／機構原始全文及參考文獻，未以二手摘要作核心技術證據。
- 存取：CAPS 重讀先前保存的原始 PDF，線上取回曾逾時；HMM／CellSense 讀原始作者稿，IEEE 發行站未取回；其餘核心讀上述原始作者、會議、機構或出版商全文。UniCellular／TransparentLoc 的限制已逐項標明。
- 方法審查分由場所／樓層、序列／地標、離開／排程三路核對，再檢查共用實驗的因果性與可觀察範圍。這不是系統性文獻回顧的窮盡性聲明。
