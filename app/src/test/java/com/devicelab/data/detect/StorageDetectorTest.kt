package com.devicelab.data.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Identifying a filesystem type from `/proc/mounts`.
 *
 * Android has no filesystem-type API at all, so `/proc/mounts` is the only source. The
 * matching rule has to cope with two facts about real mount tables: the interesting mount
 * is often nested (`/data` sits under `/`, and both match a query for `/data`), and an
 * app's mount namespace sometimes hides the real one entirely.
 *
 * The longest matching mount point wins, because that is the one actually backing the
 * path. Returning `/`'s type for a query about `/data` would report the wrong filesystem
 * with total confidence -- and on a modern device `/` is often ext4 while `/data` is f2fs,
 * so the wrong answer is also the plausible-looking one.
 */
class StorageDetectorTest {

    @Test
    fun `an exact mount point match returns its type`() {
        assertEquals("f2fs", StorageDetector.findFilesystem(MOUNTS, "/data"))
    }

    /** The whole point of the longest-match rule. */
    @Test
    fun `the longest matching mount point wins over its parent`() {
        val mounts = """
            /dev/root / ext4 ro,seclabel 0 0
            /dev/block/dm-48 /data f2fs rw,seclabel,noatime 0 0
        """.trimIndent()
        assertEquals("f2fs", StorageDetector.findFilesystem(mounts, "/data"))
        assertEquals("ext4", StorageDetector.findFilesystem(mounts, "/"))
    }

    @Test
    fun `a path under a mount point resolves to that mount`() {
        assertEquals("f2fs", StorageDetector.findFilesystem(MOUNTS, "/data/user/0"))
    }

    /**
     * The emulated volume is a FUSE mount, and the detector queries the prefix
     * `/storage/emulated` rather than a full path, so a mount point *under* the query
     * counts as a match too.
     */
    @Test
    fun `a mount point under the queried prefix resolves`() {
        assertEquals("fuse", StorageDetector.findFilesystem(MOUNTS, "/storage/emulated"))
        assertEquals("fuse", StorageDetector.findFilesystem(MOUNTS, "/storage"))
    }

    /**
     * That third rule has to require a separator. Without it every mount point begins
     * with `/`, so a query for the root would "match" the whole table and come back with
     * whichever mount happened to be deepest -- fuse, on the table below.
     */
    @Test
    fun `a query for the root returns the root and not the deepest mount`() {
        assertEquals("ext4", StorageDetector.findFilesystem(MOUNTS, "/"))
    }

    /** A sibling directory sharing a name prefix is not a match. */
    @Test
    fun `a mount point that merely shares a prefix is not a match`() {
        val mounts = """
            /dev/block/dm-1 /datamisc ext4 rw 0 0
            /dev/block/dm-2 /data f2fs rw 0 0
        """.trimIndent()
        assertEquals("f2fs", StorageDetector.findFilesystem(mounts, "/data"))
        assertEquals("ext4", StorageDetector.findFilesystem(mounts, "/datamisc"))
    }

    @Test
    fun `a path with no matching mount is null rather than a guess`() {
        assertNull(StorageDetector.findFilesystem(MOUNTS, "/nonexistent"))
    }

    /**
     * An app's mount namespace can hide the real data mount. Null is the honest answer;
     * the detector turns it into a row that says the type could not be identified rather
     * than naming the most likely filesystem.
     */
    @Test
    fun `an empty or unreadable mount table is null`() {
        assertNull(StorageDetector.findFilesystem("", "/data"))
        assertNull(StorageDetector.findFilesystem("\n\n", "/data"))
    }

    @Test
    fun `lines too short to be a mount entry are skipped`() {
        val mounts = """
            garbage
            /dev/block/dm-48 /data
            /dev/block/dm-48 /data f2fs rw 0 0
        """.trimIndent()
        assertEquals("f2fs", StorageDetector.findFilesystem(mounts, "/data"))
    }

    @Test
    fun `a table of nothing but malformed lines is null`() {
        assertNull(StorageDetector.findFilesystem("one\ntwo\n", "/data"))
    }

    @Test
    fun `the first of two equally long matches is kept`() {
        val mounts = """
            /dev/block/dm-1 /data f2fs rw 0 0
            /dev/block/dm-2 /data ext4 rw 0 0
        """.trimIndent()
        assertEquals("f2fs", StorageDetector.findFilesystem(mounts, "/data"))
    }

    @Test
    fun `a realistic mount table resolves both paths the detector asks about`() {
        assertEquals("f2fs", StorageDetector.findFilesystem(MOUNTS, "/data"))
        assertEquals("fuse", StorageDetector.findFilesystem(MOUNTS, "/storage/emulated"))
    }

    private companion object {
        /** An excerpt of a real device's `/proc/mounts`, in its real order. */
        val MOUNTS = """
            /dev/root / ext4 ro,seclabel,relatime 0 0
            tmpfs /dev tmpfs rw,seclabel,nosuid,relatime,mode=755 0 0
            proc /proc proc rw,relatime,gid=3009,hidepid=invisible 0 0
            sysfs /sys sysfs rw,seclabel,nosuid,nodev,noexec,relatime 0 0
            /dev/block/dm-47 /vendor ext4 ro,seclabel,relatime 0 0
            /dev/block/dm-48 /data f2fs rw,seclabel,nosuid,nodev,noatime 0 0
            /dev/block/by-name/metadata /metadata ext4 rw,seclabel,nosuid,nodev,noatime 0 0
            /data/media /storage/emulated fuse rw,nosuid,nodev,noexec,noatime 0 0
        """.trimIndent()
    }
}
