package org.apache.spark.sql.streaming

import org.apache.spark.Logging
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.sources.SchemaRelationProvider
import org.apache.spark.sql.types.StructType
import org.apache.spark.storage.StorageLevel
import org.apache.spark.streaming.dstream.DStream
import org.apache.spark.util.Utils

/**
  * Created by ymahajan on 25/09/15.
  */

final class SocketStreamSource extends SchemaRelationProvider {
  override def createRelation(sqlContext: SQLContext,
                              options: Map[String, String],
                              schema: StructType): SocketStreamRelation = {
    new SocketStreamRelation(sqlContext, options, schema)
  }
}

case class SocketStreamRelation(@transient override val sqlContext: SQLContext,
                                options: Map[String, String],
                                override val schema: StructType)
  extends StreamBaseRelation with Logging with StreamPlan with Serializable {

  val hostname: String = options.get("hostname").get // .getOrElse("localhost")

  val port: Int = options.get("port").map(_.toInt).get // .getOrElse(9999)
  // TODO: Yogesh, revisit these defaults

  val storageLevel = options.get("storageLevel")
    .map(StorageLevel.fromString)
    .getOrElse(StorageLevel.MEMORY_AND_DISK_SER_2)

  import scala.reflect.runtime.{universe => ru}

  @transient val context = StreamingCtxtHolder.streamingContext
  val CONVERTER = "converter"

  @transient private val socketStream =
    if (options.exists(_._1 == CONVERTER)) {
      val converter = Utils.getContextOrSparkClassLoader.loadClass(
        options(CONVERTER)).newInstance().asInstanceOf[StreamConverter]
      val clazz: Class[_] = converter.getTargetType
      val mirror = ru.runtimeMirror(clazz.getClassLoader)
      val sym = mirror.staticClass(clazz.getName) // obtain class symbol for `c`
      val tpe = sym.selfType // obtain type object for `c`
      val tt = ru.TypeTag[Product](mirror, new reflect.api.TypeCreator {
          def apply[U <: reflect.api.Universe with Singleton](m:
                                                              reflect.api.Mirror[U]) = {
            assert(m eq mirror, s"TypeTag[$tpe] defined in $mirror " +
              s"cannot be migrated to $m.")
            tpe.asInstanceOf[U#Type]
          }
        })
      context.socketStream(hostname, port, converter.convert,
        storageLevel)
    }
    else {
      context.socketTextStream(hostname, port,
        storageLevel)
    }

  private val streamToRows = {
    try {
      val clz = Utils.getContextOrSparkClassLoader.loadClass(options("streamToRows"))
      clz.newInstance().asInstanceOf[StreamToRowsConverter]
    } catch {
      case e: Exception => sys.error(s"Failed to load class : ${e.toString}")
    }
  }

  @transient val stream: DStream[InternalRow] = {
    socketStream.flatMap(streamToRows.toRows)
  }
}