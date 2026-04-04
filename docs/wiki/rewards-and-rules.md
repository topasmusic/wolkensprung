# Rewards And Rules

## Item rewards

Item rewards can use any valid item id.

Examples:

```mcfunction
/wolkensprung course course1 reward add 60 minecraft:diamond 64
/wolkensprung course course1 reward add 90 minecraft:emerald 12
/wolkensprung course course1 reward fallback minecraft:gold_ingot 4
```

The first matching time tier is used. If no tier matches, the fallback reward is used.

## Command rewards

Command rewards run server commands when the player finishes a course.

Examples:

```mcfunction
/wolkensprung course course1 reward addcommand 60 give %player% minecraft:nether_star 1
/wolkensprung course course1 reward addcommand 90 title %player% actionbar {"text":"Silver time!","color":"yellow"}
/wolkensprung course course1 reward fallbackcommand xp add %player% 5 levels
```

Item rewards and command rewards can be combined on the same course.

## Placeholders

Reward commands support these placeholders:

- `%player%`
- `%uuid%`
- `%course%`
- `%seconds%`

## Reward rules

```mcfunction
/wolkensprung course course1 reward onlynewbest true
/wolkensprung course course1 reward oncepertier true
/wolkensprung course course1 reward dailylimit 3
```

- `onlynewbest true`: rewards are paid only when the player beats their personal best
- `oncepertier true`: each reward tier can only be claimed once per player
- `dailylimit <count>`: limits paid rewards per player and course each day
- `dailylimit 0`: disables the daily limit

## Notes

- Personal bests are tracked per player and per course.
- Rewards are resolved per course.
- Command rewards run with server authority, so only trusted admins should configure them.
