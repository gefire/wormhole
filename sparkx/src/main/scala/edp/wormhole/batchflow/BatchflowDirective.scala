/*-
 * <<
 * wormhole
 * ==
 * Copyright (C) 2016 - 2017 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */


package edp.wormhole.batchflow

import com.alibaba.fastjson.JSON
import edp.wormhole.common.util.{DateUtils, JsonUtils}
import edp.wormhole.common.{FeedbackPriority, KVConfig}
import edp.wormhole.core._
import edp.wormhole.kafka.WormholeKafkaProducer
import edp.wormhole.memorystorage.ConfMemoryStorage
import edp.wormhole.sinks.SinkProcessConfig
import edp.wormhole.swifts.parse.{ParseSwiftsSql, SwiftsProcessConfig}
import edp.wormhole.swifts.validity.ValidityConfig
import edp.wormhole.ums.UmsProtocolUtils.feedbackDirective
import edp.wormhole.ums.{Ums, UmsFeedbackStatus, UmsFieldType, UmsSysField}

import scala.collection.mutable

object BatchflowDirective extends Directive {

  private def registerFlowStartDirective(sourceNamespace: String, fullsinkNamespace: String, streamId: Long, directiveId: Long,
                                         swiftsStr: String, sinksStr: String, feedbackTopicName: String, brokers: String, consumptionDataStr: String): Unit = {
    val consumptionDataMap = mutable.HashMap.empty[String, Boolean]
    val consumption = JSON.parseObject(consumptionDataStr)
    val initial = consumption.getString(InputDataRequirement.INITIAL.toString).trim.toLowerCase.toBoolean
    val increment = consumption.getString(InputDataRequirement.INCREMENT.toString).trim.toLowerCase.toBoolean
    val batch = consumption.getString(InputDataRequirement.BATCH.toString).trim.toLowerCase.toBoolean
    consumptionDataMap(InputDataRequirement.INITIAL.toString) = initial
    consumptionDataMap(InputDataRequirement.INCREMENT.toString) = increment
    consumptionDataMap(InputDataRequirement.BATCH.toString) = batch
    val swiftsProcessConfig: Option[SwiftsProcessConfig] = if (swiftsStr != null) {
      val swifts = JSON.parseObject(swiftsStr)
      if (swifts.size() > 0) {
        val validity = if (swifts.containsKey("validity") && swifts.getString("validity").trim.nonEmpty && swifts.getJSONObject("validity").size > 0) swifts.getJSONObject("validity") else null
        var validityConfig: Option[ValidityConfig] = None
        if (validity != null) {
          val check_columns = validity.getString("check_columns").trim.toLowerCase
          val check_rule = validity.getString("check_rule").trim.toLowerCase
          val rule_mode = validity.getString("rule_mode").trim.toLowerCase
          val rule_params = validity.getString("rule_params").trim.toLowerCase
          val against_action = validity.getString("against_action").trim.toLowerCase
          var i = 0
          if (check_rule.nonEmpty) i += 1
          if (check_columns.nonEmpty) i += 1
          if (rule_mode.nonEmpty) i += 1
          if (rule_params.nonEmpty) i += 1
          if (against_action.nonEmpty) i += 1
          if (!(i == 5 || i == 0)) {
            throw new Exception("rule related fields must be all null or not null ")
          }
          if (i > 0) validityConfig = Some(ValidityConfig(check_columns.split(",").map(_.trim), check_rule, rule_mode, rule_params, against_action))
        }
        val action: String = if (swifts.containsKey("action") && swifts.getString("action").trim.nonEmpty) swifts.getString("action").trim else null
        val dataframe_show = if (swifts.containsKey("dataframe_show") && swifts.getString("dataframe_show").trim.nonEmpty)
          Some(swifts.getString("dataframe_show").trim.toLowerCase.toBoolean)
        else Some(false)
        val dataframe_show_num: Option[Int] = if (swifts.containsKey("dataframe_show_num"))
          Some(swifts.getInteger("dataframe_show_num")) else Some(20)
        //      val specialConfig: JSONObject = if (swifts.containsKey("swifts_specific_config")) Some(swifts.getJSONObject("swifts_specific_config")) else None
        val specialConfigMap = mutable.HashMap.empty[String, String]
        //      if (specialConfig != null) {
        //        val initial = specialConfig.getString(InputDataRequirement.INITIAL.toString).trim.toLowerCase
        //        val increment = specialConfig.getString(InputDataRequirement.INCREMENT.toString).trim.toLowerCase
        //        val batch = specialConfig.getString(InputDataRequirement.BATCH.toString).trim.toLowerCase
        //        specialConfigMap(InputDataRequirement.INITIAL.toString) = initial
        //        specialConfigMap(InputDataRequirement.INCREMENT.toString) = increment
        //        specialConfigMap(InputDataRequirement.BATCH.toString) = batch
        //      }
        val pushdown_connection = if (swifts.containsKey("pushdown_connection") && swifts.getString("pushdown_connection").trim.nonEmpty && swifts.getJSONArray("pushdown_connection").size>0) swifts.getJSONArray("pushdown_connection") else null
        if (pushdown_connection != null) {
          val connectionListSize = pushdown_connection.size()
          for (i <- 0 until connectionListSize) {
            val jsonObj = pushdown_connection.getJSONObject(i)
            val name_space = jsonObj.getString("name_space").trim.toLowerCase
            val jdbc_url = jsonObj.getString("jdbc_url")
            val username = if (jsonObj.containsKey("username")) Some(jsonObj.getString("username")) else None
            val password = if (jsonObj.containsKey("password")) Some(jsonObj.getString("password")) else None
            val parameters = if (jsonObj.containsKey("connection_config") && jsonObj.getString("connection_config").trim.nonEmpty) Some(JsonUtils.json2caseClass[Seq[KVConfig]](jsonObj.getString("connection_config"))) else None
            ConfMemoryStorage.registerDataStoreConnectionsMap(name_space, jdbc_url, username, password, parameters)
          }
        }

        val output = if (swifts.containsKey("output") && swifts.getString("output").trim.nonEmpty) {
          var tmpOutput = swifts.getString("output").trim.toLowerCase.split(",").map(_.trim).mkString(",")
          if (tmpOutput.nonEmpty && tmpOutput.indexOf(UmsSysField.TS.toString) < 0) {
            tmpOutput = tmpOutput + "," + UmsSysField.TS.toString
          }
          if (tmpOutput.nonEmpty && tmpOutput.indexOf(UmsSysField.ID.toString) < 0) {
            tmpOutput = tmpOutput + "," + UmsSysField.ID.toString
          }
          if (tmpOutput.nonEmpty && tmpOutput.indexOf(UmsSysField.OP.toString) < 0) {
            tmpOutput = tmpOutput + "," + UmsSysField.OP.toString
          }
          if (tmpOutput.nonEmpty && validity != null && tmpOutput.indexOf(UmsSysField.UID.toString) < 0) {
            tmpOutput = tmpOutput + "," + UmsSysField.UID.toString
          }
          tmpOutput
        } else ""
        val SwiftsSqlArr = if (action != null) {
          val sqlStr = new String(new sun.misc.BASE64Decoder().decodeBuffer(action))
          ParseSwiftsSql.parse(sqlStr, sourceNamespace, fullsinkNamespace, if (validity == null) false else true)
        } else None
        //      val SwiftsSqlArr = ParseSwiftsSql.parse(sqlStr, sourceNamespace, fullsinkNamespace, if (validity == null) false else true)
        Some(SwiftsProcessConfig(output, SwiftsSqlArr, validityConfig, dataframe_show, dataframe_show_num, Some(specialConfigMap.toMap)))
      } else {
        None
      }
    } else None

    val sinks = JSON.parseObject(sinksStr)
    val sink_connection_url = sinks.getString("sink_connection_url").trim.toLowerCase
    val sink_connection_username = if (sinks.containsKey("sink_connection_username")) Some(sinks.getString("sink_connection_username").trim) else None
    val sink_connection_password = if (sinks.containsKey("sink_connection_password")) Some(sinks.getString("sink_connection_password").trim) else None
    val parameters = if (sinks.containsKey("sink_connection_config") && sinks.getString("sink_connection_config").trim.nonEmpty) Some(JsonUtils.json2caseClass[Seq[KVConfig]](sinks.getString("sink_connection_config"))) else None
    val sink_table_keys = if (sinks.containsKey("sink_table_keys") && sinks.getString("sink_table_keys").trim.nonEmpty) Some(sinks.getString("sink_table_keys").trim.toLowerCase) else None
    val sink_specific_config = if (sinks.containsKey("sink_specific_config") && sinks.getString("sink_specific_config").trim.nonEmpty) Some(sinks.getString("sink_specific_config")) else None
    val sink_process_class_fullname = sinks.getString("sink_process_class_fullname").trim
    val sink_retry_times = sinks.getString("sink_retry_times").trim.toLowerCase.toInt
    val sink_retry_seconds = sinks.getString("sink_retry_seconds").trim.toLowerCase.toInt
    val sinkProcessConfig = SinkProcessConfig(sink_table_keys, sink_specific_config, sink_process_class_fullname, sink_retry_times, sink_retry_seconds)
    val swiftsStrCache = if (swiftsStr == null) "" else swiftsStr

    ConfMemoryStorage.registerStreamLookupNamespaceMap(sourceNamespace, fullsinkNamespace, swiftsProcessConfig)
    ConfMemoryStorage.registerFlowConfigMap(sourceNamespace, fullsinkNamespace, swiftsProcessConfig, sinkProcessConfig, directiveId, swiftsStrCache, sinksStr, consumptionDataMap.toMap)
    ConfMemoryStorage.registerDataStoreConnectionsMap(fullsinkNamespace, sink_connection_url, sink_connection_username, sink_connection_password, parameters)
    WormholeKafkaProducer.sendMessage(feedbackTopicName, FeedbackPriority.FeedbackPriority1, feedbackDirective(DateUtils.currentDateTime, directiveId, UmsFeedbackStatus.SUCCESS, streamId, ""), None, brokers)

  }

