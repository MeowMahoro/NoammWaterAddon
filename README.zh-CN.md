[English](README.md) | **简体中文**

# NoammWaterAddon

[NoammAddons](https://github.com/Noamm9/NoammAddons) 的独立附属 mod，用于从水板的**当前状态**求解
**Water Board** 谜题，而不是依赖静态查表。

## 使用方法

1. 在 `Dungeon → Water Board Addon` 里给 **Recalculate Shortcut** 绑定一个按键。
2. 关闭总水阀，等棋盘排干。
3. 按下快捷键，按画面上的指向线和倒计时操作。

总开关之前的拉杆一出现就是 `CLICK`，时间线在你拨下总开关那一刻才开始。只有真的点了那根拉杆，该步骤才会
消失；晚拨不会破坏方案。

## 设置项

| 设置 | 默认 | 说明 |
|---|---|---|
| `Click Color` | 绿 | 指向下一根拉杆的 tracer 颜色 |
| `Next Click Color` | 黄 | 指向再下一根的连线颜色 |
| `Recalculate Solver` | 开 | 总开关 |
| `Recalculate Shortcut` | 未绑定 | 触发重算的按键 |
| `Debug: Record Recalc Trace` | 关 | 把方案记录到 `config/NoammWaterAddon/` |

方案生效期间会临时关闭 NoammAddons 自带的水板 solver，结束后按原值恢复。

## 许可证

MIT，见 [LICENSE](LICENSE)。

与 NoammAddons、Dungeons Guide 及 Hypixel 均无隶属关系。
