# 多主频检测方案

## 背景

当前声纹分析器仅检测单一主频。但实际音频信号通常包含多个频率成分（谐波、泛音、多个声源）。我们需要检测并显示所有显著的主频。

## 算法：四阶段流水线

所有逻辑放在 `FftProcessor.java` 中。算法综合使用三个判据，稳健地识别真实频谱峰值，同时排除噪声和旁瓣伪影。

### 第一阶段 + 第二阶段：局部极大值 + 相对阈值（单次扫描）

- 扫描 `magnitude[1..displayBins-1]`，寻找局部极大值：`mag[i] > mag[i-1] && mag[i] > mag[i+1]`
- **相对阈值**：仅保留幅度 >= 全局最大幅度的 10% 的峰值（即 -20 dB）
  - 为什么是 10%：汉明窗的第一旁瓣在 -42 dB（约 0.8%），因此 10% 的阈值可以干净地排除旁瓣伪影，同时保留中等强度的谐波

### 第三阶段：最小距离滤波（防旁瓣）

- 将候选峰值按幅度降序排列
- 贪心接受：仅当一个峰值与所有已接受峰值的距离均 >= **50 Hz** 时才接受
  - 汉明窗主瓣宽度约 4 个 bin。FFT=8192 时约 21.5 Hz；50 Hz 提供充足余量越过第一旁瓣
  - FFT=512 时，相邻 bin 已间隔 86 Hz，距离滤波自然满足

### 第四阶段：突出度滤波

- 对每个已接受的峰值，向左和向右扫描，直到遇到更高的 bin 或数组边界
- `突出度 = magnitude[peak] - max(左侧最小值, 右侧最小值)`
- 拒绝突出度 < **全局最大幅度的 5%** 的峰值
  - 自适应噪声基底；捕获中等谐波，拒绝泄漏波纹

**上限**：最多返回 8 个峰值，按幅度降序排列。

## 判定逻辑总结

一个频率 bin 被判定为"主频"需要同时满足：

| 判据 | 条件 | 目的 |
|------|------|------|
| 局部极大值 | `mag[i] > mag[i-1]` 且 `mag[i] > mag[i+1]` | 确认是峰值而非斜坡 |
| 相对阈值 | `>= 10% × 全局最大幅度` | 过滤噪声 |
| 最小间距 | 与其他已接受峰值间距 `>= 50 Hz` | 防止旁瓣误报 |
| 突出度 | `>= 5% × 全局最大幅度` | 确认峰值真正从背景中突出 |

## 数据结构

在 `FftProcessor` 中新增 `FftPeak` 内部类：

```java
FftPeak {
    int binIndex;           // 原始 bin 索引
    float interpolatedBin;  // 抛物线插值后的分数 bin 索引
    float frequencyHz;      // 最终频率（Hz）
    float magnitude;        // 幅度值
}
```

新增方法签名：

```java
public static List<FftPeak> findDominantPeaks(
    float[] magnitude,    // FFT 幅度谱
    float sampleRate,     // 采样率（44100）
    int maxBins,          // 可见频率范围对应的 bin 数
    int fftSize           // FFT 大小
)
```

## 文件变更

### 1. `FftProcessor.java`
- 添加导入：`java.util.List`、`java.util.ArrayList`、`java.util.Collections`
- 添加 `FftPeak` 静态内部类（实现 `Comparable`，按幅度降序排序）
- 添加 `findDominantPeaks()` 方法，实现四阶段流水线
- 对每个接受的峰值应用抛物线插值（复用已有的 `interpolatedPeakIndex()`）
- 不修改现有方法

### 2. `VoiceprintFragment.java`
- 添加导入：`java.util.List`
- **`recordingLoop()`**：将单峰值检测逻辑替换为：
  ```java
  List<FftProcessor.FftPeak> peaks = FftProcessor.findDominantPeaks(
      mag, SAMPLE_RATE, displayBins, size);
  ```
- **`updateChart()`**：修改签名，接受 `List<FftProcessor.FftPeak>` 而非 `float peakFreq`
- 显示格式：
  - 0 个峰值：`"Peak: -- Hz"`
  - 1 个峰值：`"Dominant: 440.0 Hz"`
  - 2+ 个峰值：`"Peaks: 440.0, 880.2, 1320.1 Hz"`

### 3. `fragment_voiceprint.xml` — 无需修改
现有的 `tvPeakFreq` TextView 可以自然换行显示多行文本。

## 性能分析

- 新增计算量：O(displayBins) 单次扫描 + O(k log k) 排序，其中 k 通常为 5-20
- 相比现有的 O(N log N) FFT 计算可忽略不计
- 线程安全：`findDominantPeaks` 是纯函数，每次调用返回新列表

## 验证方法

1. 使用 `./gradlew assembleDebug` 构建项目
2. 在设备上测试：
   - **静音** → 应显示 "Peak: -- Hz"
   - **单音**（如口哨） → "Dominant: X.X Hz"（1 个峰值）
   - **多音**（播放两个不同频率） → "Peaks: X.X, Y.Y Hz"（2+ 个峰值）
   - **切换 FFT 大小** → 所有大小下峰值应正确更新
   - **切换频率范围** → 仅显示可见范围内的峰值
