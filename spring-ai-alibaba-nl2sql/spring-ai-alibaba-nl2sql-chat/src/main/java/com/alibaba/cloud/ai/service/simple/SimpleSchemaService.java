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
package com.alibaba.cloud.ai.service.simple;

import com.alibaba.cloud.ai.connector.config.DbConfig;
import com.alibaba.cloud.ai.request.SearchRequest;
import com.alibaba.cloud.ai.service.base.BaseSchemaService;
import com.alibaba.cloud.ai.service.base.BaseVectorStoreService;
import com.google.gson.Gson;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class SimpleSchemaService extends BaseSchemaService {

	@Autowired
	public SimpleSchemaService(DbConfig dbConfig, Gson gson,
			@Qualifier("simpleVectorStoreService") BaseVectorStoreService vectorStoreService) {
		super(dbConfig, gson, vectorStoreService);
	}

	@Override
	protected void addTableDocument(List<Document> tableDocuments, String tableName, String vectorType) {
		handleDocumentQuery(tableDocuments, tableName, vectorType, name -> {
			SearchRequest req = new SearchRequest();
			req.setName(name);
			return req;
		}, vectorStoreService::searchTableByNameAndVectorType);
	}

	@Override
	protected void addColumnsDocument(Map<String, Document> weightedColumns, String tableName, String vectorType) {
		handleDocumentQuery(weightedColumns, tableName, vectorType, name -> {
			SearchRequest req = new SearchRequest();
			req.setName(name);
			return req;
		}, vectorStoreService::searchTableByNameAndVectorType);
	}

}
