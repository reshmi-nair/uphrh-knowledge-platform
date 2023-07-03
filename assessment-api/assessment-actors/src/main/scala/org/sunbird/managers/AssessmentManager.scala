package org.sunbird.managers

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.{ArrayNode, ObjectNode}
import com.mashape.unirest.http.Unirest

import java.util
import org.slf4j.{Logger, LoggerFactory}
import org.apache.commons.lang3.StringUtils
import org.sunbird.common.{DateUtils, JsonUtils, Platform}
import org.sunbird.common.dto.{Request, Response, ResponseHandler}
import org.sunbird.common.exception.{ClientException, ErrorCodes, ResourceNotFoundException, ServerException}
import org.sunbird.graph.OntologyEngineContext
import org.sunbird.graph.dac.model.{Node, Relation}
import org.sunbird.graph.nodes.DataNode
import org.sunbird.graph.utils.NodeUtil
import org.sunbird.managers.AssessmentManager.{hideCorrectResponse, hideEditorStateAns}
import org.sunbird.telemetry.logger.TelemetryManager
import org.sunbird.telemetry.util.LogTelemetryEventUtil
import org.sunbird.utils.{AssessmentConstants, JavaJsonUtils, JwtUtils, RequestUtil}

import scala.concurrent.{ExecutionContext, Future}
import scala.collection.JavaConversions._
import scala.collection.JavaConverters
import scala.collection.JavaConverters._

object AssessmentManager {

	val skipValidation: Boolean = Platform.getBoolean("assessment.skip.validation", false)
	val validStatus = List("Draft", "Review")
	val mapper = new ObjectMapper()

	private val logger:Logger = LoggerFactory.getLogger(getClass.getName)
	def create(request: Request, errCode: String)(implicit oec: OntologyEngineContext, ec: ExecutionContext): Future[Response] = {
		val visibility: String = request.getRequest.getOrDefault("visibility", "Default").asInstanceOf[String]
		if (StringUtils.isNotBlank(visibility) && StringUtils.equalsIgnoreCase(visibility, "Parent"))
			throw new ClientException(errCode, "Visibility cannot be Parent!")
		RequestUtil.restrictProperties(request)
		DataNode.create(request).map(node => {
			val response = ResponseHandler.OK
			response.putAll(Map("identifier" -> node.getIdentifier, "versionKey" -> node.getMetadata.get("versionKey")).asJava)
			response
		})
	}

	def read(request: Request, resName: String)(implicit oec: OntologyEngineContext, ec: ExecutionContext): Future[Response] = {
		val fields: util.List[String] = JavaConverters.seqAsJavaListConverter(request.get("fields").asInstanceOf[String].split(",").filter(field => StringUtils.isNotBlank(field) && !StringUtils.equalsIgnoreCase(field, "null"))).asJava
		request.getRequest.put("fields", fields)
		DataNode.read(request).map(node => {
			val serverEvaluable = node.getMetadata.getOrDefault(AssessmentConstants.EVAL,AssessmentConstants.FLOWER_BRACKETS)
			val data = mapper.readValue(serverEvaluable.asInstanceOf[String], classOf[java.util.Map[String, String]])
			if (data.get(AssessmentConstants.MODE) != null && data.get(AssessmentConstants.MODE) == AssessmentConstants.SERVER && !StringUtils.equals(request.getOrDefault("isEditor","").asInstanceOf[String], "true")) {
				val hideEditorResponse =  hideEditorStateAns(node)
				if(StringUtils.isNotEmpty(hideEditorResponse))
				node.getMetadata.put("editorState", hideEditorResponse)
				val hideCorrectAns = hideCorrectResponse(node)
				if(StringUtils.isNotEmpty(hideCorrectAns))
				node.getMetadata.put("responseDeclaration", hideCorrectAns )
			}
			val metadata: util.Map[String, AnyRef] = NodeUtil.serialize(node, fields, node.getObjectType.toLowerCase.replace("Image", ""), request.getContext.get("version").asInstanceOf[String])
			metadata.put("identifier", node.getIdentifier.replace(".img", ""))
			if(!StringUtils.equalsIgnoreCase(metadata.get("visibility").asInstanceOf[String],"Private")) {
				ResponseHandler.OK.put(resName, metadata)
			}
			else {
				throw new ClientException("ERR_ACCESS_DENIED", s"$resName visibility is private, hence access denied")
			}
		})
	}

