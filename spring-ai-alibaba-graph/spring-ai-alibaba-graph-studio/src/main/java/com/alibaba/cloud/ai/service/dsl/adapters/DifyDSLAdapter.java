/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.service.dsl.adapters;

import com.alibaba.cloud.ai.model.App;
import com.alibaba.cloud.ai.model.AppMetadata;
import com.alibaba.cloud.ai.model.Variable;
import com.alibaba.cloud.ai.model.chatbot.ChatBot;
import com.alibaba.cloud.ai.model.workflow.Edge;
import com.alibaba.cloud.ai.model.workflow.Graph;
import com.alibaba.cloud.ai.model.workflow.Node;
import com.alibaba.cloud.ai.model.workflow.NodeData;
import com.alibaba.cloud.ai.model.workflow.NodeType;
import com.alibaba.cloud.ai.model.workflow.Workflow;
import com.alibaba.cloud.ai.model.workflow.nodedata.CodeNodeData;
import com.alibaba.cloud.ai.model.workflow.nodedata.EmptyNodeData;
import com.alibaba.cloud.ai.model.workflow.nodedata.IterationNodeData;
import com.alibaba.cloud.ai.model.workflow.nodedata.VariableAggregatorNodeData;
import com.alibaba.cloud.ai.service.dsl.DSLDialectType;
import com.alibaba.cloud.ai.service.dsl.Serializer;
import com.alibaba.cloud.ai.service.dsl.NodeDataConverter;
import com.alibaba.cloud.ai.service.dsl.AbstractDSLAdapter;
import com.alibaba.cloud.ai.model.VariableSelector;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import org.apache.commons.lang3.NotImplementedException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * DifyDSLAdapter converts Dify DSL to {@link App} and vice versa.
 */
@Component
public class DifyDSLAdapter extends AbstractDSLAdapter {

	private static final String[] DIFY_CHATBOT_MODES = { "chat", "completion", "agent-chat" };

	private static final String[] DIFY_WORKFLOW_MODES = { "workflow", "advanced-chat" };

	private final List<NodeDataConverter<? extends NodeData>> nodeDataConverters;

	private final Serializer serializer;

	public DifyDSLAdapter(List<NodeDataConverter<? extends NodeData>> nodeDataConverters,
			@Qualifier("yaml") Serializer serializer) {
		this.nodeDataConverters = nodeDataConverters;
		this.serializer = serializer;
	}

