# AwesomeArmorStandEditor — 設計與實作規劃

> 獨立可開源的盔甲座 / Display 場景編輯器。此文件是設計層 + 路線圖;版本待辦看本檔末的 P1 清單與 `HANDOFF.md`。

## 0. 定位與硬規則

- **可開源、上架 SpigotMC**:程式碼與命名對外界友善,所有玩家可見文字外部化到 `lang/<代碼>.yml`(附繁中與英文,依伺服器地區自動選,可被覆寫/翻譯)。
- **完全獨立、跨平台(Spigot + Paper)**:不硬依賴任何插件(含 LycoLib)。外部使用者一載即可用,丟 Spigot 不 crash、正常運作。
  - **只用 Bukkit/Spigot API 面**,不碰 Paper-only 方法(否則 Spigot 端 `NoSuchMethodError`)。踩雷點:用 `World.rayTraceEntities` 不用 Paper `getTargetEntity`;`TextDisplay.setText(String)` 不用 `text(Component)`;文字一律走自帶 audience 不用 Paper 原生 `sendMessage(Component)`;物品序列化用 `BukkitObjectStream` 不用 Paper `serializeAsBytes`。
  - **文字用打包(shade+relocate)的 Adventure + MiniMessage**,經 `BukkitAudiences` 送:Spigot/Paper 一致的現代文字 + 匯出指令一鍵點擊複製。relocate 到 `com.tinyyana.awesomeArmorStandEditor.libs.kyori.*`。
- **不反向依賴外部插件**。與外部整合一律用三種手段之一:
  1. **Bukkit 權限節點** — 任何權限插件(LuckPerms…)透明支援,零依賴。
  2. **軟整合(softdepend + 反射)** — 領地類(GP/WorldGuard)與 LycoLib 都用 `PluginManager` 存在檢查 + 反射/事件探針,存在才啟用;編譯期不依賴它們。
  3. **自家 API / 事件** — 讓別的插件反過來掛我們,而不是我們掛它們。
- **LycoLib 軟整合**:偵測到 LycoLib 時反射呼叫其 `AuditLog`(Lycohinya 環境加值);缺席則 no-op。編譯期不連 LycoLib、不進 composite build。
- 效能與安全是紅線,不是加分項(見 §8)。

## 1. 四個已定案決策(2026-07-08)

| # | 決策 | 選定 |
|---|---|---|
| 1 | 編輯互動模型 | **混合式**:GUI 控制面板 + 手持編輯工具在世界中直接抓取/旋轉 + actionbar 即時讀數 |
| 2 | 動畫載體與執行 | **盔甲座與 Display 皆可上關鍵影格 + 嚴格預算**:Display 走客戶端插值(便宜),盔甲座逐 tick 更新但有硬並發上限 + 距離裁剪 |
| 3 | 首版範圍 | **分階段**:P1 先做紮實靜態編輯器;粒子(P2)、關鍵影格動畫(P3)、分享碼/API(P4)排後 |
| 4 | 領地尊重 | **通用事件探針 + 選用橋接**:放置/編輯前模擬保護事件(自動相容會攔事件的領地插件);另對 GP/WorldGuard 加反射橋接給精準訊息 |

## 2. 資料模型

骨架一次到位(含動畫欄位),功能分期長出來。序列化用 **Gson(Paper 執行期已內建,`compileOnly` 對編譯,執行期由伺服器提供,不 shade)**;`SceneCodec` 手寫 `toJson/fromJson`(顯式處理 `Element` 多型的 `type` 判別欄位與 `schemaVersion` 演進),不用反射對映——避免 Gson 以 Unsafe 繞過建構子讓 Kotlin 非空欄位變 null。

