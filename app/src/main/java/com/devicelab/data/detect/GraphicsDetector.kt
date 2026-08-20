package com.devicelab.data.detect

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.FeatureInfo
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES10
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLES31
import com.devicelab.core.common.Format
import com.devicelab.core.detect.Probe
import com.devicelab.core.model.Absent
import com.devicelab.core.model.Domain
import com.devicelab.core.model.Fact
import com.devicelab.core.model.Lab
import com.devicelab.core.model.LabReport
import com.devicelab.core.model.Section
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * GPU, OpenGL ES, EGL and Vulkan.
 *
 * The GPU renderer string is not available from any Android service: the only way
 * to learn it is to hold a current GL context and call `glGetString(GL_RENDERER)`.
 * So [queryGl] creates a real off-screen EGL context on a 1×1 pbuffer, reads the
 * strings and limits, and tears everything down again. It is the single expensive
 * operation in the whole app, so its result is cached for the lifetime of the scan.
 *
 * **Vulkan.** Vulkan has no Java or Kotlin bindings on any Android version --
 * `vkEnumerateInstanceExtensionProperties` and friends are reachable only through
 * JNI. Rather than ship a stub, this detector uses the mechanism Android's own
 * documentation prescribes for Vulkan capability checks: the `FeatureInfo.version`
 * field of the `android.hardware.vulkan.*` system features, which the platform
 * populates from the driver itself. That yields the real API version, hardware
 * level, compute level and dEQP level. Per-extension enumeration is reported as
 * not exposed, with the reason stated, instead of being faked.
 */
class GraphicsDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val probe: Probe,
) : CapabilityDetector {

    override val lab = Lab.GRAPHICS

    /** Everything one current GL context can tell us. */
    private data class GlInfo(
        val vendor: String?,
        val renderer: String?,
        val version: String?,
        val shadingLanguage: String?,
        val extensions: List<String>,
        val eglVendor: String?,
        val eglVersion: String?,
        val eglClientApis: String?,
        val eglExtensions: List<String>,
        val contextClientVersion: Int,
        val limits: Map<String, Int>,
        val compressedFormats: List<String>,
        val error: String? = null,
    )

    private var cached: GlInfo? = null

    override suspend fun detect(): LabReport {
        val pm = context.packageManager
        val gl = queryGl()
        val notes = buildList {
            gl?.error?.let {
                add("The off-screen GL context could not be created ($it), so renderer and " +
                    "extension strings are unavailable. The OpenGL ES version below still " +
                    "comes from ActivityManager, which does not need a context.")
            }
        }
        return LabReport(
            lab = lab,
            sections = listOf(
                gpu(gl, pm),
                openGl(gl, pm),
                egl(gl),
                vulkan(pm),
                glLimits(gl),
                textureCompression(gl),
                glExtensions(gl),
            ),
            notes = notes,
        )
    }

    private fun gpu(gl: GlInfo?, pm: PackageManager) = Section(
        id = "gpu",
        title = "GPU",
        subtitle = "glGetString() on an off-screen EGL context",
        facts = listOf(
            probe.value(
                "Renderer",
                "glGetString(GL_RENDERER)",
                domain = Domain.GRAPHICS,
                searchTerms = listOf("gpu", "adreno", "mali", "powervr", "xclipse", "immortalis"),
                detail = "Reported by the driver itself. Android has no service that " +
                    "returns a GPU name, so this is read from a real GL context.",
            ) { gl?.renderer },
            probe.value(
                "Vendor",
                "glGetString(GL_VENDOR)",
                domain = Domain.GRAPHICS,
            ) { gl?.vendor },
            probe.value(
                "Hardware acceleration",
                "ApplicationInfo.FLAG_HARDWARE_ACCELERATED",
                domain = Domain.GRAPHICS,
            ) {
                val flags = context.applicationInfo.flags
                if (flags and ApplicationInfo.FLAG_HARDWARE_ACCELERATED != 0) {
                    "Enabled for this process"
                } else {
                    "Not enabled for this process"
                }
            },
            probe.flag(
                "Low-RAM device profile",
                "ActivityManager.isLowRamDevice()",
                minApi = 19,
                supportedText = "Yes — the platform asks apps to reduce graphics load",
                unsupportedText = "No",
            ) {
                context.getSystemService(ActivityManager::class.java)?.isLowRamDevice
            },
        ),
    )

    private fun openGl(gl: GlInfo?, pm: PackageManager) = Section(
        id = "opengl",
        title = "OpenGL ES",
        subtitle = "ActivityManager.getDeviceConfigurationInfo(), glGetString()",
        facts = listOf(
            probe.value(
                "Device OpenGL ES version",
                "ConfigurationInfo.getGlEsVersion()",
                domain = Domain.GRAPHICS,
                searchTerms = listOf("opengl", "gles", "gl es", "3.2", "3.1"),
                detail = "The highest OpenGL ES version the device claims. Reported by " +
                    "ActivityManager, so it is available even without a GL context.",
            ) {
                context.getSystemService(ActivityManager::class.java)
                    ?.deviceConfigurationInfo
                    ?.glEsVersion
            },
            probe.value(
                "Context version string",
                "glGetString(GL_VERSION)",
            ) { gl?.version },
            probe.value(
                "Shading language",
                "glGetString(GL_SHADING_LANGUAGE_VERSION)",
                searchTerms = listOf("glsl", "shader"),
            ) { gl?.shadingLanguage },
            probe.flag(
                "Compute shaders",
                "GL_VERSION ≥ ES 3.1",
                domain = Domain.GRAPHICS,
                searchTerms = listOf("compute shader", "gpgpu"),
                detail = "Compute shaders are core in OpenGL ES 3.1.",
            ) { gl?.let { glesAtLeast(it, 3, 1) } },
            probe.flag(
                "Geometry & tessellation shaders",
                "GL_VERSION ≥ ES 3.2",
                searchTerms = listOf("geometry shader", "tessellation"),
            ) { gl?.let { glesAtLeast(it, 3, 2) } },
            probe.flag(
                "AEP (Android Extension Pack)",
                "PackageManager.FEATURE_OPENGLES_EXTENSION_PACK",
                minApi = 21,
                searchTerms = listOf("aep", "extension pack"),
            ) { pm.hasSystemFeature(PackageManager.FEATURE_OPENGLES_EXTENSION_PACK) },
            probe.value(
                "Extension count",
                "glGetString(GL_EXTENSIONS)",
            ) { gl?.extensions?.size?.takeIf { it > 0 }?.toString() },
        ),
    )

    private fun egl(gl: GlInfo?) = Section(
        id = "egl",
        title = "EGL",
        subtitle = "EGL14.eglQueryString()",
        facts = listOf(
            probe.value("EGL version", "eglQueryString(EGL_VERSION)") { gl?.eglVersion },
            probe.value("EGL vendor", "eglQueryString(EGL_VENDOR)") { gl?.eglVendor },
            probe.value("Client APIs", "eglQueryString(EGL_CLIENT_APIS)") { gl?.eglClientApis },
            probe.value("Context client version", "EGL_CONTEXT_CLIENT_VERSION") {
                gl?.contextClientVersion?.takeIf { it > 0 }?.let { "OpenGL ES $it" }
            },
            probe.value("EGL extension count", "eglQueryString(EGL_EXTENSIONS)") {
                gl?.eglExtensions?.size?.takeIf { it > 0 }?.toString()
            },
            probe.flag(
                "EGL_ANDROID_get_frame_timestamps",
                "eglQueryString(EGL_EXTENSIONS)",
                searchTerms = listOf("frame timestamps", "latency"),
            ) { gl?.eglExtensions?.contains("EGL_ANDROID_get_frame_timestamps") },
            probe.flag(
                "EGL_KHR_gl_colorspace",
                "eglQueryString(EGL_EXTENSIONS)",
                searchTerms = listOf("colorspace", "srgb"),
            ) { gl?.eglExtensions?.contains("EGL_KHR_gl_colorspace") },
            probe.flag(
                "EGL_EXT_gl_colorspace_display_p3",
                "eglQueryString(EGL_EXTENSIONS)",
                searchTerms = listOf("p3", "wide colour"),
            ) { gl?.eglExtensions?.contains("EGL_EXT_gl_colorspace_display_p3") },
        ),
        children = listOfNotNull(
            gl?.eglExtensions?.takeIf { it.isNotEmpty() }?.let { extensions ->
                Section(
                    id = "egl-extensions",
                    title = "All EGL extensions",
                    subtitle = "${extensions.size} reported",
                    facts = extensions.sorted().mapIndexed { index, name ->
                        Fact(
                            label = name,
                            value = "Present",
                            provenance = com.devicelab.core.model.Provenance
                                .Queried("eglQueryString(EGL_EXTENSIONS)"),
                            support = com.devicelab.core.model.Support.SUPPORTED,
                            searchTerms = listOf("egl extension", name),
                        )
                    },
                )
            },
        ),
    )

    /**
     * Vulkan through the system-feature version fields.
     *
     * `FeatureInfo.version` for `android.hardware.vulkan.version` is a packed
     * `VK_MAKE_VERSION` integer; for `.level` and `.compute` it is the integer
     * level; for `.deqp.level` it is a date-shaped integer the CTS defines. All four
     * are set by the platform from the driver, so they are real driver-reported
     * values, not inferences from the device model.
     */
    private fun vulkan(pm: PackageManager): Section {
        val features = SystemFeatures.byName(pm)
        fun feature(name: String): FeatureInfo? = features[name]

        val versionFeature = feature("android.hardware.vulkan.version")
        val levelFeature = feature("android.hardware.vulkan.level")
        val computeFeature = feature("android.hardware.vulkan.compute")
        val deqpFeature = feature("android.hardware.vulkan.deqp.level")

        return Section(
            id = "vulkan",
            title = "Vulkan",
            subtitle = "PackageManager system-feature versions",
            facts = listOf(
                probe.verdict(
                    "Vulkan",
                    "FEATURE_VULKAN_HARDWARE_VERSION",
                    minApi = 24,
                    domain = Domain.GRAPHICS,
                    searchTerms = listOf("vulkan", "vk"),
                ) {
                    if (versionFeature == null) {
                        Probe.Verdict.no(
                            "Not available",
                            "The device does not declare android.hardware.vulkan.version, " +
                                "which means no Vulkan driver is present.",
                        )
                    } else {
                        Probe.Verdict.yes(
                            "Available",
                            "Driver reports Vulkan " + Format.vulkanVersion(versionFeature.version),
                        )
                    }
                },
                probe.value(
                    "Vulkan API version",
                    "FEATURE_VULKAN_HARDWARE_VERSION → FeatureInfo.version",
                    minApi = 24,
                    domain = Domain.GRAPHICS,
                    searchTerms = listOf("vulkan 1.1", "vulkan 1.2", "vulkan 1.3", "vulkan api"),
                    detail = "Decoded from the packed VK_MAKE_VERSION integer the platform " +
                        "stores in this feature's version field.",
                ) { versionFeature?.version?.let { Format.vulkanVersion(it) } },
                probe.value(
                    "Hardware level",
                    "FEATURE_VULKAN_HARDWARE_LEVEL → FeatureInfo.version",
                    minApi = 24,
                    domain = Domain.GRAPHICS,
                    detail = "Level 0 is the baseline required of any Vulkan device; " +
                        "level 1 adds the limits and features Android requires of " +
                        "mainstream hardware; level 2 adds more still.",
                ) { levelFeature?.let { "Level ${it.version}" } },
                probe.value(
                    "Compute level",
                    "FEATURE_VULKAN_HARDWARE_COMPUTE → FeatureInfo.version",
                    minApi = 24,
                    searchTerms = listOf("vulkan compute"),
                ) { computeFeature?.let { "Level ${it.version}" } },
                probe.value(
                    "dEQP level",
                    "FEATURE_VULKAN_DEQP_LEVEL → FeatureInfo.version",
                    minApi = 30,
                    detail = "The Khronos conformance-test level the driver passes, " +
                        "encoded by CTS as a date.",
                ) { deqpFeature?.version?.toString() },
                probe.notExposedByAndroid(
                    "Vulkan device name & extensions",
                    "Vulkan is a native-only API — vkEnumerateInstanceExtensionProperties " +
                        "and vkGetPhysicalDeviceProperties have no Java or Kotlin binding " +
                        "on any Android version, so a JNI library would be required to " +
                        "enumerate them",
                    domain = null,
                    searchTerms = listOf("vulkan extensions", "vkenumerate", "physical device"),
                ),
            ),
        )
    }

    private fun glLimits(gl: GlInfo?): Section {
        val limits = gl?.limits.orEmpty()
        return Section(
            id = "gl-limits",
            title = "OpenGL ES limits",
            subtitle = "glGetIntegerv()",
            facts = LIMIT_LABELS.map { (key, label) ->
                probe.value(label, "glGetIntegerv($key)") {
                    limits[key]?.takeIf { it > 0 }?.let { value ->
                        if (key == "GL_MAX_TEXTURE_SIZE" || key == "GL_MAX_RENDERBUFFER_SIZE" ||
                            key == "GL_MAX_CUBE_MAP_TEXTURE_SIZE"
                        ) {
                            "$value px"
                        } else {
                            value.toString()
                        }
                    }
                }
            },
        )
    }

    private fun textureCompression(gl: GlInfo?) = Section(
        id = "texture-compression",
        title = "Texture compression",
        subtitle = "GL extensions and GL_COMPRESSED_TEXTURE_FORMATS",
        facts = listOf(
            probe.flag(
                "ASTC",
                "glGetString(GL_EXTENSIONS)",
                domain = Domain.GRAPHICS,
                searchTerms = listOf("astc", "texture compression"),
            ) {
                gl?.extensions?.any { it.contains("texture_compression_astc", ignoreCase = true) }
            },
            probe.flag(
                "ETC2 / EAC",
                "GL_VERSION ≥ ES 3.0",
                searchTerms = listOf("etc2", "eac"),
                detail = "ETC2 and EAC are mandatory in OpenGL ES 3.0 and later.",
            ) { gl?.let { glesAtLeast(it, 3, 0) } },
            probe.flag(
                "ETC1",
                "glGetString(GL_EXTENSIONS)",
                searchTerms = listOf("etc1"),
            ) {
                gl?.extensions?.any { it.contains("compression_ETC1", ignoreCase = true) }
            },
            probe.flag(
                "S3TC / DXT",
                "glGetString(GL_EXTENSIONS)",
                searchTerms = listOf("s3tc", "dxt", "bc1"),
            ) {
                gl?.extensions?.any { it.contains("texture_compression_s3tc", ignoreCase = true) }
            },
            probe.flag(
                "PVRTC",
                "glGetString(GL_EXTENSIONS)",
                searchTerms = listOf("pvrtc"),
            ) {
                gl?.extensions?.any { it.contains("texture_compression_pvrtc", ignoreCase = true) }
            },
            probe.value(
                "Compressed formats reported",
                "glGetIntegerv(GL_COMPRESSED_TEXTURE_FORMATS)",
                absentText = Absent.NONE,
            ) { gl?.compressedFormats?.size?.takeIf { it > 0 }?.toString() },
        ),
    )

    private fun glExtensions(gl: GlInfo?): Section {
        val extensions = gl?.extensions.orEmpty()
        return Section(
            id = "gl-extensions",
            title = "All OpenGL ES extensions",
            subtitle = if (extensions.isEmpty()) {
                "Unavailable without a GL context"
            } else {
                "${extensions.size} reported"
            },
            facts = extensions.sorted().map { name ->
                Fact(
                    label = name,
                    value = "Present",
                    provenance = com.devicelab.core.model.Provenance
                        .Queried("glGetString(GL_EXTENSIONS)"),
                    support = com.devicelab.core.model.Support.SUPPORTED,
                    searchTerms = listOf("gl extension", name),
                )
            },
        )
    }

    private fun glesAtLeast(gl: GlInfo, major: Int, minor: Int): Boolean? {
        val version = gl.version ?: return null
        // "OpenGL ES 3.2 v1.r40p1-01eac0" -- take the first "N.M" in the string.
        val match = Regex("""(\d+)\.(\d+)""").find(version) ?: return null
        val gotMajor = match.groupValues[1].toIntOrNull() ?: return null
        val gotMinor = match.groupValues[2].toIntOrNull() ?: return null
        return gotMajor > major || (gotMajor == major && gotMinor >= minor)
    }

    /**
     * Creates a 1×1 off-screen EGL context, reads everything, and destroys it.
     *
     * Every step is checked: a device with no ES3 config falls back to ES2, and any
     * failure returns a [GlInfo] carrying the error rather than throwing, so the
     * rest of the graphics lab still reports what ActivityManager and PackageManager
     * know.
     */
    private fun queryGl(): GlInfo? {
        cached?.let { return it }

        var display: EGLDisplay? = null
        var context: EGLContext? = null
        var surface: EGLSurface? = null
        val result = try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) error("eglGetDisplay returned EGL_NO_DISPLAY")

            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                error("eglInitialize failed (0x${Integer.toHexString(EGL14.eglGetError())})")
            }

            val eglVendor = EGL14.eglQueryString(display, EGL14.EGL_VENDOR)
            val eglVersion = EGL14.eglQueryString(display, EGL14.EGL_VERSION)
            val eglClientApis = EGL14.eglQueryString(display, EGL14.EGL_CLIENT_APIS)
            val eglExtensions = EGL14.eglQueryString(display, EGL14.EGL_EXTENSIONS)
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                .orEmpty()

            var clientVersion = 3
            var config = chooseConfig(display, EGLExt.EGL_OPENGL_ES3_BIT_KHR)
            if (config == null) {
                clientVersion = 2
                config = chooseConfig(display, EGL14.EGL_OPENGL_ES2_BIT)
            }
            if (config == null) error("no EGL config with a pbuffer surface")

            context = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, clientVersion, EGL14.EGL_NONE),
                0,
            )
            if (context == null || context == EGL14.EGL_NO_CONTEXT) {
                error("eglCreateContext failed (0x${Integer.toHexString(EGL14.eglGetError())})")
            }

            surface = EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            if (surface == null || surface == EGL14.EGL_NO_SURFACE) {
                error("eglCreatePbufferSurface failed")
            }
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
                error("eglMakeCurrent failed (0x${Integer.toHexString(EGL14.eglGetError())})")
            }

            val glVersion = GLES20.glGetString(GLES20.GL_VERSION)
            val extensions = readExtensions(glVersion)

            GlInfo(
                vendor = GLES20.glGetString(GLES20.GL_VENDOR),
                renderer = GLES20.glGetString(GLES20.GL_RENDERER),
                version = glVersion,
                shadingLanguage = GLES20.glGetString(GLES20.GL_SHADING_LANGUAGE_VERSION),
                extensions = extensions,
                eglVendor = eglVendor,
                eglVersion = eglVersion,
                eglClientApis = eglClientApis,
                eglExtensions = eglExtensions,
                contextClientVersion = clientVersion,
                limits = readLimits(glVersion),
                compressedFormats = readCompressedFormats(),
            )
        } catch (t: Throwable) {
            GlInfo(
                null, null, null, null, emptyList(), null, null, null, emptyList(), 0,
                emptyMap(), emptyList(),
                error = t.message ?: t.javaClass.simpleName,
            )
        } finally {
            try {
                if (display != null) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                    if (surface != null) EGL14.eglDestroySurface(display, surface)
                    if (context != null) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (ignored: Throwable) {
                // Teardown failures cannot be acted on and must not mask a good result.
            }
        }
        cached = result
        return result
    }

    private fun chooseConfig(display: EGLDisplay, renderableType: Int): EGLConfig? {
        val attribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, renderableType,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val ok = EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0)
        return if (ok && count[0] > 0) configs[0] else null
    }

    /**
     * ES 3.0 deprecated the single space-separated `GL_EXTENSIONS` string in favour
     * of indexed queries, and some drivers return null for the old form. Try the
     * indexed path first on ES 3+, then fall back.
     */
    private fun readExtensions(glVersion: String?): List<String> {
        val indexed = try {
            val isEs3 = glVersion?.contains("ES 3") == true
            if (!isEs3) {
                emptyList()
            } else {
                val count = IntArray(1)
                GLES30.glGetIntegerv(GLES30.GL_NUM_EXTENSIONS, count, 0)
                (0 until count[0]).mapNotNull { GLES30.glGetStringi(GLES30.GL_EXTENSIONS, it) }
            }
        } catch (t: Throwable) {
            emptyList()
        }
        if (indexed.isNotEmpty()) return indexed
        return try {
            GLES20.glGetString(GLES20.GL_EXTENSIONS)
                ?.split(' ')
                ?.filter { it.isNotBlank() }
                .orEmpty()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    private fun readLimits(glVersion: String?): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        fun read(key: String, enum: Int) {
            try {
                val v = IntArray(1)
                GLES20.glGetIntegerv(enum, v, 0)
                if (GLES20.glGetError() == GLES20.GL_NO_ERROR && v[0] > 0) out[key] = v[0]
            } catch (ignored: Throwable) {
            }
        }
        read("GL_MAX_TEXTURE_SIZE", GLES20.GL_MAX_TEXTURE_SIZE)
        read("GL_MAX_CUBE_MAP_TEXTURE_SIZE", GLES20.GL_MAX_CUBE_MAP_TEXTURE_SIZE)
        read("GL_MAX_RENDERBUFFER_SIZE", GLES20.GL_MAX_RENDERBUFFER_SIZE)
        read("GL_MAX_TEXTURE_IMAGE_UNITS", GLES20.GL_MAX_TEXTURE_IMAGE_UNITS)
        read("GL_MAX_VERTEX_ATTRIBS", GLES20.GL_MAX_VERTEX_ATTRIBS)
        read("GL_MAX_VERTEX_UNIFORM_VECTORS", GLES20.GL_MAX_VERTEX_UNIFORM_VECTORS)
        read("GL_MAX_FRAGMENT_UNIFORM_VECTORS", GLES20.GL_MAX_FRAGMENT_UNIFORM_VECTORS)
        read("GL_MAX_VARYING_VECTORS", GLES20.GL_MAX_VARYING_VECTORS)
        if (glVersion?.contains("ES 3") == true) {
            read("GL_MAX_3D_TEXTURE_SIZE", GLES30.GL_MAX_3D_TEXTURE_SIZE)
            read("GL_MAX_ARRAY_TEXTURE_LAYERS", GLES30.GL_MAX_ARRAY_TEXTURE_LAYERS)
            read("GL_MAX_COLOR_ATTACHMENTS", GLES30.GL_MAX_COLOR_ATTACHMENTS)
            read("GL_MAX_DRAW_BUFFERS", GLES30.GL_MAX_DRAW_BUFFERS)
            read("GL_MAX_SAMPLES", GLES30.GL_MAX_SAMPLES)
            read("GL_MAX_UNIFORM_BUFFER_BINDINGS", GLES30.GL_MAX_UNIFORM_BUFFER_BINDINGS)
            read("GL_MAX_TEXTURE_LOD_BIAS", GLES30.GL_MAX_TEXTURE_LOD_BIAS)
        }
        try {
            if (glVersion?.contains("ES 3.1") == true || glVersion?.contains("ES 3.2") == true) {
                read("GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS", GLES31.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS)
                read("GL_MAX_COMPUTE_SHARED_MEMORY_SIZE", GLES31.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE)
                read("GL_MAX_SHADER_STORAGE_BLOCK_SIZE", GLES31.GL_MAX_SHADER_STORAGE_BLOCK_SIZE)
            }
        } catch (ignored: Throwable) {
        }
        return out
    }

    private fun readCompressedFormats(): List<String> = try {
        val count = IntArray(1)
        GLES10.glGetIntegerv(GLES10.GL_NUM_COMPRESSED_TEXTURE_FORMATS, count, 0)
        if (count[0] <= 0) {
            emptyList()
        } else {
            val formats = IntArray(count[0])
            GLES10.glGetIntegerv(GLES10.GL_COMPRESSED_TEXTURE_FORMATS, formats, 0)
            formats.map { "0x" + Integer.toHexString(it).uppercase() }
        }
    } catch (t: Throwable) {
        emptyList()
    }

    private companion object {
        val LIMIT_LABELS = listOf(
            "GL_MAX_TEXTURE_SIZE" to "Max texture size",
            "GL_MAX_CUBE_MAP_TEXTURE_SIZE" to "Max cube map size",
            "GL_MAX_3D_TEXTURE_SIZE" to "Max 3D texture size",
            "GL_MAX_ARRAY_TEXTURE_LAYERS" to "Max array texture layers",
            "GL_MAX_RENDERBUFFER_SIZE" to "Max renderbuffer size",
            "GL_MAX_TEXTURE_IMAGE_UNITS" to "Max texture image units",
            "GL_MAX_VERTEX_ATTRIBS" to "Max vertex attributes",
            "GL_MAX_COLOR_ATTACHMENTS" to "Max colour attachments",
            "GL_MAX_DRAW_BUFFERS" to "Max draw buffers",
            "GL_MAX_SAMPLES" to "Max MSAA samples",
            "GL_MAX_UNIFORM_BUFFER_BINDINGS" to "Max uniform buffer bindings",
            "GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS" to "Max compute work-group invocations",
            "GL_MAX_COMPUTE_SHARED_MEMORY_SIZE" to "Max compute shared memory",
            "GL_MAX_SHADER_STORAGE_BLOCK_SIZE" to "Max SSBO size",
        )
    }
}
