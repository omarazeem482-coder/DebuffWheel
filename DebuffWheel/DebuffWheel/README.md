# DebuffWheel (Paper 1.21.8, Java 21)

Build:   gradle build        (jar ends up in build/libs/)
Install: drop the jar into your server's plugins/ folder.

Commands (aliases /debuff, /dw)
  /dw spin               spin the wheel for yourself (once per day)
  /dw status             see your current debuff + time left
  /dw list               list wheel entries
  /dw spin <player|*>    [admin] force a spin for someone / everyone
  /dw clear <player|*>   [admin] remove debuff
  /dw reset <player|*>   [admin] allow another daily spin
  /dw reload             [admin] reload config.yml

Edit plugins/DebuffWheel/config.yml to change duration, effects and levels.
