/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

// scalastyle:off number.of.types
package com.spotify.scio.bigquery

import java.util.concurrent.ConcurrentHashMap
import java.util.function

import com.google.cloud.bigquery.storage.v1beta1.ReadOptions.TableReadOptions
import com.google.api.services.bigquery.model.{TableReference, TableSchema}
import com.spotify.scio.ScioContext
import com.spotify.scio.bigquery.ExtendedErrorInfo._
import com.spotify.scio.bigquery.client.BigQuery
import com.spotify.scio.bigquery.types.BigQueryType.HasAnnotation
import com.spotify.scio.coders.{Coder, CoderMaterializer}
import com.spotify.scio.io.{ScioIO, Tap, TapOf, TestIO, UnsupportedTap}
import com.spotify.scio.util.ScioUtil
import com.spotify.scio.values.SCollection
import org.apache.beam.sdk.extensions.gcp.options.GcpOptions
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.TypedRead.Method
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.{CreateDisposition, WriteDisposition}
import org.apache.beam.sdk.io.gcp.bigquery.SchemaAndRecord
import org.apache.beam.sdk.io.gcp.{bigquery => beam}
import org.apache.beam.sdk.io.{Compression, TextIO}
import org.apache.beam.sdk.transforms.SerializableFunction

import scala.collection.JavaConverters._
import scala.reflect.ClassTag
import scala.reflect.runtime.universe._

private object Reads {
  private[this] val cache = new ConcurrentHashMap[ScioContext, BigQuery]()

  @inline private def client(sc: ScioContext): BigQuery =
    cache.computeIfAbsent(
      sc,
      new function.Function[ScioContext, BigQuery] {
        override def apply(context: ScioContext): BigQuery = {
          val opts = context.optionsAs[GcpOptions]
          BigQuery(opts.getProject, opts.getGcpCredential)
        }
      }
    )

  private[scio] def bqReadQuery[T](sc: ScioContext)(
    typedRead: beam.BigQueryIO.TypedRead[T],
    sqlQuery: String,
    flattenResults: Boolean = false
  ): SCollection[T] = sc.wrap {
    val bigQueryClient = client(sc)
    if (bigQueryClient.isCacheEnabled) {
      val read = bigQueryClient.query
        .newQueryJob(sqlQuery, flattenResults)
        .map { job =>
          sc.onClose { _ =>
            bigQueryClient.waitForJobs(job)
          }

          typedRead.from(job.table).withoutValidation()
        }

      sc.applyInternal(read.get)
    } else {
      val baseQuery = if (!flattenResults) {
        typedRead.fromQuery(sqlQuery).withoutResultFlattening()
      } else {
        typedRead.fromQuery(sqlQuery)
      }
      val query = if (bigQueryClient.query.isLegacySql(sqlQuery, flattenResults)) {
        baseQuery
      } else {
        baseQuery.usingStandardSql()
      }
      sc.applyInternal(query)
    }
  }

  private[scio] def bqReadStorage[T: ClassTag](sc: ScioContext)(
    typedRead: beam.BigQueryIO.TypedRead[T],
    table: Table,
    selectedFields: List[String] = Nil,
    rowRestriction: String = null
  ): SCollection[T] = sc.wrap {
    val read = typedRead
      .from(table.spec)
      .withMethod(Method.DIRECT_READ)
      .withReadOptions(StorageUtil.tableReadOptions(selectedFields, rowRestriction))
    sc.applyInternal(read)
  }

  private[scio] def avroBigQueryRead[T <: HasAnnotation: ClassTag: TypeTag](sc: ScioContext) = {
    val fn = BigQueryType[T].fromAvro
    beam.BigQueryIO
      .read(new SerializableFunction[SchemaAndRecord, T] {
        override def apply(input: SchemaAndRecord): T = fn(input.getRecord)
      })
      .withCoder(CoderMaterializer.beam(sc, Coder.kryo[T]))
  }

