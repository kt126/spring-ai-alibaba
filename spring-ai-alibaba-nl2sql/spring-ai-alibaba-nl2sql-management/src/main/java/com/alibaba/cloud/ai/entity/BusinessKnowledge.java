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
package com.alibaba.cloud.ai.entity;

import java.time.LocalDateTime;

/**
 * 业务知识管理实体类
 */
public class BusinessKnowledge {

	private Long id;

	private String businessTerm; // 业务名词

	private String description; // 说明

	private String synonyms; // 同义词，逗号分隔

	private Boolean defaultRecall; // 默认召回

	private String datasetId; // 关联的数据集ID

	private LocalDateTime createTime;

	private LocalDateTime updateTime;

	public BusinessKnowledge() {
	}

	public BusinessKnowledge(String businessTerm, String description, String synonyms, Boolean defaultRecall,
			String datasetId) {
		this.businessTerm = businessTerm;
		this.description = description;
		this.synonyms = synonyms;
		this.defaultRecall = defaultRecall;
		this.datasetId = datasetId;
		this.createTime = LocalDateTime.now();
		this.updateTime = LocalDateTime.now();
	}

	public BusinessKnowledge(Long id, String businessTerm, String description, String synonyms, Boolean defaultRecall,
			String datasetId, LocalDateTime createTime, LocalDateTime updateTime) {
		this.id = id;
		this.businessTerm = businessTerm;
		this.description = description;
		this.synonyms = synonyms;
		this.defaultRecall = defaultRecall;
		this.datasetId = datasetId;
		this.createTime = createTime;
		this.updateTime = updateTime;
	}

	// Getters and Setters
	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public String getBusinessTerm() {
		return businessTerm;
	}

	public void setBusinessTerm(String businessTerm) {
		this.businessTerm = businessTerm;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getSynonyms() {
		return synonyms;
	}

	public void setSynonyms(String synonyms) {
		this.synonyms = synonyms;
	}

	public Boolean getDefaultRecall() {
		return defaultRecall;
	}

	public void setDefaultRecall(Boolean defaultRecall) {
		this.defaultRecall = defaultRecall;
	}

	public String getDatasetId() {
		return datasetId;
	}

	public void setDatasetId(String datasetId) {
		this.datasetId = datasetId;
	}

	public LocalDateTime getCreateTime() {
		return createTime;
	}

	public void setCreateTime(LocalDateTime createTime) {
		this.createTime = createTime;
	}

	public LocalDateTime getUpdateTime() {
		return updateTime;
	}

	public void setUpdateTime(LocalDateTime updateTime) {
		this.updateTime = updateTime;
	}

}
