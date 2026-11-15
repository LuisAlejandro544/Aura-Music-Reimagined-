package com.auramusic.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pruebas unitarias de la cadena de migraciones y esquemas versionados de Room para Aura Music.
 */
class AuraMigrationsTest {
    @Test
    fun currentDatabaseVersionHasValidMigrationChain() {
        assertTrue(AuraMigrations.validateMigrationChain())
    }

    @Test
    fun validatesContiguousMigrationChainAndRejectsGaps() {
        val m1To2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }
        val m2To3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = Unit
        }

        assertTrue(AuraMigrations.validateMigrationChain(targetVersion = 3, migrations = arrayOf(m1To2, m2To3)))
        assertFalse(AuraMigrations.validateMigrationChain(targetVersion = 3, migrations = arrayOf(m1To2)))
    }

    @Test
    fun currentDatabaseVersionSchemaFileIsVersionedAndValid() {
        val relativeSchema = "schemas/com.auramusic.core.database.AuraDatabase/${AuraMigrations.CURRENT_DATABASE_VERSION}.json"
        val candidates = listOf(
            File(relativeSchema),
            File("android/core/database/$relativeSchema"),
        )
        val schemaFile = candidates.firstOrNull { it.exists() }
        assertTrue("Debe existir el esquema Room versionado en $relativeSchema", schemaFile != null)

        // En unit tests JVM locales sin Robolectric, org.json.JSONObject lanza stub "not mocked".
        // Validamos la estructura JSON del esquema generado por Room en Kotlin puro.
        val rawJson = schemaFile!!.readText()
        assertTrue(
            "El esquema debe declarar version = ${AuraMigrations.CURRENT_DATABASE_VERSION}",
            Regex(""""version"\s*:\s*${AuraMigrations.CURRENT_DATABASE_VERSION}\b""").containsMatchIn(rawJson),
        )
        for (expectedTable in listOf("tracks", "playlists", "playlist_tracks")) {
            assertTrue(
                "El esquema debe contener la tabla $expectedTable",
                Regex(""""tableName"\s*:\s*"$expectedTable"""").containsMatchIn(rawJson),
            )
        }
    }
}
