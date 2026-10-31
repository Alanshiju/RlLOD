# RlLOD — Client-Side LOD Mod for Minecraft 1.12.2 (RLCraft)

RlLOD is a lightweight, client-side Level of Detail (LOD) mod built for **Minecraft 1.12.2 (Forge)**, designed specifically to work alongside **RLCraft 2.9.3** and **OptiFine**.

It captures and caches terrain and structures beyond the standard render distance and renders them as simplified distant meshes, allowing you to see distant biomes, mountains, villages, and RLCraft landmarks (such as BattleTowers and floating structures) without keeping distant chunks loaded on the server tick loop.

---

> [!NOTE]
> ### Educational Purpose Disclaimer
> This project is developed strictly for **educational and personal learning purposes** as part of an independent undergraduate computer science study. It is a non-commercial, open-source personal project and is not intended to infringe on any existing intellectual properties.

---

## Features

- **Client-Side Only:** Runs entirely on the client with zero server-side tick overhead.
- **Multi-Tier LOD Rendering:** Renders high-detail blocks near the loaded chunk boundary (`LOD 0`) and progressively simplifies distant terrain (`LOD 1`, `LOD 2`, `LOD 3`) up to a configurable radius (32 to 256 chunks).
- **OptiFine & Shader Compatibility:** Works with OptiFine (`HD_U_G5`) and shader packs such as *MakeUp-UltraFast* (including extended shader fog distance and water pass support), as well as a built-in fallback GLSL shader when external shader packs are disabled.
- **RLCraft Structure & Biome Support:** Samples block textures and biome colors from modded blocks (such as *Defiled Lands*, *Traverse*, and *Dynamic Trees*) and preserves tall landmarks like BattleTowers and floating islands in the distance.
- **ChunkAnimator Integration:** Optional smooth rising animation for newly rendered LOD regions to match RLCraft's visual style.
- **Disk Cache & Background Surface Scanning:** Saves visited chunk snapshots compressed on disk (`rllod_cache/`) so distant terrain loads quickly when rejoining a world, and scans nearby generated terrain in singleplayer.
- **In-Game Settings Menu:** Accessible directly from `Options -> RlLOD Settings...` or `Video Settings -> RlLOD Settings...`, with live F3 debug screen telemetry.

---

## Tech Stack

- **Minecraft Version:** 1.12.2
- **Mod Loader:** Minecraft Forge (`14.23.5.2860+`)
- **Mappings:** MCP `stable_39`
- **Build System:** Gradle 8.8 + RetroFuturaGradle (`1.4.2`)
- **Java Target:** Java 8 (compiled and tested against Zulu JDK 8 & OpenGL 2.0/4.6)

---

## Installation (Using Pre-Built Release)

1. Download the latest `rllod-1.12.2-*.jar` from the **Releases** page.
2. Place the `.jar` file into your Minecraft / RLCraft `mods/` folder (e.g., `.minecraft/mods/`).
3. Launch the game. You can adjust the LOD render distance, quality preset, and shader mode in-game via `Esc -> Options -> RlLOD Settings...`.

---

## Building from Source

1. Clone the repository:
   ```bash
   git clone https://github.com/Alanshiju/RlLOD.git
   cd RlLOD
   ```
2. Ensure JDK 8 (for target compilation) and JDK 17/21 (for the Gradle 8.8 daemon) are configured in `gradle.properties`.
3. Build the reobfuscated mod JAR:
   ```powershell
   .\gradlew.bat build
   ```
4. The compiled mod JAR will be output to `build/libs/`.

---

## Known Issues / Limitations

- Minor height/seam overlap can occasionally appear at the immediate boundary between loaded vanilla chunks and the first LOD ring when flying.
- Heavily forested distant mountains at coarse LOD tiers may appear predominantly green due to tree canopy color averaging.

---

## Acknowledgments & Open-Source Attribution

This project is built upon the collective research and innovations of the broader voxel graphics and Minecraft modding communities:

- **[Distant Horizons](https://gitlab.com/distant-horizons/distant-horizons)** — Major credit and sincere gratitude go to the creators and contributors of the **Distant Horizons** project for pioneering and popularizing the concept and implementation of Level of Detail (LOD) rendering pipelines in Minecraft. Their groundbreaking work demonstrated what is possible with distant terrain visualization and remains a primary inspiration in this domain.
- **Open-Source Modding Community** — This project draws significant architectural inspiration from open-source graphics, rendering techniques, and community-driven patterns established within the Minecraft modding ecosystem.

---

## License

This project is licensed under the [MIT License](LICENSE).
