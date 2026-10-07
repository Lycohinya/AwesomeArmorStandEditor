# AwesomeArmorStandEditor — 測試指南

## 建置

```powershell
./gradlew build          # 產生 build/libs/AwesomeArmorStandEditor-<版本>.jar(已 shade Adventure)
./gradlew test           # 純邏輯單元測試(model/codec/pose/transform)
./gradlew runServer      # 起一個 Paper 測試伺服器(run/ 目錄)
```

Spigot 相容性:編譯只用 Bukkit/Spigot API 面,Adventure 以 shade+relocate 內嵌,執行期不依賴 Paper。
要在真 Spigot 驗證,把 jar 丟進 Spigot server 的 `plugins/`,確認 enable 無錯、指令與 GUI 正常。

## L1 已驗證(自動)

- `./gradlew build` 通過:編譯 + shadowJar + 單元測試。
- 單元測試 `EditorLogicTest`:場景 JSON round-trip、姿勢角度 wrap、四元數/縮放/平移運算、**分享碼 round-trip + 惡意/損毀輸入回 null 不丟例外**。
- 單元測試 `LangFilesTest`(1.0.0 新增):`lang/zh_TW.yml` 與 `lang/en.yml` 的 key 集合一致、`{placeholder}` 一一對應、每條字串與每頁手冊都能被 MiniMessage 解析。**已做變異驗證**:從 `en.yml` 刪掉一個 key、或拿掉 `scene.saved` 的 `{count}`,兩條測試都會紅。
- 打包 jar 內 Adventure 已 relocate 到 `com/tinyyana/awesomeArmorStandEditor/libs/kyori`,原 `net/kyori` 無殘留。

## L3 已驗證(本輪,2026-07-10 — 語言切換)

Paper 26.2 `runServer`,四次啟動,每次都 `AwesomeArmorStandEditor v1.0.0 enabled` + `Done (~20s)`、**插件零 Exception**;測試服已關、port 25565 已釋放。

- `language` 未設(從 0.2.2 升上來的舊 `config.yml`)→ log `Language: zh_TW`(本機 JVM `user.language=zh`),`lang/zh_TW.yml` 產生。
- `language: en` → log `Language: en`,`lang/en.yml` 產生;`zh_TW.yml` 已存在也**不再**印 Bukkit 的 `Could not save ... already exists` 警告。
- `language: klingon`(不認得的值)→ log 警告 `Unknown language 'klingon' ... using auto` 並退回 `zh_TW`。
- 舊的 `messages.yml` / `guide.yml` 還在資料夾裡 → 兩行 log 提醒「不再讀取」,**檔案沒有被刪也沒有被覆寫**。

⚠ **這輪測不到主控台指令。** 本機這顆 Paper 26.2(`26.2-56-8cd4f47`)對**任何**主控台指令都丟 `NullPointerException: ... CommandSourceStack.getLevel() is null`——連原版 `/stop` 都一樣,與本插件無關。所以 `/aase reload` 換語言、以及所有玩家可見字串的實際渲染都**沒有在執行期驗證過**,列在下面的 L4 手動步驟。

- 玩家端行為(裝備選單、分享/匯入、資訊、匯出/範本存檔的權限擋人)為 L4,需真人;步驟見下。

## L1 已驗證(本輪,2026-07-21 — 版面契約)

`./gradlew clean test build` 通過,31 個測試全綠(讀 `build/test-results/test/*.xml`)。本輪新增兩支:

- `ControlPanelLayoutTest`:**slot 49 沒有任何按鈕**(那是全遊戲分頁畫面的惰性頁碼格)、
  確認頁的關閉在 `base+8` 而不是 `base+4`、沒有 slot 被用兩次、
  最下面一列只有 `?` 與 `✕`、每一組的格子都在同一列且從第 0 欄起連續無洞、
  取消與確認不相鄰且取消在左。
- `PageWindowTest` 改寫:視窗高度跟著資料縮(3 個範本 = 3 列 = 27 格)、
  內容整列滿排從第 0 欄開始、超過 36 筆才分頁且分頁後視窗固定 6 列、
  頁尾在每種列數下都符合 `base = (rows-1)*9` 的契約。

**L4 未做**:實機擷取與渲染由擁有 dev server 的人跑,見下面的手動步驟。

### 版面(2026-07-21 改版,玩家視角)

1. `/aase` → 控制盤 **6 列**。由上而下應該是:新增元件 ×4 + 裝備 / 模式 ×5 + 幅度 ×4 /
   部位 ×6 + 軸 ×3 / 外觀開關 ×7 / 狀態卡 + 範本 + 儲存 + 匯出 + 刪除。
   **每一列都從最左邊那格開始,中間沒有空格。**
2. 最下面那一列(45–53)**只有右邊兩顆**:`📖 使用說明`(52)與 `關閉`(53)。
   中間那格(49)必須是空的——那是分頁畫面的頁碼格,不能有任何按鈕。
3. 「刪除選取的展示物」在第 5 列最右邊(岩漿桶),**跟關閉鍵不同列也不同 icon**。
   點它 → 確認頁 3 列:說明在左上角、**取消在左(綠色染料)、確定刪除在右(岩漿桶)**、
   `←` 在左下、`✕` 在右下(26,不是 22)。
