package com.kitefoldlabs.bridle

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser

/**
 * The database (compose history, encrypted keys, known hosts), the DataStore settings and the Tink
 * keyset prefs must never leave the device through Android backup or device-to-device transfer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BackupRulesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    /** Every `<exclude>` and `<include>` as (enclosing section, tag, domain, path). */
    private fun rules(xml: Int): List<List<String?>> {
        val parser = context.resources.getXml(xml)
        val rules = mutableListOf<List<String?>>()
        var section: String? = null
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "include", "exclude" ->
                    rules +=
                        listOf(
                            section,
                            parser.name,
                            parser.getAttributeValue(null, "domain"),
                            parser.getAttributeValue(null, "path"),
                        )
                else -> section = parser.name
            }
        }
        return rules
    }

    @Test
    fun `cloud backup and device transfer exclude every domain`() {
        val rules = rules(R.xml.data_extraction_rules)
        rules.none { it[1] == "include" } shouldBe true
        for (section in listOf("cloud-backup", "device-transfer")) {
            rules.filter { it[0] == section }.map { it[2] } shouldContainExactlyInAnyOrder ALL_DOMAINS
            // No path: the whole domain.
            rules.filter { it[0] == section }.all { it[3] == null } shouldBe true
        }
    }

    @Test
    fun `legacy full backup excludes every domain`() {
        val rules = rules(R.xml.backup_rules)
        rules.none { it[1] == "include" } shouldBe true
        rules.map { it[2] } shouldContainExactlyInAnyOrder ALL_DOMAINS
        rules.all { it[3] == "." } shouldBe true
    }

    private companion object {
        val ALL_DOMAINS =
            listOf(
                "root",
                "file",
                "database",
                "sharedpref",
                "external",
                "device_root",
                "device_file",
                "device_database",
                "device_sharedpref",
            )
    }
}