	private NodeDataConverter<? extends NodeData> getNodeDataConverter(NodeType nodeType) {
		return nodeDataConverters.stream()
			.filter(converter -> converter.supportNodeType(nodeType))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("invalid dify node type " + nodeType));
	}

	@Override
	public void validateDSLData(Map<String, Object> dslData) {
		if (dslData == null || !dslData.containsKey("app")) {
			throw new IllegalArgumentException("invalid dify dsl");
		}
	}

	@Override
	public Serializer getSerializer() {
		return serializer;
	}

	@Override
	public AppMetadata mapToMetadata(Map<String, Object> data) {
		Map<String, Object> map = (Map<String, Object>) data.get("app");
		AppMetadata metadata = new AppMetadata();
		if (Arrays.asList(DIFY_CHATBOT_MODES).contains((String) map.get("mode"))) {
			metadata.setMode(AppMetadata.CHATBOT_MODE);
		}
		else if (Arrays.asList(DIFY_WORKFLOW_MODES).contains((String) map.get("mode"))) {
			metadata.setMode(AppMetadata.WORKFLOW_MODE);
		}
		else {
			throw new IllegalArgumentException("unknown dify app mode" + map.get("mode"));
		}
		metadata.setId(UUID.randomUUID().toString());
		metadata.setName((String) map.getOrDefault("name", metadata.getMode() + "-" + metadata.getId()));
		metadata.setDescription((String) map.getOrDefault("description", ""));
		return metadata;
	}

	@Override
	public Map<String, Object> metadataToMap(AppMetadata metadata) {
		Map<String, Object> data = new HashMap<>();
		String difyMode = metadata.getMode().equals(AppMetadata.WORKFLOW_MODE) ? "workflow" : "agent-chat";
		data.put("app", Map.of("name", metadata.getName(), "description", metadata.getDescription(), "mode", difyMode));
		data.put("kind", "app");
		return data;
	}

	@Override
	public Workflow mapToWorkflow(Map<String, Object> data) {
		Map<String, Object> workflowData = (Map<String, Object>) data.get("workflow");
		Workflow workflow = new Workflow();
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		// map key is snake_case style
		objectMapper.setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
		List<Variable> convVars = new ArrayList<>();
		if (workflowData.containsKey("conversation_variables")) {
			List<Map<String, Object>> variables = (List<Map<String, Object>>) workflowData
				.get("conversation_variables");
			convVars = variables.stream().map(variable -> objectMapper.convertValue(variable, Variable.class)).toList();
		}

		if (workflowData.containsKey("environment_variables")) {
			List<Map<String, Object>> variables = (List<Map<String, Object>>) workflowData.get("environment_variables");
			List<Variable> envVars = variables.stream()
				.map(variable -> objectMapper.convertValue(variable, Variable.class))
				.collect(Collectors.toList());
			workflow.setEnvVars(envVars);
		}

		Graph graph = constructGraph((Map<String, Object>) workflowData.get("graph"));

		// del codeNode
		Map<String, String> idToVarName = graph.getNodes()
			.stream()
			.collect(Collectors.toMap(Node::getId, n -> n.getData().getVarName()));

		for (Node node : graph.getNodes()) {
			if (NodeType.CODE.value().equals(node.getType())) {
				CodeNodeData cd = (CodeNodeData) node.getData();
				for (VariableSelector sel : cd.getInputs()) {
					String upstreamId = sel.getNamespace();
					String upstreamVar = idToVarName.get(upstreamId);
					sel.setName(upstreamVar + "_output");
				}
			}
		}
		workflow.setGraph(graph);
		// register overAllState output key
		List<Variable> extraVars = graph.getNodes().stream().flatMap(node -> {
			NodeType type = NodeType.fromValue(node.getType())
				.orElseThrow(() -> new IllegalArgumentException("Unsupported NodeType: " + node.getType()));
			@SuppressWarnings("unchecked")
			NodeDataConverter<NodeData> conv = (NodeDataConverter<NodeData>) getNodeDataConverter(type);
			return conv.extractWorkflowVars(node.getData());
		}).toList();

		List<Variable> allVars = new ArrayList<>(Stream.concat(convVars.stream(), extraVars.stream())
			.collect(Collectors.toMap(Variable::getName, v -> v, (v1, v2) -> v1))
			.values());

		workflow.setWorkflowVars(allVars);

		return workflow;
	}

	private Graph constructGraph(Map<String, Object> data) {
		Graph graph = new Graph();
		List<Node> nodes = new ArrayList<>();
		List<Edge> edges = new ArrayList<>();
		// convert nodes
		if (data.containsKey("nodes")) {
			List<Map<String, Object>> nodeMaps = (List<Map<String, Object>>) data.get("nodes");
			nodes = constructNodes(nodeMaps);
		}
		// convert edges
		if (data.containsKey("edges")) {
			List<Map<String, Object>> edgeMaps = (List<Map<String, Object>>) data.get("edges");
			edges = constructEdges(edgeMaps);
		}

		graph.setNodes(nodes);
		graph.setEdges(edges);
		return graph;
	}

	private List<Node> constructNodes(List<Map<String, Object>> nodeMaps) {
		ObjectMapper objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
				false);

		Map<NodeType, Integer> counters = new HashMap<>();
		List<Node> nodes = new ArrayList<>();

		for (Map<String, Object> nodeMap : nodeMaps) {
			@SuppressWarnings("unchecked")
			Map<String, Object> nodeDataMap = (Map<String, Object>) nodeMap.get("data");
			String difyNodeType = (String) nodeDataMap.get("type");
			if (difyNodeType == null || difyNodeType.isBlank()) {
				// This node is just a "note", skip it, and the corresponding node will
				// not be generated [compatible dify]
				continue;
			}
			String nodeId = (String) nodeMap.get("id");
			nodeDataMap.put("id", nodeId);
			// determine the type of dify node is supported yet
			NodeType nodeType = NodeType.fromDifyValue(difyNodeType)
				.orElseThrow(() -> new NotImplementedException("unsupported node type " + difyNodeType));

			// convert node map to workflow node using jackson
			nodeMap.remove("data");
			Node node = objectMapper.convertValue(nodeMap, Node.class);
			// set title and desc
			node.setTitle((String) nodeDataMap.get("title")).setDesc((String) nodeDataMap.get("desc"));

			// convert node data using specific WorkflowNodeDataConverter
			@SuppressWarnings("unchecked")
			NodeDataConverter<NodeData> converter = (NodeDataConverter<NodeData>) getNodeDataConverter(nodeType);

			NodeData data = converter.parseMapData(nodeDataMap, DSLDialectType.DIFY);

			// Generate a readable varName and inject it into NodeData
			int count = counters.merge(nodeType, 1, Integer::sum);
			String varName = converter.generateVarName(count);

			data.setVarName(varName);

			// Post-processing: Overwrite the default outputKey and refresh the outputs
			converter.postProcess(data, varName);

			node.setData(data);
			node.setType(nodeType.value());
			nodes.add(node);
		}

		// 等待所有的节点都生成了变量名后，补充迭代节点的起始名称
		Map<String, String> varNames = nodes.stream()
			.collect(Collectors.toMap(Node::getId, n -> n.getData().getVarName()));
		for (Node node : nodes) {
			if (node.getData() instanceof IterationNodeData iterationNodeData) {
				iterationNodeData
					.setStartNodeName(varNames.getOrDefault(iterationNodeData.getStartNodeId(), "unknown"));
				iterationNodeData.setEndNodeName(varNames.getOrDefault(iterationNodeData.getEndNodeId(), "unknown"));
			}
		}

		Map<String, String> idToOutputKey = nodes.stream()
			.filter(n -> !(n.getData() instanceof EmptyNodeData))
			.collect(Collectors.toMap(Node::getId, n -> {
				try {
					return ((VariableAggregatorNodeData) n.getData()).getOutputKey();
				}
				catch (ClassCastException e) {
					return n.getData().getOutputs().get(0).getName();
				}
			}));

		// Replace all variable paths in all aggregation nodes.
		for (Node node : nodes) {
			if (node.getData() instanceof VariableAggregatorNodeData agg) {
				List<List<String>> newVars = agg.getVariables().stream().map(path -> {
					String srcId = path.get(0);
					String tail = path.get(1);
					String mapped = idToOutputKey.getOrDefault(srcId, srcId);
					return List.of(mapped, tail);
				}).toList();
				agg.setVariables(newVars);

				var adv = agg.getAdvancedSettings();
				if (adv != null && adv.getGroups() != null) {
					for (var group : adv.getGroups()) {
						List<List<String>> newGroupVars = group.getVariables().stream().map(path -> {
							String srcId = path.get(0);
							String tail = path.get(1);
							String mapped = idToOutputKey.getOrDefault(srcId, srcId);
							return List.of(mapped, tail);
						}).toList();
						group.setVariables(newGroupVars);
					}
				}
			}
		}

		return nodes;
	}

	private List<Edge> constructEdges(List<Map<String, Object>> edgeMaps) {
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		return edgeMaps.stream().map(edgeMap -> objectMapper.convertValue(edgeMap, Edge.class)).toList();
	}

	@Override
	public Map<String, Object> workflowToMap(Workflow workflow) {
		Map<String, Object> data = new HashMap<>();
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		List<Map<String, Object>> workflowVars = objectMapper.convertValue(workflow.getWorkflowVars(), List.class);
		List<Map<String, Object>> envVars = objectMapper.convertValue(workflow.getEnvVars(), List.class);
		Graph graph = workflow.getGraph();
		Map<String, Object> graphMap = deconstructGraph(graph);
		data.put("workflow",
				Map.of("conversation_variables", workflowVars, "environment_variables", envVars, "graph", graphMap));
		return data;
	}

	private Map<String, Object> deconstructGraph(Graph graph) {
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		// deconstruct edge
		List<Map<String, Object>> edgeMaps = deconstructEdge(graph.getEdges());
		// deconstruct node
		List<Map<String, Object>> nodeMaps = deconstructNode(graph.getNodes());
		return Map.of("edges", edgeMaps, "nodes", nodeMaps);
	}

	private List<Map<String, Object>> deconstructEdge(List<Edge> edges) {
		ObjectMapper objectMapper = new ObjectMapper();
		objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		return edges.stream().map(edge -> {
			Map<String, Object> edgeMap = objectMapper.convertValue(edge, new TypeReference<>() {
			});
			edgeMap.put("type", "custom");
			return edgeMap;
		}).toList();

	}

	private List<Map<String, Object>> deconstructNode(List<Node> nodes) {
		ObjectMapper objectMapper = new ObjectMapper();
		List<Map<String, Object>> nodeMaps = new ArrayList<>();
		objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		for (Node node : nodes) {
			Map<String, Object> n = objectMapper.convertValue(node, new TypeReference<>() {
			});
			NodeType nodeType = NodeType.fromValue(node.getType())
				.orElseThrow(() -> new NotImplementedException("Unsupported NodeType: " + node.getType()));
			NodeDataConverter<? extends NodeData> nodeDataConverter = getNodeDataConverter(nodeType);
			Map<String, Object> nodeData = dumpMapData(nodeDataConverter, node.getData());
			nodeData.put("type", nodeType.difyValue());
			nodeData.put("title", node.getTitle());
			nodeData.put("desc", node.getDesc());
			n.put("data", nodeData);
			n.put("type", "custom");
			nodeMaps.add(n);
		}
		return nodeMaps;
	}

	private <T extends NodeData> Map<String, Object> dumpMapData(NodeDataConverter<T> converter, NodeData data) {
		return converter.dumpMapData((T) data, DSLDialectType.DIFY);
	}

	@Override
	public ChatBot mapToChatBot(Map<String, Object> data) {
		// TODO
		return null;
	}

	@Override
	public Map<String, Object> chatbotToMap(ChatBot chatBot) {
		// TODO
		return null;
	}

	@Override
	public Boolean supportDialect(DSLDialectType dialectType) {
		return DSLDialectType.DIFY.equals(dialectType);
	}

}
