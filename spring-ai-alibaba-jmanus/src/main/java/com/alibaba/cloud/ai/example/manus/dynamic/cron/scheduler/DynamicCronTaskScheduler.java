/*
 * Copyright 2025 the original author or authors.
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
package com.alibaba.cloud.ai.example.manus.dynamic.cron.scheduler;

import com.alibaba.cloud.ai.example.manus.dynamic.cron.entity.CronEntity;
import com.alibaba.cloud.ai.example.manus.dynamic.cron.enums.TaskStatus;
import com.alibaba.cloud.ai.example.manus.dynamic.cron.repository.CronRepository;
import com.alibaba.cloud.ai.example.manus.planning.PlanningFactory;
import com.alibaba.cloud.ai.example.manus.planning.coordinator.PlanIdDispatcher;
import com.alibaba.cloud.ai.example.manus.planning.coordinator.PlanningCoordinator;
import com.alibaba.cloud.ai.example.manus.planning.model.vo.ExecutionContext;
import com.alibaba.cloud.ai.example.manus.planning.service.PlanTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

/**
 * 动态任务调度器 负责管理所有动态定时任务的生命周期
 */
@Component
public class DynamicCronTaskScheduler {

	private static final Logger log = LoggerFactory.getLogger(DynamicCronTaskScheduler.class);

	private final TaskScheduler taskScheduler;

	private final CronRepository cronRepository;

	@Autowired
	private PlanIdDispatcher planIdDispatcher;

	@Autowired
	private PlanningFactory planningFactory;

	@Autowired
	private PlanTemplateService planTemplateService;

	@Autowired
	private ObjectMapper objectMapper;

	// 存储正在运行的任务
	private final Map<Long, ScheduledFuture<?>> scheduledTasks = new ConcurrentHashMap<>();

	@Autowired
	public DynamicCronTaskScheduler(TaskScheduler taskScheduler, CronRepository cronRepository) {
		this.taskScheduler = taskScheduler;
		this.cronRepository = cronRepository;
	}

	/**
	 * 执行定时任务
	 * @param cronEntity 任务实体
	 */
	private void executeTask(CronEntity cronEntity) {
		try {
			// 更新任务执行时间
			cronEntity.setLastExecutedTime(LocalDateTime.now());
			cronRepository.save(cronEntity);

			String planTemplateId = cronEntity.getPlanTemplateId();

			if (planTemplateId != null && !planTemplateId.trim().isEmpty()) {
				// 如果存在计划模板ID，则按计划模板执行
				executePlanTemplate(planTemplateId);
			}
			else {
				executePlan(cronEntity);
			}
		}
		catch (Exception e) {
			log.error("任务执行失败: {} - {}", cronEntity.getCronName(), e.getMessage());
		}
	}

	/**
	 * 生成计划并执行
	 * @param cronEntity 任务实体
	 */
	private void executePlan(CronEntity cronEntity) {
		String planDesc = cronEntity.getPlanDesc();
		log.info("执行定时任务: {} - {}", cronEntity.getCronName(), planDesc);

		ExecutionContext context = new ExecutionContext();
		context.setUserRequest(planDesc);

		String planId = planIdDispatcher.generatePlanId();
		context.setCurrentPlanId(planId);
		context.setRootPlanId(planId);
		context.setNeedSummary(true);

		PlanningCoordinator planningFlow = planningFactory.createPlanningCoordinator(planId);

		// 异步执行任务
		CompletableFuture.supplyAsync(() -> {
			try {
				return planningFlow.executePlan(context);
			}
			catch (Exception e) {
				log.error("计划执行失败: {} - {}", cronEntity.getCronName(), e.getMessage());
				throw new RuntimeException("计划执行失败: " + e.getMessage(), e);
			}
		});
	}

	/**
	 * 执行计划模板
	 * @param planTemplateId 计划模板ID
	 */
	private void executePlanTemplate(String planTemplateId) {
		try {
			log.info("使用PlanTemplateController执行计划模板: {}", planTemplateId);

			// 调用PlanTemplateController的公共方法executePlanByTemplateId
			ResponseEntity<Map<String, Object>> response = planTemplateService
				.executePlanByTemplateIdInternal(planTemplateId, null);

			if (response.getStatusCode().is2xxSuccessful()) {
				Map<String, Object> responseBody = response.getBody();
				if (responseBody != null) {
					String planId = (String) responseBody.get("planId");
					log.info("计划模板执行成功，新计划ID: {}", planId);
				}
				else {
					log.warn("计划模板执行成功，但响应体为空");
				}
			}
			else {
				log.error("计划模板执行失败，状态码: {}", response.getStatusCode());
			}
		}
		catch (Exception e) {
			log.error("执行计划模板失败: {}", planTemplateId, e);
		}
	}

	/**
	 * 添加定时任务
	 */
	public boolean addTask(CronEntity cronEntity) {
		try {
			if (scheduledTasks.containsKey(cronEntity.getId())) {
				return false;
			}

			if (!TaskStatus.ENABLED.getCode().equals(cronEntity.getStatus())) {
				return false;
			}

			ScheduledFuture<?> future = taskScheduler.schedule(() -> executeTask(cronEntity),
					new CronTrigger(cronEntity.getCronTime()));

			scheduledTasks.put(cronEntity.getId(), future);
			log.info("添加定时任务: {} [{}]", cronEntity.getCronName(), cronEntity.getCronTime());
			return true;
		}
		catch (Exception e) {
			log.error("添加任务失败: {} - {}", cronEntity.getCronName(), e.getMessage());
			return false;
		}
	}

	/**
	 * 移除定时任务
	 */
	public boolean removeTask(Long taskId) {
		try {
			ScheduledFuture<?> future = scheduledTasks.remove(taskId);
			if (future != null) {
				future.cancel(false);
				return true;
			}
			return false;
		}
		catch (Exception e) {
			log.error("移除任务失败: {} - {}", taskId, e.getMessage());
			return false;
		}
	}

	/**
	 * 根据任务ID立即执行任务
	 */
	public void executeTaskById(Long taskId) {
		CronEntity cronEntity = cronRepository.findById(taskId)
			.orElseThrow(() -> new IllegalArgumentException("Cron task not found: " + taskId));

		log.info("手动执行定时任务: {} - {}", cronEntity.getCronName(), cronEntity.getPlanDesc());
		executeTask(cronEntity);
	}

	/**
	 * 获取当前运行的任务ID集合
	 */
	public Set<Long> getRunningTaskIds() {
		return new HashSet<>(scheduledTasks.keySet());
	}

}
