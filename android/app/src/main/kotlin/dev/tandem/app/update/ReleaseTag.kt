package dev.tandem.app.update

/**
 * A release tag in the release workflow's form, `vMAJOR.MINOR.PATCH` with an optional `-suffix` or `+suffix`. The
 * workflow (.github/workflows/release.yml) builds the release APK with versionCode `major*1_000_000 + minor*1_000 +
 * patch` and versionName the tag without its `v`; these read the same numbers back from a tag. Anything else is no
 * release of ours: null.
 */
object ReleaseTag {
    private val TAG = Regex("""v([0-9]{1,10})\.([0-9]{1,3})\.([0-9]{1,3})([-+][0-9A-Za-z.\-+]+)?""")

    fun versionCode(tag: String): Int? {
        val m = TAG.matchEntire(tag) ?: return null
        val (major, minor, patch) = m.destructured
        val code = major.toLong() * 1_000_000 + minor.toLong() * 1_000 + patch.toLong()
        return code.takeIf { it <= Int.MAX_VALUE }?.toInt()
    }

    fun versionName(tag: String): String? = if (versionCode(tag) != null) tag.removePrefix("v") else null
}
