/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.datasources.v2

import java.net.URI
import java.util

import scala.collection.JavaConverters._
import scala.collection.mutable

import org.apache.spark.sql.catalyst.{SQLConfHelper, TableIdentifier}
import org.apache.spark.sql.catalyst.analysis.{NoSuchTableException, TableAlreadyExistsException}
import org.apache.spark.sql.catalyst.catalog.{BucketSpec, CatalogDatabase, CatalogTable, CatalogTableType, CatalogUtils, SessionCatalog}
import org.apache.spark.sql.connector.catalog.{CatalogManager, CatalogV2Util, Identifier, NamespaceChange, SupportsNamespaces, Table, TableCatalog, TableChange, V1Table}
import org.apache.spark.sql.connector.catalog.NamespaceChange.RemoveProperty
import org.apache.spark.sql.connector.expressions.{BucketTransform, FieldReference, IdentityTransform, Transform}
import org.apache.spark.sql.errors.{QueryCompilationErrors, QueryExecutionErrors}
import org.apache.spark.sql.execution.datasources.DataSource
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.util.CaseInsensitiveStringMap

/**
 * A [[TableCatalog]] that translates calls to the v1 SessionCatalog.
 */
class V2SessionCatalog(catalog: SessionCatalog)
  extends TableCatalog with SupportsNamespaces with SQLConfHelper {
  import V2SessionCatalog._

  override val defaultNamespace: Array[String] = Array("default")

  override def name: String = CatalogManager.SESSION_CATALOG_NAME

  // This class is instantiated by Spark, so `initialize` method will not be called.
  override def initialize(name: String, options: CaseInsensitiveStringMap): Unit = {}

  override def listTables(namespace: Array[String]): Array[Identifier] = {
    namespace match {
      case Array(db) =>
        catalog
          .listTables(db)
          .map(ident => Identifier.of(ident.database.map(Array(_)).getOrElse(Array()), ident.table))
          .toArray
      case _ =>
        throw QueryCompilationErrors.noSuchNamespaceError(namespace)
    }
  }

  override def loadTable(ident: Identifier): Table = {
    val catalogTable = try {
      catalog.getTableMetadata(ident.asTableIdentifier)
    } catch {
      case _: NoSuchTableException =>
        throw QueryCompilationErrors.noSuchTableError(ident)
    }

    V1Table(catalogTable)
  }

  override def loadTable(ident: Identifier, timestamp: Long): Table = {
    failTimeTravel(ident, loadTable(ident))
  }

  override def loadTable(ident: Identifier, version: String): Table = {
    failTimeTravel(ident, loadTable(ident))
  }

  private def failTimeTravel(ident: Identifier, t: Table): Table = {
    t match {
      case V1Table(catalogTable) =>
        if (catalogTable.tableType == CatalogTableType.VIEW) {
          throw QueryCompilationErrors.viewNotSupportTimeTravelError(
            ident.namespace() :+ ident.name())
        } else {
          throw QueryCompilationErrors.tableNotSupportTimeTravelError(ident)
        }

      case _ => throw QueryCompilationErrors.tableNotSupportTimeTravelError(ident)
    }
  }

  override def invalidateTable(ident: Identifier): Unit = {
    catalog.refreshTable(ident.asTableIdentifier)
  }

  override def createTable(
      ident: Identifier,
      schema: StructType,
      partitions: Array[Transform],
      properties: util.Map[String, String]): Table = {

    val (partitionColumns, maybeBucketSpec) = V2SessionCatalog.convertTransforms(partitions)
    val provider = properties.getOrDefault(TableCatalog.PROP_PROVIDER, conf.defaultDataSourceName)
    val tableProperties = properties.asScala
    val location = Option(properties.get(TableCatalog.PROP_LOCATION))
    val storage = DataSource.buildStorageFormatFromOptions(toOptions(tableProperties.toMap))
        .copy(locationUri = location.map(CatalogUtils.stringToURI))
    val isExternal = properties.containsKey(TableCatalog.PROP_EXTERNAL)
    val tableType = if (isExternal || location.isDefined) {
      CatalogTableType.EXTERNAL
    } else {
      CatalogTableType.MANAGED
    }

    val tableDesc = CatalogTable(
      identifier = ident.asTableIdentifier,
      tableType = tableType,
      storage = storage,
      schema = schema,
      provider = Some(provider),
      partitionColumnNames = partitionColumns,
      bucketSpec = maybeBucketSpec,
      properties = tableProperties.toMap,
      tracksPartitionsInCatalog = conf.manageFilesourcePartitions,
      comment = Option(properties.get(TableCatalog.PROP_COMMENT)))

    try {
      catalog.createTable(tableDesc, ignoreIfExists = false)
    } catch {
      case _: TableAlreadyExistsException =>
        throw QueryCompilationErrors.tableAlreadyExistsError(ident)
    }

    loadTable(ident)
  }

  private def toOptions(properties: Map[String, String]): Map[String, String] = {
    properties.filterKeys(_.startsWith(TableCatalog.OPTION_PREFIX)).map {
      case (key, value) => key.drop(TableCatalog.OPTION_PREFIX.length) -> value
    }.toMap
  }

  override def alterTable(
      ident: Identifier,
      changes: TableChange*): Table = {
    val catalogTable = try {
      catalog.getTableMetadata(ident.asTableIdentifier)
    } catch {
      case _: NoSuchTableException =>
        throw QueryCompilationErrors.noSuchTableError(ident)
    }

    val properties = CatalogV2Util.applyPropertiesChanges(catalogTable.properties, changes)
    val schema = CatalogV2Util.applySchemaChanges(catalogTable.schema, changes)
    val comment = properties.get(TableCatalog.PROP_COMMENT)
    val owner = properties.getOrElse(TableCatalog.PROP_OWNER, catalogTable.owner)
    val location = properties.get(TableCatalog.PROP_LOCATION).map(CatalogUtils.stringToURI)
    val storage = if (location.isDefined) {
      catalogTable.storage.copy(locationUri = location)
    } else {
      catalogTable.storage
    }

    try {
      catalog.alterTable(
        catalogTable.copy(
          properties = properties, schema = schema, owner = owner, comment = comment,
          storage = storage))
    } catch {
      case _: NoSuchTableException =>
        throw QueryCompilationErrors.noSuchTableError(ident)
    }

    loadTable(ident)
  }

  override def dropTable(ident: Identifier): Boolean = {
    try {
      if (loadTable(ident) != null) {
        catalog.dropTable(
          ident.asTableIdentifier,
          ignoreIfNotExists = true,
          purge = true /* skip HDFS trash */)
        true
      } else {
        false
      }
    } catch {
      case _: NoSuchTableException =>
        false
    }
  }

  override def renameTable(oldIdent: Identifier, newIdent: Identifier): Unit = {
    if (tableExists(newIdent)) {
      throw QueryCompilationErrors.tableAlreadyExistsError(newIdent)
    }

    // Load table to make sure the table exists
    loadTable(oldIdent)
    catalog.renameTable(oldIdent.asTableIdentifier, newIdent.asTableIdentifier)
  }

  implicit class TableIdentifierHelper(ident: Identifier) {
    def asTableIdentifier: TableIdentifier = {
      ident.namespace match {
        case Array(db) =>
          TableIdentifier(ident.name, Some(db))
        case other =>
          throw QueryCompilationErrors.requiresSinglePartNamespaceError(other)
      }
    }
  }

  override def namespaceExists(namespace: Array[String]): Boolean = namespace match {
    case Array(db) =>
      catalog.databaseExists(db)
    case _ =>
      false
  }

  override def listNamespaces(): Array[Array[String]] = {
    catalog.listDatabases().map(Array(_)).toArray
  }

  override def listNamespaces(namespace: Array[String]): Array[Array[String]] = {
    namespace match {
      case Array() =>
        listNamespaces()
      case Array(db) if catalog.databaseExists(db) =>
        Array()
      case _ =>
        throw QueryCompilationErrors.noSuchNamespaceError(namespace)
    }
  }

  override def loadNamespaceMetadata(namespace: Array[String]): util.Map[String, String] = {
    namespace match {
      case Array(db) =>
        catalog.getDatabaseMetadata(db).toMetadata

      case _ =>
        throw QueryCompilationErrors.noSuchNamespaceError(namespace)
    }
  }

  override def createNamespace(
      namespace: Array[String],
      metadata: util.Map[String, String]): Unit = namespace match {
    case Array(db) if !catalog.databaseExists(db) =>
      catalog.createDatabase(
        toCatalogDatabase(db, metadata, defaultLocation = Some(catalog.getDefaultDBPath(db))),
        ignoreIfExists = false)

    case Array(_) =>
      throw QueryCompilationErrors.namespaceAlreadyExistsError(namespace)

    case _ =>
      throw QueryExecutionErrors.invalidNamespaceNameError(namespace)
  }

  override def alterNamespace(namespace: Array[String], changes: NamespaceChange*): Unit = {
    namespace match {
      case Array(db) =>
        // validate that this catalog's reserved properties are not removed
        changes.foreach {
          case remove: RemoveProperty
            if CatalogV2Util.NAMESPACE_RESERVED_PROPERTIES.contains(remove.property) =>
            throw QueryExecutionErrors.cannotRemoveReservedPropertyError(remove.property)
          case _ =>
        }

        val metadata = catalog.getDatabaseMetadata(db).toMetadata
        catalog.alterDatabase(
          toCatalogDatabase(db, CatalogV2Util.applyNamespaceChanges(metadata, changes)))

      case _ =>
        throw QueryCompilationErrors.noSuchNamespaceError(namespace)
    }
  }

  override def dropNamespace(namespace: Array[String]): Boolean = namespace match {
    case Array(db) if catalog.databaseExists(db) =>
      if (catalog.listTables(db).nonEmpty) {
        throw QueryExecutionErrors.namespaceNotEmptyError(namespace)
      }
      catalog.dropDatabase(db, ignoreIfNotExists = false, cascade = false)
      true

    case Array(_) =>
      // exists returned false
      false

    case _ =>
      throw QueryCompilationErrors.noSuchNamespaceError(namespace)
  }

  def isTempView(ident: Identifier): Boolean = {
    catalog.isTempView(ident.namespace() :+ ident.name())
  }

  override def toString: String = s"V2SessionCatalog($name)"
}

