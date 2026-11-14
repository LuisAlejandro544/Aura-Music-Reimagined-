package com.auramusic.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas unitarias de la cadena de migraciones de Room para Aura Music.
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
}
