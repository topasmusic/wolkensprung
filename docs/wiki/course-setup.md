# Course Setup

This is the standard setup for a parkour course with a start island, a fall reset, and a case on the finish island.

## 1. Create the course

```mcfunction
/wolkensprung course create course1
```

## 2. Place and bind the NPC

```mcfunction
/summon wolkensprung:wolkensprung_npc ~ ~ ~
/wolkensprung npc course course1
/wolkensprung npc config 0
```

Use `npc config <radius>` if you want the NPC to patrol.

## 3. Set the course reset

Stand at the course start:

```mcfunction
/wolkensprung course course1 respawn set
/wolkensprung course course1 respawn height 100
```

If the player falls below `Y=100`, they are teleported back to the respawn point for that course.

## 4. Add finish case positions

Stand on the valid base block under the finish case location:

```mcfunction
/wolkensprung course course1 area sethere
```

Add more possible finish points if needed:

```mcfunction
/wolkensprung course course1 area add
```

## 5. Optional checkpoints

```mcfunction
/wolkensprung course course1 checkpoint wand
```

Then click checkpoint blocks with a stick.

If you do not want mid-course checkpoints, do not set any.

## 6. Configure rewards

Example:

```mcfunction
/wolkensprung course course1 reward add 60 minecraft:diamond 64
/wolkensprung course course1 reward add 90 minecraft:diamond 20
/wolkensprung course course1 reward fallback minecraft:diamond 5
```

## 7. Test the run

- Right-click the NPC
- Accept the quest
- Fall below the configured height once
- Confirm the respawn works
- Finish the course and open the case
- Confirm the reward matches the configured tier

The finish case now emits a small beam that is only visible to the player currently running that quest.