  private[scio] def bqReadTable[T: ClassTag](
    sc: ScioContext
  )(typedRead: beam.BigQueryIO.TypedRead[T], table: TableReference): SCollection[T] =
    sc.wrap(sc.applyInternal(typedRead.from(table)))
}

sealed trait BigQueryIO[T] extends ScioIO[T] {
  override final val tapT = TapOf[T]
}

object BigQueryIO {
  @inline final def apply[T](id: String): BigQueryIO[T] =
    new BigQueryIO[T] with TestIO[T] {
      override def testId: String = s"BigQueryIO($id)"
    }

  @inline final def apply[T](source: Source): BigQueryIO[T] =
    new BigQueryIO[T] with TestIO[T] {
      override def testId: String = source match {
        case t: Table => s"BigQueryIO(${t.spec})"
        case q: Query => s"BigQueryIO(${q.underlying})"
      }
    }
}
object BigQueryTypedSelect {
  object ReadParam {
    private[bigquery] val DefaultFlattenResults = false
  }

  final case class ReadParam private (flattenResults: Boolean = ReadParam.DefaultFlattenResults)
}

final case class BigQueryTypedSelect[T: Coder](
  reader: beam.BigQueryIO.TypedRead[T],
  sqlQuery: Query,
  fromTableRow: TableRow => T
) extends BigQueryIO[T] {
  override type ReadP = BigQueryTypedSelect.ReadParam
  override type WriteP = Nothing // ReadOnly

  override def testId: String = s"BigQueryIO(${sqlQuery.underlying})"

  override protected def read(sc: ScioContext, params: ReadP): SCollection[T] = {
    val rc = reader.withCoder(CoderMaterializer.beam(sc, Coder[T]))
    Reads.bqReadQuery(sc)(rc, sqlQuery.underlying, params.flattenResults)
  }

  override protected def write(data: SCollection[T], params: WriteP): Tap[T] =
    throw new UnsupportedOperationException("BigQuerySelect is read-only")

  override def tap(params: ReadP): Tap[T] = {
    val tableReference = BigQuery
      .defaultInstance()
      .query
      .run(sqlQuery.underlying, flattenResults = params.flattenResults)
    BigQueryTap(tableReference).map(fromTableRow)
  }
}

/**
 * Get an SCollection for a BigQuery SELECT query.
 * Both [[https://cloud.google.com/bigquery/docs/reference/legacy-sql Legacy SQL]] and
 * [[https://cloud.google.com/bigquery/docs/reference/standard-sql/ Standard SQL]] dialects are
 * supported. By default the query dialect will be automatically detected. To override this
 * behavior, start the query string with `#legacysql` or `#standardsql`.
 */
final case class BigQuerySelect(sqlQuery: Query) extends BigQueryIO[TableRow] {
  override type ReadP = BigQuerySelect.ReadParam
  override type WriteP = Nothing // ReadOnly

  private[this] lazy val underlying =
    BigQueryTypedSelect(beam.BigQueryIO.readTableRows(), sqlQuery, identity)

  override def testId: String = s"BigQueryIO(${sqlQuery.underlying})"

  override protected def read(sc: ScioContext, params: ReadP): SCollection[TableRow] =
    sc.read(underlying)(params)

  override protected def write(data: SCollection[TableRow], params: WriteP): Tap[TableRow] =
    throw new UnsupportedOperationException("BigQuerySelect is read-only")

  override def tap(params: ReadP): Tap[TableRow] = underlying.tap(params)
}

object BigQuerySelect {
  type ReadParam = BigQueryTypedSelect.ReadParam
  val ReadParam = BigQueryTypedSelect.ReadParam

  @inline final def apply(sqlQuery: String): BigQuerySelect = new BigQuerySelect(Query(sqlQuery))
}

/**
 * Get an IO for a BigQuery table.
 */
