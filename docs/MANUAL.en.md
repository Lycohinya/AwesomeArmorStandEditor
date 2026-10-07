# AwesomeArmorStandEditor — User Manual

> This is the exhaustive reference. In-game there's also a short paginated guide book (`/aase guide`); this document is the full version.
> Audience: everyone from "I have never posed anything" to "I want to build animations and export a datapack."
>
> 繁體中文版請見 [`MANUAL.md`](MANUAL.md)。

---

## Table of contents

1. [What this is](#1-what-this-is)
2. [Installation](#2-installation)
3. [Three core concepts](#3-three-core-concepts)
4. [5-minute quick start (no art skills needed)](#4-5-minute-quick-start-no-art-skills-needed)
5. [Full workflow](#5-full-workflow)
6. [The editing tool in detail](#6-the-editing-tool-in-detail)
7. [Control panel GUI](#7-control-panel-gui)
8. [Preset library: poses / effects / mirror / save-your-own](#8-preset-library)
9. [Armor stands: pose · equipment · flags](#9-armor-stands)
10. [Display entities: item / block / text](#10-display-entities)
11. [Particle effects](#11-particle-effects)
12. [Animation (keyframes) — key chapter](#12-animation-keyframes)
13. [Save · load · share · edit an existing build](#13-save-load-share-edit-an-existing-build)
14. [Export: commands / mcfunction datapack](#14-export)
15. [Command reference](#15-command-reference)
16. [Permission reference](#16-permission-reference)
17. [Configuration files](#17-configuration-files)
18. [Troubleshooting FAQ](#18-troubleshooting-faq)
19. [Performance & safety design](#19-performance--safety-design)
20. [Roadmap: towards a full animation tool](#20-roadmap)

---

## 1. What this is

A scene editor that lets survival and creative players freely customize armor stands and display entities. You can:

- Pose armor stands, dress them in equipment, toggle flags (invisible, no-baseplate, glowing…)
- Place display entities (item / block / text) with free scale, rotation, and tilt — degrees of freedom armor stands don't have
- Attach particle effects
- Animate with keyframes, with live preview playback
- Save, share, place multiple copies, and re-bind to an existing build to keep editing it
- Export as a `/summon` command or an mcfunction datapack

It is **standalone, open-source-friendly, and cross-platform (Spigot/Paper)** — no other plugin is required.

## 2. Installation

Drop `AwesomeArmorStandEditor-<version>.jar` into the server's `plugins/` folder and restart. On first boot it generates:

```
plugins/AwesomeArmorStandEditor/
  config.yml      behavior / performance settings
  lang/en.yml     every player-facing string (including the guide book pages)
  lang/zh_TW.yml  the same file in Traditional Chinese
  presets.yml     pose and effect presets (freely editable)
  scenes/         your saves (one folder per player)
  exports/        exported commands / datapacks
```

**Language** is server-wide, set by `language` in `config.yml`: `auto` (default — reads the server JVM's locale, so a Chinese locale gets `zh_TW` and everything else gets `en`), `zh_TW`, or `en`. Both language files are written out (so you can compare them, or edit one before switching to it); only the selected one is read. Edit it in place to customize.

After editing config, run `/aase reload` (no restart needed).

## 3. Three core concepts

| Concept | Meaning |
|---|---|
| **Scene** | One build = one save file. It holds multiple elements + particles + animation. |
| **Element** | A single node in a scene: one armor stand, or one display entity. Each has an index `#1 #2…`. |
| **Blueprint vs. world entity** | The save file is a "blueprint"; what you see in the world is one *placement* of that blueprint. Deleting the world entity does not delete the save; the same save can be placed in many locations, and **each placement is its own copy**: `edit` and `remove` only touch the copy you point at, never the other copies. |

![Scene, element and placement](assets/concepts.svg)

Three more rules worth remembering:

- **Ownership**: every element remembers who made it. You can only edit your own; others can't break or take your build.
- **It persists in the world**: a placed build is a real entity and stays there like any ordinary armor stand. To keep editing it, stand next to it and run `/aase edit` to re-bind — this does **not** create a duplicate.
- **Placed by mistake or don't want it? Recall it yourself**: `/aase remove` (§13) takes your placed build out of the world and keeps the save. Elements can't be broken, so don't try punching them away.

## 4. 5-minute quick start (no art skills needed)

![5-minute quick start](assets/quickstart-flow.svg)

```
/aase new My Build       ← start a new scene
/aase addstand           ← spawn an armor stand at your feet
right-click the stand    ← select it (you must select before editing)
/aase presets            ← open the graphical preset library
  → click "Wave / Cheer / Sit…" on the top row → the stand instantly poses
  → click "Mirror" for left-right symmetry
  → click "Flame Ring / Heart / Cherry Blossom" on the bottom row → particles added directly
/aase save                ← save
```

**You never see a single angle number.** This is the path built for people with zero posing sense.
Once you land on a pose you like, `/aase pose save MySignature` saves it as your own preset — `/aase pose MySignature` recalls it instantly afterwards.

## 5. Full workflow

1. `/aase new <name>` — start a scene (your current position becomes the build's origin).
2. `/aase tool` — get the editing tool.
3. Add elements: `/aase addstand` or `/aase adddisplay item|block|text`.
4. Select an element: **right-click** it with the tool.
5. Adjust: via presets (`/aase presets`), the tool, or the control panel (`/aase`).
6. (Optional) add particles: `/aase particle add <type>` or a preset effect.
7. (Optional) animate: see §12.
8. `/aase save`.
9. Later: `/aase load <name>` to place another copy, `/aase edit` to keep editing an existing build, `/aase export …` to export.
10. Don't want this one anymore: `/aase close` ends the session (it asks first if there are unsaved changes); recall whatever stays in the world with `/aase remove` (§13).

## 6. The editing tool in detail

`/aase tool` gives you a tool (a blaze rod by default, configurable). While a scene session is open, holding it:

| Action | Effect |
|---|---|
| **Right-click an element** | Select it |
| **Left-click** (air/block) | Current axis **−** one step |
| **Right-click** (air/block) | Current axis **+** one step |
| **Scroll wheel** | Cycle step size (e.g. 1° / 15° / 45°) |
| **Sneak + scroll** | Switch axis X / Y / Z |
| **Sneak + left-click** (air/block) | Switch mode (pose / move / translate / rotate / scale) |
| **Sneak + right-click** | Switch body part (head / body / left-right arm / left-right leg, armor stand pose only) |
| **Sneak + left-click one of your own armor stands** | Preview recalling that build (same as `/aase remove look`; you still click to confirm) |

![Edit tool controls](assets/tool-controls.svg)

**Recall gesture details**:

- Hit one of your own armor stands → "This will recall N element(s)" with `[Confirm recall]` and `[Cancel]`; if you are editing that same copy there is an extra `[Delete only element #id]`. Hit someone else's → it only tells you the owner and touches nothing.
- **Only regular (non-marker) armor stands can be hit.** Item / block / text displays and marker armor stands have no hitbox, so the tool can't click or hit them; use `/aase remove look`, `here`, `scene <name>`, or the control panel's "Recall builds" button.
- With no session open, right-clicking a build with the tool says "Bind this build with `/aase edit` first, or sneak + left-click to recall it", with an `[/aase edit]` button.
- Hitting a build bare-handed (or with anything else) shows an actionbar hint on how to recall it, or who owns it; at most once every 3 seconds.

The actionbar shows a live readout: `Stand#1 | Head | Axis Y | Step 15° | Y=+45°`.

> Note: while a scene is being edited, the scroll wheel is repurposed for step/axis switching. To scroll your hotbar normally again, run `/aase close` or drop the tool.

**Modes**:

- **POSE**: armor stands only. Rotate a single body part.
- **MOVE**: both element types. Translate the whole element along an axis.
- **TRANSLATE / ROTATE / SCALE**: displays only. Edit its internal transform.

## 7. Control panel GUI

`/aase` opens the control panel. If you don't want to memorize commands, almost everything is clickable here:

![Control panel layout](assets/gui-layout.svg)

- **Top row**: info, guide book (📖, top-right), add armor stand / item / block / text display
- **Mode row**: move / pose / translate / rotate / scale (the active one is marked)
- **Body part row**: head / body / left-right arm / left-right leg
- **Axis + nudge row**: X / Y / Z, step −/+, nudge −/+
- **Flags row**: small / invisible / no-baseplate / no-gravity / arms / marker / glowing, equipment shortcut
- **Build row**: state card, preset library, save, export, delete, **Recall builds** (hopper)
  - "Recall builds" lists your elements within 8 blocks and closes the window; nothing leaves the world until you click `[Confirm recall]` in chat. Saves are kept.
- **Bottom row**: guide book (📖) and close

## 8. Preset library

`/aase presets` (or the "Preset library" button in the panel's bottom row) opens the graphical preset library.

- **Top row = pose presets**: click to apply to the currently selected armor stand. Built-in: attention, T-pose, cheer, wave, point, think, sit, run.
- **Bottom row = effect presets**: click to add a tuned particle set to the selected element (or your position). Built-in: flame ring, heart, cherry blossom, stardust, soul fire.
- **Mirror button**: mirrors the left arm/leg angles onto the right side — a one-click fix for the thing non-artists get wrong most often.

**Command equivalents**: `/aase pose <id>`, `/aase fx <id>`, `/aase mirror`.

**Saving your own preset**: pose an armor stand → `/aase pose save <id> [name]`. This writes the current pose into `presets.yml`, and `/aase pose <id>` will recall it afterwards. You bring the aesthetics; the plugin remembers them.

> Built-in pose angles are conservative approximations and may not be perfect — either edit the numbers (in degrees) directly in `presets.yml` and `/aase reload`, or fine-tune in-game and overwrite with `pose save`.

## 9. Armor stands

**Pose**: six body parts (head / body / left arm / right arm / left leg / right leg), each with three axes (X tilt front-back, Y turn left-right, Z lean sideways), in degrees. Adjust via presets, the tool, or the panel.

**Equipment (menu, recommended)**: select an armor stand → `/aase equip` (or the panel's "Equipment" button) → opens the equipment menu. **Click an inventory item onto your cursor, then click the helmet/chestplate/leggings/boots/main-hand/off-hand slot** to equip it; **click a slot with an empty cursor to unequip**. This only ever copies the item on your cursor to the stand — **your items are never consumed or duplicated**.

**Equipment (command, legacy path)**: hold the item in your **off-hand**, run `/aase setequip <slot>`: `head / chest / legs / feet / mainhand / offhand`. Empty off-hand clears that slot.

**Flags**: `/aase flag <name>` or toggle from the panel's flags row:

| Flag | Effect |
|---|---|
| small | Mini armor stand |
| invisible | Invisible (only equipment is visible) |
| nobaseplate | Removes the baseplate |
| nogravity | No gravity (on by default while editing) |
| arms | Show arms (lets you pose them) |
| marker | Marker mode: no hitbox, tiny, commonly used as a decorative base |
| glowing | Glowing outline |

**Naming**: `/aase setname <MiniMessage>`, e.g. `/aase setname <red>Guardian`. Leave blank to clear it.

## 10. Display entities

Display entities are far more flexible than armor stands: arbitrary scale, arbitrary rotation, no hitbox. Three kinds:

- **Item display**: shows an item. `/aase adddisplay item` (uses your off-hand item by default, stone if empty); then `/aase setitem` (off-hand item) to change it.
- **Block display**: shows a block. `/aase adddisplay block` → `/aase setblock minecraft:oak_log`.
- **Text display**: floating text. `/aase adddisplay text` → `/aase settext <MiniMessage>`.

Editing the transform: select it, switch the tool to **translate / rotate / scale** mode, or use the control panel. Display rotation/scale is the smoothest carrier for animation (see §12).

## 11. Particle effects

`/aase particle add <particle type>` (tab-complete for a common list, e.g. FLAME / HEART / CHERRY_LEAVES / END_ROD / DUST / SOUL_FIRE_FLAME). The emitter is added at your current position (relative to the scene origin). `/aase particle clear` removes every emitter in the scene. The preset library's bottom row is a faster way to get a tuned effect.

**Performance design**: emitters are invisible marker entities that only fire when their chunk is loaded *and* a player is nearby, under a global per-tick processing cap (config `particles.budget-per-tick`). Placing a lot of them won't tank server TPS.

## 12. Animation (keyframes)

> This is the foundation this tool is designed to grow into a full animation tool from. Understand the concept before diving in.

### Concept

An animation is a timeline (length measured in **ticks**, 20 ticks = 1 second) with one **track** per element, and **keyframes** on each track. During playback, the plugin **interpolates** between keyframes automatically — you only need to pose a handful of key moments and everything in between animates itself.

### Building an animation (steps)

1. Place your scene's elements (`/aase addstand`, etc.) inside a session.
2. Select an element, pose its **starting** frame → `/aase anim key 0` (records a keyframe at tick 0).
3. Pose the same element into its **ending** frame → `/aase anim key 20` (records a keyframe at tick 20).
4. `/aase anim length 20` sets the animation's total length.
5. `/aase anim play` → the element smoothly loops between the two poses.
6. `/aase anim loop` toggles looping; `/aase anim stop` stops and reverts to the saved pose; `/aase anim clear` wipes the animation.

Multiple elements with their own `key` calls animate together. For finer control, record more keyframes (e.g. 0 / 10 / 20 / 30).

### How interpolation works

- Position / scale: linear interpolation.
- Display rotation: quaternion shortest-path interpolation (nlerp) — no unexpected spins.
- Pose angles: linear interpolation per axis.

### Performance & choosing a carrier (important)

- **Display entities** use client-side interpolation — near-zero server cost, the smoothest option. **Prefer displays for animation.**
- **Armor stands** have no client-side interpolation; the plugin re-poses them tick-by-tick during playback, which has a real cost — so armor stand animation playback **only runs during your editing session**, never persistently.
- For a build that "just keeps animating on its own in the world" → **export an mcfunction datapack** (§14), which drives the animation server-side without costing an editing session.

### Current limitations

- Animation editing is currently command-driven (`/aase anim …`) — there's no visual timeline GUI yet (see §20 roadmap).
- Live playback only runs inside an editing session; on logout or disconnect it stops automatically and the session ends (the build and any save are untouched).

## 13. Save, load, share, edit an existing build

- `/aase save` — saves to `scenes/<your-uuid>/<scene-id>.json`.
- `/aase list` — your list of scenes.
- `/aase load <name>` — places **a new copy** at your feet (you can place several). The first element is **auto-selected**, so `setequip`/`flag`/presets work right away. **Every placement is its own copy**: place one save twice and `edit` / `remove` on one never affects the other.
- `/aase edit` — stand next to an existing build to **re-bind** it as your session and keep editing (no duplicate is created). It binds only the **nearest copy**; other placed copies of the same save nearby are not pulled in. Note that it selects the element **nearest to you**; in a scene that mixes displays in, follow up with `/aase select` to pick the exact element.
  - **Orphans are skipped**: an element whose save is gone (or whose id the save no longer lists), and that no open session claims, is an "orphan"; `edit` never binds it. If only orphans are nearby you get a hint to recall them with `/aase remove look` (with a button).
  - **Builds placed by 1.1.0 or earlier** are grouped automatically the first time you `/aase edit` them, then behave like new ones. Nothing to do.
- `/aase select <element id|next|prev>` — **select a specific element** (ids show in tab-complete and `/aase info`; a leading `#` is fine). Displays have no hitbox for the tool to click, so this is the reliable way to move the selection in multi-element scenes; `next`/`prev` cycle through.
- `/aase close` — ends the editing session (the build stays in the world). **With unsaved changes it does not just close**; it asks first:
  - `[Save and close]` (= `/aase close save`): saves, then ends the session; the build stays in the world.
  - `[Discard and recall]` (= `/aase close discard`): does not save, and recalls **this copy's** entities and particles from the world (no extra confirmation); any existing save is untouched.
  - `[Keep editing]`: does nothing.
  - With no unsaved changes, `/aase close` just ends the session.
- Going offline (quit, disconnect) stops any playing animation and ends the session; **nothing is saved and nothing is removed**. The build stays in the world; use `/aase edit` to pick it up again.
- `/aase delete [element id]` — deletes the selected element, or the one you name (`/aase delete 3`). **World first**: the entity is removed, then the element leaves the build. If no entity can be found in the world (for example its chunk is not loaded), it says so plainly ("No entity for #3 found in the world"), still drops the element from the model, and gives you `[Save]` and `[Recall what you're looking at]` buttons. Remember to save afterwards.
- `/aase info` — current scene info (element count, armor stands/displays, emitters, animation, selection, save state).
- **Share (short code)**: `/aase share` uploads the build to AASE Studio and gives you a 7-character **short code**, with `[Click to copy /aase import <code>]` and `[Open in AASE Studio]` buttons. On any server, `/aase import <code> [new name]` places a copy at your feet (ownership becomes the importer's, with a new id; per-player cap and region checks apply). The code may also be the full link (`https://…/s/<code>`), any letter case. Codes are kept on the website for a while (30 days by default); share again after that.
  - Before uploading, the **owner, scene id and last anchor are stripped**, so the website never sees your UUID.
  - If the server turned uploads off (`share.upload: false`) or remote access off (`import.remote.enabled: false`), or the upload fails (no answer, too many uploads, build too big), you get **share code text** instead: `[Click to copy the share code text to your clipboard]` (`AASE1:...`). It is longer than chat's 256-character limit; paste it into AASE Studio or keep it in a file. `/aase import AASE1:…` still imports it.
  - If an item is **unknown to the importing server** (an item from another version, a modded item), that slot stays empty and chat lists which ones, e.g. `#2 main hand minecraft:foo_sword`. The rest is placed normally.
  - With remote import off, `/aase import <short code>` answers "This server doesn't allow remote imports"; share code text still works.
- **File sharing**: the JSON file is portable too — hand someone `scenes/…/xxx.json` and have them drop it into their own `scenes/<their-uuid>/` (remember to edit the `owner` field inside).

### Recalling your own placed work (`/aase remove`)

Placed it in the wrong spot, don't want it, regret a `new`? Elements can't be broken, so use these commands. Permission `aase.use`; they **only touch what you placed** (admins too; touching other people's builds goes through `/aase admin`).

| Command | What it recalls |
|---|---|
| `/aase remove look` | The build you are looking at; if you aren't aiming at one, your own nearest element within range (`tool.select-range`, default 6 blocks) |
| `/aase remove here [radius]` | All your elements within the radius around you. Default 8 blocks, capped by `admin.max-purge-radius` (default 64) |
| `/aase remove scene <name>` | By build name: every placed copy made from that name (the name may contain spaces) |
| `/aase remove confirm` / `cancel` | Confirm / cancel the last preview |

It is **two-stage**: the first three only **preview**, saying "This will recall N element(s) (M group(s))" (particle emitters are listed separately); click `[Confirm recall]` (or run `/aase remove confirm`) to actually do it. You have **30 seconds** to confirm; after that, preview again.

- **Saves are never deleted.** You can `/aase load` the build again any time.
- **Only entities in loaded chunks are affected** (the plugin never scans the world), and the message says so; walk over to load the chunk first if needed.
- If the copy you are currently editing is recalled, your editing session ends with it.
- Leftover elements that have no save to bind to (orphans) are cleaned up the same way; `/aase edit` hands you the button when it can't bind them.
- Every recall writes a LycoLib audit entry (silently skipped when LycoLib is absent).

## 14. Export

### Summon command

`/aase export command` — chat shows a **click-to-copy** button that copies the whole scene's `/summon` command to your clipboard; it's also saved as `exports/<scene>.txt`. Paste it in-game or into a command block to reproduce the build.

### mcfunction datapack

`/aase export function` — exports a datapack to `exports/<scene>/datapack/`:

```
pack.mcmeta
data/aase/function/summon.mcfunction     ← summons the whole scene (elements carry tags)
(if there's an animation, also:)
data/aase/function/load.mcfunction        ← initializes the timer
data/aase/function/tick.mcfunction        ← drives keyframe playback (self-scheduling every tick)
data/aase/function/frames/frame_*.mcfunction
```

Usage: drop the folder into the world's `datapacks/`, run `/reload`, then:

- Static: `/function aase:summon`
- Animated: `/function aase:load` → `/function aase:summon` → `/function aase:tick`

> **NBT has been tested against 26.2**: pose/flags/equipment/transform/item/block/brightness/glow are all correct; custom names and text use SNBT (`CustomName:"name"`, `text:"text"`, not the legacy JSON string). Still best-effort: `pack_format` and the `function` folder name may need adjusting per version; equipment only carries the item id, blocks only carry the block name, and names/text lose color. `SummonExporter` / `McFunctionExporter` are the single points to patch.

## 15. Command reference

| Command | Description | Permission |
|---|---|---|
| `/aase` | Open the control panel | aase.use |
| `/aase guide` | Open the paginated in-game book | aase.use |
| `/aase tool` | Get the editing tool | aase.use |
| `/aase new <name>` | New scene | aase.use |
| `/aase presets` | Preset library GUI | aase.use |
| `/aase pose <id>` / `pose save <id> [name]` | Apply / save a pose | aase.use |
| `/aase fx <id>` | Add an effect preset | aase.use |
| `/aase mirror` | Left-right mirror | aase.use |
| `/aase addstand` | Add an armor stand | aase.create.armorstand |
| `/aase adddisplay <item\|block\|text>` | Add a display | aase.create.display |
| `/aase equip` | Equipment menu (click slots to equip/unequip) | aase.use |
| `/aase setblock/settext/setitem/setname/setequip/flag …` | Edit content | aase.use |
| `/aase particle add <type>` / `clear` | Particles | aase.use |
| `/aase anim key/length/loop/play/stop/clear` | Animation | aase.animate |
| `/aase save` / `load <name>` / `list` / `info` / `edit` | Save/load/list/info/edit | aase.use / aase.scene.save |
| `/aase delete [element id]` | Delete the selected (or the named) element; world first | aase.use |
| `/aase remove look` / `here [radius]` / `scene <name>` | Preview recalling your own placed work (yours only) | aase.use |
| `/aase remove confirm` / `cancel` | Confirm / cancel a recall within 30 seconds | aase.use |
| `/aase share` / `import <code\|link\|AASE1:…> [name]` | Upload for a short code (or share code text) / import | aase.scene.share |
| `/aase pose save <id> [name]` | Save into the shared preset library | **aase.preset.save (default: OP)** |
| `/aase export command` / `export function` | Export (writes server files) | **aase.export.command (default: OP)** |
| `/aase close [save\|discard]` | End the session; asks first if there are unsaved changes. `save` saves then closes, `discard` doesn't save and recalls this copy | aase.use (`close save` also needs aase.scene.save) |
| `/aase admin whois` | Show who placed the nearest element, which placement it belongs to, and whether it is an orphan | **aase.admin (default: OP)** |
| `/aase admin remove` | Remove the nearest element | **aase.admin (default: OP)** |
| `/aase admin purge <radius> [player]` | Preview elements to remove within the radius | **aase.admin (default: OP)** |
| `/aase admin confirm` | Execute the previewed purge | **aase.admin (default: OP)** |
| `/aase reload` | Reload configuration | aase.admin |

> Why `export` / `pose save` are OP-only by default: both **write to server files or mutate the server-wide shared `presets.yml`**, which isn't appropriate to hand to every player (it invites file/preset-library spam). The control panel's export button is **hidden entirely** for players without the permission — clicking it does nothing. Grant `aase.export.command` / `aase.preset.save` to a builder group with LuckPerms to allow it.
>
> `/aase admin …` is OP-only because it acts on **other players' builds**. See the moderation section below for its deliberate boundaries.

## 16. Permission reference

```
aase.use                 open the editor / use the tool (default: everyone)
aase.create.armorstand   place armor stands (default: everyone)
aase.create.display      place displays (default: everyone)
aase.scene.save          save (own save folder, default: everyone)
aase.scene.share         generate / import share codes (never writes files, default: everyone)
aase.animate             animation (default: everyone)
aase.clear               /aase clear <radius> — remove others' elements where you may build (default: everyone)
aase.export.command      export commands / datapacks (writes server files) — default: OP
aase.preset.save         /aase pose save writes the server-wide presets.yml — default: OP
aase.admin               admin (edit others' builds, /aase admin whois|remove|purge, reload) — default: OP
aase.bypass.region       bypass region checks — default: OP
aase.bypass.limit        bypass element caps — default: OP
```

### Moderation: when someone dumps builds where they shouldn't (`/aase admin`)

1. `/aase admin whois` — stand next to it and find out **who placed it**, which scene it belongs to, and its coordinates. A second line shows the **placement** (first 8 characters of the placement id; builds placed by 1.1.0 or earlier say "legacy, not grouped yet"), and an element with no save to bind to is marked **(orphan: no save to bind to)**.
2. `/aase admin remove` — remove the nearest element (no editing session required).
3. To clear an area: `/aase admin purge <radius> [player]` only **previews** how many elements would go; `/aase admin confirm` (within 60 seconds) actually does it.

Deliberate boundaries — do not mistake this for a general entity remover:

- **Only touches elements this plugin placed.** Hand-placed vanilla armor stands and entities from other plugins are never removed; `whois` says so explicitly.
- **Only reaches loaded chunks.** The plugin never scans the world (hard performance rule), so a purge cannot reach art in chunks nobody has loaded.
- **Never deletes the owner's saved scene.** A purge removes placed entities only; the owner can re-place their build somewhere sensible. Least destructive fix that solves the problem.
- **Radius purge is two-stage**, and the radius is clamped by `admin.max-purge-radius` in `config.yml` (default `64`).
- Every `remove` / `purge` writes a LycoLib audit entry (silently skipped when LycoLib is absent).

## 17. Configuration files

- **config.yml**: `language` (`auto` / `zh_TW` / `en`), tool material, step sizes, per-player/per-chunk/global element caps, region-event-probe toggle, particle budget and range, `admin.max-purge-radius` (default 64; also the cap for players' `/aase remove here` radius).
  - `store.write-schema`: save format, `3` (default; degrees and item ids, editable in AASE Studio) or `2` (legacy, for servers still on 1.2.x or older). The plugin reads both.
  - `import.remote.*`: remote import. `enabled` (off = no network at all), `base-url` (https only; plain http only for localhost), `timeout-seconds`, `max-bytes` (default 1 MiB), `cooldown-seconds` (per player), `max-concurrent` (server-wide).
  - `share.upload`: whether `/aase share` uploads for a short code. The plugin only makes outbound connections and never opens a listening port.
- **presets.yml**: pose and effect presets (angles in degrees, freely editable; `/aase pose save` writes here). A preset's **display name** comes from `preset.name.<id>` in the lang file; presets you save yourself have no such key and show the `name` from presets.yml.
- **lang/zh_TW.yml, lang/en.yml**: every player-facing string (MiniMessage; command names use `<aqua>`, click hints use `<yellow>`), plus `guide.pages` — the in-game paginated book (parchment background, so use dark colors). To add a language, copy a file, rename it, and point `language` at it.

All of the above take effect with `/aase reload`, no restart needed.

> Upgrading from 0.x: the old `messages.yml` / `guide.yml` are no longer read (the server logs a reminder on boot). Move any edits into `lang/<code>.yml`, then delete them.

## 18. Troubleshooting FAQ

**Q: The tool's left/right click does nothing?** Start a session with `/aase new` or `/aase edit` first, then right-click an element with the tool to select it.

**Q: The scroll wheel won't switch hotbar slots?** While editing, the scroll wheel is repurposed for step/axis. Run `/aase close` to end the session.

**Q: Can't place anything, "this area is protected"?** You're in someone else's claim. Move to your own land, or ask an admin to grant `aase.bypass.region`.

**Q: Someone left a build in my claim and I can't break it?** Elements are deliberately indestructible, so a stray arrow, creeper, or punch can't ruin an hour of posing. Stand there and run `/aase clear <radius>` — you have build rights inside your claim, so it clears; outside it, other people's elements stay put. Their saved scene is untouched, so they can re-place it elsewhere.

**⚠ Admins: don't use `/kill` on builds.** What `/kill` does to this plugin's elements depends on the server version: on 26.2 it went straight through the protection (`/kill @e[type=armor_stand]` wipes every build, hand-placed vanilla stands included); on 26.3 the protection blocked it and the element stayed. Either way a killed element drops none of its equipment (display items may come from someone else's share). Use `/aase clear` or `/aase admin remove|purge` — they only ever touch entities this plugin tagged.

**Q: `load` after editing creates two copies?** `load` always places **a new copy**. To keep editing an existing build, use `/aase edit`. Recall the one you don't want with `/aase remove look`.

**Q: I regret a `/aase new` and something is left in the world?** Run `/aase close` first and pick `[Discard and recall]` at the "unsaved changes" prompt; that copy leaves the world (a brand-new scene has no save yet; an existing save is untouched). If you already closed the session, stand next to it and use `/aase remove look` (or `here`).

**Q: `/aase edit` says "no save to bind to" or "only elements without a save are nearby"?** Those are orphans (the save is gone, or the save no longer lists their id). Follow the hint and recall them with `/aase remove look`.

**Q: The tool's sneak + left-click can't hit my display / marker armor stand?** Those two have no hitbox, so the gesture can't reach them. Use `/aase remove look`, `here [radius]`, `scene <name>`, or the control panel's "Recall builds".

**Q: `/aase delete` says no entity was found in the world?** Usually the element's chunk isn't loaded. The element is already gone from the model (remember to save); if you later see a leftover on site, recall it with `/aase remove look`.

**Q: A scene has several armor stands and the second one won't take equipment?** `setequip` acts on the **currently selected** element, and `/aase edit` binds the entity **nearest to you** — when the scene mixes in displays (block/text/item), you think you selected the stand but actually got a display next to it, so you keep seeing "only armor stands wear equipment." Use `/aase select <id>` to pick that stand directly (tab-complete lists the ids), then `setequip`.

**Q: The animation stops instead of running forever?** Live playback only runs during an editing session. For a persistent animation, `export function` and drive it with the datapack.

**Q: A preset pose looks off?** Built-in angles are approximations. Edit `presets.yml`, or pose it yourself and overwrite with `pose save`.

**Q: The exported command looks wrong in-game?** NBT export is best-effort and version-sensitive. Report it so `SummonExporter`/`McFunctionExporter` can be fixed.

## 19. Performance & safety design

- **No world/chunk scanning**: element counts are tracked in memory; orphaned entities are only handled when their chunk loads.
- **Particles are budgeted**: only fire near a player in a loaded chunk, under a server-wide per-tick cap.
- **Animation is throttled**: displays use client-side interpolation; armor stand tick-by-tick updates only run inside an editing session and stop automatically when you log off.
- **Anti-grief**: elements carry an ownership PDC tag — others can't break or take them; caps prevent spam; region-protection plugins are respected.
- **Cross-platform**: only Bukkit/Spigot API surface is used; text goes through shaded-in Adventure, so it works fine on Spigot too.

## 20. Roadmap

Next steps towards a "full animation tool" (not yet built):

- **Visual timeline GUI**: drag keyframes, scrub a preview, replacing the current `/aase anim` commands.
- **In-between pose helper**: pick two saved poses, generate the animation between them with one click.
- **Easing**: ease-in/out beyond linear, for more natural motion.
- **More external API / events**: let other plugins hook into our save/place events.
- **Drag-and-drop equipment GUI**, a visual editor for particles/animation.

Let the author know if you have a preferred priority order.
