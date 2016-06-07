/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package org.apache.spark.sql.internal


import org.apache.spark.rdd.RDD
import org.apache.spark.sql.aqp.SnappyContextFunctions
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.Analyzer
import org.apache.spark.sql.catalyst.parser.ParserInterface
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.catalyst.rules.RuleExecutor
import org.apache.spark.sql.execution.datasources.{ResolveDataSource, StoreDataSourceStrategy}
import org.apache.spark.sql.execution.exchange.EnsureRequirements
import org.apache.spark.sql.execution.python.ExtractPythonUDFs
import org.apache.spark.sql.execution.{SparkSqlParser, QueryExecution, SparkPlan, SparkPlanner}
import org.apache.spark.sql.hive.{ExternalTableType, SnappyStoreHiveCatalog, QualifiedTableName}
import org.apache.spark.sql.sources.{BaseRelation, StoreStrategy}
import org.apache.spark.sql.streaming.StreamBaseRelation
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.{execution => sparkexecution, _}
import org.apache.spark.util.Utils


class SnappySessionState (sparkSession: SnappySession)
    extends SessionState(sparkSession) with SnappyContextFunctions{

  self =>

  override def planner: SparkPlanner = new DefaultPlanner

  override lazy val sqlParser: ParserInterface =
    new SnappySqlParser(conf, getSQLDialect(sparkSession))

  override def executePlan(plan: LogicalPlan): QueryExecution =
    new sparkexecution.QueryExecution(catalog.sparkSession, plan)

  @transient
  override protected[sql] lazy val analyzer: Analyzer =  new Analyzer(catalog, catalog.conf) {
    override val extendedResolutionRules =
      ExtractPythonUDFs ::
          PreInsertCheckCastAndRename ::
          new ResolveDataSource(catalog.sparkSession) :: Nil

    override val extendedCheckRules = Seq(
      sparkexecution.datasources.PreWriteCheck(catalog.conf, catalog), PrePutCheck)
  }

  /**
   * Internal catalog for managing table and database states.
   */
  override lazy val catalog = new SnappySessionCatalog(
    sparkSession.sharedState.externalCatalog,
    sparkSession,
    functionResourceLoader,
    functionRegistry,
    conf,
    newHadoopConf())


  class DefaultPlanner
      extends SparkPlanner(sparkSession.sparkContext, conf, experimentalMethods.extraStrategies)
      with SnappyStrategies {

    val sampleSnappyCase: PartialFunction[LogicalPlan, Seq[SparkPlan]] = {
      case _ => Nil
    }
    val sampleStreamCase: PartialFunction[LogicalPlan, Seq[SparkPlan]] = {
      case _ => Nil
    }

    // TODO temporary flag till we determine every thing works fine with the optimizations
    val storeOptimization =  conf.getConfString(
      "snappy.store.optimization", "true").toBoolean

    val storeOptimizedRules: Seq[Strategy] = if (storeOptimization)
      Seq(StoreDataSourceStrategy, LocalJoinStrategies)
    else Nil

    override def strategies: Seq[Strategy] =
      Seq(SnappyStrategies,
        StreamDDLStrategy(sampleStreamCase),
        StoreStrategy, StreamQueryStrategy) ++
          storeOptimizedRules ++
          super.strategies
  }

  def clear(): Unit = {}
  def postRelationCreation(relation: BaseRelation, snc: SnappyContext): Unit ={}

  def getAQPRuleExecutor(sqlContext: SQLContext): RuleExecutor[SparkPlan] =
    new RuleExecutor[SparkPlan] {
      val batches = Seq(
        Batch("Add exchange", Once, EnsureRequirements(sqlContext.conf)),
        Batch("Add row converters", Once, EnsureRowFormats)
      )
    }


  override def registerAQPErrorFunctions(session: SnappySession){}

  override def createTopK(session: SnappySession, tableName: String,
      keyColumnName: String, schema: StructType,
      topkOptions: Map[String, String], ifExists: Boolean): Unit =
    throw new UnsupportedOperationException("missing aqp jar")

  override def dropTopK(session: SnappySession, topKName: String): Unit =
    throw new UnsupportedOperationException("missing aqp jar")

  def insertIntoTopK(session: SnappySession, rows: RDD[Row],
      topKName: QualifiedTableName, time: Long): Unit =
    throw new UnsupportedOperationException("missing aqp jar")

  override def queryTopK(session: SnappySession, topKName: String,
      startTime: String, endTime: String, k: Int): DataFrame =
    throw new UnsupportedOperationException("missing aqp jar")

  override def queryTopK(session: SnappySession, topK: String,
      startTime: Long, endTime: Long, k: Int): DataFrame =
    throw new UnsupportedOperationException("missing aqp jar")

  override def queryTopKRDD(session: SnappySession, topK: String,
      startTime: String, endTime: String, schema: StructType): RDD[InternalRow] =
    throw new UnsupportedOperationException("missing aqp jar")

  protected[sql] def collectSamples(session: SnappySession, rows: RDD[Row],
      aqpTables: Seq[String], time: Long): Unit =
    throw new UnsupportedOperationException("missing aqp jar")

  def createSampleDataFrameContract(session: SnappySession, df: DataFrame,
      logicalPlan: LogicalPlan): SampleDataFrameContract =
    throw new UnsupportedOperationException("missing aqp jar")

  def convertToStratifiedSample(options: Map[String, Any], session: SnappySession,
      logicalPlan: LogicalPlan): LogicalPlan =
    throw new UnsupportedOperationException("missing aqp jar")

  def isStratifiedSample(logicalPlan: LogicalPlan): Boolean =
    throw new UnsupportedOperationException("missing aqp jar")

  def withErrorDataFrame(df: DataFrame, error: Double,
      confidence: Double): DataFrame =
    throw new UnsupportedOperationException("missing aqp jar")



  override def getSQLDialect(session: SnappySession): ParserDialect = {
    try {
      val clazz = Utils.classForName(
        "org.apache.spark.sql.SnappyExtendedParserDialect")
      clazz.getConstructor(classOf[SnappySession]).newInstance(session)
          .asInstanceOf[ParserDialect]
    } catch {
      case _: Exception =>
        new SnappyParserDialect(session)
    }
  }

  def aqpTablePopulator(session: SnappySession): Unit = {
    // register blank tasks for the stream tables so that the streams start
    val catalog = context.catalog
    catalog.getDataSourceRelations[StreamBaseRelation](Seq(ExternalTableType
        .Stream), None).foreach(_.rowStream.foreachRDD(rdd => Unit))
  }

  def getSnappyCatalog(session: SnappySession): SnappyStoreHiveCatalog = {
    SnappyStoreHiveCatalog.closeCurrent()
    new SnappyStoreHiveCatalog(context)
  }
  def getSnappyDDLParser(session: SnappySession,
      planGenerator: String => LogicalPlan): DDLParser =
    new SnappyDDLParser(context.conf.caseSensitiveAnalysis, planGenerator)
}
