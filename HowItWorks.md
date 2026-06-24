# How it works

BootStudio modifies the Android boot animation by using a systemless approach. This avoids issues with read-only partitions (system, product, etc.) and keeps the device's original integrity.

## Magisk Module Implementation

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

BootStudio targets multiple common paths to ensure compatibility across different ROMs and Android versions.

## Boot Animation Parsing

Boot animations are uncompressed `.zip` files containing a `desc.txt` file and folders of images.

The `desc.txt` file follows this format:
- **Line 1:** [Width] [Height] [FPS]
- **Lines 2+:** [Type] [Loop Count] [Pause] [Folder Name]

BootStudio parses this file to determine how to render previews and how to package animations correctly during the creation process.

## Preview Generation (FFmpeg)

To show animations in the app without consuming too much memory, BootStudio generates a downsampled GIF:

1. Frames are extracted from the zip.
2. The sequence is capped at 300 frames and downsampled to ~15 FPS.
3. FFmpeg (via `ffmpeg-kit`) compiles these frames into a 256x256 GIF.
4. The GIF is displayed using the Coil library.

## Community Store

The store is a simple client-side implementation that reads from the GitHub repository:

1. It fetches `bootanimations.json` from the repo.
2. It parses the entries to find the download URLs for the `.zip` and `.gif` files.
3. When a user downloads an animation, the app writes it directly into the Magisk module path and triggers a refresh.
