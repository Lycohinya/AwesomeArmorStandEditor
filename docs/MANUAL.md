# AwesomeArmorStandEditor 使用說明手冊

> 巨詳細版。遊戲內有精簡的翻頁書(`/aase guide`);這份是完整參考。
> 目標讀者:從「完全不會擺姿勢」到「想做動畫、想匯出資料包」的所有人。
> English version: [`MANUAL.en.md`](MANUAL.en.md)。

---

## 目錄

1. [這是什麼](#1-這是什麼)
2. [安裝](#2-安裝)
3. [先懂三個概念](#3-先懂三個概念)
4. [五分鐘上手(不用會美術)](#4-五分鐘上手不用會美術)
5. [完整工作流程](#5-完整工作流程)
6. [編輯工具詳解](#6-編輯工具詳解)
7. [控制面板 GUI](#7-控制面板-gui)
8. [範本庫:姿勢 / 特效 / 鏡像 / 自存](#8-範本庫)
9. [盔甲座:姿勢 · 裝備 · 開關](#9-盔甲座)
10. [Display 元件:物品 / 方塊 / 文字](#10-display-元件)
11. [粒子特效](#11-粒子特效)
12. [動畫(關鍵影格)— 重點章節](#12-動畫關鍵影格)
13. [存檔 · 讀取 · 分享 · 編輯既有作品](#13-存檔讀取分享編輯既有作品)
14. [匯出:指令 / mcfunction 資料包](#14-匯出)
15. [指令總表](#15-指令總表)
16. [權限總表](#16-權限總表)
17. [設定檔](#17-設定檔)
18. [疑難排解 FAQ](#18-疑難排解-faq)
19. [效能與安全設計](#19-效能與安全設計)
20. [路線圖:往「完整動畫工具」長](#20-路線圖)

---

## 1. 這是什麼

一個讓生存 / 創造玩家自由自訂盔甲座與 Display 實體的場景編輯器。你可以:

- 擺盔甲座姿勢、穿裝備、開關(隱形、無底板、發光…)
- 放 Display 實體(物品 / 方塊 / 文字),自由縮放、旋轉、傾斜(盔甲座做不到的自由度)
- 掛粒子特效
- 用關鍵影格做動畫,並即時預覽播放
- 存檔、分享、放置多份、綁定既有作品繼續編輯
- 匯出成 `/summon` 指令或 mcfunction 資料包

它是**獨立、可開源、跨 Spigot/Paper** 的插件,不需要安裝任何其他插件。

## 2. 安裝

把 `AwesomeArmorStandEditor-<版本>.jar` 放進伺服器 `plugins/`,重啟。第一次啟動會產生:

```
plugins/AwesomeArmorStandEditor/
  config.yml      行為/效能設定
  lang/zh_TW.yml  繁體中文的玩家可見文字(含遊戲內手冊頁面)
  lang/en.yml     英文的同一份
  presets.yml     姿勢與特效範本(可自由增改)
  scenes/         你的存檔(每人一個資料夾)
  exports/        匯出的指令/資料包
```

**語言**由 `config.yml` 的 `language` 決定,全服一種:`auto`(預設,看伺服器 JVM 的地區設定——中文語系給 `zh_TW`,其餘給 `en`)、`zh_TW`、`en`。兩份語言檔都會產生(方便你對照、或切過去前先改好),只有選中的那份會被讀取;想自訂就直接改它。

改完設定用 `/aase reload`(不用重啟)。

## 3. 先懂三個概念

| 概念 | 意思 |
|---|---|
| **場景(Scene)** | 一份作品 = 一個存檔。裡面有很多元件 + 粒子 + 動畫。 |
| **元件(Element)** | 場景裡的一個節點:一個盔甲座,或一個 Display。每個有編號 `#1 #2…`。 |
| **藍圖 vs 世界實體** | 存檔是「藍圖」;世界裡看到的是藍圖的一次「放置」(placement)。刪掉世界裡的實體不會刪存檔;同一份存檔可以放很多份到不同地方,**每放一次就是獨立的一份**:`edit`、`remove` 只動你指到的那一份,不會牽連其他複本。 |

還有三個規則要記得:

- **擁有權**:每個元件記著是誰做的。你只能編輯自己的;別人打不壞、拿不走你的作品。
- **會留在世界**:放好的作品是真的實體,會一直在(像真的盔甲座)。要繼續編輯用 `/aase edit` 站旁邊重新綁定,不會產生分身。
- **放錯、不要了,自己收回**:`/aase remove`(§13)把你放在世界裡的作品收掉,存檔不會被刪。元件打不壞,所以不要用打的。

![場景、元件與放置](assets/concepts.svg)

## 4. 五分鐘上手(不用會美術)

![五分鐘上手](assets/quickstart-flow.svg)

```
/aase new 我的作品      ← 開一個新場景
/aase addstand          ← 生一個盔甲座在你腳下
右鍵點那個盔甲座          ← 選取它(先選才能改)
/aase presets           ← 打開圖形範本庫
  → 點上排的「揮手 / 萬歲 / 坐姿…」→ 盔甲座立刻變那樣
  → 點「鏡像」讓左右對稱
  → 點下排的「火焰光環 / 愛心 / 櫻花」→ 直接加粒子
/aase save              ← 存檔
```

**完全不會看到任何角度數字。** 這就是給「美術細胞為 0」的人準備的路徑。
擺到滿意的姿勢還能 `/aase pose save 我的招牌` 存成自己的範本,以後 `/aase pose 我的招牌` 一鍵叫回來。

## 5. 完整工作流程

1. `/aase new <名稱>` 開場景(把你現在站的位置當作品原點)。
2. `/aase tool` 拿編輯工具。
3. 加元件:`/aase addstand` 或 `/aase adddisplay item|block|text`。
4. 選元件:拿工具**右鍵點**它。
5. 調整:用範本(`/aase presets`)或工具微調或控制面板(`/aase`)。
6. (可選)加粒子:`/aase particle add <類型>` 或範本庫特效。
7. (可選)做動畫:見 §12。
8. `/aase save` 存檔。
9. 之後 `/aase load <名稱>` 放置一份、`/aase edit` 站旁邊接續編輯、`/aase export …` 匯出。
10. 不要這份了:`/aase close` 結束編輯(有沒存的變更會先問你);世界裡留下的作品用 `/aase remove` 收回(§13)。

## 6. 編輯工具詳解

`/aase tool` 給你一支工具(預設烈焰棒,可在 config 改)。有進行中的場景時,拿著它:

| 操作 | 作用 |
|---|---|
| **右鍵點元件** | 選取那個元件 |
| **左鍵**(對空氣/方塊) | 目前軸 **−** 一個步進 |
| **右鍵**(對空氣/方塊) | 目前軸 **+** 一個步進 |
| **滾輪** | 切換步進大小(1° / 15° / 45° 之類) |
| **潛行 + 滾輪** | 切換軸 X / Y / Z |
| **潛行 + 左鍵**(對空氣/方塊) | 切換模式(姿勢 / 移動 / 平移 / 旋轉 / 縮放) |
| **潛行 + 右鍵** | 切換部位(頭 / 身 / 左右臂 / 左右腿,盔甲座姿勢用) |
| **潛行 + 左鍵打自己的盔甲座** | 預覽收回這一份作品(跟 `/aase remove look` 一樣,要再點確認才會收) |

![編輯工具速查](assets/tool-controls.svg)

**收回手勢的細節**:

- 打到的是自己的盔甲座 → 顯示「將收回 N 個元件」與 `[確認收回]` `[取消]`;正在編輯同一份時多一顆 `[只刪這個元件 #編號]`。打到別人的 → 只顯示擁有者,不會動它。
- **只有一般盔甲座(非 marker)打得到。** 物品 / 方塊 / 文字 Display 和 marker 盔甲座沒有碰撞箱,工具點不到也打不到;它們請用 `/aase remove look`、`here`、`scene <名稱>`,或控制面板的「收回作品」。
- 還沒進入編輯(沒有 session)就拿工具右鍵作品 → 提示「先 `/aase edit` 綁定這組作品,或潛行左鍵收回」,附 `[/aase edit]` 按鈕。
- 空手(或拿別的東西)打作品 → 螢幕下方(actionbar)提示怎麼收回,或顯示擁有者;3 秒內最多提示一次。

畫面下方(actionbar)會即時顯示:`盔甲座#1 | 姿勢頭 | 軸 Y | 步進 15° | Y=+45°`。

> 小提醒:有場景在編輯時,滾輪被拿去當「換步進/換軸」。想正常換物品欄,先 `/aase close` 結束編輯,或把工具丟掉。

**模式說明**:

- **姿勢(POSE)**:只有盔甲座有。旋轉某個身體部位。
- **移動(MOVE)**:兩種元件都有。把整個元件沿軸平移。
- **平移 / 旋轉 / 縮放(TRANSLATE / ROTATE / SCALE)**:只有 Display 有,改它的內部變換。

## 7. 控制面板 GUI

`/aase` 打開控制面板。不想背指令的話,這裡幾乎所有事都能點:

![控制面板配置](assets/gui-layout.svg)

- **上排**:資訊、右上角📖手冊、新增盔甲座 / 物品 / 方塊 / 文字 Display
- **模式列**:移動 / 姿勢 / 平移 / 旋轉 / 縮放(目前選中的會標記)
- **部位列**:頭 / 身 / 左右臂 / 左右腿
- **軸 + 微調列**:X / Y / Z、步進 −/+、微調 −/+
- **旗標列**:迷你 / 隱形 / 無底板 / 無重力 / 手臂 / 標記 / 發光、裝備提示
- **作品列**:狀態卡、範本庫、儲存、匯出、刪除、**收回作品**(漏斗)
  - 「收回作品」只會列出身邊 8 格內你放的元件並關掉視窗,等你在聊天點 `[確認收回]` 才從世界收走;存檔保留。
- **最下面一排**:📖手冊與關閉

## 8. 範本庫

`/aase presets`(或面板底排「範本庫」)打開圖形範本庫。

- **上排 = 姿勢範本**:點一下套到「目前選取的盔甲座」。內建:立正、T 字、萬歲、揮手、指向、沉思、坐姿、跑步。
- **下排 = 特效範本**:點一下把一組調好的粒子加到選取元件(或你的位置)。內建:火焰光環、愛心、櫻花、星塵、靈魂之焰。
- **鏡像鍵**:把左手/左腿的角度鏡射到右邊,一鍵對稱(非美術最容易弄歪的地方)。

**指令版**:`/aase pose <id>`、`/aase fx <id>`、`/aase mirror`。

**存成自己的範本**:擺好一個盔甲座 → `/aase pose save <id> [名稱]`。它會把目前姿勢寫進 `presets.yml`,之後 `/aase pose <id>` 就能重用。等於你負責美感、插件負責記憶。

> 內建姿勢的角度是保守的近似值,可能不完美 —— 直接改 `presets.yml` 的數字(度),`/aase reload` 生效;或在遊戲裡微調好再 `pose save` 覆蓋。

## 9. 盔甲座

**姿勢**:六個部位(頭 / 身 / 左臂 / 右臂 / 左腿 / 右腿),每個三軸(X 前後傾、Y 左右轉、Z 側展),單位度。用範本、工具或面板調。

**裝備(選單,推薦)**:選取盔甲座後 `/aase equip`(或控制面板「裝備」鍵)→ 開裝備選單。**把背包物品點到游標上,再點頭盔/胸甲/護腿/靴子/主手/副手格子**就穿上;**空手(游標空)點格子=卸下**。全程只是「複製」游標上的物品給盔甲座,**不會消耗或弄丟你的物品**。

**裝備(指令,舊路徑)**:把裝備拿在**副手**,執行 `/aase setequip <欄位>`:`head / chest / legs / feet / mainhand / offhand`。副手空 = 清掉那格。

**開關(旗標)**:`/aase flag <名稱>` 或面板旗標列切換:

| 旗標 | 作用 |
|---|---|
| small | 迷你盔甲座 |
| invisible | 隱形(只看得到裝備) |
| nobaseplate | 去掉底板 |
| nogravity | 不受重力(編輯預設開) |
| arms | 顯示手臂(能擺手臂姿勢) |
| marker | 標記模式:無碰撞箱、超小,常用來當裝飾底座 |
| glowing | 發光輪廓 |

**命名**:`/aase setname <MiniMessage>`,例如 `/aase setname <red>守衛`。留空清除。

## 10. Display 元件

Display 實體比盔甲座自由太多:任意縮放、任意旋轉、無碰撞箱。三種:

- **物品 Display(item)**:顯示一個物品。`/aase adddisplay item`(預設用你副手的物品,沒有就石頭);之後 `/aase setitem`(副手物品)換內容。
- **方塊 Display(block)**:顯示一個方塊。`/aase adddisplay block` → `/aase setblock minecraft:oak_log`。
- **文字 Display(text)**:懸浮文字。`/aase adddisplay text` → `/aase settext <MiniMessage>`。

編輯變換:選中後用工具切到 **平移 / 旋轉 / 縮放** 模式微調,或控制面板。Display 的旋轉/縮放是動畫最順的載體(見 §12)。

## 11. 粒子特效

`/aase particle add <粒子類型>`(tab 補全看常用清單,例如 FLAME / HEART / CHERRY_LEAVES / END_ROD / DUST / SOUL_FIRE_FLAME)。發射器會加在你目前位置(相對場景原點)。`/aase particle clear` 清掉整個場景的發射器。用範本庫下排特效更快。

**效能設計**:發射器是隱形標記實體,只在「所在區塊已載入」且「附近有玩家」時才發射,而且全服每 tick 有處理上限(config `particles.budget-per-tick`)。不會因為你放很多而拖垮伺服器。

## 12. 動畫(關鍵影格)

> 這是這個工具「以後可以被變成動畫」的核心。先讀懂概念再動手。

### 概念

動畫 = 一條時間軸(長度以 **tick** 計,20 tick = 1 秒)+ 每個元件一條「軌道」+ 軌道上的「關鍵影格」。播放時,插件在關鍵影格之間**自動補間(插值)**,所以你只要擺幾個關鍵姿勢,中間會自己動。

### 做一段動畫(步驟)

1. 先把場景元件放好(`/aase addstand` 等),進 session。
2. 選一個元件,擺好**起始**姿勢 → `/aase anim key 0`(在第 0 tick 記一格)。
3. 把同一個元件擺成**結束**姿勢 → `/aase anim key 20`(在第 20 tick 記一格)。
4. `/aase anim length 20` 設定動畫長度。
5. `/aase anim play` → 元件會在兩個姿勢間平滑來回。
6. `/aase anim loop` 切換循環;`/aase anim stop` 停止並回到存檔姿勢;`/aase anim clear` 清空動畫。

多個元件各自 `key` 就會一起動。想要更細緻就多記幾格(例如 0 / 10 / 20 / 30)。

### 補間怎麼算

- 位置 / 縮放:直線內插。
- Display 旋轉:四元數最短路徑內插(nlerp),不會亂轉。
- 姿勢角度:各軸直線內插。

### 效能與載體選擇(重要)

- **Display 實體**走客戶端插值 —— 幾乎零伺服器成本、最順,**做動畫優先用 Display**。
- **盔甲座**沒有客戶端插值,播放時插件逐 tick 幫它換姿勢,有成本 —— 所以盔甲座動畫**只在你編輯的當下**播放,不常駐。
- 想要「作品放在世界裡自己一直動」→ **匯出成 mcfunction 資料包**(§14),由資料包在伺服器上驅動,不吃編輯 session。

### 現況與限制

- 動畫編輯目前走指令(`/aase anim …`),還沒有視覺化時間軸 GUI(見 §20 路線圖)。
- 即時播放只在編輯 session 內;登出或斷線時會自動停播並結束 session(作品與存檔都不動)。

## 13. 存檔 · 讀取 · 分享 · 編輯既有作品

- `/aase save` — 存成 `scenes/<你的UUID>/<場景id>.json`。
- `/aase list` — 你的場景清單。
- `/aase load <名稱>` — 在你腳下**放一份新的**(可放多份)。放置後**自動選取第一個元件**,可以直接接 `setequip`/`flag`/範本。**每次放置都是獨立的一份**:同一份存檔放兩次,兩份的 `edit`、`remove` 互不影響。
- `/aase edit` — 站在既有作品旁邊,把它**重新綁定**成 session 繼續編輯(不會產生分身)。只會綁**離你最近的那一份**,不會把附近同一份存檔的其他複本一起拉進來。注意它選取的是**離你最近的元件**;場景混有 display 時想精準選某個盔甲座,接一句 `/aase select`。
  - **孤兒會被跳過**:世界裡的元件如果存檔已經不在(或存檔裡已沒有這個編號),又沒有編輯中的 session 認領它,就是「孤兒」,`edit` 不會綁它。附近只剩孤兒時會提示用 `/aase remove look` 收回(附按鈕)。
  - **1.1.0 以前放的舊作品**第一次 `/aase edit` 時會自動補上分組,之後跟新作品一樣運作,不用做任何事。
- `/aase select <元件編號|next|prev>` — **指定選取**場景裡的元件(編號看 tab 補全或 `/aase info`,帶不帶 `#` 都可以)。Display 沒有碰撞箱、工具點不到,多元件場景換選取用這個最穩;`next`/`prev` 逐個輪。
- `/aase close` — 結束編輯(作品保留在世界)。**有未儲存的變更時不會直接關**,會先問:
  - `[儲存並關閉]`(= `/aase close save`):先存檔再結束,作品留在世界。
  - `[放棄並收回]`(= `/aase close discard`):不存檔,把**這一份作品**的實體與粒子從世界收回(不用再確認),存檔(如果有)不受影響。
  - `[繼續編輯]`:什麼都不做。
  - 沒有未存變更時,`/aase close` 直接結束。
- 離線(登出、斷線)會自動停掉播放中的動畫並結束 session;**不存檔、也不刪作品**,作品留在世界,回來用 `/aase edit` 接續。
- `/aase delete [元件編號]` — 刪掉選取的元件,或指定編號(`/aase delete 3`)。**世界優先**:實體會先被移除,再從作品裡拿掉。如果世界裡找不到那個實體(例如在未載入的區塊),會直說「世界中找不到 #3 的實體」,模型仍照刪,並給你 `[儲存]` 與 `[收回看著的作品]` 兩顆按鈕。刪完記得存檔。
- `/aase info` — 目前場景資訊(元件數、盔甲座/Display、發射器、動畫、選取、存檔狀態)。
- **分享(短碼)**:`/aase share` 會把作品上傳到 AASE Studio,回你一組 7 碼的**短碼**,附 `[點擊複製 /aase import <短碼>]` 與 `[在 AASE Studio 開啟]` 兩顆按鈕。別人在自己的伺服器輸入 `/aase import <短碼> [新名稱]` 就會在腳下放一份(owner 變成匯入者、新的 id,受每人數量上限、領地檢查守門)。短碼也可以是整段網址(`https://…/s/<短碼>`),大小寫不拘。短碼在網站上保留一段時間(預設 30 天),過期就要重新分享。
  - 上傳前會**去掉擁有者、場景 id、最後放置位置**,網站拿不到你的 UUID。
  - 伺服器關掉上傳(`share.upload: false`)、關掉遠端(`import.remote.enabled: false`)、或上傳失敗(網站沒回應、太頻繁、作品太大)時,改給**分享碼文字** `[點擊複製分享碼文字到剪貼簿]`(`AASE1:...`)。這串碼超過聊天欄 256 字的上限,請貼到 AASE Studio 或存成檔案轉交;`/aase import AASE1:…` 照樣能匯入(通常要用指令方塊或聊天以外的方式輸入)。
  - 匯入時有物品**這台伺服器認不得**(例如別的版本才有的物品、模組物品),那一格會留空,聊天會列出是哪幾格,例如 `#2 主手 minecraft:foo_sword`。其他部分照常放置。
  - 伺服器關掉遠端匯入時,`/aase import <短碼>` 會回「此伺服器未開放遠端匯入」;分享碼文字不受影響。
- **分享(檔案)**:JSON 檔本身也可攜 —— 把 `scenes/…/xxx.json` 傳給別人丟進他的 `scenes/<他的UUID>/`(記得改檔內 `owner`)。

### 收回自己放在世界裡的作品(`/aase remove`)

放錯了、不要了、`new` 之後後悔?元件打不壞,用這組指令收回。權限 `aase.use`,**只動你自己放的**(管理員也一樣;動別人的走 `/aase admin`)。

| 指令 | 收哪些 |
|---|---|
| `/aase remove look` | 你看著的那一份作品;沒對準時改取範圍內離你最近、你自己的元件(範圍是 `tool.select-range`,預設 6 格) |
| `/aase remove here [半徑]` | 你身邊半徑內全部的元件。預設 8 格,上限是 `admin.max-purge-radius`(預設 64) |
| `/aase remove scene <名稱>` | 用作品名稱找:這個名稱放出來的所有複本(名稱可含空格) |
| `/aase remove confirm` / `cancel` | 確認 / 取消上一次預覽 |

流程是**兩段式**:前三種只**預覽**,顯示「將收回 N 個元件(M 組)」(有粒子發射器會另列),再點 `[確認收回]`(或打 `/aase remove confirm`)才真的動手。預覽後 **30 秒內**要確認,逾時要重新預覽。

- **存檔不會被刪**,收回後隨時可以 `/aase load` 再放一份。
- **只處理已載入區塊裡的實體**(不掃世界),訊息會提醒;太遠的區塊先走過去載入再收。
- 你正在編輯的那一份被收回時,編輯會一併結束。
- 沒有存檔可綁定的殘留元件(孤兒)也用這組指令清,`/aase edit` 找不到時會直接給你按鈕。
- 每次收回都寫一筆 LycoLib 稽核紀錄(沒裝 LycoLib 就略過)。

## 14. 匯出

### summon 指令

`/aase export command` —— 聊天出現一個**可點擊複製**的按鈕,把整個場景的 `/summon` 指令複製到剪貼簿;同時存成 `exports/<場景>.txt`。貼進遊戲或指令方塊執行就能重現作品。

### mcfunction 資料包

`/aase export function` —— 匯出一個資料包到 `exports/<場景>/datapack/`:

```
pack.mcmeta
data/aase/function/summon.mcfunction     ← 召喚整個場景(元件帶 Tag)
（有動畫時再多)
data/aase/function/load.mcfunction        ← 初始化計時器
data/aase/function/tick.mcfunction        ← 驅動關鍵影格播放(每 tick 自我排程)
data/aase/function/frames/frame_*.mcfunction
```

用法:把資料夾丟進世界的 `datapacks/`,`/reload`,然後:

- 靜態:`/function aase:summon`
- 動畫:`/function aase:load` → `/function aase:summon` → `/function aase:tick`

> **NBT 已對 26.2 實測**:姿勢/旗標/裝備/變換/物品/方塊/亮度/發光皆正確;自訂名與文字用 SNBT(`CustomName:"名字"`、`text:"文字"`,不是舊版 JSON 字串)。仍為最佳努力:pack_format 與 `function` 資料夾名稱依版本可能要調;裝備只帶物品 id、方塊只帶方塊名、名稱/文字不保留顏色。`SummonExporter` / `McFunctionExporter` 是集中修改點。

## 15. 指令總表

| 指令 | 說明 | 權限 |
|---|---|---|
| `/aase` | 開控制面板 | aase.use |
| `/aase guide` | 打開翻頁手冊 | aase.use |
| `/aase tool` | 取得編輯工具 | aase.use |
| `/aase new <名稱>` | 新場景 | aase.use |
| `/aase presets` | 範本庫 GUI | aase.use |
| `/aase pose <id>` / `pose save <id> [名稱]` | 套用 / 儲存姿勢 | aase.use |
| `/aase fx <id>` | 加特效範本 | aase.use |
| `/aase mirror` | 左右鏡像 | aase.use |
| `/aase addstand` | 加盔甲座 | aase.create.armorstand |
| `/aase adddisplay <item\|block\|text>` | 加 Display | aase.create.display |
| `/aase equip` | 裝備選單(點格子穿脫) | aase.use |
| `/aase setblock/settext/setitem/setname/setequip/flag …` | 編輯內容 | aase.use |
| `/aase particle add <類型>` / `clear` | 粒子 | aase.use |
| `/aase anim key/length/loop/play/stop/clear` | 動畫 | aase.animate |
| `/aase save` / `load <名稱>` / `list` / `info` / `edit` | 存讀/清單/資訊/編輯 | aase.use / aase.scene.save |
| `/aase delete [元件編號]` | 刪選取的(或指定編號的)元件;世界優先 | aase.use |
| `/aase remove look` / `here [半徑]` / `scene <名稱>` | 預覽收回自己放的作品(只動自己的) | aase.use |
| `/aase remove confirm` / `cancel` | 30 秒內確認 / 取消收回 | aase.use |
| `/aase share` / `import <短碼\|網址\|AASE1:…> [名稱]` | 上傳取得短碼(或給分享碼文字)/ 匯入 | aase.scene.share |
| `/aase pose save <id> [名稱]` | 存進共用範本庫 | **aase.preset.save(預設 OP)** |
| `/aase export command` / `export function` | 匯出(寫伺服器檔案) | **aase.export.command(預設 OP)** |
| `/aase close [save\|discard]` | 結束編輯;有未存變更會先問,`save` 存了再關、`discard` 不存並收回這份作品 | aase.use(`close save` 另需 aase.scene.save) |
| `/aase admin whois` | 查最近的元件是誰放的、屬於哪一份放置、是不是孤兒 | **aase.admin(預設 OP)** |
| `/aase admin remove` | 移除最近的一個元件 | **aase.admin(預設 OP)** |
| `/aase admin purge <半徑> [玩家]` | 預覽半徑內要移除的元件 | **aase.admin(預設 OP)** |
| `/aase admin confirm` | 確認執行上一次預覽的清除 | **aase.admin(預設 OP)** |
| `/aase reload` | 重載設定 | aase.admin |

> 為什麼 `export` / `pose save` 預設只給 OP:這兩個會**寫入伺服器的檔案 / 改動全服共用的 `presets.yml`**,不適合開放給每個玩家(避免洗檔、洗範本庫)。控制面板的匯出鍵對沒權限的人**直接隱藏**,點也點不到。用 LuckPerms 把 `aase.export.command` / `aase.preset.save` 授予建築師群組即可放行。

## 16. 權限總表

```
aase.use                 開編輯器 / 用工具(預設所有人)
aase.create.armorstand   放盔甲座(預設所有人)
aase.create.display      放 Display(預設所有人)
aase.scene.save          存檔(自己的存檔資料夾,預設所有人)
aase.scene.share         產生 / 匯入分享碼(不落地檔案,預設所有人)
aase.animate             動畫(預設所有人)
aase.clear               /aase clear <半徑> 清掉別人放在你有建築權之處的元件(預設所有人)
aase.export.command      匯出指令 / 資料包(寫伺服器檔案)— 預設 OP
aase.preset.save         /aase pose save 寫全服共用 presets.yml — 預設 OP
aase.admin               管理(編他人、/aase admin whois|remove|purge、reload)— 預設 OP
aase.bypass.region       略過領地檢查 — 預設 OP
aase.bypass.limit        略過數量上限 — 預設 OP
```

### 管理員:有人亂放怎麼辦(`/aase admin`)

玩家把盔甲座或展示實體放在奇怪的地方時,管理員的處理流程:

1. `/aase admin whois` — 站到它旁邊,查出**是誰放的**、屬於哪個作品、座標,另一行是**放置分組**(取放置 ID 前 8 碼;1.1.0 以前的舊元件寫「舊版未分組」),沒有存檔可綁定的會標 **(孤兒:沒有存檔可綁定)**。
2. `/aase admin remove` — 移除最近的那一個(不必開編輯 session)。
3. 一整片都要清:`/aase admin purge <半徑> [玩家]` **只會預覽**要清幾個,再 `/aase admin confirm` 才真的執行(60 秒內有效)。

刻意的邊界,別誤會它的能力範圍:

- **只碰本插件放置的元件。** 玩家用手放的原版盔甲座、別的插件生的實體一律不動 —— 否則這插件就變成 entity killer。`whois` 找不到就會直說。
- **只碰已載入區塊。** 本插件不做世界/區塊掃描(效能紅線),所以 purge 到不了沒人載入的地方。先過去把區塊載入再清。
- **不刪玩家的存檔。** purge 只清世界上的實體,玩家的作品檔還在,他們可以重新放到合理的位置。這是能解決問題的最小破壞。
- **半徑清除是兩段式**,且 `purge` 的半徑會被 `config.yml` 的 `admin.max-purge-radius`(預設 64)夾住。
- 每次 `remove` / `purge` 都會寫一筆 **LycoLib 稽核紀錄**(沒裝 LycoLib 就安靜略過)。

## 17. 設定檔

- **config.yml**:`language`(`auto` / `zh_TW` / `en`)、工具材質、步進大小、每人/每區塊/全域數量上限、領地事件探針開關、粒子預算與範圍、`admin.max-purge-radius`(管理員清除半徑上限,預設 64;玩家 `/aase remove here` 的半徑也以它為上限)。
  - `store.write-schema`:存檔格式,`3`(預設,度數與物品 id,可以直接在 AASE Studio 編輯)或 `2`(舊格式,給還在跑 1.2.x 以前的伺服器讀)。兩種插件都讀得懂。
  - `import.remote.*`:遠端匯入。`enabled`(關掉就完全不連線)、`base-url`(只接受 https;http 只限 localhost)、`timeout-seconds`、`max-bytes`(預設 1 MiB)、`cooldown-seconds`(每人)、`max-concurrent`(全服)。
  - `share.upload`:`/aase share` 要不要上傳取短碼。插件只會主動對外連線,不開任何監聽 port。
- **presets.yml**:姿勢與特效範本(角度用度,可增改;`/aase pose save` 會寫進這裡)。範本的**顯示名**由語言檔的 `preset.name.<id>` 決定;自己存的範本沒有這個 key,就直接顯示 presets.yml 裡的 `name`。
- **lang/zh_TW.yml、lang/en.yml**:所有玩家可見文字(MiniMessage;指令名 `<aqua>`、點擊提示 `<yellow>`),連同 `guide.pages`(遊戲內翻頁手冊,書是紙底色,請用深色)。要加語言就複製一份改檔名,再把 `language` 指過去。

全部 `/aase reload` 生效,不用重啟。

> 從 0.x 升上來:舊的 `messages.yml` / `guide.yml` 不再被讀取,啟動時會在 log 提醒你。把改過的字搬進 `lang/<代碼>.yml` 之後就能刪掉。

## 18. 疑難排解 FAQ

**Q:工具左右鍵沒反應?** 先 `/aase new` 或 `/aase edit` 進 session,再拿工具右鍵點元件選取。

**Q:滾輪不能換物品欄?** 編輯中滾輪被拿去換步進/軸。`/aase close` 結束編輯即可。

**Q:放不了東西、說「這裡受保護」?** 你在別人的領地。到自己的地,或請管理員給 `aase.bypass.region`。

**Q:有人把作品放在我的領地裡,我打不壞?** 元件是刻意打不壞的(免得被流彈、怪物、隨手一拳毀掉)。站在那裡跑 `/aase clear <半徑>`——你在領地內有建築權,所以清得掉;領地外別人的東西你動不了。他們的存檔不會被刪,可以重放。

**⚠ 管理員注意:別用 `/kill` 處理作品。** `/kill` 對本插件元件的效果依伺服器版本而異:26.2 實測會穿透保護直接移除(`/kill @e[type=armor_stand]` 會清光全服作品,連同玩家手放的原版盔甲座),26.3 實測則被保護擋下、元件留著。不論哪種,元件被殺時都不會掉出裝備(展示用物品可能來自別人的分享)。要清元件請用 `/aase clear` 或 `/aase admin remove|purge`——它們只碰本插件標記過的東西。

**Q:載入(load)後變兩份?** `load` 是「放新的一份」。要接續編輯既有作品用 `/aase edit`。不要的那份用 `/aase remove look` 收回。

**Q:`/aase new` 之後後悔了,世界上留著東西?** 先 `/aase close`,在「有未儲存的變更」那句選 `[放棄並收回]`,這一份就從世界收走(新場景沒存過就不會有存檔,存過的存檔不受影響)。已經關掉 session 才發現的話,站在旁邊用 `/aase remove look`(或 `here`)收回。

**Q:`/aase edit` 說「沒有存檔可綁定」或「附近只剩沒有存檔可綁定的元件」?** 那些是孤兒(存檔已不存在,或存檔裡沒有它的編號)。照提示用 `/aase remove look` 收回。

**Q:拿工具潛行左鍵打不到我的 Display / marker 盔甲座?** 這兩種沒有碰撞箱,手勢打不到。用 `/aase remove look`、`here [半徑]`、`scene <名稱>`,或控制面板的「收回作品」。

**Q:`/aase delete` 說「世界中找不到實體」?** 通常是那個元件所在的區塊沒載入。作品模型裡已經刪掉了(記得存檔);之後在現場看到殘留,用 `/aase remove look` 收回。

**Q:場景裡有好幾個盔甲座,第二個怎麼樣都穿不上裝備?** `setequip` 只作用在「目前選取」的元件,而 `/aase edit` 綁的是**離你最近的實體**——場景裡混有 display(方塊/文字/物品)時,你以為選到盔甲座,其實選到旁邊的 display,就會一直看到「只有盔甲座能穿裝備」。用 `/aase select <編號>` 直接指定那個盔甲座(編號按 tab 就列出來),再 `setequip`。

**Q:動畫播完就停/不會一直動?** 即時播放只在編輯時。要常駐動畫請 `export function` 用資料包驅動。

**Q:範本姿勢看起來怪?** 內建角度是近似值。改 `presets.yml` 或自己擺好 `pose save` 覆蓋。

**Q:匯出的指令跑出來怪怪的?** NBT 是最佳努力、版本敏感。回報給作者修 `SummonExporter`/`McFunctionExporter`。

## 19. 效能與安全設計

- **不掃世界 / 不掃區塊**:數量計數在記憶體,孤兒實體只在區塊載入當下處理。
- **粒子有預算**:只在附近有玩家 + 已載入區塊發射,每 tick 全服上限。
- **動畫有節制**:Display 走客戶端插值;盔甲座逐 tick 只在編輯 session 內,離線自動停。
- **反格里芬**:元件有擁有權 PDC,別人打不壞、拿不走;數量上限防洗版;尊重領地保護插件。
- **跨平台**:只用 Bukkit/Spigot API,文字用內嵌 Adventure,丟 Spigot 也正常。

## 20. 路線圖

往「完整動畫工具」的下一步(尚未做):

- **視覺化時間軸 GUI**:拖曳關鍵影格、預覽刷桿,取代目前的 `/aase anim` 指令。
- **姿勢間補間助手**:選兩個存好的姿勢,一鍵生成中間動畫。
- **緩動(easing)**:linear 以外的 ease-in/out,讓動作更自然。
- **對外 API / 事件**:讓別的插件掛我們的存檔/放置事件。
- **拖放式裝備 GUI**、**粒子/動畫視覺化編輯面板**。

有想優先的方向跟作者說。