```
Scene                         # 存檔單位,一個 .json(1.3.0 起預設寫 v3,v2 照讀)
  schemaVersion: Int          # 3;讀入時 2 / 缺省走舊格式讀取器
  id: String (uuid)
  owner: UUID
  name: String
  anchor: 相對錨點(存相對座標,可攜/可分享)
  elements: List<Element>
  animation: Animation?       # P3 才填,P1 欄位留空

Element (sealed)              # 兩種一等公民,皆帶 localId
  ArmorStandElement
    pose: Pose6 (head/body/leftArm/rightArm/leftLeg/rightLeg = 各 xyz 弧度)
    equipment: 6 格 ItemRef?(見下)
    flags: {small, invisible, noBasePlate, noGravity, arms, marker, glowing}
    offset: Vec3(相對錨點) + yaw
  DisplayElement
    kind: ITEM | BLOCK | TEXT
    transform: Transform(translation, leftRotation(quat), scale, rightRotation(quat))
    item: ItemRef?(ITEM)/ payload: blockData(BLOCK)| MiniMessage(TEXT)
    billboard, brightness?, glow?, viewRange
    offset: Vec3 + yaw

Animation (P3)
  lengthTicks: Int
  loop: Boolean
  tracks: List<Track>         # 每 track 綁一個 element.localId
    keyframes: List<Keyframe>  # tick → 目標 pose/transform + 插值型別(linear/step/ease)
```

**Scene JSON v3(1.3.0)**:真本是 repo 根目錄的 `schema/scene.v3.schema.json`(JSON Schema 2020-12,全面 `additionalProperties:false`),jar 內附一份。和 v2 的差別:盔甲座姿勢寫 `poseDeg`(度,同 `/summon` 的 Pose,缺的部位 = 0)、Display 旋轉可寫 `rotationDeg`(歐拉角,qx·qy·qz,JOML `rotationXYZ`)、物品寫 **ItemRef** `{id, count, components, bukkit}`、Display 內容分成 `item` / `block` / `text`。舊形狀(`pose` 弧度、四元數、`payload`、base64 物品字串)在 v3 仍合法,與新形狀互斥。
- **ItemRef**:`id`(`minecraft:diamond_sword`)、`count`(1..99)、`components`(與 `/give` 相同的 `[k=v,...]`,`ItemMeta#getAsComponentString()` 正規化只留中括號部分)、`bukkit`(插件寫的精確 base64)。插件執行期解析順序:`bukkit` → `ItemFactory#createItemStack(id + components)`;都不行就那格留空並告訴玩家。v2 的 base64 讀成 `ItemRef(id=null, bukkit=…)`,寫 v3 時仍是字串。
- **讀**:`SceneCodec.decode` — v2 寬鬆照舊;v3 先 `SceneValidator`(解讀內附 schema + 語意檢查:localId 不重複、Display 有對應內容、動畫軌指向存在的元件、發射器 id 不重複),錯誤是 `{pointer(RFC 6901), message}`,依文件順序深度優先排序、取第一個,與網站 `validate.ts` 一致(負向 golden 樣本兩邊都跑)。通過才正規化:度 → 弧度、`rotationDeg` → 四元數、補預設。
- **寫**:預設 v3(`poseDeg` 四捨五入到 4 位小數、四元數原樣、ItemRef);`store.write-schema: 2` 寫舊格式(沒有 `bukkit` 的 ItemRef 在執行期轉成物品再編碼,轉不出來就省略該格並 log)。寫出的 v3 自己再驗一次,無法表達(例如超過 200 個元件)就退回 v2,不丟資料。

執行期實體不存 NBT 大狀態;實體只掛 PDC 幾個小 key:`owner`、`scene`(sceneId)、`local`(elementLocalId),1.2.0 起加 `placement`(放置 ID,字串 UUID)與 `sceneName`(放置當時的作品名)。粒子發射器 marker 另有 `emitter`。其餘狀態以 Scene JSON 為準。

**一次放置 = 一個 placement**:`load` / `import` / `new` 每放一次就產生新的 `placement` ID,蓋在該次生成的所有元件與發射器上,session 也持有同一個 ID(`EditSession.placementId`)。`edit` / `select` / `remove` 一律以 placement 為界,所以同一份存檔的兩份複本互不牽連。1.1.0 以前的實體沒有 `placement`(`sceneName` 同),第一次 `/aase edit` 時以「擁有者 + 作品 + 位置」分組後補蓋(`LegacyGrouping`),補完即同新實體。`placement` / `sceneName` 只用於分組與收回,不是真本——存檔才是。

## 3. 持久化與分享