private[sql] object V2SessionCatalog {

  /**
   * Convert v2 Transforms to v1 partition columns and an optional bucket spec.
   */
  private def convertTransforms(partitions: Seq[Transform]): (Seq[String], Option[BucketSpec]) = {
    val identityCols = new mutable.ArrayBuffer[String]
    var bucketSpec = Option.empty[BucketSpec]

    partitions.map {
      case IdentityTransform(FieldReference(Seq(col))) =>
        identityCols += col

      case BucketTransform(numBuckets, FieldReference(Seq(col))) =>
        bucketSpec = Some(BucketSpec(numBuckets, col :: Nil, Nil))

      case transform =>
        throw QueryExecutionErrors.unsupportedPartitionTransformError(transform)
    }

    (identityCols.toSeq, bucketSpec)
  }

  private def toCatalogDatabase(
      db: String,
      metadata: util.Map[String, String],
      defaultLocation: Option[URI] = None): CatalogDatabase = {
    CatalogDatabase(
      name = db,
      description = metadata.getOrDefault(SupportsNamespaces.PROP_COMMENT, ""),
      locationUri = Option(metadata.get(SupportsNamespaces.PROP_LOCATION))
          .map(CatalogUtils.stringToURI)
          .orElse(defaultLocation)
          .getOrElse(throw QueryExecutionErrors.missingDatabaseLocationError()),
      properties = metadata.asScala.toMap --
        Seq(SupportsNamespaces.PROP_COMMENT, SupportsNamespaces.PROP_LOCATION))
  }

  private implicit class CatalogDatabaseHelper(catalogDatabase: CatalogDatabase) {
    def toMetadata: util.Map[String, String] = {
      val metadata = mutable.HashMap[String, String]()

      catalogDatabase.properties.foreach {
        case (key, value) => metadata.put(key, value)
      }
      metadata.put(SupportsNamespaces.PROP_LOCATION, catalogDatabase.locationUri.toString)
      metadata.put(SupportsNamespaces.PROP_COMMENT, catalogDatabase.description)

      metadata.asJava
    }
  }
}