4. 用**沒有** `aase.export.command` 的帳號開 `/aase` → 匯出那格是**灰色染料 + 寫出需要什麼權限**,
   不是空格。點下去只回「沒有權限」,不會匯出。
5. `/aase presets` → 範本 3 個時視窗是 **3 列**(第 0 列說明卡 + 鏡像,第 1 列三個範本,第 2 列頁尾),
   **不會出現 `‹ # ›` 三顆分頁鍵**。範本超過 36 個才會出現分頁,且第 1 頁與第 2 頁**視窗一樣高**。
6. 反向:把 `presets.yml` 清空 → `/aase presets` 的「目前沒有範本」出現在**內容區第一格**(左上),
   不是視窗正中央。

## L2–L4 手動測試

前置:`./gradlew runServer` 起服,或把 jar 丟進既有 Paper/Spigot 服。

### 語言(1.0.0 新增,管理員視角)

1. **auto**:`config.yml` 保持 `language: auto` 起服 → log 印 `Language: <代碼>`,`plugins/AwesomeArmorStandEditor/lang/` 只有那一份語言檔。中文系統應得 `zh_TW`,英文系統應得 `en`。
2. **明確指定 + 熱切換**:把 `language` 改成 `en` → `/aase reload` → 應回英文 `Config reloaded`,`lang/en.yml` 出現。接著 `/aase`(控制面板)、`/aase presets`(範本庫)、`/aase guide`(手冊)、拿工具看螢幕下方讀數 —— **整條路徑都要是英文,不能中英混雜**。特別檢查範本庫的格子名(應是 `T-Pose`、`Cherry Blossom`,不是「T 字」「櫻花飄落」)。改回 `zh_TW` → `/aase reload` → 同樣幾個畫面都要變回繁中。
3. **壞值**:`language: nonsense` → `/aase reload` → log 警告 `Unknown language 'nonsense' ... using auto`,並照 auto 的結果跑,**不能整個插件掛掉**。
4. **自訂翻譯**:改 `lang/en.yml` 的 `panel.title` → `/aase reload` → `/aase` 的視窗標題跟著變(證明讀的是資料夾裡的檔,不是 jar 內的)。把某個 key **整行刪掉** → reload → 那句話應退回 jar 內的英文,**不是**紅字 `<red>panel.title`。
5. **自己存的範本不受影響**:`/aase pose save mypose 我的姿勢` → `/aase presets` → 在任一語言下都應顯示「我的姿勢」(語言檔沒有 `preset.name.mypose`,退回 `presets.yml` 的 `name`)。
6. **從 0.x 升級**:資料夾裡留著舊的 `messages.yml` / `guide.yml` 起服 → log 出現兩行「不再讀取」提醒,而且**兩個檔案原封不動**(不會被刪、不會被覆寫)。

### 管理員視角

1. `/aase reload` — 應回「設定已重載」,無報錯。
2. 權限:預設 `aase.use`/`aase.create.*` 給所有人;`aase.admin`/`aase.bypass.*` 給 OP。用非 OP 帳號確認 bypass 無效、限制生效。
3. 數量上限:把 `config.yml` `limits.per-player` 調小(如 3)→ reload → 連續 `/aase addstand` 超過即被擋(「數量已達上限」)。
4. 領地(需要 GriefPrevention/WorldGuard,且測試帳號**不是 OP**——OP 有 `aase.bypass.region`)。
   準備:玩家 A 用金鏟圈一塊領地;玩家 B 站進 A 的領地內。以 B 的身分逐條測,每條都該回「這裡受保護」且**地上不留任何東西**:
   1. `/aase addstand`
   2. `/aase load <B 自己存的作品>` — 存檔是 B 的,但落點在 A 的地
   3. `/aase import <B 自己產的分享碼>`
   4. `/aase fx <任一特效>`(先在領地外開 session、選一個元件,再走進來)
   5. `/aase particle add FLAME`

   跨界搬運:B 在領地**外**放一個盔甲座 → 拿工具切 MOVE 模式 → 朝 A 的領地方向推。
   元件應停在領地邊界前一格,越界的那一下被擋(「這裡受保護」),元件不會進去。

   動畫夾帶:在領地外做一個帶動畫的作品,把某個 keyframe 的位置拉進 A 的領地 → 存檔 → `/aase load`。
   應在放置階段就被擋掉,而不是等播放時才把元件甩進去。

   最後把 `region.event-probe: false` → `/aase reload` → 上面每一條都應該放行(確認開關真的有效)。

