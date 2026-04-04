# Commands And Admin

All commands start with `/wolkensprung`.

## Player quest commands

```mcfunction
/wolkensprung quest accept
/wolkensprung quest decline
/wolkensprung quest cancel
```

These are usually triggered from the NPC dialog, but they can also be used directly.

## NPC commands

```mcfunction
/wolkensprung npc config
/wolkensprung npc config <radius>
/wolkensprung npc course <id>
/wolkensprung npc delete
/wolkensprung npc deleteid <uuid>
```

- `config 0` keeps the NPC stationary
- `config <radius>` lets the NPC patrol around its home point
- `course <id>` binds the nearest NPC to a specific course

## Course commands

```mcfunction
/wolkensprung course list
/wolkensprung course create <id>
/wolkensprung course <id> info
```

## Respawn commands

```mcfunction
/wolkensprung course <id> respawn set
/wolkensprung course <id> respawn set <x> <y> <z>
/wolkensprung course <id> respawn clear
/wolkensprung course <id> respawn height <y>
/wolkensprung course <id> respawn info
```

## Area commands

```mcfunction
/wolkensprung course <id> area wand
/wolkensprung course <id> area add
/wolkensprung course <id> area sethere
/wolkensprung course <id> area set <x> <y> <z>
/wolkensprung course <id> area list
/wolkensprung course <id> area remove <x> <y> <z>
/wolkensprung course <id> area removeall
```

Area points are valid chest spawn positions for that course.

## Checkpoint commands

```mcfunction
/wolkensprung course <id> checkpoint wand
/wolkensprung course <id> checkpoint set <x> <y> <z>
/wolkensprung course <id> checkpoint list
/wolkensprung course <id> checkpoint remove <x> <y> <z>
/wolkensprung course <id> checkpoint removeall
```

Checkpoints override the course respawn once the player reaches them.

## Reward commands

```mcfunction
/wolkensprung course <id> reward list
/wolkensprung course <id> reward add <seconds> <item> <count>
/wolkensprung course <id> reward clear
/wolkensprung course <id> reward fallback <item> <count>
/wolkensprung course <id> reward clearfallback
/wolkensprung course <id> reward addcommand <seconds> <command...>
/wolkensprung course <id> reward clearcommands
/wolkensprung course <id> reward fallbackcommand <command...>
/wolkensprung course <id> reward clearfallbackcommands
/wolkensprung course <id> reward onlynewbest <true|false>
/wolkensprung course <id> reward oncepertier <true|false>
/wolkensprung course <id> reward dailylimit <count>
```

See [Rewards and rules](./rewards-and-rules.md) for details.