final case class BigQueryTable(table: Table) extends BigQueryIO[TableRow] {
  override type ReadP = Unit
  override type WriteP = BigQueryTable.WriteParam

  override def testId: String = s"BigQueryIO(${table.spec})"

  override protected def read(sc: ScioContext, params: ReadP): SCollection[TableRow] =
    Reads.bqReadTable(sc)(beam.BigQueryIO.readTableRows(), table.ref)

  override protected def write(data: SCollection[TableRow], params: WriteP): Tap[TableRow] = {
    var transform = beam.BigQueryIO.writeTableRows().to(table.ref)
    if (params.schema != null) {
      transform = transform.withSchema(params.schema)
    }
    if (params.createDisposition != null) {
      transform = transform.withCreateDisposition(params.createDisposition)
    }
    if (params.writeDisposition != null) {
      transform = transform.withWriteDisposition(params.writeDisposition)
    }
    if (params.tableDescription != null) {
      transform = transform.withTableDescription(params.tableDescription)
    }
    if (params.timePartitioning != null) {
      transform = transform.withTimePartitioning(params.timePartitioning.asJava)
    }
    val wr = data.applyInternal(transform)

    transform = params.extendedErrorInfo match {
      case Disabled => transform
      case Enabled  => transform.withExtendedErrorInfo()
    }
    params.insertErrorTransform(params.extendedErrorInfo.coll(data.context, wr))

    if (params.writeDisposition == WriteDisposition.WRITE_APPEND) {
      UnsupportedTap("BigQuery with append does not support tap")
    } else {
      BigQueryTap(table.ref)
    }
  }

  override def tap(read: ReadP): Tap[TableRow] = BigQueryTap(table.ref)
}

object BigQueryTable {
  sealed trait WriteParam {
    val schema: TableSchema
    val writeDisposition: WriteDisposition
    val createDisposition: CreateDisposition
    val tableDescription: String
    val timePartitioning: TimePartitioning
    val extendedErrorInfo: ExtendedErrorInfo
    val insertErrorTransform: SCollection[extendedErrorInfo.Info] => Unit
  }

  object WriteParam {
    private[bigquery] val DefaultSchema: TableSchema = null
    private[bigquery] val DefaultWriteDisposition: WriteDisposition = null
    private[bigquery] val DefaultCreateDisposition: CreateDisposition = null
    private[bigquery] val DefaultTableDescription: String = null
    private[bigquery] val DefaultTimePartitioning: TimePartitioning = null
    private[bigquery] val DefaultExtendedErrorInfo: ExtendedErrorInfo = ExtendedErrorInfo.Disabled
    private[bigquery] val DefaultInsertErrorTransform
      : SCollection[DefaultExtendedErrorInfo.Info] => Unit = sc => {
      // A NoOp on the failed inserts, so that we don't have DropInputs (UnconsumedReads)
      // in the pipeline graph.
      sc.withName("DropFailedInserts").map(_ => ())
      ()
    }

    @inline final def apply(
      s: TableSchema,
      wd: WriteDisposition,
      cd: CreateDisposition,
      td: String,
      tp: TimePartitioning,
      ei: ExtendedErrorInfo
    )(it: SCollection[ei.Info] => Unit): WriteParam = new WriteParam {
      val schema: TableSchema = s
      val writeDisposition: WriteDisposition = wd
      val createDisposition: CreateDisposition = cd
      val tableDescription: String = td
      val timePartitioning: TimePartitioning = tp
      val extendedErrorInfo: ei.type = ei
      val insertErrorTransform: SCollection[extendedErrorInfo.Info] => Unit = it
    }

    @inline final def apply(
      s: TableSchema = DefaultSchema,
      wd: WriteDisposition = DefaultWriteDisposition,
      cd: CreateDisposition = DefaultCreateDisposition,
      td: String = DefaultTableDescription,
      tp: TimePartitioning = DefaultTimePartitioning
    ): WriteParam = apply(s, wd, cd, td, tp, DefaultExtendedErrorInfo)(DefaultInsertErrorTransform)

  }

  @deprecated("this method will be removed; use apply(Table.Ref(table)) instead", "Scio 0.8")
  @inline final def apply(table: TableReference): BigQueryTable =
    BigQueryTable(Table.Ref(table))

