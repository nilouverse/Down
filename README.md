# DOWN
A Devils & Demons inspired ARPG. Pre-rendered sprites, flipbook combat, infinite tiled world.
Built 100% from a phone: code lives here, GitHub Actions compiles the APK.

## Controls
- Left side of screen: virtual joystick (glide movement)
- Bottom-right ATK button: plays the 8-frame slash flipbook

## Build
Actions tab -> Run workflow -> download APK artifact.

## Art pipeline
AI sheets on magenta chroma key -> upload to app/src/main/assets/sprites/
Engine strips magenta at runtime. idle.png = 2x2 grid, attack.png = 4x2 grid.
