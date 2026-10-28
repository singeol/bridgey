package dev.bridgey.android

import android.content.SharedPreferences
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** testdata/migrations/android/<version>/: SharedPreferences files as released versions wrote them. */
internal object MigrationFixtures {
    private val root = File(System.getProperty("bridgey.repoRoot") ?: "../..", "testdata/migrations/android")

    val versions: List<String> get() = root.listFiles()?.filter(File::isDirectory)?.map(File::getName)?.sorted().orEmpty()

    fun file(version: String, name: String): File = File(File(root, version), name)

    /** A SharedPreferences backed by the fixture `<name>.xml` of [version], or empty when the version had no such file. */
    fun preferences(version: String, name: String): FixturePreferences =
        FixturePreferences(file(version, "$name.xml").takeIf(File::isFile)?.let(::parse).orEmpty())

    /** Parses the XML format Android writes to shared_prefs/<name>.xml. */
    fun parse(file: File): Map<String, Any> {
        val document = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }.newDocumentBuilder().parse(file)
        val values = mutableMapOf<String, Any>()
        val children = document.documentElement.childNodes
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            val name = element.getAttribute("name")
            values[name] = when (element.tagName) {
                "string" -> element.textContent
                "boolean" -> element.getAttribute("value").toBooleanStrict()
                "int" -> element.getAttribute("value").toInt()
                "long" -> element.getAttribute("value").toLong()
                "float" -> element.getAttribute("value").toFloat()
                "set" -> element.getElementsByTagName("string").let { strings -> (0 until strings.length).map { strings.item(it).textContent }.toSet() }
                else -> error("unknown SharedPreferences type ${element.tagName} in $file")
            }
        }
        return values
    }
}

/** An in-memory SharedPreferences for JVM tests; edits apply immediately. */
internal class FixturePreferences(initial: Map<String, Any>) : SharedPreferences {
    val values = initial.toMutableMap()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private var clear = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor = change(key, value)
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = change(key, values?.toSet())
        override fun putInt(key: String, value: Int): SharedPreferences.Editor = change(key, value)
        override fun putLong(key: String, value: Long): SharedPreferences.Editor = change(key, value)
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor = change(key, value)
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = change(key, value)
        override fun remove(key: String): SharedPreferences.Editor = change(key, null)
        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        private fun change(key: String, value: Any?): SharedPreferences.Editor {
            changes[key] = value
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clear) values.clear()
            changes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
        }
    }
}
