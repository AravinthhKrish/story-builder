# 🎬 Local Story-to-Animation API Pipeline

This document explains how text transforms into an animation video using a local microservice.

## 🧭 System Architecture Diagram

```
[ 📄 Written Text Input ]
│
▼
+---------------------------+
| 1. STORY BREAKDOWN        |
| - Splits text into scenes |
| - Measures word counts    |
+-------------┬-------------+
│
▼
+---------------------------+
| 2. ASSET GENERATION       |
| 🔊 TTS -> Audio (.mp3)    |
| 🎨 Code -> Canvas Layer   |
+-------------┬-------------+
│
▼
+---------------------------+
| 3. TIMELINE LAYOUT & MATH |
| - Sets grid coordinates   |
| - Applies motion formulas |
+-------------┬-------------+
│
▼
+---------------------------+
| 4. VIDEO RENDERING        |
| - Snaps frame snapshots   |
| - Binds audio tracks      |
+-------------┬-------------+
│
▼
[ 🎥 Finished .mp4 Video ]
```

---

## 🔍 Core Pipeline Stages

### 1️⃣ Story Breakdown
The engine analyzes your input block and isolates segments into discrete blocks.
* **Text Parsing:** Sentences or paragraphs are categorized as independent "scenes".
* **Duration Assessment:** The pipeline calculates reading speed to allocate precise scene time slots.

### 2️⃣ Asset Generation
The service creates the raw background assets for the production timeline.
* **Audio Layer:** Text-to-Speech (TTS) engines render synthetic speech narration files.
* **Visual Objects:** Vector spaces generate target texts, resolution grids, and backdrop colors.

### 3️⃣ Timeline Layout & Motion Math
Instead of physical drawings, moving elements use simple mathematical algorithms evaluated over runtime variables.

| Frame Component | Property Mapping | Execution Logic |
| :--- | :--- | :--- |
| **Grid Boundary** | Resolution Width / Height | Fixed at `1920x1080` Canvas Pixels |
| **Animation Function** | Position Tracking Over Time | `Vertical Position = 500 - (Current Time * 15)` |

> 💡 **How it moves:** As the scene timer increases, the text height coordinate decreases. Recalculating this position 24 times a second produces a clean scrolling motion!

### 4️⃣ Video Rendering
The system invokes processing dependencies (such as **FFmpeg**) to merge assets.
* **Frame Assembly:** The system merges vector positions and static blocks into image buffers.
* **Multiplexing:** Video images and matching sound files are packed together.
* **Encoding:** Output data builds an `H.264` codec compressed stream wrapper packaged as an ordinary `.mp4` file.
