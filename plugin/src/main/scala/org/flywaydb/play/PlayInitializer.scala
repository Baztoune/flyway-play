/*
 * Copyright 2013 Toshiyuki Takahashi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.play

import java.io.FileNotFoundException
import javax.inject._

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.internal.util.jdbc.DriverDataSource
import play.api._
import play.core._

import scala.collection.JavaConverters._

@Singleton
class PlayInitializer @Inject() (
    configuration: Configuration,
    environment: Environment,
    flyways: Flyways,
    webCommands: WebCommands) {

  private val flywayConfigurations = {
    val configReader = new ConfigReader(configuration, environment)
    configReader.getFlywayConfigurations
  }

  private val allDatabaseNames = flywayConfigurations.keys

  private val flywayPrefixToMigrationScript = "db/migration"

  private def migrationDescriptionToShow(dbName: String, migration: MigrationInfo): String = {
    environment.resourceAsStream(s"${flywayPrefixToMigrationScript}/${dbName}/${migration.getScript}").map { in =>
      s"""|--- ${migration.getScript} ---
          |${FileUtils.readInputStreamToString(in)}""".stripMargin
    }.orElse {
      import scala.util.control.Exception._
      val code = for {
        script <- FileUtils.findJdbcMigrationFile(environment.rootPath, migration.getScript)
      } yield FileUtils.readFileToString(script)
      allCatch opt { environment.classLoader.loadClass(migration.getScript) } map { cls =>
        s"""|--- ${migration.getScript} ---
            |$code""".stripMargin
      }
    }.getOrElse(throw new FileNotFoundException(s"Migration file not found. ${migration.getScript}"))
  }

  private def checkState(dbName: String): Unit = {
    flyways.get(dbName).foreach { flyway =>
      val pendingMigrations = flyway.info().pending
      if (!pendingMigrations.isEmpty) {
        throw InvalidDatabaseRevision(
          dbName,
          pendingMigrations.map(migration => migrationDescriptionToShow(dbName, migration)).mkString("\n"))
      }
    }
  }

  def onStart(): Unit = {
    val flywayWebCommand = new FlywayWebCommand(configuration, environment, flywayPrefixToMigrationScript, flyways)
    webCommands.addHandler(flywayWebCommand)

    for (dbName <- allDatabaseNames) {
      if (environment.mode == Mode.Test || flywayConfigurations(dbName).auto) {
        migrateAutomatically(dbName)
      } else {
        checkState(dbName)
      }
    }
  }

  private def migrateAutomatically(dbName: String): Unit = {
    flyways.get(dbName).foreach { flyway =>
      flyway.migrate()
    }
  }

  val enabled: Boolean =
    !configuration.getString("flywayplugin").exists(_ == "disabled")

  if (enabled) {
    onStart()
  }

}