5. `/aase clear <半徑>`(領主自助清理,**不需要 `aase.admin`**)。用兩個非 OP 帳號 A、B:
   1. A 圈一塊領地。B(不在信任名單內)**無法**在裡面放東西(第 4 條已驗)。請管理員用 `aase.bypass.region` 在 A 的領地內幫 B 放一個元件,模擬 0.2.1 之前留下的舊元件。
   2. A 站在旁邊 `/aase clear 8` → B 的元件應被清除,訊息列出 B 的名字。
   3. **A 自己的元件必須留著**——先讓 A 在同一塊地放兩個自己的元件再 `clear`,它們一個都不能少(自己的要用編輯器刪)。
   4. B 站進 A 的領地跑 `/aase clear 8` → 應回「沒有你能清除的元件」,A 的東西一個都不能動。
   5. 無主土地:A 放一個元件,B 走過去 `/aase clear 8` → **會被清掉**(該處本來就沒有保護,與方塊一致)。這是刻意行為,不是 bug。
   6. 清完後 B 的存檔還在:B 跑 `/aase load <作品>` 應能重新放置。
   7. 元件仍打不壞:徒手打、爬行者炸、箭射 A 的元件 → 都不該消失。
      反向驗證(console 即可,不必玩家)。**只看實體還在不在,不要讀 `damage` 的回應字串**——它兩組都會印「Target is invulnerable」,不能拿來判讀:
      ```
      summon armor_stand 12 66 -12 {Tags:["ctrl"]}            # 控制組:無 AASE 標記
      damage @e[tag=ctrl,limit=1] 1000 minecraft:explosion
      execute if entity @e[tag=ctrl]                          # 預期 Test failed(被炸掉)
      ```
      同樣的爆炸傷害打在本插件的元件上,元件應**存活**(`EntityProtectionListener` 生效)。
      `/kill` 依版本而異:26.2 實測會穿透移除;2026-10-08 在 Lecithin 26.3 實測 `minecraft:kill <uuid>` 回「Killed Armor Stand」但本插件的盔甲座仍在,同一台的原版盔甲座會被殺掉。兩種情況下都不應有裝備掉落。
      收回本插件作品一律用 `/aase remove` / `/aase admin purge`。

6. 效能:反覆放置/編輯時開 Spark 或 `/tps`,確認無掉 TPS(不應有 chunk 掃描)。
   MOVE 模式連續微調時,只有跨越方塊邊界的那一下會發探針事件;`/aase clear` 對同一格上的多個元件只探一次。若裝了 CoreProtect,確認不會被洗版。

### 玩家視角(遊戲內手冊)

0. `/aase guide`(或 `/aase help`、或面板右上角📖)→ 打開一本可翻頁的書,12 頁涵蓋:最快上手、開始、範本庫、工具微調、控制面板、裝備外觀、粒子、動畫、存檔匯出、收回作品、小提醒(1.2.0 起含「收回作品」一頁)。翻頁確認文字在紙底色上看得清楚(深色)。完整版看 `docs/MANUAL.md`。

### 玩家視角(零基礎最快路徑 — 範本庫)

給不會擺姿勢的人:
1. `/aase new 測試` → `/aase addstand`(生一個盔甲座)→ 右鍵點它選取。
2. `/aase presets`(或 `/aase` 面板點「範本庫」)→ 開範本庫 GUI。
3. 點上排任一姿勢(立正/T字/萬歲/揮手/指向/沉思/坐姿/跑步)→ 盔甲座立刻變那個姿勢。
4. 點「鏡像」讓左右對稱;點下排特效(火焰光環/愛心/櫻花/星塵/靈魂之焰)直接加粒子。
5. 擺好一個滿意的姿勢 → `/aase pose save myPose 我的姿勢` → 以後 `/aase pose myPose` 一鍵重用。
6. `/aase save` 存檔。**全程不需要懂任何角度數字。**

### 玩家視角(核心流程 — 進階手動微調)

1. `/aase new 測試` → 「已建立場景」。
2. `/aase tool` → 拿到「盔甲座編輯工具」。
3. `/aase addstand` → 腳下出現盔甲座,actionbar 顯示 `盔甲座#1 | 姿勢頭 | 軸 Y | 步進 …`。
4. 拿工具:**右鍵點盔甲座**選取 → **左/右鍵**微調角度 → **滾輪**換步進 → **潛行+滾輪**換軸 → **潛行+左鍵**換模式(姿勢/移動)→ **潛行+右鍵**換部位。盔甲座姿勢應即時改變。
5. `/aase` 開控制面板:點模式/部位/軸/微調/旗標(迷你、隱形、無底板、手臂…)按鈕,盔甲座應即時反映。
6. Display:`/aase adddisplay block` → 出現方塊 Display;切到 SCALE/ROTATE 模式微調,應即時縮放/旋轉。`/aase adddisplay item`(副手拿物品先)、`/aase adddisplay text` + `/aase settext <內容>`。
7. 裝備(選單):選取盔甲座 → `/aase equip`(或面板「裝備」鍵)→ 開 27 格裝備選單。**把物品拿到游標上(點一下背包物品)→ 點頭盔/胸甲/…格子**,盔甲座立刻穿上;**空手(游標空)點格子=卸下**。全程你的物品不會被消耗或複製(游標物品保留)。也可 `/aase setequip head`(副手物品)舊路徑。
8. `/aase info` → 聊天顯示場景資訊(元件數/盔甲座/Display/發射器/動畫/目前選取/存檔狀態)。
9. `/aase save` → 「已儲存(共 N 個元件)」;檢查 `plugins/AwesomeArmorStandEditor/scenes/<uuid>/<id>.json`。
9. `/aase close` → 作品保留在世界。走遠再回來 `/aase edit`(站在作品旁)→ 重新綁定既有作品繼續編輯(不應產生分身)。
10. `/aase list` → 列出場景;`/aase load 測試` → 在腳下放一份新的。
11. `/aase export command` → 聊天出現可點擊「複製 summon 指令」,同時存成 `exports/測試.txt`;把指令貼到遊戲執行,應重現作品(NBT 為最佳努力,若版本格式有變請回報)。
12. 反格里芬:換別的玩家 → 嘗試打/互動你的元件應無效(受保護);別人不能用工具選取你的元件。