- 路徑:`plugins/AwesomeArmorStandEditor/scenes/<owner-uuid>/<sceneId>.json`。
- **不上資料庫**(開源友善、可攜)。存檔就是可讀 JSON。
- 分享:`/aase share` 上傳到擺景亭取短碼(`share.upload`),失敗或關閉時給 `AASE1:` 分享碼文字(gzip + URL-safe Base64 的 v3 JSON);兩者都**去識別**(剝 `owner` / `id` / `lastAnchor`)。整份 JSON 檔也可直接轉交。
- **遠端匯入的信任邊界**(`remote/`):`/aase import <短碼|網址>` 是插件唯一的網路行為,**只 outbound**(`java.net.http.HttpClient`,不開監聽 port);`base-url` 只接受 https(http 只限 localhost / 127.0.0.1),不跟隨轉址。回應邊讀邊計數,超過 `max-bytes`(預設 1 MiB)立即中止;連線與整體各有逾時;每人 cooldown、全服同時數上限。內容先嚴格 JSON 解析、再 schema + 語意驗證,通過後回主執行緒(Bukkit scheduler `runTask`,玩家已離線就丟棄),再走與 `load` 相同的守門:未存變更詢問 → 每人元件上限 → `checkLimits` → `checkRegion` → `AaseScenePlaceEvent` → 新 placement、重新擁有。`enabled: false` 時不建立 HttpClient 也不開執行緒。外來字串(作品名、錯誤訊息)放進 MiniMessage 前先跳脫。
  - **不受信任來源一律嚴格驗證**:AASE1 分享碼、遠端短碼與任何外部 JSON 都走 `SceneCodec.decode`(預設 untrusted)→ `SceneValidator`,不論 `schemaVersion`(2 或缺省也一樣;schema 本身就描述了 v2 的弧度 pose、四元數、base64 物品與 `payload`)。只有 `SceneStore` 讀自己的存檔用 `trusted = true`,v2/缺省走舊的寬鬆讀取,舊存檔不會讀不出來。驗證順序:解析前用字元掃描算巢狀深度(略過字串內容與跳脫),超過 64 層在 `""` 報 `nesting too deep`;解析後每個數字必須是有限數(Gson 把 `1e999` 取成 double 是 Infinity),否則在該數字報 `must be a finite number`;之後才是 schema 與語意。schema 數值範圍:offset(元素、粒子、關鍵影格)與 translation ±256、scale ±64(負值為鏡像)、yaw/poseDeg/rotationDeg ±3600、粒子 count 0–1000、rateTicks 1–1200、lengthTicks 1–72000、tick 0–72000、各種 id ≤ 2147483647。網站 `validate.ts` 用同一份 schema、同樣順序,invalid fixtures 兩邊第一個 pointer 必須相同。AASE1 匯入失敗時回覆前 3 個 pointer(與遠端匯入同格式)。
  - **物品 base64 是信任邊界**:`bukkit` 欄位(以及 v2 分享碼的裝備)會進 `BukkitObjectInputStream`,也就是 Java 反序列化任何人都能 POST 的位元組。`ItemCodec.decode` 掛 `ObjectInputFilter` 白名單(Bukkit `Wrapper`、Guava 不可變集合、`java.util` 常用集合、基本型別包裝與字串,外加深度/參照數/位元組數/陣列長度上限),其他類別一律拒絕,擋掉反序列化 gadget。物品**內容**仍可偽造(任意附魔、其他插件信任的自訂資料)——`id + components` 本來就做得到——所以展示品上的物品永遠不能回到玩家手上:裝備選單只複製游標、拿不出東西;原版取裝備被擋;本插件實體死亡時清空掉落物。