  override def flowStartProcess(ums: Ums, feedbackTopicName: String, brokers: String): Unit = {
    val payloads = ums.payload_get
    val schemas = ums.schema.fields_get
    val sourceNamespace = ums.schema.namespace.toLowerCase
    payloads.foreach(tuple => {
      val streamId = UmsFieldType.umsFieldValue(tuple.tuple, schemas, "stream_id").toString.toLong
      val directiveId = UmsFieldType.umsFieldValue(tuple.tuple, schemas, "directive_id").toString.toLong
      try {
        val swiftsEncoded = UmsFieldType.umsFieldValue(tuple.tuple, schemas, "swifts")

        val swiftsStr = if (swiftsEncoded != null && !swiftsEncoded.toString.isEmpty) new String(new sun.misc.BASE64Decoder().decodeBuffer(swiftsEncoded.toString)) else null
        logInfo("swiftsStr:" + swiftsStr)
        val sinksStr = new String(new sun.misc.BASE64Decoder().decodeBuffer(UmsFieldType.umsFieldValue(tuple.tuple, schemas, "sinks").toString))
        logInfo("sinksStr:" + sinksStr)
        val fullSinkNamespace = UmsFieldType.umsFieldValue(tuple.tuple, schemas, "sink_namespace").toString.toLowerCase

        val consumptionDataStr = new String(new sun.misc.BASE64Decoder().decodeBuffer(UmsFieldType.umsFieldValue(tuple.tuple, schemas, "consumption_data_type").toString))

        registerFlowStartDirective(sourceNamespace, fullSinkNamespace, streamId, directiveId, swiftsStr, sinksStr, feedbackTopicName, brokers, consumptionDataStr)
      } catch {
        case e: Throwable =>
          logAlert("registerFlowStartDirective,sourceNamespace:" + sourceNamespace, e)
          WormholeKafkaProducer.sendMessage(feedbackTopicName, FeedbackPriority.FeedbackPriority1, feedbackDirective(DateUtils.currentDateTime, directiveId, UmsFeedbackStatus.FAIL, streamId, ""), None, brokers)

      }

    })
  }
}