### 玩家視角(粒子 P2)

13. `/aase particle add FLAME`(tab 補全看清單)→ 你腳下位置持續冒火焰粒子;`/aase particle add HEART`、`CHERRY_LEAVES`、`DUST` 等。
14. 走遠超過 `particles.render-range`(預設 32 格)→ 粒子停;走近再冒(不掃世界、只在附近玩家時發射)。
15. `/aase save` → 粒子發射器一起存進 JSON;`/aase particle clear` 清除。

### 玩家視角(關鍵影格動畫 P3)

16. 選取一個元件 → 擺姿勢 → `/aase anim key 0`(在 tick 0 記錄)→ 換個姿勢 → `/aase anim key 20` → `/aase anim length 20` → `/aase anim play`:元件應在兩個姿勢間平滑來回(Display 走客戶端插值、盔甲座逐 tick)。
17. `/aase anim loop` 切換循環;`/aase anim stop` 停止(元件回存檔姿勢);`/aase anim clear` 清空動畫。

### 玩家視角(mcfunction 匯出 P3)

18. `/aase export function` → 匯出資料包到 `exports/<場景>/datapack/`(含 `pack.mcmeta`、`summon.mcfunction`、有動畫則含 `load/tick/frames/*`)。把資料夾丟進世界 `datapacks/`,`/reload`,`/function aase:summon`(有動畫再 `/function aase:load` 然後 `/function aase:tick`)。**最佳努力**:pack_format/function 資料夾名依版本可能要調。

### 玩家視角(分享碼 P4)

19. 有作品的玩家 `/aase share` → 聊天出現可點擊「點擊複製分享碼文字到剪貼簿」,複製到剪貼簿(一串 `AASE1:...`),下一行灰字提示「通常超過聊天欄 256 字的上限,請存成檔案保存或轉交,不要直接貼在聊天」;沒有元件時回「還沒有任何元件」。**把剪貼簿內容貼進聊天框會被截斷**(1.2.0 起文件不再說可以這樣分享)。
20. 匯入:短碼流程見下方「短碼分享與遠端匯入(1.3.0)」。分享碼文字只驗**較短的碼**能走通 `/aase import AASE1:… [新名稱]`(例如只有一個元件的作品)→ 在腳下放置同一份作品,回「已匯入並放置 …」;owner 變成匯入者、是新的 id(不影響原作者存檔)。長碼放不進聊天是已知限制,不是 bug。
21. 亂碼防護:`/aase import 隨便亂打` → 回「分享碼無效或已損毀」,不應有紅字例外。
22. 上限防護:把 `limits.per-player` 調小 → 匯入元件數超過上限的碼 → 被擋(「元件太多」)。

### 權限分界(本輪新增,管理員視角)

23. 用**非 OP** 帳號:`/aase export command` / `/aase export function` / `/aase pose save x` 應回「你沒有權限」(這三個會寫伺服器檔案 / 改全服 `presets.yml`,預設 `op`)。
24. 非 OP 開 `/aase` 控制面板:**匯出鍵應不顯示**;就算點到該格也不會匯出(GUI 有二次權限檢查,不能繞過指令權限)。
25. 給該帳號 `aase.export.command` / `aase.preset.save`(LuckPerms)後,以上恢復可用。

### 管理員強制移除工具(0.2.0 新增,管理員視角)

> 需要 `aase.admin`。這組指令**只作用於用 `/aase` 放置的元件**、**只碰已載入區塊**、**不刪玩家的存檔**。

26. 請另一個玩家(或用第二個帳號)`/aase new x` → `/aase addstand` 放一個盔甲座。用管理員站到旁邊 `/aase admin whois` → 應顯示**擁有者名稱 / 作品名 / 元件編號 / 世界+座標**。
27. **邊界(必測)**:用手放一個**原版盔甲座**(拿盔甲座物品右鍵地面,不經 `/aase`),站旁邊 `/aase admin whois` → 應回「附近沒有本插件放置的元件」。**絕不能把它當成可移除目標。**
28. `/aase admin remove` → 最近的那一個元件消失;再 `/aase admin whois` 應找不到。
29. 粒子發射器也算元件:`/aase particle add FLAME` 後對著它 `/aase admin whois` → 位置行尾應標「(粒子發射器)」;`remove` 可清掉,粒子隨之停止。
30. **兩段式清除**:`/aase admin purge 16` → 只印「半徑 16 格內有 N 個元件即將被移除」+ 確認提示,**世界上的東西一個都不能少**。接著 `/aase admin confirm` → 才真的清掉並回報數量。
31. 只清某人的:`/aase admin purge 16 <玩家名>` → 預覽行應標「(只算 <玩家名> 的)」;confirm 後**別人的元件要留著**。
32. 找不到玩家:`/aase admin purge 16 不存在的名字` → 回「找不到玩家」,不進入待確認狀態。
33. **參數防呆**:`/aase admin purge`(不給半徑)、`purge 0`、`purge -5`、`purge abc` → 一律印用法,**什麼都不清、也不建立待確認**。
34. **確認防呆**:沒先 purge 就 `/aase admin confirm` → 回「沒有待確認的清除」。
35. **逾時**:`/aase admin purge 16` 後等 **超過 60 秒** 再 `confirm` → 回「確認已逾時」,不執行。
36. **半徑夾限**:`/aase admin purge 9999` → 實際只會用 `config.yml` 的 `admin.max-purge-radius`(預設 64)。
37. **session 清理**:讓某玩家正在 `/aase edit` 他的作品時,管理員 `remove` 掉其中一個元件 → 該玩家繼續操作不應噴例外(元件已從他的 session 移除)。
38. **權限與 tab**:一般玩家 `/aase admin whois` → 「你沒有權限」;且輸入 `/aase ` 按 tab **看不到 `admin` / `reload`**。
39. **稽核**:若伺服器有 LycoLib,`remove` / `purge` 應各寫一筆稽核紀錄(誰、清了誰的、幾個、座標)。沒有 LycoLib 時應安靜略過、不報錯。
40. **未載入區塊**:把元件放在很遠的地方讓區塊卸載,回到出生點 `purge 64` → 那些元件**不會**被清掉(刻意:不掃描世界)。訊息會提醒「只會影響已載入區塊內的元件」。

