# `.arscript` 脚本格式规范 v1.0

`.arscript` 是 UTF-8 编码的 JSON 文档。文件名的扩展名固定为 `.arscript`，
MIME 类型使用 `application/json`。

```json
{
  "version": "1.0",
  "info": { ... },
  "execution": { ... },
  "flow": [ ... ]
}
```

---

## 1. 根对象

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `version` | String | ✔ | 格式版本，当前仅支持 `"1.0"` |
| `info` | Object | ✔ | 元数据，见 §2 |
| `execution` | Object | ✔ | 执行模式，见 §3 |
| `flow` | Array | ✔ | 动作序列，见 §4；不允许为空（否则校验失败） |

未知字段会被**忽略**，以便新版本生成的脚本在旧版本上仍可加载。

---

## 2. `info` 元数据

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `name` | String | `""` | 脚本名称；为空时以“未命名脚本 + 时间戳”补齐 |
| `description` | String | `""` | 描述；录制生成时为「录制于 yyyy-MM-dd」 |
| `device` | Object | `{0,0,1.0}` | 录制设备信息 |
| `createdAt` | String | `""` | ISO-8601 UTC 时间戳，例如 `2026-01-15T10:30:00Z` |
| `coordinateSpace` | String | `"absolute"` | `"absolute"` 或 `"normalized"` |
| `tags` | Array\<String\> | `[]` | 预留标签，可用于列表搜索 |

### `info.device`

| 字段 | 类型 | 说明 |
|------|------|------|
| `width` | Int | 屏幕宽度（像素） |
| `height` | Int | 屏幕高度（像素） |
| `density` | Float | 屏幕密度（如 `2.75`） |

---

## 3. `execution` 执行模式

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `mode` | String | `"once"` | `"once"` 单次执行；`"repeat"` 重复执行 |
| `repeatCount` | Int | `1` | 重复次数，仅 `mode="repeat"` 时有效；**`0` 表示无限循环** |
| `intervalMs` | Long | `0` | 每轮完整执行之间的间隔（毫秒） |
| `failureStrategy` | String | `"abort"` | `"skip"` 跳过失败动作 / `"abort"` 终止脚本 |
| `reconnectTimeoutMs` | Long | `15000` | 无障碍服务断开后的最大重连等待（毫秒） |
| `restoreDelayMs` | Long | `0` | 每轮开始前的额外稳定等待（毫秒） |

`mode="once"` 时 `repeatCount` 被忽略（并产生一条校验警告）。

---

## 4. `flow` 动作

所有动作共享两个字段：

| 字段 | 类型 | 说明 |
|------|------|------|
| `type` | String | 多态判别字段 |
| `delay` | Long | **动作完成后**的等待毫秒数，默认随类型不同而不同 |

坐标字段的含义取决于 `info.coordinateSpace`：
绝对坐标直接使用像素值；百分比坐标使用 `0.0 ~ 1.0`，
执行时由 `CoordinateResolver` 按当前设备分辨率换算为像素。

### 4.1 `tap` 点击

```json
{ "type": "tap", "x": 540, "y": 1200, "duration": 50, "delay": 500 }
```

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `x` / `y` | Float | — | 触点坐标 |
| `duration` | Long | `50` | 按压时长（毫秒），必须 > 0 |

### 4.2 `longPress` 长按

```json
{ "type": "longPress", "x": 540, "y": 1200, "duration": 1500, "delay": 300 }
```

| 字段 | 类型 | 默认 |
|------|------|------|
| `x` / `y` | Float | — |
| `duration` | Long | `800` |

### 4.3 `swipe` 滑动

```json
{ "type": "swipe", "fromX": 540, "fromY": 1800, "toX": 540, "toY": 600, "duration": 300, "delay": 200 }
```

| 字段 | 类型 | 默认 |
|------|------|------|
| `fromX` / `fromY` | Float | — |
| `toX` / `toY` | Float | — |
| `duration` | Long | `300` |

起点与终点相同时产生校验警告（`degenerate_swipe`）。

### 4.4 `multiTouch` 多点触控

