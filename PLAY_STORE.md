# 躺著逛街上架準備文件

> 本文件提供 Google Play 上架所需的權限說明、隱私政策模板、商店描述草稿與資料安全性問卷草稿。

## 1) 權限說明（Play Console 用）

**使用的權限**
- `android.permission.ACCESS_FINE_LOCATION`
  - 用途：App 需要模擬定位並輸出位置點給系統。若未授權，無法啟動 Mock Location。
- `android.permission.FOREGROUND_SERVICE`
- `android.permission.FOREGROUND_SERVICE_LOCATION`
  - 用途：在背景中持續推送模擬位置，需要前台服務與通知。
- `android.permission.POST_NOTIFICATIONS`（Android 13+）
  - 用途：顯示前台服務通知，讓使用者知道模擬定位正在執行。
- `android.permission.INTERNET`
  - 用途：載入地圖圖資（OpenStreetMap/MapLibre）。

**權限使用說明（短版）**
- 本 App 需要定位權限以向系統送出模擬定位點。
- 前台服務與通知權限用於背景穩定執行與告知使用者。
- 網路權限僅用於載入地圖圖資。

**Permission Explanation (English)**  
- Location permission is used to provide simulated location output on the device.  
- Foreground service and notification permissions keep location simulation running reliably in the background.  
- Internet permission is only used to load map tiles.

---

## 2) 隱私政策模板（可直接修改）

> 請將「公司/開發者名稱」與「聯絡信箱」替換。

**隱私政策**

**生效日期：** YYYY-MM-DD

**1. 我們收集的資料**
- 本 App 不會收集可識別使用者身分的個人資料。
- 若你在 App 中建立路線、收藏或執行紀錄，相關資料僅儲存在裝置本機。

**2. 資料使用方式**
- 路線、收藏、歷史紀錄僅用於 App 功能運作。
- App 不會將任何資料上傳到伺服器。

**3. 第三方服務**
- 地圖顯示使用 OpenStreetMap 與 MapLibre。地圖資料由第三方提供，且不包含你裝置的個人識別資訊。

**4. 資料分享**
- 本 App 不會分享你的資料給第三方。

**5. 資料保存與刪除**
- 所有資料存於裝置本機。你可以隨時在 App 中刪除路線或清除 App 資料。

**6. 兒童隱私**
- 本 App 不針對 13 歲以下兒童設計或收集任何資訊。

**7. 聯絡我們**
- 若有問題，請聯絡：your@email.com

---

## 3) Google Play 商店描述草稿（俏皮版 / 不含敏感字眼）

**App 名稱**
- 躺著逛街

**短描述（80 字內）**
- 躺著逛街：給自己安排一條小旅行路線，走走停停剛剛好！

**完整描述（建議 4–6 段）**
- 躺著逛街是一款俏皮又實用的定位小工具，讓你用自己的節奏安排小旅程。
- 地圖上點一點就能畫路線，想快一點、慢一點都可以，還能設定停留時間。
- 喜歡的路線存起來，下次一鍵開走，走累了也能暫停再出發。
- 支援 GPX / KML 匯入匯出，備份分享都方便。
- 不需要 root，也能自由玩出你的節奏感。

**功能重點（Bullet）**
- 路線建立與路線庫管理
- 速度模式、隨機速度與停留設定
- 開始 / 暫停 / 停止控制
- 歷史紀錄與事件記錄
- GPX / KML 匯入與匯出
- 無需 root

**English Version (Playful)**  
**Short Description (≤80 chars)**  
- Liezhe Guangjie: plan cute routes, pick your pace, and go at your rhythm.

**Full Description**  
- Liezhe Guangjie is a playful, handy location tool that lets you plan little journeys at your own pace.  
- Tap on the map to create a route, speed it up or slow it down, and add pause times for a more natural flow.  
- Save your favorite routes, start or pause anytime, and pick up right where you left off.  
- It also supports GPX/KML import and export for easy backup and sharing.  
- No root required—just set your rhythm and go.

**Highlights**  
- Tap-to-build routes on the map  
- Speed modes + random speed + pause times  
- Start / Pause / Stop with one tap  
- Route favorites & history  
- GPX / KML import & export  
- No root required

**上架注意事項**
- 請避免將功能描述成可用於「欺騙性用途」。

---

## 4) 內容分級與合規（建議）

- 目標族群：一般使用者 / 測試人員
- 內容分級：一般（視 Google Play 問卷結果）
- 資料安全性：無收集、無分享

---

## 5) 資料安全性問卷草稿（Play Console）

**是否收集或分享使用者資料？**
- 不收集、不分享

**是否收集以下資料類型？**
- 位置資料：不收集（僅在本機運作，未上傳）
- 個人資訊：不收集
- 金融資訊：不收集
- 通訊錄/訊息/媒體檔案：不收集
- 裝置資訊或識別碼：不收集
- 使用情況/診斷資料：不收集（無分析/崩潰回報 SDK）

**位置權限用途（Play Console 欄位）**
- 用於提供定位模擬功能，並在裝置本機運作，不會上傳或分享位置資料。

**資料是否在傳輸中加密？**
- 不適用（無資料傳輸）

**使用者可否刪除資料？**
- 可以（App 內刪除路線/收藏，或清除 App 資料）

**兒童隱私**
- 不針對 13 歲以下兒童設計或蒐集資料

**Data Safety (English)**  
- Data collection/sharing: None  
- Location data: Not collected (local only)  
- Personal info / financial info / contacts / media: Not collected  
- Device identifiers / diagnostics: Not collected  
- Data in transit encrypted: Not applicable (no data transmission)  
- User data deletion: Supported (delete in app or clear app data)  
- Children’s privacy: Not intended for under 13

---

## 6) 上架檢查清單

- [ ] App 名稱與圖示完成
- [ ] 隱私政策 URL 可公開存取
- [ ] `privacy_policy.html` 已上傳並可公開存取
- [ ] `support.html` 已上傳並可公開存取
- [ ] 權限用途說明完整
- [ ] 短描述 / 長描述 / 螢幕截圖 / 圖示
- [ ] 測試帳號與測試流程（如需）
- [ ] 隱私權與資料安全問卷完成
