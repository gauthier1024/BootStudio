# How it works

BootStudio modifies the Android boot animation by using a systemless approach. This avoids issues with read-only partitions (system, product, etc.) and keeps the device's original integrity.

## Module Implementation

The app manages a module located at `/data/adb/modules/BootStudio`. Instead of using a traditional filesystem overlay (which can be inconsistent across different Android versions for boot animations), it uses dynamic bind mounts. When the device boots, it executes the `service.sh` script within the module to mount the custom animation over the target system paths.

### Module Initialization

On the first run, the app sets up the module structure:

1. Creates the base directory at `/data/adb/modules/BootStudio`.
2. Identifies all potential boot animation paths (e.g., `/system/media/`, `/product/media/`, `/data/misc/bootanim/`).
3. Backs up the original factory `bootanimation.zip` files into an `original/` folder.
4. Generates `module.prop` to identify the module.
5. Generates `service.sh`, which contains the logic to apply `mount --bind` for each detected path.
6. Creates `action.sh` to allow users to open the app directly from the Magisk/KernelSU manager.

### Filesystem Structure

```text
BootStudio
├── auto_mount
├── module.prop
├── service.sh      <-- Applies bind mounts at boot
├── action.sh       <-- App shortcut
├── bootanimation.zip <-- The active custom animation
├── original/       <-- Backup of original system files
└── disable         <-- Control file to disable the module
```

## Boot Animation Parsing

Boot animations are uncompressed `.zip` files containing a `desc.txt` file and folders of images.

The `desc.txt` file follows this format:
- **Line 1:** [Width] [Height] [FPS]
- **Lines 2+:** [Type] [Loop Count] [Pause] [Folder Name]

BootStudio parses this file to determine how to render previews and how to package animations correctly during the creation process.