	def privateRead(request: Request, resName: String)(implicit oec: OntologyEngineContext, ec: ExecutionContext): Future[Response] = {
		val fields: util.List[String] = JavaConverters.seqAsJavaListConverter(request.get("fields").asInstanceOf[String].split(",").filter(field => StringUtils.isNotBlank(field) && !StringUtils.equalsIgnoreCase(field, "null"))).asJava
		request.getRequest.put("fields", fields)
		if (StringUtils.isBlank(request.getRequest.getOrDefault("channel", "").asInstanceOf[String])) throw new ClientException("ERR_INVALID_CHANNEL", "Please Provide Channel!")
		DataNode.read(request).map(node => {
			val metadata: util.Map[String, AnyRef] = NodeUtil.serialize(node, fields, node.getObjectType.toLowerCase.replace("Image", ""), request.getContext.get("version").asInstanceOf[String])
			metadata.put("identifier", node.getIdentifier.replace(".img", ""))
				if (StringUtils.equalsIgnoreCase(metadata.getOrDefault("channel", "").asInstanceOf[String],request.getRequest.getOrDefault("channel", "").asInstanceOf[String])) {
					ResponseHandler.OK.put(resName, metadata)
				}
				else {
					throw new ClientException("ERR_ACCESS_DENIED", "Channel id is not matched")
				}
		})
	}

	def updateNode(request: Request)(implicit oec: OntologyEngineContext, ec: ExecutionContext): Future[Response] = {
		DataNode.update(request).map(node => {
			ResponseHandler.OK.putAll(Map("identifier" -> node.getIdentifier.replace(".img", ""), "versionKey" -> node.getMetadata.get("versionKey")).asJava)
		})
	}

