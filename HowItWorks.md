# Magisk module

## First app launch

The app creates the Magisk module structure:

```sh
MODULE_PATH="/data/adb/modules/BootStudio"

mkdir -p "$MODULE_PATH/system/product/media"
mkdir -p "$MODULE_PATH/original"

if [ ! -f "$MODULE_PATH/original/bootanimation.zip" ]; then
    cp "$setupPath" "$MODULE_PATH/original/bootanimation.zip"
fi

printf "id=bootstudio\nname=BootStudio Bootanimation\nversion=1.0\nversionCode=1\nauthor=BootStudio\ndescription=Custom bootanimation overlay\n" > "$MODULE_PATH/module.prop"

touch "$MODULE_PATH/auto_mount"
touch "$MODULE_PATH/disable"
```

This code can change, check MagiskManager.kt.

This creates the Magisk module and stores the original bootanimation.zip as a backup and creates a link between the Magisk module and the `bootanimation.zip` file used by the system.
You can then change the boot animation file in the app.



## Magisk module structure

```text
BootStudio
├── 📄 auto_mount
├── 📄 module.prop
├── 📄 disable
├── 📂 original
│   ├── 📦 data_misc_bootanim_bootanimation.zip
│   └── 📦 product_media_bootanimation.zip
└── 📂 system
    ├── 📂 data
    │   └── 📂 misc
    │       └── 📂 bootanim
    │           └── 📦 bootanimation.zip
    └── 📂 product
        └── 📂 media
            └── 📦 bootanimation.zip
```

| Path | Description |
|------|-------------|
| 📄 `auto_mount` | Script executed by Magisk to automatically mount the module files at boot. |
| 📄 `module.prop` | Magisk module information file (name, version, author, description...). |
| 📄 `disable` | Toggle file used to disable the Magisk module without removing it. |
| 📦 `original/data_misc_bootanim_bootanimation.zip` | Stores the original boot animation from `/data/misc/bootanim/`. |
| 📦 `original/product_media_bootanimation.zip` | Stores the original boot animation from `/product/media/`. |
| 📦 `system/data/misc/bootanim/bootanimation.zip` | First bootanimation path used by Android. |
| 📦 `system/product/media/bootanimation.zip` | Secondary bootanimation (there is usually only one). |

All of the path above are specific to my rom and can be different on your device
