package utils

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipUtils {
    /**
     * Zips a folder and a file (desc.txt) into a bootanimation.zip without compression (STORED).
     */
    fun zipBootAnimation(sourceFolder: File, descFile: File, outputFile: File): Boolean {
        return try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
                // Set to STORED (no compression) for boot animations
                zos.setMethod(ZipOutputStream.STORED)

                // 1. Add desc.txt
                addFileToZip(descFile, "desc.txt", zos)

                // 2. Add parts folders and their contents
                zipFolder(sourceFolder, sourceFolder.name, zos)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun zipAdvancedBootAnimation(workDir: File, descFile: File, outputFile: File): Boolean {
        return try {
            ZipOutputStream(BufferedOutputStream(FileOutputStream(outputFile))).use { zos ->
                zos.setMethod(ZipOutputStream.STORED)

                // 1. Add desc.txt
                addFileToZip(descFile, "desc.txt", zos)

                // 2. Add all subdirectories (part folders)
                workDir.listFiles()?.filter { it.isDirectory }?.forEach { folder ->
                    zipFolder(folder, folder.name, zos)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun addFileToZip(file: File, entryName: String, zos: ZipOutputStream) {
        val bytes = file.readBytes()
        val entry = ZipEntry(entryName)
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        entry.crc = calculateCRC32(bytes)
        
        zos.putNextEntry(entry)
        zos.write(bytes)
        zos.closeEntry()
    }

    private fun zipFolder(folder: File, parentName: String, zos: ZipOutputStream) {
        // Add the folder entry itself
        val folderEntry = ZipEntry("$parentName/")
        folderEntry.size = 0
        folderEntry.compressedSize = 0
        folderEntry.crc = 0
        zos.putNextEntry(folderEntry)
        zos.closeEntry()

        folder.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                zipFolder(file, "$parentName/${file.name}", zos)
            } else {
                val entryName = "$parentName/${file.name}"
                val entry = ZipEntry(entryName)
                val bytes = file.readBytes()
                
                entry.size = bytes.size.toLong()
                entry.compressedSize = bytes.size.toLong()
                entry.crc = calculateCRC32(bytes)
                
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    private fun calculateCRC32(bytes: ByteArray): Long {
        val crc = java.util.zip.CRC32()
        crc.update(bytes)
        return crc.value
    }
}