  @deprecated("this method will be removed; use apply(Table.Spec(table)) instead", "Scio 0.8")
  @inline final def apply(spec: String): BigQueryTable =
    BigQueryTable(Table.Spec(spec))
}

/**
 * Get an IO for a BigQuery table using the storage API.
 */
final case class BigQueryStorage(table: Table) extends BigQueryIO[TableRow] {
  override type ReadP = BigQueryStorage.ReadParam
  override type WriteP = Nothing // ReadOnly

  override def testId: String = s"BigQueryIO(${table.spec})"

  override protected def read(sc: ScioContext, params: ReadP): SCollection[TableRow] =
    Reads.bqReadStorage(sc)(
      beam.BigQueryIO.readTableRows(),
      table,
      params.selectFields,
      params.rowRestriction
    )

  override protected def write(data: SCollection[TableRow], params: WriteP): Tap[TableRow] =
    throw new UnsupportedOperationException("BigQueryStorage is read-only")

  override def tap(read: ReadP): Tap[TableRow] = {
    val readOptions = TableReadOptions
      .newBuilder()
      .setRowRestriction(read.rowRestriction)
      .addAllSelectedFields(read.selectFields.asJava)
      .build()
    BigQueryStorageTap(table, readOptions)
  }
}

object BigQueryStorage {
  final case class ReadParam(selectFields: List[String], rowRestriction: String)

  @deprecated("this method will be removed; use apply(Table.Ref(table)) instead", "Scio 0.8")
  @inline final def apply(table: TableReference): BigQueryStorage =
    BigQueryStorage(Table.Ref(table))

  @deprecated("this method will be removed; use apply(Table.Spec(table)) instead", "Scio 0.8")
  @inline final def apply(spec: String): BigQueryStorage =
    BigQueryStorage(Table.Spec(spec))
}

final case class BigQueryStorageSelect(sqlQuery: Query) extends BigQueryIO[TableRow] {
  override type ReadP = Unit
  override type WriteP = Nothing // ReadOnly

  private[this] lazy val underlying =
    BigQueryTypedSelect(
      beam.BigQueryIO.readTableRows().withMethod(Method.DIRECT_READ),
      sqlQuery,
      identity
    )

  override def testId: String = s"BigQueryIO(${sqlQuery.underlying})"

  override protected def read(sc: ScioContext, params: ReadP): SCollection[TableRow] =
    sc.read(underlying)(BigQueryTypedSelect.ReadParam())

  override protected def write(data: SCollection[TableRow], params: WriteP): Tap[TableRow] =
    throw new UnsupportedOperationException("BigQuerySelect is read-only")

  override def tap(params: ReadP): Tap[TableRow] = underlying.tap(BigQueryTypedSelect.ReadParam())
}

/**
 * Get an IO for a BigQuery TableRow JSON file.
 */
final case class TableRowJsonIO(path: String) extends ScioIO[TableRow] {
  override type ReadP = Unit
  override type WriteP = TableRowJsonIO.WriteParam
  override final val tapT = TapOf[TableRow]

  override protected def read(sc: ScioContext, params: ReadP): SCollection[TableRow] =
    sc.wrap(sc.applyInternal(TextIO.read().from(path)))
      .map(e => ScioUtil.jsonFactory.fromString(e, classOf[TableRow]))

  override protected def write(data: SCollection[TableRow], params: WriteP): Tap[TableRow] = {
    data
      .map(e => ScioUtil.jsonFactory.toString(e))
      .applyInternal(data.textOut(path, ".json", params.numShards, params.compression))
    tap(())
  }

  override def tap(read: ReadP): Tap[TableRow] =
    TableRowJsonTap(ScioUtil.addPartSuffix(path))
}

object TableRowJsonIO {
  object WriteParam {
    private[bigquery] val DefaultNumShards = 0
    private[bigquery] val DefaultCompression = Compression.UNCOMPRESSED
  }

  final case class WriteParam private (
    numShards: Int = WriteParam.DefaultNumShards,
    compression: Compression = WriteParam.DefaultCompression
  )
}