### 指定選取 `/aase select`(1.0.2 新增,玩家視角)

背景:場景混有盔甲座與 display 時,`/aase edit` 綁「離眼睛最近的實體」可能抓到 display(display 也沒有碰撞箱,工具點不到),`setequip` 就一直回「只有盔甲座能穿裝備」。`select` 是不靠距離的替代路。

41. 建一個混合場景(2 盔甲座 + 1 block display)→ `/aase select 2` → 回「已選取 #2(盔甲座)」,actionbar 讀數列跟著換;接 `setequip head`(副手拿物品)應穿在 #2 上。
42. `#` 前綴也收:`/aase select #3` → 選到 display,模式自動切到移動(TRANSLATE)。
43. `next`/`prev` 照場景順序輪且會繞圈:從最後一個 `next` 回到第一個,從第一個 `prev` 跳到最後一個。
44. 壞編號:`/aase select 99`、`select abc` → 回「沒有這個元件編號」並列出場景現有編號,選取不變。
45. tab 補全:`/aase select ` + Tab → 列出 `next`、`prev` 與目前場景的元件編號;沒開 session 時只列 `next`/`prev`。
46. 沒 session 時 `/aase select 1` → 回「你還沒有進行中的場景」。

### 收回與放置分組 `/aase remove`(1.2.0 新增,雙視角)

> 自動測試:`RecallLogicTest` 覆蓋純邏輯(只動請求者自己的實體、placement 範圍不牽連另一份複本、球形範圍、場景名稱比對、舊作品分組、孤兒判定、`close` 不會默默丟變更、提示限速);結果以當次 `./gradlew test` 為準。下面是需要真人 / 真伺服器的部分(L4)。
>
> 前置:玩家 A、玩家 B 都是**非 OP**,管理員 C 有 `aase.admin`。A 在自己能蓋的地方測。語言看 `language`,下面引用的是 `zh_TW`。

**A. 新場景後悔:`new` → `close` 不存 → 收回(玩家 A)**

47. A:`/aase new 收回測試` → `/aase addstand` → `/aase adddisplay block`(**先不要 `/aase save`**)→ `/aase close`。
    **應看到**:「有未儲存的變更,要怎麼處理?」與三顆按鈕 `[儲存並關閉]` `[放棄並收回]` `[繼續編輯]`;此時 session 還在(`/aase info` 照常回資訊)、世界上的盔甲座與方塊都還在,**沒有被默默關掉**。
48. 點 `[繼續編輯]` → 開控制面板,session 仍在。再 `/aase close` → 點 `[放棄並收回]`。
    **應看到**:「已放棄變更並收回這份作品(2 個實體),存檔不受影響」+ 灰字「只處理已載入區塊內的實體…」;盔甲座與方塊**都從世界消失**;`/aase list` **沒有**「收回測試」(從沒存過);`/aase info` → 「你還沒有進行中的場景」。
49. 重做一次到 `/aase close`,改點 `[儲存並關閉]`(或直接打 `/aase close save`)。
    **應看到**:「已儲存(共 2 個元件)」+「已結束編輯(作品保留在世界)」;作品留在世界,`/aase list` 有「收回測試」。已經存過、沒有新變更時 `/aase close` **直接關**,不再問。
50. 對一個**已存檔**的作品改一下姿勢(變成未存)→ `/aase close` → `[放棄並收回]`。**應看到**:世界上的作品被收走,但 `/aase list` 裡的存檔還在,`/aase load 收回測試` 能再放一份(存檔是舊的姿勢)。

**B. `/aase remove` 預覽、確認與防呆(玩家 A)**

51. 站在步驟 49 留下的作品旁,對準盔甲座 `/aase remove look`。
    **應看到**:「將收回 2 個元件(1 組)」、`[確認收回]` `[取消]`、灰字「只處理已載入區塊內的實體;存檔不會被刪…」;**此刻世界上一個都不能少**。點 `[確認收回]` → 「已收回 2 個實體」;作品消失,`/aase list` 存檔仍在,`/aase load 收回測試` 能再放。