```json
{
  "type": "multiTouch",
  "points": [
    { "x": 400, "y": 1200, "startOffset": 0 },
    { "x": 680, "y": 1200, "startOffset": 0 }
  ],
  "duration": 300,
  "delay": 300
}
```

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `points` | Array | — | 至少 2 个触点（否则校验失败） |
| `points[].x` / `.y` | Float | — | 触点坐标 |
| `points[].startOffset` | Long | `0` | 该触点相对手势开始的延迟（毫秒） |
| `duration` | Long | `300` | 手势总时长 |

Android 的 `GestureDescription` 最多支持 10 条 stroke，超出部分会被执行器拒绝。

### 4.5 `gamepad` 手柄动作（可选功能）

```json
{ "type": "gamepad", "button": "A", "action": "press", "value": 1.0, "x": 0.0, "y": 0.0, "delay": 100 }
```

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `button` | String | `"A"` | `A` `B` `X` `Y` `LB` `RB` `LT` `RT` `BACK` `START` `GUIDE` `L3` `R3` `DPAD_UP` `DPAD_DOWN` `DPAD_LEFT` `DPAD_RIGHT` |
| `action` | String | `"press"` | `press` `release` `click` `stick` `trigger` |
| `value` | Float | `1.0` | 模拟量幅度，`0.0 ~ 1.0`（`stick` / `trigger` 使用） |
| `x` / `y` | Float | `0.0` | 摇杆坐标，`-1.0 ~ 1.0`（`stick` 使用） |

### 4.6 `delay` 等待

```json
{ "type": "delay", "duration": 1000, "delay": 0 }
```

| 字段 | 类型 | 默认 |
|------|------|------|
| `duration` | Long | `500`（必须 > 0） |

---

## 5. 校验规则

`ScriptValidator` 在导入、保存与执行前运行，错误会阻止导入/执行，警告仅提示。

### 错误（ERROR）

| 代码 | 触发条件 |
|------|---------|
| `unsupported_version` | `version` 不在支持列表 |
| `empty_flow` | `flow` 为空 |
| `non_positive_duration` | 动作时长 ≤ 0 |
| `negative_delay` | `delay` < 0 |
| `insufficient_pointers` | `multiTouch.points` 少于 2 个 |
| `invalid_analogue_value` | 手柄 `value` 不在 `0.0 ~ 1.0` |
| `invalid_stick_range` | 手柄 `x`/`y` 不在 `-1.0 ~ 1.0` |
| `normalised_out_of_range` | 百分比坐标不在 `0.0 ~ 1.0` |
| `negative_coordinate` | 绝对坐标为负 |
| `negative_interval` / `negative_repeat` / `negative_reconnect_timeout` | 执行参数为负 |

### 警告（WARNING）

| 代码 | 触发条件 |
|------|---------|
| `blank_name` | 名称为空 |
| `missing_device_info` | 未记录设备分辨率 |
| `coordinate_out_of_bounds` | 绝对坐标超出录制设备分辨率 |
| `degenerate_swipe` | 滑动起终点相同 |
| `excessive_duration` | 动作时长 > 2 分钟 |
| `excessive_delay` | 延迟 > 1 小时 |
| `repeat_count_ignored` | `mode="once"` 但 `repeatCount > 1` |

---

## 6. 读写示例（Kotlin）

```kotlin
val codec = ArScriptCodec()

// 解析（失败抛出 ScriptFormatException）
val script = codec.decode(text)

// 解析（不抛异常）
val scriptOrNull = codec.decodeOrNull(text)

// 序列化
val pretty = codec.encode(script, formatted = true)

// 校验
val result = ScriptValidator.validate(script, ScreenMetrics(1080, 2400, 2.75f))
if (!result.isValid) {
    println(result.summary())
    result.errors.forEach { println("第 ${it.stepIndex?.plus(1)} 项：${it.message}") }
}

// 坐标换算
val normalized = CoordinateResolver.normaliseScript(script, ScreenMetrics(1080, 2400, 2.75f))
```

## 7. 文件命名

* 仓储层每个脚本一个 `<id>.arscript` 文件，`id` 由脚本名安全化后生成，冲突时追加 `-2`、`-3`…
* 导出建议文件名由 `ArScriptConventions.fileNameFor(name)` 生成（仅保留字母、数字、`-`、`_`）。