object BigQueryTyped {
  import com.spotify.scio.bigquery.{Table => STable}

  @annotation.implicitNotFound(
    """
    Can't find annotation for type ${T}.
    Make sure this class is annotated with BigQueryType.fromStorage, BigQueryType.fromTable or
    BigQueryType.fromQuery.
    Alternatively, use BigQueryTyped.Storage("<table>"), BigQueryTyped.Table("<table>"), or
    BigQueryTyped.Query("<query>") to get a ScioIO instance.
  """
  )
  sealed trait IO[T <: HasAnnotation] {
    type F[_ <: HasAnnotation] <: ScioIO[_]
    def impl: F[T]
  }

  // scalastyle:off structural.type
  object IO {
    type Aux[T <: HasAnnotation, F0[_ <: HasAnnotation] <: ScioIO[_]] =
      IO[T] { type F[A <: HasAnnotation] = F0[A] }

    implicit def tableIO[T <: HasAnnotation: ClassTag: TypeTag: Coder](
      implicit t: BigQueryType.Table[T]
    ): Aux[T, Table] =
      new IO[T] {
        type F[A <: HasAnnotation] = Table[A]
        def impl: Table[T] = Table(STable.Spec(t.table))
      }

    implicit def queryIO[T <: HasAnnotation: ClassTag: TypeTag: Coder](
      implicit t: BigQueryType.Query[T]
    ): Aux[T, Select] =
      new IO[T] {
        type F[A <: HasAnnotation] = Select[A]
        def impl: Select[T] = Select(Query(t.query))
      }

    implicit def storageIO[T <: HasAnnotation: ClassTag: TypeTag: Coder](
      implicit t: BigQueryType.StorageOptions[T]
    ): Aux[T, Storage] =
      new IO[T] {
        type F[A <: HasAnnotation] = Storage[A]
        def impl: Storage[T] = Storage(STable.Spec(t.table))
      }
  }
  // scalastyle:on structural.type

  /**
   * Get a typed SCollection for a BigQuery table or a SELECT query.
   *
   * Note that `T` must be annotated with
   * [[com.spotify.scio.bigquery.types.BigQueryType.fromTable BigQueryType.fromStorage]],
   * [[com.spotify.scio.bigquery.types.BigQueryType.fromTable BigQueryType.fromTable]], or
   * [[com.spotify.scio.bigquery.types.BigQueryType.fromQuery BigQueryType.fromQuery]]
   *
   * The source (table) specified in the annotation will be used
   */
  @inline final def apply[T <: HasAnnotation: ClassTag: TypeTag](implicit t: IO[T]): t.F[T] =
    t.impl

  /**
   * Get a typed SCollection for a BigQuery SELECT query.
   *
   * Both [[https://cloud.google.com/bigquery/docs/reference/legacy-sql Legacy SQL]] and
   * [[https://cloud.google.com/bigquery/docs/reference/standard-sql/ Standard SQL]] dialects are
   * supported. By default the query dialect will be automatically detected. To override this
   * behavior, start the query string with `#legacysql` or `#standardsql`.
   */
  final case class Select[T <: HasAnnotation: ClassTag: TypeTag: Coder](query: Query)
      extends BigQueryIO[T] {
    override type ReadP = Unit
    override type WriteP = Nothing // ReadOnly

    private[this] lazy val underlying = {
      val fromAvro = BigQueryType[T].fromAvro
      val fromTableRow = BigQueryType[T].fromTableRow
      val reader = beam.BigQueryIO
        .read(new SerializableFunction[SchemaAndRecord, T] {
          override def apply(input: SchemaAndRecord): T = fromAvro(input.getRecord)
        })
      BigQueryTypedSelect(reader, query, fromTableRow)(Coder.kryo[T])
    }

    override def testId: String = s"BigQueryIO(${query.underlying})"

    override protected def read(sc: ScioContext, params: ReadP): SCollection[T] =
      sc.read(underlying)(BigQueryTypedSelect.ReadParam())

    override protected def write(data: SCollection[T], params: WriteP): Tap[T] =
      throw new UnsupportedOperationException("Select queries are read-only")

    override def tap(params: ReadP): Tap[T] = underlying.tap(BigQueryTypedSelect.ReadParam())
  }