52. 再放一份,`/aase remove look` 後改 `/aase remove cancel` → 「已取消收回」,東西還在。再預覽一次,等**超過 30 秒**再 `/aase remove confirm` → 「確認已逾時(30 秒),請重新預覽一次」,不收。
53. 防呆:沒預覽就 `/aase remove confirm` → 「沒有待確認的收回,先用 /aase remove look、here 或 scene 預覽」;`/aase remove`、`remove foo`、`remove scene`(沒給名稱)、`remove here abc`、`remove here 0` → 一律印「用法:/aase remove <look|here [半徑]|scene <名稱>|confirm|cancel>」,什麼都不收;周圍沒有自己的元件時 `remove look` → 「附近沒有你放置的元件,對準要收回的盔甲座再試一次」。
54. 半徑:一份作品放在身邊、另一份放在 12 格外 → `/aase remove here` 預覽只含身邊那份(預設 8);`remove here 32` 兩份都含。到**沒有任何自己元件**的地方 `remove here 9999` → 「半徑 64 格內沒有你放置的元件」(被 `admin.max-purge-radius` 夾住,預設 64)。
55. 名稱:`/aase remove scene 收回測試` → 預覽該名稱放出來的所有複本(大小寫不拘);打錯名 → 「已載入區塊中找不到你的作品 …」。
56. 只動自己的:B 對準 A 的盔甲座 `/aase remove look` → 「這是 A 的作品,只能收回自己的」;B 站在 A 的作品旁 `/aase remove here` → 「半徑 8 格內沒有你放置的元件」。**管理員 C 也一樣**(`aase.admin` 不放寬),要清別人的得用 `/aase admin remove|purge`。兩種情況 A 的作品都完全不動。
57. 未載入區塊:把作品放在很遠的地方並讓區塊卸載,回出生點 `/aase remove scene 收回測試` → 「已載入區塊中找不到你的作品 …」(刻意:不掃世界)。

**C. 一次放置一份複本(玩家 A)**

58. 把「收回測試」(2 個元件)存好,在 P1 `/aase load 收回測試`、走 15 格在 P2 再 `/aase load 收回測試`。站在 P2 的作品旁 `/aase edit` → 「正在編輯既有作品 收回測試」;拿工具把 P2 的盔甲座姿勢調大幅度。
    **應看到**:**只有 P2 的動**,走回 P1,那份完全沒變。
59. 仍在編輯 P2 的狀態下,拿工具右鍵 P1 的盔甲座 → 「那個元件屬於別的場景或別份複本」,選取不變。
60. 站在 P1 旁 `/aase remove look` → 預覽「將收回 2 個元件(**1 組**)」,確認 → P1 消失;**P2 原封不動,而且你的編輯沒被打斷**(沒有「編輯已結束」)。重放 P1,站兩份中間 `/aase remove here 40` → 預覽「4 個元件(2 組)」,先取消。
61. 站在 P2 旁 `/aase remove look` → 確認。**應看到**「已收回 2 個實體」與「你正在編輯的這份作品已被收回,編輯已結束」兩句;`/aase info` → 「你還沒有進行中的場景」。
62. 管理員 C:`/aase admin whois` 對著 P1、P2 的元件 → 多一行「放置 xxxxxxxx」(8 碼);**同一份複本的元件同碼,P1 與 P2 不同碼**。

**D. 舊作品遷移與孤兒**

63. 舊作品:用 **1.1.0** jar 放一份作品並存檔,換成 1.2.0 jar 重啟。C `/aase admin whois` → 放置行「舊版未分組」;A 站旁 `/aase remove look` 照樣能預覽收回(1 組)。改為 A `/aase edit` → 正常綁定、可編輯;再 `/aase admin whois` → 放置行變成 8 碼 ID(已自動補上分組)。
64. 孤兒:A `/aase new 孤兒` → `/aase addstand` → **不存檔** → `/aase new 別的`。C `/aase admin whois` 對著那個盔甲座 → 放置行標「(孤兒:沒有存檔可綁定)」;A 站旁 `/aase edit` → 「附近只剩沒有存檔可綁定的元件,可以用 /aase remove look 收回」+ `[收回看著的作品]`,**不會綁到它**。點按鈕 → 預覽 → 確認收回。
65. 反向:A `/aase new 進行中` → `/aase addstand`(**未存、session 開著**)→ C `/aase admin whois` → **不標孤兒**(編輯中的作品不是殘留)。

**E. 工具手勢(玩家 A、B)**

66. A `/aase tool`,有 session 時對準自己的盔甲座**潛行 + 左鍵**。
    **應看到**:跟 `remove look` 一樣的預覽,多一顆 `[只刪這個元件 #編號]`;**模式沒有被切換**(actionbar 讀數的模式不變)。對著空氣潛行 + 左鍵則照舊切模式。點 `[只刪這個元件 #1]` → 「已刪除展示物 #1」+ `[儲存]`,只有那個盔甲座消失,作品其他元件還在。
