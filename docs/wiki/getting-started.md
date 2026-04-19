# Getting Started

## What Wolkensprung does

Wolkensprung is built for parkour and adventure-map style quests.

A player talks to an NPC, starts a run, reaches a spawned case on the target island or course end, and gets a reward based on the configured rules for that course.

## Basic flow

1. Create a course.
2. Bind an NPC to that course.
3. Set the respawn point and fall-height reset.
4. Add one or more chest spawn points.
5. Configure rewards.
6. Let players talk to the NPC and run the course.

## Version folders

- [`1.21.11`](../../1.21.11): Minecraft `1.21.11`, Java 21
- [`26.1.2`](../../26.1.2): Minecraft `26.1.2`, Java 25

Commands are the same in both versions.

## First test setup

```mcfunction
/wolkensprung course create course1
/summon wolkensprung:wolkensprung_npc ~ ~ ~
/wolkensprung npc course course1
/wolkensprung npc config 0
/wolkensprung course course1 respawn set
/wolkensprung course course1 respawn height 100
/wolkensprung course course1 area sethere
/wolkensprung course course1 reward fallback minecraft:diamond 1
```

After that, right-click the NPC, accept the quest, and finish the run by opening the spawned case.
