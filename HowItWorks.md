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

This creates the Magisk module and stores the original bootanimation.zip as a backup and creates a link between the Magisk module and the `bootanimation.zip` file used by the system.

You can then change the boot animation file in the app.