- 存檔是「藍圖」;世界裡的實體是藍圖的一次「放置(placement)」。刪實體不刪存檔;可重複放置同一存檔到不同位置,每次放置各有自己的 placement ID(見 §2)。
- **孤兒定義**:實體帶本插件的 PDC 但沒有東西能綁它——(a) 該 scene 沒有存檔,且沒有任何開著的 session 認領它的 placement / scene;或 (b) 存檔還在但已不列出它的 localId(發射器與元件各自比對)。若 session 的記憶體模型仍有該 localId(剛加還沒存),不算孤兒。`edit` 跳過孤兒、`admin whois` 標示孤兒,清除走 `/aase remove`。純邏輯在 `recall/RecallLogic.kt`(`OrphanRule`)。
- **收回**(`recall/RecallService`)只改世界,不碰存檔;查找不走世界掃描:`EntityRegistry` 索引(UUID → Tag,以 `Server.getEntity` 解析)聯集 seed 附近 `getNearbyEntities`,都再用 PDC 過濾,所以只到得了已載入區塊。只處理請求者自己的實體(`aase.admin` 不放寬)。

## 4. 架構與套件配置(沿用 house 慣例)

```
com.tinyyana.awesomeArmorStandEditor
  AwesomeArmorStandEditorPlugin        # onEnable 串接;服務以 lateinit var private set 暴露
  model/            Scene, Element, Transform, Pose6 …(@Serializable)
  store/            SceneStore(JSON 讀寫), SceneCodec
  session/          EditSession(每玩家編輯狀態:選中元件/軸/步進/模式), EditSessionManager
  placement/        PlacementService(把 Scene 放進世界 / 收回), EntityRegistry(追蹤本插件實體+計數+上限)
  edit/             EditToolListener(工具點擊/滾輪/潛行), PoseOps / TransformOps(純邏輯,可單元測試)
  menu/             ControlPanelHolder/Service/Listener, EquipmentMenu, FlagsMenu, SceneListMenu(Holder+Service+Listener)
  command/          AaseCommand(TabExecutor,子指令 when 分派)
  region/           RegionGuard(介面) + EventProbeGuard(通用) + GriefPreventionBridge / WorldGuardBridge(反射,選用)
  export/           SummonExporter(P1), McFunctionExporter(P3)
  config/           EditorSettings, LimitSettings(companion load(config))
  api/              事件:SceneSaveEvent / ElementPlaceEvent …(P4 對外)
```

慣例:GUI 一律 Holder+Service+Listener(取消點擊、`holder.actions[slot]` 分派);文字全走 `plugin.messages.get(key, …)`;item 名/lore 用 `mm.deserializeUpright`;狀態變更/管理動作呼叫 `AuditLog.log`;顏色用 `LycoColors`;GUI 大小只有 3/4/6 排,54 格用 `Bukkit.createInventory`。

## 5. 編輯 UX(混合式)

**選取**:手持編輯工具看向元件 → 射線選中(`rayTraceEntities`/`getTargetEntity`),actionbar 顯示選中元件與當前軸。空手不觸發,避免誤操作。

**世界中直接操作(工具)**:
- 左鍵 / 右鍵 = 沿當前軸 −/+ 一個步進。
- 潛行 + 滾輪 = 切換軸(X/Y/Z)或部位(頭/身/左右臂/左右腿)。
- 潛行 + 左鍵 = 切換步進(1° / 15° / 45°,或平移 0.1 / 1)。
- 全程 actionbar 即時讀數:`盔甲座#1 · 頭 · Y=+45° · 步進15°`。

**GUI 控制面板**(`/aase` 或工具右鍵開):
- 選元件 / 部位 / 軸、數值微調(±1/±15)、切換平移⇄旋轉⇄縮放(Display)。
- 裝備子選單(6 格拖放)、旗標子選單(toggle)、存檔/讀取/清單、匯出。
- 面板與世界工具共享同一 `EditSession`,兩邊即時同步。

**安全**:攔 `PlayerArmorStandManipulateEvent`,編輯模式下禁止原版拿取裝備;非擁有者的元件不可選取/編輯。

## 6. 領地尊重(RegionGuard)

```
interface RegionGuard { fun canBuild(player, location): Boolean }
```

**為什麼需要探針。** 我們用 `world.spawn()` 直接生成盔甲座與 Display,這**不會觸發任何原版放置事件**(`BlockPlaceEvent` 是方塊的;`EntityPlaceEvent` 標記為 internal 且需要已生成的實體,無法當前置檢查)。也就是說領地插件從頭到尾看不到我們的寫入,不可能替我們否決。所以 guard 不是「模擬我們的行為」,而是**主動去問**領地插件一個等價問題:「這個玩家可以在這一格蓋東西嗎?」然後由我們自己遵守答案。

