# How it works

BootStudio modifies the Android boot animation by using a systemless approach. This avoids issues with read-only partitions (system, product, etc.) and keeps the device's original integrity.

## Module Implementation

The app manages a Magisk module located at `/data/adb/modules/BootStudio`. This module acts as a filesystem overlay. When the device boots, Magisk mounts the files in this directory over the corresponding system files.

### Module Initialization

On the first run, the app sets up the module structure:

1. Creates the directory tree at `/data/adb/modules/BootStudio/system`.
2. Identifies the device's boot animation path (e.g., `/product/media/` or `/system/media/`).
3. Backs up the factory `bootanimation.zip` into an `original/` folder within the module.
4. Generates `module.prop` to identify the module to Magisk.
5. Creates `auto_mount` and `disable` control files.

### Filesystem Structure

```text
BootStudio
├── auto_mount
├── module.prop
├── disable
├── original
│   └── (Backup of original system files)
└── system
    ├── product
    │   └── media
    │       └── bootanimation.zip
    └── data/misc/bootanim
        └── bootanimation.zip
```

## Boot Animation Parsing

Boot animations are uncompressed `.zip` files containing a `desc.txt` file and folders of images.

The `desc.txt` file follows this format:
- **Line 1:** [Width] [Height] [FPS]
- **Lines 2+:** [Type] [Loop Count] [Pause] [Folder Name]

BootStudio parses this file to determine how to render previews and how to package animations correctly during the creation process.