67. 沒有 session(`/aase close` 後)拿工具**右鍵**自己的盔甲座 → 「先 /aase edit 綁定這組作品,或潛行左鍵收回」+ `[/aase edit]` 按鈕(3 秒內連點只提示一次);點按鈕 → 綁定。這時潛行左鍵打它 → 預覽,但**沒有** `[只刪這個元件]`(沒綁定那份)。
68. B 拿工具潛行左鍵打 A 的盔甲座 → actionbar 「這是 A 的作品」,**沒有預覽、什麼都沒被收**。
69. A 空手(或拿別的東西)打自己的盔甲座 → actionbar 「用 /aase remove look 或拿工具潛行左鍵收回」;連打每 3 秒才提示一次。打 B 的 → 「這是 B 的作品」。元件**仍然打不壞**。
70. **邊界(必測)**:放一個 block display、再放一個 `/aase flag marker` 的盔甲座。拿工具潛行左鍵對著它們 → **沒有任何預覽**(沒有碰撞箱,打不到)。改用 `/aase remove look` 站近一點(6 格內)→ 能預覽並收回;或見下一步的面板。

**F. 控制面板與 delete(玩家 A)**

71. `/aase` → 「作品」那一列最右邊是**漏斗**「收回作品」(說明:列出身邊 8 格內你放的元件,確認後才會從世界收回,存檔保留);刪除(岩漿桶)位置沒變。點漏斗 → 視窗關閉、聊天出現「將收回 N 個元件」與確認按鈕,**世界上一個都不少**,直到點 `[確認收回]`。身邊沒有元件時 → 「半徑 8 格內沒有你放置的元件」。
72. 有 session 時 `/aase delete 2` → 「已刪除展示物 #2」+ `[儲存]`,世界上 #2 消失;`/aase delete 99` → 「這個展示物已經不在作品裡，沒有刪除其他東西」;`/aase delete abc` → 「用法:/aase delete [元件編號]」;沒選取就 `/aase delete` → 「先右鍵點擊一個元件來選取」。
73. **找不到實體**(只在測試服):A 在 session 中放好兩個元件後,傳送到 500 格外(讓作品所在區塊卸載),對其中一個編號 `/aase delete <編號>`。
    **應看到**:「世界中找不到 #N 的實體(可能在未載入區塊),模型已移除;之後看到殘留,用 /aase remove look 收回」+ `[儲存]` `[收回看著的作品]`;`/aase info` 的元件數少 1。
74. 離線:A `/aase new 離線測試` → `/aase addstand` → 記兩格動畫 → `/aase anim play` → A 登出再登入。**應看到**:盔甲座仍在世界、不再動(姿勢回到模型);`/aase info` → 「你還沒有進行中的場景」;`/aase list` 沒有「離線測試」(沒存過)。站旁 `/aase remove look` 能收回。**沒有任何東西被存檔或被刪**。

**G. 權限、tab、稽核**

75. 一般玩家(非 OP)`/aase remove look` 可用(`aase.use`);拿掉 `aase.use` → 「你沒有權限這麼做」。沒有 `aase.scene.save` 的玩家 `/aase close save` → 「你沒有權限這麼做」且 session 沒關。
76. Tab:`/aase remove ` → `look` `here` `scene` `confirm` `cancel`;`/aase remove here ` → `8` `16` `32`;`/aase remove scene ` → 自己的存檔名;`/aase close ` → `save` `discard`;`/aase delete ` → 場景的元件編號。
77. 若有 LycoLib:每次 `[確認收回]` 後稽核紀錄多一筆 `scene.remove`(件數、組數、範圍種類、座標);沒裝時安靜略過、不報錯。

### 短碼分享與遠端匯入、schema v3(1.3.0 新增,雙視角)

L1(自動,`./gradlew build`):`ContractFixturesTest`(6 份正向 golden 的 summon / mcfunction 完全相等、v3 寫回再讀匯出不變;19 份負向樣本第一個錯誤 pointer;`math/rotation.json` 與 `math/pose.json`)、`SceneCodecTest`(v2 ⇄ v3、v2 寫出時物品編碼與省略、無法以 v3 表達時退回 v2、去識別、元件字串正規化、code point 長度)、`RemoteLogicTest`(短碼 / 網址辨識、base-url 只收 https、邊讀邊計數的大小上限與逾時、cooldown、上傳回應只接受真短碼)。網站端驗證器以同一批負向樣本比對過 pointer(見回報)。

管理員視角(先準備):`config.yml` 的 `import.remote.base-url` 指向可用的 AASE Studio(本機測試可用 `http://localhost:<port>`)。
1. **存檔格式**:`/aase new t` → 加一個盔甲座、副手拿一把附魔鑽石劍 `/aase setequip mainhand` → `/aase save`。打開 `scenes/<UUID>/<id>.json`:`schemaVersion: 3`、姿勢是 `poseDeg`(度)、主手是 `{"id":"minecraft:diamond_sword","count":1,"components":"[...]","bukkit":"..."}`。記下 `components` 的實際內容(**待實機確認 `getAsComponentString()` 的格式**,應只有中括號部分)。
2. **舊檔照讀**:把 1.2.0 存的舊檔(`schemaVersion: 2`)放回 `scenes/<UUID>/`,`/aase load <名稱>` 正常放置、裝備都在;`/aase save` 後變成 v3。
3. **寫 v2**:`store.write-schema: 2` → `/aase reload` → `/aase save`,檔案回到 `schemaVersion: 2`(弧度、base64 字串)。測完改回 3。
4. **只有 id 的物品**:手改存檔,把某格改成 `{"id":"minecraft:golden_helmet"}`(不帶 `bukkit`)和 `{"id":"minecraft:foo_sword"}` → `/aase load`:金頭盔有裝上;聊天出現「有 1 格物品沒裝上」與 `#1 主手 minecraft:foo_sword`。再試帶 components 的 `{"id":"minecraft:diamond_sword","components":"[enchantments={sharpness:5}]"}`,劍要有附魔光澤(**待實機確認 `createItemStack` 吃 components 的行為**)。
5. **遠端關閉**:`import.remote.enabled: false` → `/aase reload` → `/aase import abc2345` 回「此伺服器未開放遠端匯入」;`/aase share` 直接給分享碼文字。主控台不應出現任何連線。

