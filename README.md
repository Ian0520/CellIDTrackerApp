# CellIDTracker (Android Port)  

### 新增ImsProbe.kt(用於Run Report) 
### 新增RootShell.kt(用於使用root shell指令，很重要) 
### 修改MainActivity 
1. 刪除echo
2. 新增echo victim_list, 新增victim_list
3. Run Report增加last assess，ims_register, ims_rat又爆炸了，但不重要
4. Run Original可以正常輸出、擷取cell id資訊
5. 加入Stop Original
6. Stop Original可以正常停止，不會閃退

## 可做調整 
1. 新增地圖
2. 過濾run original的輸出，現在的輸出內容很雜很亂
3. 美化UI
4. 刪除victim from victom_list