	def getValidatedNodeForUpdate(request: Request, errCode: String)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Node] = {
		DataNode.read(request).map(node => {
			if (StringUtils.equalsIgnoreCase(node.getMetadata.getOrDefault("visibility", "").asInstanceOf[String], "Parent"))
				throw new ClientException(errCode, node.getMetadata.getOrDefault("objectType", "").asInstanceOf[String].replace("Image", "") + " with visibility Parent, can't be updated individually.")
			node
		})
	}

	def getValidatedNodeForReview(request: Request, errCode: String)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Node] = {
		request.put("mode", "edit")
		DataNode.read(request).map(node => {
			if (StringUtils.equalsIgnoreCase(node.getMetadata.getOrDefault("visibility", "").asInstanceOf[String], "Parent"))
				throw new ClientException(errCode, s"${node.getObjectType.replace("Image", "")} with visibility Parent, can't be sent for review individually.")
			if (!StringUtils.equalsAnyIgnoreCase(node.getMetadata.getOrDefault("status", "").asInstanceOf[String], "Draft"))
				throw new ClientException(errCode, s"${node.getObjectType.replace("Image", "")} with status other than Draft can't be sent for review.")
			node
		})
	}

	def getValidatedNodeForPublish(request: Request, errCode: String)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Node] = {
		request.put("mode", "edit")
		DataNode.read(request).map(node => {
			if (StringUtils.equalsIgnoreCase(node.getMetadata.getOrDefault("visibility", "").asInstanceOf[String], "Parent"))
				throw new ClientException(errCode, s"${node.getObjectType.replace("Image", "")} with visibility Parent, can't be sent for publish individually.")
			if (StringUtils.equalsAnyIgnoreCase(node.getMetadata.getOrDefault("status", "").asInstanceOf[String], "Processing"))
				throw new ClientException(errCode, s"${node.getObjectType.replace("Image", "")} having Processing status can't be sent for publish.")
			node
		})
	}

	def getValidatedNodeForRetire(request: Request, errCode: String)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Node] = {
		DataNode.read(request).map(node => {
			if (StringUtils.equalsIgnoreCase("Retired", node.getMetadata.get("status").asInstanceOf[String]))
				throw new ClientException(errCode, s"${node.getObjectType.replace("Image", "")} with identifier : ${node.getIdentifier} is already Retired.")
			node
		})
	}

	def getValidateNodeForReject(request: Request, errCode: String)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Node] = {
		request.put("mode", "edit")
		DataNode.read(request).map(node => {
			if (StringUtils.equalsIgnoreCase(node.getMetadata.getOrDefault("visibility", "").asInstanceOf[String], "Parent"))
				throw new ClientException(errCode, s"${node.getObjectType.replace("Image", "")} with visibility Parent, can't be sent for reject individually.")
			if (!StringUtils.equalsIgnoreCase("Review", node.getMetadata.get("status").asInstanceOf[String]))
				throw new ClientException(errCode, s"${node.getObjectType.replace("Image", "")} is not in 'Review' state for identifier: " + node.getIdentifier)
			node
		})
	}

	def getValidatedQuestionSet(request: Request)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Node] = {
		request.put("mode", "edit")
		DataNode.read(request).map(node => {
			if (!StringUtils.equalsIgnoreCase("QuestionSet", node.getObjectType))
				throw new ClientException("ERR_QUESTION_SET_ADD", "Node with Identifier " + node.getIdentifier + " is not a Question Set")
			node
		})
	}

	def validateQuestionSetHierarchy(hierarchyString: String, rootUserId: String)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Unit = {
		if (!skipValidation) {
			val hierarchy = if (!hierarchyString.asInstanceOf[String].isEmpty) {
				JsonUtils.deserialize(hierarchyString.asInstanceOf[String], classOf[java.util.Map[String, AnyRef]])
			} else
				new java.util.HashMap[String, AnyRef]()
			val children = hierarchy.getOrDefault("children", new util.ArrayList[java.util.Map[String, AnyRef]]).asInstanceOf[util.List[java.util.Map[String, AnyRef]]]
			validateChildrenRecursive(children, rootUserId)
		}
	}

	def getQuestionSetHierarchy(request: Request, rootNode: Node)(implicit ec: ExecutionContext, oec: OntologyEngineContext): Future[Any] = {
		request.put("rootId", request.get("identifier").asInstanceOf[String])
		HierarchyManager.getUnPublishedHierarchy(request).map(resp => {
			if (!ResponseHandler.checkError(resp) && resp.getResponseCode.code() == 200) {
				val hierarchy = resp.getResult.get("questionSet").asInstanceOf[util.Map[String, AnyRef]]
				JsonUtils.serialize(hierarchy)
			} else throw new ServerException("ERR_QUESTION_SET_HIERARCHY", "No hierarchy is present in cassandra for identifier:" + rootNode.getIdentifier)
		})
	}

	private def validateChildrenRecursive(children: util.List[util.Map[String, AnyRef]], rootUserId: String): Unit = {
		children.toList.foreach(content => {
			if ((StringUtils.equalsAnyIgnoreCase(content.getOrDefault("visibility", "").asInstanceOf[String], "Default")
			  && !StringUtils.equals(rootUserId, content.getOrDefault("createdBy", "").asInstanceOf[String]))
			  && !StringUtils.equalsIgnoreCase(content.getOrDefault("status", "").asInstanceOf[String], "Live"))
				throw new ClientException("ERR_QUESTION_SET", "Object with identifier: " + content.get("identifier") + " is not Live. Please Publish it.")
			validateChildrenRecursive(content.getOrDefault("children", new util.ArrayList[Map[String, AnyRef]]).asInstanceOf[util.List[util.Map[String, AnyRef]]], rootUserId)
		})
	}

	def getChildIdsFromRelation(node: Node): (List[String], List[String]) = {
		val outRelations: List[Relation] = if (node.getOutRelations != null) node.getOutRelations.asScala.toList else List[Relation]()
		val visibilityIdMap: Map[String, List[String]] = outRelations
		  .groupBy(_.getEndNodeMetadata.get("visibility").asInstanceOf[String])
		  .mapValues(_.map(_.getEndNodeId).toList)
		(visibilityIdMap.getOrDefault("Default", List()), visibilityIdMap.getOrDefault("Parent", List()))
	}

	def updateHierarchy(hierarchyString: String, status: String, rootUserId: String): (java.util.Map[String, AnyRef], java.util.List[String]) = {
		val hierarchy: java.util.Map[String, AnyRef] = if (!hierarchyString.asInstanceOf[String].isEmpty) {
			JsonUtils.deserialize(hierarchyString.asInstanceOf[String], classOf[java.util.Map[String, AnyRef]])
		} else
			new java.util.HashMap[String, AnyRef]()
		val keys = List("identifier", "children").asJava
		hierarchy.keySet().retainAll(keys)
		val children = hierarchy.getOrDefault("children", new util.ArrayList[java.util.Map[String, AnyRef]]).asInstanceOf[util.List[java.util.Map[String, AnyRef]]]
		val childrenToUpdate: List[String] = updateChildrenRecursive(children, status, List(), rootUserId)
		(hierarchy, childrenToUpdate.asJava)
	}

	private def updateChildrenRecursive(children: util.List[util.Map[String, AnyRef]], status: String, idList: List[String], rootUserId: String): List[String] = {
		children.toList.flatMap(content => {
			val objectType = content.getOrDefault("objectType", "").asInstanceOf[String]
			val updatedIdList: List[String] =
				if (StringUtils.equalsAnyIgnoreCase(content.getOrDefault("visibility", "").asInstanceOf[String], "Parent") || (StringUtils.equalsIgnoreCase( objectType, "Question") && StringUtils.equalsAnyIgnoreCase(content.getOrDefault("visibility", "").asInstanceOf[String], "Default") && validStatus.contains(content.getOrDefault("status", "").asInstanceOf[String]) && StringUtils.equals(rootUserId, content.getOrDefault("createdBy", "").asInstanceOf[String]))) {
					content.put("lastStatusChangedOn", DateUtils.formatCurrentDate)
					content.put("prevStatus", content.getOrDefault("status", "Draft"))
					content.put("status", status)
					content.put("lastUpdatedOn", DateUtils.formatCurrentDate)
					if(StringUtils.equalsAnyIgnoreCase(objectType, "Question")) content.get("identifier").asInstanceOf[String] :: idList else idList
				} else idList
			val list = updateChildrenRecursive(content.getOrDefault("children", new util.ArrayList[Map[String, AnyRef]]).asInstanceOf[util.List[util.Map[String, AnyRef]]], status, updatedIdList, rootUserId)
			list ++ updatedIdList
		})
	}

	@throws[Exception]
	def pushInstructionEvent(identifier: String, node: Node)(implicit oec: OntologyEngineContext): Unit = {
		logger.info("Inside the pushInstructionEvent")
		val (actor, context, objData, eData) = generateInstructionEventMetadata(identifier.replace(".img", ""), node)
		val beJobRequestEvent: String = LogTelemetryEventUtil.logInstructionEvent(actor.asJava, context.asJava, objData.asJava, eData)
		logger.info("beJobRequestEvent is "+beJobRequestEvent)
		val topic: String = Platform.getString("kafka.topics.instruction", "sunbirddev.learning.job.request")
		logger.info("topic for publish "+topic)
		if (StringUtils.isBlank(beJobRequestEvent)) throw new ClientException("BE_JOB_REQUEST_EXCEPTION", "Event is not generated properly.")
		oec.kafkaClient.send(beJobRequestEvent, topic)
	}

	def generateInstructionEventMetadata(identifier: String, node: Node): (Map[String, AnyRef], Map[String, AnyRef], Map[String, AnyRef], util.Map[String, AnyRef]) = {
		val metadata: util.Map[String, AnyRef] = node.getMetadata
		val publishType = if (StringUtils.equalsIgnoreCase(metadata.getOrDefault("status", "").asInstanceOf[String], "Unlisted")) "unlisted" else "public"
		val eventMetadata = Map("identifier" -> identifier, "mimeType" -> metadata.getOrDefault("mimeType", ""), "objectType" -> node.getObjectType.replace("Image", ""), "pkgVersion" -> metadata.getOrDefault("pkgVersion", 0.asInstanceOf[AnyRef]), "lastPublishedBy" -> metadata.getOrDefault("lastPublishedBy", ""))
		val actor = Map("id" -> s"${node.getObjectType.toLowerCase().replace("image", "")}-publish", "type" -> "System".asInstanceOf[AnyRef])
		val context = Map("channel" -> metadata.getOrDefault("channel", ""), "pdata" -> Map("id" -> "org.sunbird.platform", "ver" -> "1.0").asJava, "env" -> Platform.getString("cloud_storage.env", "dev"))
		val objData = Map("id" -> identifier, "ver" -> metadata.getOrDefault("versionKey", ""))
		val eData: util.Map[String, AnyRef] = new util.HashMap[String, AnyRef] {{
				put("action", "publish")
				put("publish_type", publishType)
				put("metadata", eventMetadata.asJava)
			}}
		(actor, context, objData, eData)
	}

	def createMapping(request: Request, str: String)(implicit oec: OntologyEngineContext, ec: ExecutionContext): Future[Response] = {
		oec.graphService.saveExternalProps(request)
	}

	val map = Map("userId" -> "userID", "attemptId" -> "attemptID")

	def validateAssessRequest(req: Request) = {
		val body = req.getRequest
		val jwt = body.getOrDefault(AssessmentConstants.QUESTION_SET_TOKEN, "").asInstanceOf[String]
		val payload = try {
			val tuple= HierarchyManager.verifyRS256Token(jwt)
			if(tuple._1 == false)
				throw new ClientException(ErrorCodes.ERR_BAD_REQUEST.name(), "Token Authentication Failed")
			tuple._2.get("data").asInstanceOf[String]
					} catch {
			case e: Exception => throw new ClientException(ErrorCodes.ERR_BAD_REQUEST.name(), "Token Authentication Failed")
		}
		val questToken = JavaJsonUtils.deserialize[java.util.Map[String, AnyRef]](payload)
		val assessments = body.getOrDefault(AssessmentConstants.ASSESSMENTS, new util.ArrayList[util.Map[String, AnyRef]]).asInstanceOf[util.List[util.Map[String, AnyRef]]]
		val courseMetaData = Option(assessments.get(0)).getOrElse(new util.HashMap[String, AnyRef])
		val count = map.filter(key => StringUtils.equals(courseMetaData.get(key._1).asInstanceOf[String], questToken.get(key._2).asInstanceOf[String])).size
		if (count != map.size)
			throw new ClientException(ErrorCodes.ERR_BAD_REQUEST.name(), "Token Authentication Failed")
		questToken.get(AssessmentConstants.QUESTION_LIST).asInstanceOf[String].split(",").asInstanceOf[Array[String]]
	}

	def questionList(fields: Array[String]): Response = {
		val url: String = Platform.getString(AssessmentConstants.QUESTION_LIST_EDITOR_URL, "")
		val bdy = "{\"request\":{\"search\":{\"identifier\":" + JavaJsonUtils.serialize(fields) + "}}}"
		val httpResponse = post(url, bdy)
		if (200 != httpResponse.status) throw new ServerException("ERR_QUESTION_LIST_API_COMM", "Error communicating to question list api")
		JsonUtils.deserialize(httpResponse.body, classOf[Response])
	}

	private case class HTTPResponse(status: Int, body: String) extends Serializable

	private def post(url: String, requestBody: String, headers: Map[String, String] = Map[String, String]("Content-Type" -> "application/json")): HTTPResponse = {
		val res = Unirest.post(url).headers(headers.asJava).body(requestBody).asString()
		HTTPResponse(res.getStatus, res.getBody)
	}

	private def populateMultiCardinality(res: util.Map[String, AnyRef], edata: util.Map[String, AnyRef], maxScore: Integer) = {
		val correctValue = getMap(res, AssessmentConstants.CORRECT_RESPONSE).getOrDefault(AssessmentConstants.VALUE, new util.ArrayList[Integer]).asInstanceOf[util.ArrayList[Integer]].flatMap(k => List(k)).sorted
		val usrResponse = edata.getOrDefault(AssessmentConstants.RESVALUES, new util.ArrayList[util.ArrayList[util.Map[String, AnyRef]]]())
			.asInstanceOf[util.ArrayList[util.ArrayList[util.Map[String, AnyRef]]]]
			.flatMap(_.flatMap(res => List(res.getOrDefault(AssessmentConstants.VALUE, -1.asInstanceOf[Integer]).asInstanceOf[Integer]))).sorted
		correctValue.equals(usrResponse) match {
			case true => edata.put(AssessmentConstants.SCORE, maxScore)
			case _ => {
				var ttlScr = 0.0d
				getListMap(res, AssessmentConstants.MAPPING).foreach(k => if (usrResponse.contains(k.getOrDefault(AssessmentConstants.RESPONSE, -1.asInstanceOf[Integer]).asInstanceOf[Integer]))
					ttlScr += getMap(k, AssessmentConstants.OUTCOMES).get(AssessmentConstants.SCORE).asInstanceOf[Double])
				edata.put(AssessmentConstants.SCORE, ttlScr.asInstanceOf[AnyRef])
				if (ttlScr > 0) edata.put(AssessmentConstants.PASS, AssessmentConstants.YES) else edata.put(AssessmentConstants.PASS, AssessmentConstants.NO)
			}
		}
	}

	private def populateSingleCardinality(res: util.Map[String, AnyRef], edata: util.Map[String, AnyRef], maxScore: Integer): Unit = {
		val correctValue = getMap(res, AssessmentConstants.CORRECT_RESPONSE).getOrDefault(AssessmentConstants.VALUE, new util.ArrayList[Integer]).asInstanceOf[String]
		val usrResponse = getListMap(edata, AssessmentConstants.RESVALUES).get(0).getOrDefault(AssessmentConstants.VALUE, "").toString
		StringUtils.equals(usrResponse, correctValue) match {
			case true => {
				edata.put(AssessmentConstants.SCORE, maxScore)
				edata.put(AssessmentConstants.PASS, AssessmentConstants.YES)
			}
			case _ => {
				edata.put(AssessmentConstants.SCORE, 0.asInstanceOf[Integer])
				edata.put(AssessmentConstants.PASS, AssessmentConstants.NO)
			}
		}
	}

	def calculateScore(privateList: Response, assessments: util.List[util.Map[String, AnyRef]]): Unit = {
		val answerMaps: (Map[String, AnyRef], Map[String, AnyRef]) = getListMap(privateList.getResult, AssessmentConstants.QUESTIONS)
			.map { que =>
				((que.get(AssessmentConstants.IDENTIFIER).toString -> que.get(AssessmentConstants.RESPONSE_DECLARATION)),
					(que.get(AssessmentConstants.IDENTIFIER).toString -> que.get(AssessmentConstants.EDITOR_STATE)))
			}.unzip match {
			case (map1, map2) => (map1.toMap, map2.toMap)
		}
		val answerMap = answerMaps._1
		val editorStateMap = answerMaps._2
		assessments.foreach { k =>
			getListMap(k, AssessmentConstants.EVENTS).toList.foreach { event =>
				val edata = getMap(event, AssessmentConstants.EDATA)
				val item = getMap(edata, AssessmentConstants.ITEM)
				val identifier = item.getOrDefault(AssessmentConstants.ID, "").asInstanceOf[String]
				if (!answerMap.contains(identifier))
					throw new ClientException(ErrorCodes.ERR_BAD_REQUEST.name(), "Invalid Request")
				val res = getMap(answerMap.get(identifier).asInstanceOf[Some[util.Map[String, AnyRef]]].x, AssessmentConstants.RESPONSE1)
				val cardinality = res.getOrDefault(AssessmentConstants.CARDINALITY, "").asInstanceOf[String]
				val maxScore = res.getOrDefault(AssessmentConstants.MAX_SCORE, 0.asInstanceOf[Integer]).asInstanceOf[Integer]
				cardinality match {
					case AssessmentConstants.MULTIPLE => populateMultiCardinality(res, edata, maxScore)
					case _ => populateSingleCardinality(res, edata, maxScore)
				}
				populateParams(item,editorStateMap)
			}
		}
	}

	private def getListMap(arg: util.Map[String, AnyRef], param: String) = {
		arg.getOrDefault(param, new util.ArrayList[util.Map[String, AnyRef]]()).asInstanceOf[util.ArrayList[util.Map[String, AnyRef]]]
	}

	private def getMap(arg: util.Map[String, AnyRef], param: String) = {
		arg.getOrDefault(param, new util.HashMap[String, AnyRef]()).asInstanceOf[util.Map[String, AnyRef]]
	}

	def hideEditorStateAns(node: Node): String = {
		// Modify editorState
		Option(node.getMetadata.get("editorState")) match {
			case Some(jsonStr: String) =>
				val jsonNode = mapper.readTree(jsonStr)
				//if (jsonNode != null && jsonNode.has("question")) {
				//val questionNode = jsonNode.get("question")
				if (jsonNode != null && jsonNode.has("options")) {
					val optionsNode = jsonNode.get("options").asInstanceOf[ArrayNode]
					val iterator = optionsNode.elements()
					while (iterator.hasNext) {
						val optionNode = iterator.next().asInstanceOf[ObjectNode]
						optionNode.remove("answer")
					}
					//}
				}
				mapper.writeValueAsString(jsonNode)
			case _ => ""
		}
	}
	def hideCorrectResponse(node: Node): String= {
		val responseDeclaration = Option(node.getMetadata.get("responseDeclaration")) match {
			case Some(jsonStr: String) => jsonStr
			case _ => ""
		}
		val jsonNode = mapper.readTree(responseDeclaration)
		if (null != jsonNode && jsonNode.has("response1")) {
			val responseNode = jsonNode.get("response1").asInstanceOf[ObjectNode]
			responseNode.remove("correctResponse")
			 mapper.writeValueAsString(jsonNode)
		}
		else
			""
	}

	private def populateParams(item: util.Map[String, AnyRef], editorState: Map[String, AnyRef]) = {
		item.put(AssessmentConstants.PARAMS, editorState.get(item.get(AssessmentConstants.ID)).asInstanceOf[util.Map[String, AnyRef]].get(AssessmentConstants.OPTIONS))
	}
}
