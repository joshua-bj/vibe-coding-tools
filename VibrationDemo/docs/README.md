# VibrationDemo

Android 实时振动与运动分析应用，使用设备传感器进行数据采集与处理。

## 功能模块

| 标签页 | 说明 |
|--------|------|
| Accel | 原始加速度计数据，支持多种滤波选项 |
| RMS | 1 秒窗口内的加速度均方根值（RMS） |
| Velocity | 通过积分计算速度，带漂移补偿 |
| Voiceprint | 基于 FFT 的音频频谱分析 |
| Elevator | 电梯速度与运行方向检测（仅 Z 轴） |

## 电梯模块（Elevator）

实时计算电梯运行速度及方向（上行/下行）。完整设计文档见 [elevator-velocity-with-direction.md](elevator-velocity-with-direction.md)。

### 信号处理链

```
TYPE_ACCELEROMETER（仅 Z 轴）
  → 高通滤波 1 Hz（去除重力 + 传感器零点漂移）
  → 积分 → 速度 (m/s)
  → 漂移滤波 HP 0.1 Hz（去除积分累积漂移）
  → 低通滤波 LP 5 Hz（平滑电梯振动，用于方向判断）
```

### 方向状态机

实际运动模式：静止 → 加速 → 短暂减速 → 匀速 → 减速 → 可能短暂加速 → 静止

- 方向一旦判定为上行或下行即**锁定**，运行过程中不会翻转
- 仅在速度持续低于阈值 0.5 秒（低速）或 1.0 秒（高速）后才判定为静止

### CSV 录制

点击 **Record** 开始录制，点击 **Stop** 停止并保存。CSV 文件保存路径：

```
/storage/emulated/0/Download/VibrationDemo/elevator_YYYYMMDD_HHmmss.csv
```

#### CSV 列说明

| 列名 | 单位 | 说明 |
|------|------|------|
| `time_ms` | ms | 距录制开始的经过时间 |
| `accel_z_mps2` | m/s² | Z 轴加速度（经高通滤波后） |
| `velocity_mm_s` | mm/s | 显示速度（经漂移滤波后） |
| `smooth_mm_s` | mm/s | 平滑速度（经低通滤波后，用于方向判断） |
| `direction` | — | UP（上行）/ DOWN（下行）/ STATIONARY（静止） |

文件采用 UTF-8 BOM 编码，兼容 Excel 直接打开。在 Excel 中将 `velocity_mm_s` 或 `smooth_mm_s` 对 `time_ms` 绘制折线图即可还原实时波形。
