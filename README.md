# Wolkensprung

Wolkensprung is a Fabric quest mod for adventure maps and parkour courses.

It lets you place quest NPCs, define one or more courses, spawn a personal case/chest target for each runner, and reward players based on their finish time.

## Supported versions

- Minecraft `26.3` in [`26.3`](./26.3) with Java 25
- Minecraft `1.21.11` in [`1.21.11`](./1.21.11) with Java 21
- Minecraft `26.2` in [`26.2`](./26.2) with Java 25
- Minecraft `26.1.2` in [`26.1.2`](./26.1.2) with Java 25

## Features

- NPC-driven course quests with per-course bindings
- Per-course respawn points, fall-height resets, chest spawn points, and checkpoints
- Time-based item rewards and command rewards
- Optional reward rules for new personal best only, once per tier, and daily reward limits
- German and English in-game localization
- Custom Wolkensprung NPC skin

## Docs

- [Getting started](./docs/wiki/getting-started.md)
- [Commands and admin](./docs/wiki/commands-and-admin.md)
- [Course setup](./docs/wiki/course-setup.md)
- [Rewards and rules](./docs/wiki/rewards-and-rules.md)
- [Localization and content](./docs/wiki/localization-and-content.md)

## Build

`26.3`

From the `26.3` folder, with Java 25 configured:

```powershell
.\gradlew.bat build
```

`1.21.11`

```powershell
Set-Location 'C:\Users\me\Desktop\Topas Mods\MC MODS\Topas Mods\Wolkensprung\1.21.11'
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.9.10-hotspot'
.\gradlew.bat build
```

`26.2`

```powershell
Set-Location 'C:\Users\me\Desktop\Topas Mods\MC MODS\Topas Mods\Wolkensprung\26.2'
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot'
.\gradlew.bat build
```

`26.1.2`

```powershell
Set-Location 'C:\Users\me\Desktop\Topas Mods\MC MODS\Topas Mods\Wolkensprung\26.1.2'
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot'
.\gradlew.bat build
```