  object Select {
    @inline final def apply[T <: HasAnnotation: ClassTag: TypeTag: Coder](
      query: String
    ): Select[T] = new Select[T](Query(query))
  }

  /**
   * Get a typed SCollection for a BigQuery table.
   */
  final case class Table[T <: HasAnnotation: ClassTag: TypeTag: Coder](table: STable)
      extends BigQueryIO[T] {
    override type ReadP = Unit
    override type WriteP = Table.WriteParam

    private lazy val bqt = BigQueryType[T]

    override def testId: String = s"BigQueryIO(${table.spec})"

    override protected def read(sc: ScioContext, params: ReadP): SCollection[T] = {
      @inline def typedRead(sc: ScioContext) = Reads.avroBigQueryRead[T](sc)
      Reads.bqReadTable(sc)(typedRead(sc), table.ref)
    }

    override protected def write(data: SCollection[T], params: WriteP): Tap[T] = {
      val initialTfName = data.tfName
      val rows =
        data
          .map(bqt.toTableRow)
          .withName(s"$initialTfName$$Write")

      val ps =
        BigQueryTable.WriteParam(
          bqt.schema,
          params.writeDisposition,
          params.createDisposition,
          bqt.tableDescription.orNull,
          params.timePartitioning,
          params.extendedErrorInfo
        )(params.insertErrorTransform)

      rows.write(BigQueryTable(table))(ps).underlying.map(bqt.fromTableRow)
    }

    override def tap(read: ReadP): Tap[T] =
      BigQueryTable(table)
        .tap(read)
        .map(bqt.fromTableRow)
  }

  object Table {

    sealed trait WriteParam {
      val writeDisposition: WriteDisposition
      val createDisposition: CreateDisposition
      val timePartitioning: TimePartitioning
      val extendedErrorInfo: ExtendedErrorInfo
      val insertErrorTransform: SCollection[extendedErrorInfo.Info] => Unit
    }

    object WriteParam {
      private[bigquery] val DefaultWriteDisposition: WriteDisposition = null
      private[bigquery] val DefaultCreateDisposition: CreateDisposition = null
      private[bigquery] val DefaultTimePartitioning: TimePartitioning = null
      private[bigquery] val DefaultExtendedErrorInfo: ExtendedErrorInfo = ExtendedErrorInfo.Disabled
      private[bigquery] val DefaultInsertErrorTransform
        : SCollection[DefaultExtendedErrorInfo.Info] => Unit = sc => {
        // A NoOp on the failed inserts, so that we don't have DropInputs (UnconsumedReads)
        // in the pipeline graph.
        sc.withName("DropFailedInserts").map(_ => ())
        ()
      }

      @inline final def apply(
        wd: WriteDisposition,
        cd: CreateDisposition,
        tp: TimePartitioning,
        ei: ExtendedErrorInfo
      )(it: SCollection[ei.Info] => Unit): WriteParam = new WriteParam {
        val writeDisposition: WriteDisposition = wd
        val createDisposition: CreateDisposition = cd
        val timePartitioning: TimePartitioning = tp
        val extendedErrorInfo: ei.type = ei
        val insertErrorTransform: SCollection[extendedErrorInfo.Info] => Unit = it
      }

      @inline final def apply(
        wd: WriteDisposition = DefaultWriteDisposition,
        cd: CreateDisposition = DefaultCreateDisposition,
        tp: TimePartitioning = DefaultTimePartitioning
      ): WriteParam = apply(wd, cd, tp, DefaultExtendedErrorInfo)(DefaultInsertErrorTransform)
    }

    @deprecated("this method will be removed; use apply(Table.Ref(table)) instead", "Scio 0.8")
    @inline
    final def apply[T <: HasAnnotation: ClassTag: TypeTag: Coder](spec: String): Table[T] =
      Table[T](STable.Spec(spec))