**推論:每一個生成或傳送元件的地方都必須自己呼叫 guard。** 漏掉一個,那條路徑就完全沒有保護,而且不會有任何下游機制補救。(0.2.0 就是這樣漏了 `load` / `import` / MOVE / `fx` 四條。)

- `EventProbeGuard`(預設、通用):於同步執行緒發一個合成 `BlockPlaceEvent`,凡是會攔 place 的領地插件(GP/WorldGuard/Towny/Lands…)自動生效。探針事件不落地、不改世界。已知代價:方塊紀錄插件(CoreProtect 等)可能記到這筆探針,放置的方塊是玩家腳下的空氣,多數會被濾掉。
- `GriefPreventionBridge` / `WorldGuardBridge`(選用、反射):偵測到插件才載入,提供更精準的拒絕訊息與 claim owner 判斷。
- 疊加規則:先擁有權(PDC owner)→ 再數量上限 → 再 RegionGuard。任一拒絕即擋。
- 整個場景落地(`load` / `import`)時,探測點由 `ScenePoints.offsets()` 列舉:原點 + 每個元件 + 每個粒子發射器 + **每個動畫關鍵影格的位移**(匯入的分享碼可以夾帶把元件甩進遠處領地的 keyframe),再依方塊座標去重。
- `aase.bypass.region` 權限可略過(管理/創造服)。

> ⚠ 合成事件探針的事件建構子簽章需對 26.2 逐一驗證(見 API 驗證表);若某事件建構子不穩,該類回退到「橋接優先 + 權限」策略,不硬送不穩的合成事件。

## 7. 權限節點

```
aase.use                 開啟編輯器 / 使用工具(預設 true 視伺服器)
aase.create.armorstand   放置盔甲座元件
aase.create.display      放置 Display 元件
aase.scene.save          存檔
aase.scene.share         匯出/匯入分享
aase.export.command      匯出 summon 指令
aase.animate             動畫(P3)
aase.clear               清除別人放在你有建築權之處的元件(領主自助清理,非管理員)
aase.admin               管理(編他人作品、reload、purge)
aase.bypass.region       略過領地檢查
aase.bypass.limit        略過數量上限
aase.limit.<n>           數量上限覆寫(取最大)
```

## 8. 效能與安全紅線

- **不做世界掃描 / 區塊掃描**。chunk-load 只建記憶體索引;孤兒只被標示(whois),由玩家 `/aase remove` 或管理員收回,範圍限已載入區塊;計數在記憶體。
- **不逐 tick 查 DB / 不逐 tick 解析設定**。設定啟動解析,`/aase reload` 重載。
- **數量上限**:每人 / 每區塊 / 全域元件上限(config),記憶體計數,超限拒絕放置。
- **動畫預算(P3)**:只有玩家附近的作品才 tick;Display 動畫走客戶端插值(伺服器只在關鍵影格設一次 transform);盔甲座逐 tick 動畫有硬並發上限 + 距離裁剪 + 每 tick 更新數上限,超限降級(降幀或暫停遠處)。
- **粒子(P2)**:經顯示預算限流,不無節制 spawn。
- 所有實體掛本插件 PDC 標記,可被 `/aase admin purge` 精準清除,不誤刪玩家原有盔甲座。

## 9. 指令

```
/aase                     開啟控制面板(玩家)
/aase tool                取得編輯工具
/aase new <name>          新場景並進入編輯
/aase save                存檔
/aase load <name>         讀取放置
/aase edit                綁定附近一份既有放置繼續編輯(跳過孤兒)
/aase delete [編號]       刪元件(世界優先);省略編號 = 目前選取的
/aase remove look|here [半徑]|scene <名稱>   預覽收回自己放的作品(只動自己的、只已載入區塊、不刪存檔)
/aase remove confirm|cancel                  30 秒內確認 / 取消
/aase close [save|discard]  結束編輯;有未存變更先問,save 存了再關,discard 不存並收回本份
/aase list                我的場景清單(GUI)
/aase export command      匯出 summon 指令(可複製)
/aase share               上傳取得短碼;關閉或失敗時給分享碼文字(超過聊天 256 字,貼到擺景亭或存檔)
/aase reload              重載設定(管理)
/aase admin …             管理:編他人、purge、統計
```

