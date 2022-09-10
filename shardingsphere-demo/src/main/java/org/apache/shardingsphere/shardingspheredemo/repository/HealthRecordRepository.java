package org.apache.shardingsphere.shardingspheredemo.repository;


import org.apache.shardingsphere.shardingspheredemo.entity.HealthRecord;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface HealthRecordRepository {

    void addEntity(HealthRecord healthRecord);

    List<HealthRecord> findAll();
}