    @deprecated("this method will be removed; use apply(Table.Spec(table)) instead", "Scio 0.8")
    @inline
    final def apply[T <: HasAnnotation: ClassTag: TypeTag: Coder](table: TableReference): Table[T] =
      Table[T](STable.Ref(table))
  }

  /**
   * Get a typed SCollection for a BigQuery table using the storage API.
   */
  final case class Storage[T <: HasAnnotation: ClassTag: TypeTag: Coder](table: STable)
      extends BigQueryIO[T] {
    override type ReadP = Storage.ReadParam
    override type WriteP = Nothing // ReadOnly

    override def testId: String = s"BigQueryIO(${table.spec})"

    override protected def read(sc: ScioContext, params: ReadP): SCollection[T] = {
      @inline def typedRead(sc: ScioContext) = Reads.avroBigQueryRead[T](sc)
      Reads.bqReadStorage(sc)(typedRead(sc), table, params.selectFields, params.rowRestriction)
    }

    override protected def write(data: SCollection[T], params: WriteP): Tap[T] =
      throw new UnsupportedOperationException("Storage API is read-only")

    override def tap(read: ReadP): Tap[T] = {
      val fn = BigQueryType[T].fromTableRow
      val readOptions = TableReadOptions
        .newBuilder()
        .setRowRestriction(read.rowRestriction)
        .addAllSelectedFields(read.selectFields.asJava)
        .build()
      BigQueryStorageTap(table, readOptions).map(fn)
    }
  }

  final case class StorageQuery[T <: HasAnnotation: ClassTag: TypeTag: Coder](sqlQuery: Query)
      extends BigQueryIO[T] {
    override type ReadP = Unit
    override type WriteP = Nothing // ReadOnly

    private[this] lazy val underlying = {
      val fromAvro = BigQueryType[T].fromAvro
      val fromTableRow = BigQueryType[T].fromTableRow
      val reader = beam.BigQueryIO
        .read(new SerializableFunction[SchemaAndRecord, T] {
          override def apply(input: SchemaAndRecord): T = fromAvro(input.getRecord)
        })
        .withMethod(Method.DIRECT_READ)
      BigQueryTypedSelect(reader, sqlQuery, fromTableRow)(Coder.kryo[T])
    }

    override def testId: String = s"BigQueryIO($sqlQuery)"

    override protected def read(sc: ScioContext, params: ReadP): SCollection[T] =
      sc.read(underlying)(BigQueryTypedSelect.ReadParam())

    override protected def write(data: SCollection[T], params: WriteP): Tap[T] =
      throw new UnsupportedOperationException("Storage API is read-only")

    override def tap(read: ReadP): Tap[T] = underlying.tap(BigQueryTypedSelect.ReadParam())
  }

  object Storage {
    type ReadParam = BigQueryStorage.ReadParam
    val ReadParam = BigQueryStorage.ReadParam
  }

  // scalastyle:off cyclomatic.complexity
  private[scio] def dynamic[T <: HasAnnotation: ClassTag: TypeTag: Coder](
    newSource: Option[Source]
  ): ScioIO.ReadOnly[T, Unit] = {
    val bqt = BigQueryType[T]
    newSource match {
      // newSource is missing, T's companion object must have either table or query
      // The case where newSource is null is only there
      // for legacy support and should not exists once
      // BigQueryScioContext.typedBigQuery is removed
      case None if bqt.isTable =>
        val table = STable.Spec(bqt.table.get)
        ScioIO.ro[T](Table[T](table))
      case None if bqt.isQuery =>
        val query = Query(bqt.query.get)
        Select[T](query)
      case Some(s: STable) =>
        ScioIO.ro(Table[T](s))
      case Some(s: Query) =>
        Select[T](s)
      case _ =>
        throw new IllegalArgumentException(s"Missing table or query field in companion object")
    }
  }
  // scalastyle:on cyclomatic.complexity
}
// scalastyle:on number.of.types