玩家視角(遠端開啟):
6. `/aase share` → 「正在上傳…」→「已上傳,短碼 xxxxxxx」+ `[點擊複製 /aase import xxxxxxx]` `[在 AASE Studio 開啟]`;開啟的網頁看到同一個場景,JSON 裡沒有 `owner` / `id` / `lastAnchor`。
7. 另一個玩家(或自己 `close` 後換位置)`/aase import xxxxxxx 新名字` → 在腳下放置、回「已匯入並放置」;`/aase import https://…/s/XXXXXXX`(大寫、網址形式)同樣可以。
8. 錯誤訊息各一次:不存在的短碼(「找不到短碼…」)、10 秒內連打兩次(「請等 N 秒再試」)、把 `base-url` 指到不回應的位址(逾時訊息,約 `timeout-seconds` 內出現)、讓網站回一份不合格的場景(列出最多 3 個 pointer)、把 `max-bytes` 調小到 1000 再匯入(「超過 …已停止下載」)。
9. 下載中途登出:不應報錯、回來後沒有多放一份。
10. 在別人的領地裡 `/aase import <短碼>`:被領地檢查擋下(與 `load` 相同)。
11. **Lecithin / Folia legacy runtime**:第 7 步要在 Lecithin 上跑一次,確認回主執行緒的 `runTask` 能用(結果有出現、實體有生成)。

### 驗收層級標記

回報測試時標明做到哪層:L1 build / L2 deployed(enable 無錯)/ L3 runtime(指令有回應)/ L4 玩家端(姿勢/存讀/匯出真的可見可用)。

## 已實作範圍

- **P1** 靜態編輯器、**P2** 粒子、**P3** 關鍵影格動畫 + mcfunction 匯出、**P4** 分享碼/匯入 + 對外事件 API 都已實作(見上方測試步驟)。
- 另有:裝備選單 GUI、`/aase info`、寫檔/共用資料的權限分界。
- **0.2.0**:管理員強制移除工具 `/aase admin whois|remove|purge|confirm`(兩段式確認 + 稽核紀錄)。
- **1.2.0**:玩家自助收回 `/aase remove`、`/aase close` 未存變更詢問與 `save|discard`、`/aase delete [編號]`、放置分組(placement)與孤兒、離線自動結束 session、工具收回手勢、面板「收回作品」。
- **1.3.0**:Scene schema v3(poseDeg、ItemRef、驗證與 JSON pointer 錯誤)、golden fixtures、`/aase import <短碼|網址>` 遠端匯入、`/aase share` 上傳取短碼。
- 仍待做:粒子/動畫的**視覺化編輯面板 / 時間軸 GUI**(目前走指令 + 範本庫)。

## 已知限制

- summon / mcfunction 匯出:**NBT 格式已對 Paper 26.2 用 RCON `/summon` 實測**(Pose/旗標/裝備 ArmorItems+HandItems/transformation/item/block/brightness/glow 皆正確;自訂名與文字用 **SNBT** `CustomName:"..."` / `text:"..."`,不是舊的 JSON 字串)。仍為最佳努力:裝備只帶物品 id(不含附魔/自訂資料);方塊只帶方塊名(不含 blockstate 屬性);名稱/文字轉純文字(顏色不保留)。若未來版本再變,`SummonExporter` / `McFunctionExporter` 是集中修改點。
- 動畫即時播放僅在編輯 session 內(播放中盔甲座逐 tick 有成本,故不常駐);要常駐播放請用匯出的 datapack。
- 粒子發射器位置目前是玩家加入時的站位(offset),尚無視覺化搬移(可刪除重加);編輯只有 add/clear。**效能**:每 tick 迴圈不再解析 PDC(生成/索引時解一次並快取解好的 `Particle`),marker 空時 ticker 早退。
- 裝備 GUI 用「手持物品點格子」而非真拖放(刻意:全程 cancel 事件、只複製游標物品,確保玩家物品零消耗/零複製);`/aase setequip`(副手)舊路徑仍在。
- 數量上限以記憶體計數,未載入區塊的既有元件不計入(不做世界掃描的取捨)。
- 收回(`/aase remove`、`close discard`)只到已載入區塊。工具手勢只打得到一般盔甲座;Display 與 marker 盔甲座沒有碰撞箱,要用指令或面板。
- 分享碼文字超過聊天欄 256 字上限,貼不進聊天框;請用短碼(`/aase share`)。
- 匯出(summon / mcfunction)仍只帶物品 id 與數量,`components` 不匯出(與網站一致)。
- 物品 base64 經白名單反序列化,內容仍可偽造;展示品的物品不得以任何方式回到玩家手上(見 DESIGN §3)。收回一律用 `/aase remove`;本插件實體死亡時不掉落裝備,擋住 `/kill` 能穿透的版本與其他插件直接殺實體的情況。
