package org.apache.shardingsphere.shardingspheredemo.service;

import org.apache.shardingsphere.shardingspheredemo.entity.HealthRecord;
import org.apache.shardingsphere.shardingspheredemo.entity.HealthTask;
import org.apache.shardingsphere.shardingspheredemo.repository.HealthRecordRepository;
import org.apache.shardingsphere.shardingspheredemo.repository.HealthTaskRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HealthRecordService {

    private final HealthRecordRepository healthRecordRepository;
    private final HealthTaskRepository healthTaskRepository;

    public HealthRecordService(
            HealthRecordRepository healthRecordRepository,
            HealthTaskRepository healthTaskRepository) {
        this.healthRecordRepository = healthRecordRepository;
        this.healthTaskRepository = healthTaskRepository;
    }

    public void processHealthRecords() {
        insertHealthRecords();
    }

    public List<HealthRecord> queryAll() {
        return healthRecordRepository.findAll();
    }

    private void insertHealthRecords() {
        for (int i = 0; i <= 10; i++) {
            HealthRecord healthRecord = insertHealthRecord(i);
            insertHealthTask(i, healthRecord);
        }
    }

    private HealthRecord insertHealthRecord(int i) {
        HealthRecord healthRecord = new HealthRecord();
        healthRecord.setUserId(i);
        healthRecord.setLevelId(i % 5);
        healthRecord.setRemark("Remark" + i);
        healthRecordRepository.addEntity(healthRecord);
        return healthRecord;
    }

    private void insertHealthTask(int i, HealthRecord healthRecord) {
        HealthTask healthTask = new HealthTask();
        healthTask.setRecordId(healthRecord.getRecordId());
        healthTask.setUserId(i);
        healthTask.setTaskName("TaskName" + i);
        healthTaskRepository.addEntity(healthTask);
    }


}