## 10. 匯出

- **P1**:`/summon` 指令(盔甲座 + display,含姿勢/變換/裝備 NBT),聊天可點擊複製。NBT 走 26.2 component 格式,需對版本驗證語法。
- **P3**:`.mcfunction` / datapack(含動畫的 schedule 或 interpolation 驅動)。

## 11. 分期與 P1 驗收清單

**P1 — 靜態編輯器(第一個可交付版本)**

- [ ] 建置串接:`includeBuild("../LycoLib")`、`compileOnly` LycoLib + stdlib + gson、plugin.yml `depend`/`libraries`/`commands`/`permissions`。編譯通過(L1)。
- [ ] 資料模型 + JSON 持久化(SceneStore 讀寫 round-trip 單元測試)。
- [ ] EntityRegistry + 擁有權 PDC + 數量上限。
- [ ] RegionGuard(EventProbeGuard + GP/WG 橋接)。
- [ ] EditSession + 編輯工具(選取/切軸/切部位/微調/步進 + actionbar 讀數)。
- [ ] 控制面板 GUI + 裝備 + 旗標子選單。
- [ ] 建立/選取/刪除盔甲座 + Display 元件;完整姿勢/變換編輯。
- [ ] 存/讀/清單/分享(檔)。
- [ ] 匯出 summon 指令。
- [ ] 指令 + 權限 + messages.yml/config.yml。
- [ ] 雙視角測試教學寫入 `docs/TESTING.md`;L4 玩家端驗收。

**已實作**:**P2** 粒子發射器(marker 實體 + 預算限流 ticker)、**P3** 關鍵影格時間軸(`Animation`/`Track`/`Keyframe` + `AnimationPlayer` 即時播放 + `McFunctionExporter` datapack 匯出)。
**已實作(P4 + 易用/效能一輪)**:
- **分享碼/匯入**:`store/ShareCode`(`AASE1:` + Base64url(gzip(JSON)),decode 有長度/解壓上限防護)+ `/aase share`(可點擊複製)/`/aase import <碼> [名稱]`(重設 owner+新 id,匯入受每人上限守門)。
- **對外事件 API**:`api/AaseSceneSaveEvent`(通知)、`api/AaseScenePlaceEvent`(可取消,load/import 前擲)—— 讓別的插件掛我們,零反向依賴。
- **裝備 GUI**:`menu/EquipmentMenu`,手持物品點格子=裝上、空手點=卸下,**只複製游標物品、全程 cancel,不會消耗/複製玩家物品**;控制面板裝備鍵改開此選單。
- **`/aase info`**:場景資訊(元件/發射器/動畫/選取/存檔狀態)。
- **效能**:`ParticleService` 改為**每個 marker 只在生成/索引時解一次 PDC 字串 + 預解析 `Particle` 列舉並快取**,每 tick 迴圈零解析(只判 rate 與玩家距離);markers 空時整個 ticker 直接早退。
- **權限姿態(2026-07-08 追加拍板)**:凡是**寫入伺服器檔案或改動全服共用資料**的動作預設不給一般玩家 —— `aase.export.command`(匯出寫 `plugins/`)、`aase.preset.save`(`/aase pose save` 改寫共用 `presets.yml`)改 `default: op`;**GUI 邊界同步強制權限**(控制面板匯出鍵會查權限、無權限則隱藏,避免點按繞過指令權限)。
**待做**:粒子/動畫的**視覺化編輯面板 / 時間軸 GUI**(目前走指令 + 範本庫;結構已足夠,屬體驗加值)。

## 12. 純邏輯單元測試點

`PoseOps`(角度加減/正規化/度⇄弧度)、`TransformOps`(平移/縮放/四元數旋轉合成)、`SceneCodec`(序列化 round-trip)、`SummonExporter`(輸出字串快照)。這些不需伺服器環境。
