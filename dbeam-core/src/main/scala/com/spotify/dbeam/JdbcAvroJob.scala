/*
 * Copyright 2017 Spotify AB.
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

package com.spotify.dbeam

import java.sql.Connection

import com.spotify.dbeam.options.{JdbcConnectionConfiguration, JdbcExportArgs}
import org.apache.avro.Schema
import org.apache.beam.sdk.metrics.Metrics
import org.apache.beam.sdk.options.PipelineOptions
import org.apache.beam.sdk.transforms.{Create, MapElements, PTransform, SerializableFunction}
import org.apache.beam.sdk.values.{PCollection, POutput, TypeDescriptors}
import org.apache.beam.sdk.{Pipeline, PipelineResult}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

object JdbcAvroJob {
  val log: Logger = LoggerFactory.getLogger(JdbcAvroJob.getClass)

  /**
    * Generate Avro schema by reading one row
    * Also save schema to output target and expose time to generate schema as a Beam counter
    */
  def createSchema(p: Pipeline, args: JdbcExportArgs): Schema = {
    val startTimeMillis: Long = System.currentTimeMillis()
    val connection: Connection = args.createConnection()
    val avroDoc = args.avroDoc.getOrElse(s"Generate schema from JDBC ResultSet from " +
      s"${args.tableName} ${connection.getMetaData.getURL}")
    val generatedSchema: Schema = JdbcAvroSchema.createSchemaByReadingOneRow(
      connection, args.tableName, args.avroSchemaNamespace, avroDoc, args.useAvroLogicalTypes)
    val elapsedTimeSchema: Long = System.currentTimeMillis() - startTimeMillis
    log.info(s"Elapsed time to schema ${elapsedTimeSchema / 1000.0} seconds")

    val cnt = Metrics.counter(this.getClass().getCanonicalName(), "schemaElapsedTimeMs");
    p.apply("ExposeSchemaCountersSeed",
      Create.of(Seq(Integer.valueOf(0)).asJava).withType(TypeDescriptors.integers()))
      .apply("ExposeSchemaCounters",
        MapElements.into(TypeDescriptors.integers()).via(
          new SerializableFunction[Integer, Integer]() {
            override def apply(input: Integer): Integer = {
              cnt.inc(elapsedTimeSchema)
              input
            }
          }
        ))
    generatedSchema
  }

  /**
    * Creates Beam transform to read data from JDBC and save to Avro, in a single step
    */
  def jdbcAvroTransform(output: String,
                        options: JdbcExportArgs,
                        generatedSchema: Schema): PTransform[PCollection[String], _ <: POutput] = {
    val jdbcAvroOptions = JdbcAvroIO.JdbcAvroOptions.create(
      JdbcConnectionConfiguration.create(options.driverClass, options.connectionUrl)
        .withUsername(options.username)
        .withPassword(options.password),
      options.fetchSize,
      options.avroCodec)
    JdbcAvroIO.Write.createWrite(
      output,
      ".avro",
      generatedSchema,
      jdbcAvroOptions
    )
  }

  def prepareExport(p: Pipeline, args: JdbcExportArgs, output: String): Unit = {
    require(output != null && output != "", "'output' must be defined")
    val generatedSchema: Schema = createSchema(p, args)
    BeamHelper.saveStringOnSubPath(output, "/_AVRO_SCHEMA.avsc", generatedSchema.toString(true))

    val queries: Iterable[String] = args.buildQueries()
    queries.zipWithIndex.foreach { case (q: String, n: Int) =>
      BeamHelper.saveStringOnSubPath(output, s"/_queries/query_${n}.sql", q)
    }
    log.info(s"Running queries: $queries")

    p.apply("JdbcQueries", Create.of(queries.asJava))
      .apply("JdbcAvroSave", jdbcAvroTransform(output, args, generatedSchema))
  }

  def runExport(opts: PipelineOptions, args: JdbcExportArgs, output: String): Unit = {
    val pipeline: Pipeline = Pipeline.create(opts)
    prepareExport(pipeline, args, output)
    val result: PipelineResult = BeamHelper.waitUntilDone(pipeline.run())
    BeamHelper.saveMetrics(MetricsHelper.getMetrics(result), output)
  }

  def main(cmdlineArgs: Array[String]): Unit = {
    val (opts: PipelineOptions, jdbcExportArgs: JdbcExportArgs, output: String) =
      JdbcExportArgs.parseOptions(cmdlineArgs)
    runExport(opts, jdbcExportArgs, output)
  }
}
